package com.microservice.rca.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DeepSeekAgentModelClientTest {

    private final ObjectMapper mapper = AgentTestSupport.mapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesSuccessfulResponseAndSendsExpectedRequest() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"model":"deepseek-flash","choices":[{"message":{"role":"assistant",
                    "content":"{\\"action\\":\\"INSUFFICIENT_EVIDENCE\\",\\"reason\\":\\"测试\\"}"}}],
                    "usage":{"prompt_tokens":12,"completion_tokens":8}}
                    """);
        });

        DeepSeekAgentModelClient client = client(3000, 3000);
        AgentModelClient.ModelResponse response = client.complete(
                new AgentModelClient.ModelRequest("system", "user", 0.1, 500));

        assertThat(response.content()).contains("INSUFFICIENT_EVIDENCE");
        assertThat(response.promptTokens()).isEqualTo(12);
        assertThat(response.completionTokens()).isEqualTo(8);
        assertThat(response.usageAvailable()).isTrue();
        assertThat(response.totalTokens()).isEqualTo(20);
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get().path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(requestBody.get().path("max_tokens").asInt()).isEqualTo(500);
        assertThat(requestBody.get().path("stream").asBoolean()).isFalse();
        assertThat(requestBody.get().path("response_format").path("type").asText())
                .isEqualTo("json_object");
    }

    @Test
    void mapsInsufficientBalance() throws Exception {
        start(exchange -> respond(exchange, 402,
                "{\"error\":{\"message\":\"Insufficient Balance\"}}"));

        assertThatThrownBy(() -> client(3000, 3000).complete(request()))
                .isInstanceOf(AgentModelException.class)
                .satisfies(error -> {
                    AgentModelException ex = (AgentModelException) error;
                    assertThat(ex.getFailureType()).isEqualTo("INSUFFICIENT_BALANCE");
                    assertThat(ex.isRetryable()).isFalse();
                });
    }

    @Test
    void mapsAuthenticationFailureWithoutLeakingKey() throws Exception {
        start(exchange -> respond(exchange, 401, "{\"error\":{\"message\":\"invalid key\"}}"));

        assertThatThrownBy(() -> client(3000, 3000).complete(request()))
                .isInstanceOf(AgentModelException.class)
                .satisfies(error -> {
                    AgentModelException ex = (AgentModelException) error;
                    assertThat(ex.getFailureType()).isEqualTo("AUTH_FAILED");
                    assertThat(ex.getMessage()).doesNotContain("test-key");
                });
    }

    @Test
    void mapsTimeoutAsRetryable() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });

        assertThatThrownBy(() -> client(1000, 200).complete(request()))
                .isInstanceOf(AgentModelException.class)
                .satisfies(error -> {
                    AgentModelException ex = (AgentModelException) error;
                    assertThat(ex.getFailureType()).isEqualTo("TIMEOUT");
                    assertThat(ex.isRetryable()).isTrue();
                });
    }

    @Test
    void usesRemainingBudgetAsReadTimeout() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });

        AgentModelClient.ModelRequest request = new AgentModelClient.ModelRequest(
                "system", "user", 0.1, 500, Duration.ofMillis(200));

        assertThatThrownBy(() -> client(1000, 3000).complete(request))
                .isInstanceOf(AgentModelException.class)
                .satisfies(error -> {
                    AgentModelException ex = (AgentModelException) error;
                    assertThat(ex.getFailureType()).isEqualTo("TIMEOUT");
                    assertThat(ex.isRetryable()).isTrue();
                });
    }

    @Test
    void refusesToCallModelWhenBudgetIsAlreadyExhausted() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        start(exchange -> {
            invoked.set(true);
            respond(exchange, 200, "{}");
        });

        AgentModelClient.ModelRequest request = new AgentModelClient.ModelRequest(
                "system", "user", 0.1, 500, Duration.ZERO);

        assertThatThrownBy(() -> client(3000, 3000).complete(request))
                .isInstanceOf(AgentModelException.class)
                .satisfies(error -> {
                    AgentModelException ex = (AgentModelException) error;
                    assertThat(ex.getFailureType()).isEqualTo("TIMEOUT");
                    assertThat(ex.getMessage()).contains("预算已耗尽");
                });
        assertThat(invoked).isFalse();
    }

    @Test
    void marksUsageMissingWithoutFabricatingTokens() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"model":"deepseek-flash","choices":[{"message":{"content":"{\\"action\\":\\"INSUFFICIENT_EVIDENCE\\",\\"reason\\":\\"无数据\\"}"}}]}
                """));

        AgentModelClient.ModelResponse response = client(3000, 3000).complete(request());

        assertThat(response.usageAvailable()).isFalse();
        assertThat(response.promptTokens()).isZero();
        assertThat(response.completionTokens()).isZero();
        assertThat(response.totalTokens()).isZero();
    }

    @Test
    void treatsEmptyContentAsRetryable() throws Exception {
        start(exchange -> respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"\"}}]}"));

        assertThatThrownBy(() -> client(3000, 3000).complete(request()))
                .isInstanceOf(AgentModelException.class)
                .satisfies(error -> {
                    AgentModelException ex = (AgentModelException) error;
                    assertThat(ex.getFailureType()).isEqualTo("INVALID_RESPONSE");
                    assertThat(ex.isRetryable()).isTrue();
                });
    }

    @Test
    void refusesToCallWhenKeyIsMissing() {
        DeepSeekAgentModelClient client = new DeepSeekAgentModelClient(
                RestClient.builder(),
                new AgentProperties(
                        new AgentProperties.DeepSeek("", "http://127.0.0.1:1", "deepseek-flash",
                                0.1, 500, 1000, 3000),
                        new AgentProperties.Limits(3, 2, 12000, 60000, 6000, 60)));

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(AgentModelException.class)
                .satisfies(error -> assertThat(((AgentModelException) error).getFailureType())
                        .isEqualTo("CONFIG_MISSING"));
    }

    private AgentModelClient.ModelRequest request() {
        return new AgentModelClient.ModelRequest("system", "user", 0.1, 500);
    }

    private DeepSeekAgentModelClient client(int connectTimeoutMs, int readTimeoutMs) {
        int port = server.getAddress().getPort();
        return new DeepSeekAgentModelClient(
                RestClient.builder(),
                new AgentProperties(
                        new AgentProperties.DeepSeek("test-key", "http://127.0.0.1:" + port,
                                "deepseek-flash", 0.1, 500, connectTimeoutMs, readTimeoutMs),
                        new AgentProperties.Limits(3, 2, 12000, 60000, 6000, 60)));
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}

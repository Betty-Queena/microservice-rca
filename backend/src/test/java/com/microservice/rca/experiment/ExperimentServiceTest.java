package com.microservice.rca.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.common.ApiResponse;
import com.microservice.rca.diagnosis.service.FaultEventService;
import com.microservice.rca.experiment.api.ExperimentFaultStatusView;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * 验证 backend 条件关闭代理：请求参数透传、旧调用不发送请求体、409 原样传递。
 * 使用 JDK 内置 HttpServer 作为 inventory-service 的本地 HTTP 桩，不访问真实服务。
 */
class ExperimentServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private HttpServer server;
    private ExperimentService service;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/experiments/faults/disable", this::handleDisable);
        server.start();

        responseBody.set(objectMapper.writeValueAsString(successEnvelope()));
        int port = server.getAddress().getPort();
        service = new ExperimentService(
                RestClient.builder(),
                mock(FaultEventService.class),
                objectMapper,
                "http://127.0.0.1:" + port,
                "http://127.0.0.1:1",
                3000);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void conditionalDisablePassesExpectedExperimentId() throws Exception {
        UUID experimentId = UUID.randomUUID();

        ExperimentFaultStatusView status = service.disable(experimentId);

        assertThat(status.active()).isFalse();
        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestPath.get()).isEqualTo("/api/experiments/faults/disable");
        JsonNode payload = objectMapper.readTree(requestBody.get());
        assertThat(payload.get("expectedExperimentId").asText())
                .isEqualTo(experimentId.toString());
    }

    @Test
    void legacyDisableStillSendsNoBody() {
        ExperimentFaultStatusView status = service.disable();

        assertThat(status.active()).isFalse();
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void inventoryConflictIsPropagatedAsConflict() throws Exception {
        UUID experimentId = UUID.randomUUID();
        responseStatus.set(409);
        responseBody.set(objectMapper.writeValueAsString(
                ApiResponse.error(409, "实验 ID 与当前活动故障不一致")));

        assertThatThrownBy(() -> service.disable(experimentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException conflict = (ResponseStatusException) ex;
                    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(conflict.getReason()).contains("实验 ID 与当前活动故障不一致");
                });
    }

    private void handleDisable(HttpExchange exchange) throws IOException {
        requestMethod.set(exchange.getRequestMethod());
        requestPath.set(exchange.getRequestURI().getPath());
        byte[] body = exchange.getRequestBody().readAllBytes();
        requestBody.set(body.length == 0 ? null : new String(body, StandardCharsets.UTF_8));

        byte[] response = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private ApiResponse<Map<String, Object>> successEnvelope() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("experimentEnabled", true);
        data.put("active", false);
        data.put("experimentId", null);
        data.put("faultType", null);
        data.put("delayMs", null);
        data.put("activatedAt", null);
        data.put("expiresAt", null);
        data.put("remainingMs", null);
        data.put("defaultDurationSeconds", 120);
        data.put("maxDelayMs", 4500);
        data.put("maxDurationSeconds", 600);
        data.put("note", null);
        return ApiResponse.ok(data);
    }
}

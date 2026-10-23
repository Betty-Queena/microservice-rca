package com.microservice.rca.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * DeepSeek 官方兼容 API 客户端（/chat/completions）。
 * API Key 仅保存在内存配置中，不打印、不写库。
 */
@Component
public class DeepSeekAgentModelClient implements AgentModelClient {

    private static final int MAX_ERROR_BODY_LENGTH = 300;

    private final RestClient.Builder builder;
    private final RestClient defaultClient;
    private final AgentProperties.DeepSeek properties;

    public DeepSeekAgentModelClient(RestClient.Builder builder, AgentProperties properties) {
        this.properties = properties.deepseek();
        this.builder = builder.clone();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.deepseek().connectTimeoutMs());
        requestFactory.setReadTimeout(properties.deepseek().readTimeoutMs());
        this.defaultClient = this.builder.clone()
                .baseUrl(properties.deepseek().baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public String modelName() {
        return properties.model();
    }

    @Override
    public boolean isConfigured() {
        return properties.configured();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        if (!isConfigured()) {
            throw new AgentModelException("CONFIG_MISSING",
                    "未配置 DEEPSEEK_API_KEY，无法调用真实模型", false);
        }

        int readTimeoutMs = properties.readTimeoutMs();
        RestClient client = defaultClient;
        if (request.timeout() != null) {
            long remainingMs = request.timeout().toMillis();
            if (remainingMs <= 0) {
                throw new AgentModelException("TIMEOUT",
                        "诊断整体预算已耗尽，未发起模型调用", false);
            }
            readTimeoutMs = (int) Math.max(1, Math.min(properties.readTimeoutMs(), remainingMs));
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Math.min(properties.connectTimeoutMs(), readTimeoutMs));
            requestFactory.setReadTimeout(readTimeoutMs);
            client = builder.clone()
                    .baseUrl(properties.baseUrl())
                    .requestFactory(requestFactory)
                    .build();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())));
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        body.put("stream", false);
        body.put("response_format", Map.of("type", "json_object"));

        try {
            JsonNode response = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parseResponse(response);
        } catch (RestClientResponseException ex) {
            throw mapHttpError(ex);
        } catch (ResourceAccessException ex) {
            if (hasTimeoutCause(ex)) {
                throw new AgentModelException("TIMEOUT",
                        "DeepSeek 调用超时（" + readTimeoutMs + " ms）", true, ex);
            }
            throw new AgentModelException("UNREACHABLE",
                    "无法连接 DeepSeek API，请检查网络或代理配置", true, ex);
        } catch (AgentModelException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof SocketTimeoutException || hasTimeoutCause(ex)) {
                throw new AgentModelException("TIMEOUT",
                        "DeepSeek 调用超时（" + readTimeoutMs + " ms）", true, ex);
            }
            throw new AgentModelException("INVALID_RESPONSE",
                    "DeepSeek 响应无法解析: " + ex.getClass().getSimpleName(), false, ex);
        }
    }

    private ModelResponse parseResponse(JsonNode response) {
        if (response == null) {
            throw new AgentModelException("INVALID_RESPONSE", "DeepSeek 返回空响应", false);
        }
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new AgentModelException("INVALID_RESPONSE", "DeepSeek 响应缺少 choices", false);
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            // JSON 模式下偶发空 content，属于可重试的瞬时响应；重试次数仍受 Agent 总预算限制。
            throw new AgentModelException("INVALID_RESPONSE", "DeepSeek 响应缺少 message.content", true);
        }
        JsonNode usage = response.path("usage");
        boolean usageAvailable = usage.isObject()
                && usage.has("prompt_tokens") && usage.has("completion_tokens");
        return new ModelResponse(
                content.asText(),
                response.path("model").asText(properties.model()),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usageAvailable);
    }

    private AgentModelException mapHttpError(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String detail = truncate(ex.getResponseBodyAsString(StandardCharsets.UTF_8));
        String suffix = detail.isBlank() ? "" : " - " + detail;
        return switch (status) {
            case 401, 403 -> new AgentModelException("AUTH_FAILED",
                    "DeepSeek 鉴权失败（" + status + "），请检查 DEEPSEEK_API_KEY" + suffix, false, ex);
            case 402 -> new AgentModelException("INSUFFICIENT_BALANCE",
                    "DeepSeek 账户余额不足（402）" + suffix, false, ex);
            case 429 -> new AgentModelException("RATE_LIMITED",
                    "DeepSeek 请求被限流（429）" + suffix, true, ex);
            default -> status >= 500
                    ? new AgentModelException("PROVIDER_ERROR",
                            "DeepSeek 服务端错误（" + status + "）" + suffix, true, ex)
                    : new AgentModelException("INVALID_REQUEST",
                            "DeepSeek 请求被拒绝（" + status + "）" + suffix, false, ex);
        };
    }

    private boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_ERROR_BODY_LENGTH
                ? compact : compact.substring(0, MAX_ERROR_BODY_LENGTH);
    }
}

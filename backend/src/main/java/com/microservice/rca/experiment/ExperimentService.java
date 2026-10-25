package com.microservice.rca.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.service.FaultEventService;
import com.microservice.rca.experiment.api.CheckoutRequest;
import com.microservice.rca.experiment.api.EnableFaultRequest;
import com.microservice.rca.experiment.api.ExperimentExecutionView;
import com.microservice.rca.experiment.api.ExperimentFaultStatusView;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 故障注入实验编排。
 * 管理后端只做代理和真实请求观测：故障状态保存在库存服务内存中，
 * 实验请求真实经过 order-service -> inventory-service 的 HTTP 调用链。
 */
@Service
public class ExperimentService {

    private static final String DOWNSTREAM_ERROR_HEADER = "X-Downstream-Error-Type";
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String INVENTORY_STATUS_SOURCE = "inventory-service:/api/experiments/faults";

    private final RestClient inventoryClient;
    private final RestClient orderClient;
    private final FaultEventService faultEventService;
    private final ObjectMapper objectMapper;
    private final long slowResponseThresholdMs;

    public ExperimentService(RestClient.Builder builder,
                             FaultEventService faultEventService,
                             ObjectMapper objectMapper,
                             @Value("${services.inventory-base-url:http://localhost:8082}") String inventoryBaseUrl,
                             @Value("${services.order-base-url:http://localhost:8081}") String orderBaseUrl,
                             @Value("${experiment.slow-response-threshold-ms:1000}") long slowResponseThresholdMs) {
        this.inventoryClient = buildClient(builder, inventoryBaseUrl, 2000, 30000);
        this.orderClient = buildClient(builder, orderBaseUrl, 2000, 10000);
        this.faultEventService = faultEventService;
        this.objectMapper = objectMapper;
        this.slowResponseThresholdMs = slowResponseThresholdMs;
    }

    public ExperimentFaultStatusView status() {
        return toStatusView(inventoryGet("/api/experiments/faults"));
    }

    public ExperimentFaultStatusView enable(EnableFaultRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("faultType", request.faultType());
        payload.put("delayMs", request.delayMs());
        payload.put("durationSeconds", request.durationSeconds());
        payload.put("note", request.note());
        return toStatusView(inventoryPost("/api/experiments/faults/enable", payload));
    }

    /**
     * 兼容旧调用的无条件关闭。Day 7 runner 和现有前端仍使用该路径。
     */
    public ExperimentFaultStatusView disable() {
        return toStatusView(inventoryPost("/api/experiments/faults/disable", null));
    }

    /**
     * 条件关闭指定实验对应的故障。expectedExperimentId 由调用方从故障事件读取，
     * 不来自任意请求参数；库存服务会在 ID 不匹配时返回 409。
     */
    public ExperimentFaultStatusView disable(UUID expectedExperimentId) {
        Map<String, Object> payload = null;
        if (expectedExperimentId != null) {
            payload = new LinkedHashMap<>();
            payload.put("expectedExperimentId", expectedExperimentId);
        }
        return toStatusView(inventoryPost("/api/experiments/faults/disable", payload));
    }

    public ExperimentFaultStatusView reset() {
        return toStatusView(inventoryPost("/api/experiments/faults/reset", null));
    }

    /**
     * 代理库存列表，供实验页面选择真实 SKU，不生成任何模拟商品数据。
     */
    public List<Map<String, Object>> inventory() {
        try {
            Map<String, Object> envelope = inventoryClient.get()
                    .uri("/api/inventory")
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (envelope == null || !Integer.valueOf(200).equals(envelope.get("code"))) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "库存服务返回非成功响应: " + (envelope != null ? envelope.get("message") : "空响应"));
            }
            if (!(envelope.get("data") instanceof List<?> items)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "库存服务响应 data 格式不正确");
            }
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> itemMap) {
                    Map<String, Object> typed = new LinkedHashMap<>();
                    itemMap.forEach((key, value) -> typed.put(String.valueOf(key), value));
                    result.add(typed);
                }
            }
            return result;
        } catch (RestClientResponseException ex) {
            throw translateInventoryError(ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "无法连接库存服务: " + ex.getMessage());
        }
    }

    public ExperimentExecutionView execute(UUID experimentId, CheckoutRequest request) {
        ExperimentFaultStatusView status = status();
        if (!status.active()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "实验未激活或已自动过期，请重新开启故障注入");
        }
        if (!experimentId.toString().equals(status.experimentId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "实验 ID 与库存服务当前激活实验不一致");
        }
        return runCheckout(experimentId, status, request);
    }

    public ExperimentExecutionView checkout(CheckoutRequest request) {
        return runCheckout(null, null, request);
    }

    private ExperimentExecutionView runCheckout(UUID experimentId,
                                                ExperimentFaultStatusView preset,
                                                CheckoutRequest request) {
        String requestId = "exp-" + UUID.randomUUID().toString().replace("-", "");
        HttpObservation observation = callOrder(requestId, request);
        ObservationClassifier.Observation classified = ObservationClassifier.classify(
                observation.httpStatus(),
                observation.downstreamErrorType(),
                observation.durationMs(),
                slowResponseThresholdMs);

        Instant executedAt = Instant.now();
        FaultEvent event = null;
        if (classified.faultObserved()) {
            event = faultEventService.createFromExperiment(
                    eventServiceName(classified.outcome()),
                    classified.outcome(),
                    classified.severity(),
                    classified.summary(),
                    experimentId,
                    preset != null ? preset.faultType() : null,
                    presetParameters(preset),
                    observedResult(observation, classified, executedAt),
                    requestId,
                    eventDetails(request, requestId, observation, classified, executedAt));
        }

        return new ExperimentExecutionView(
                experimentId,
                requestId,
                preset != null ? preset.faultType() : null,
                classified.outcome(),
                classified.faultObserved(),
                observation.httpStatus(),
                observation.durationMs(),
                observation.downstreamErrorType(),
                observation.orderStatus(),
                observation.orderId(),
                observation.remainingStock(),
                observation.body(),
                event != null ? event.getId() : null,
                executedAt,
                "实际结果来自订单服务真实 HTTP 响应与耗时；presetFaultType 仅用于实验编排，"
                        + "不作为根因结论，也未写入 root_cause");
    }

    private HttpObservation callOrder(String requestId, CheckoutRequest request) {
        long startedAt = System.nanoTime();
        try {
            ResponseEntity<JsonNode> response = orderClient.post()
                    .uri("/api/orders/checkout")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(REQUEST_ID_HEADER, requestId)
                    .body(new OrderCheckoutBody(request.sku(), request.quantity()))
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> {
                        // 保留非 2xx 响应体，由观测分类器根据状态码、下游错误标记和耗时判定。
                    })
                    .toEntity(JsonNode.class);
            long durationMs = elapsedMs(startedAt);
            return toObservation(response.getStatusCode().value(),
                    response.getHeaders().getFirst(DOWNSTREAM_ERROR_HEADER),
                    response.getBody(),
                    durationMs,
                    null);
        } catch (RestClientResponseException ex) {
            long durationMs = elapsedMs(startedAt);
            JsonNode body = parseBody(ex.getResponseBodyAsString());
            return toObservation(ex.getStatusCode().value(),
                    ex.getResponseHeaders() != null
                            ? ex.getResponseHeaders().getFirst(DOWNSTREAM_ERROR_HEADER) : null,
                    body,
                    durationMs,
                    null);
        } catch (ResourceAccessException ex) {
            return new HttpObservation(0, null, elapsedMs(startedAt), null, null, null,
                    null, null, ex.getMessage());
        } catch (Exception ex) {
            return new HttpObservation(0, null, elapsedMs(startedAt), null, null, null,
                    null, null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private HttpObservation toObservation(int httpStatus,
                                          String downstreamErrorType,
                                          JsonNode body,
                                          long durationMs,
                                          String transportError) {
        return new HttpObservation(
                httpStatus,
                downstreamErrorType,
                durationMs,
                text(body, "data", "orderId"),
                text(body, "data", "status"),
                intValue(body, "data", "remainingStock"),
                text(body, "message"),
                body,
                transportError);
    }

    private Map<String, Object> presetParameters(ExperimentFaultStatusView preset) {
        if (preset == null) {
            return null;
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("faultType", preset.faultType());
        parameters.put("delayMs", preset.delayMs());
        parameters.put("activatedAt", preset.activatedAt() != null ? preset.activatedAt().toString() : null);
        parameters.put("expiresAt", preset.expiresAt() != null ? preset.expiresAt().toString() : null);
        parameters.put("experimentNote", preset.note());
        parameters.put("disclaimer", "实验预设参数仅用于故障编排，不是根因结论");
        return parameters;
    }

    private Map<String, Object> observedResult(HttpObservation observation,
                                               ObservationClassifier.Observation classified,
                                               Instant executedAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", classified.outcome());
        result.put("httpStatus", observation.httpStatus());
        result.put("durationMs", observation.durationMs());
        result.put("downstreamErrorType", observation.downstreamErrorType());
        result.put("orderStatus", observation.orderStatus());
        result.put("orderId", observation.orderId());
        result.put("remainingStock", observation.remainingStock());
        result.put("responseMessage", observation.message());
        result.put("transportError", observation.transportError());
        result.put("observedAt", executedAt.toString());
        result.put("classificationBasis", "由真实订单结算请求的 HTTP 状态、下游错误类型与耗时共同判定");
        result.put("disclaimer", "observedResult 是实际观测结果，不包含根因推断");
        return result;
    }

    private Map<String, Object> eventDetails(CheckoutRequest request,
                                             String requestId,
                                             HttpObservation observation,
                                             ObservationClassifier.Observation classified,
                                             Instant executedAt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sku", request.sku());
        details.put("quantity", request.quantity());
        details.put("requestId", requestId);
        details.put("requestPath", "order-service POST /api/orders/checkout -> "
                + "inventory-service POST /api/inventory/{sku}/reserve");
        details.put("downstreamErrorType", observation.downstreamErrorType());
        details.put("responseMessage", observation.message());
        details.put("transportError", observation.transportError());
        details.put("outcome", classified.outcome());
        details.put("observedAt", executedAt.toString());
        details.put("observationSource", "order-service 实际 HTTP 响应，非前端状态或静态数据");
        return details;
    }

    private String eventServiceName(String outcome) {
        return switch (outcome) {
            case "SLOW_RESPONSE", "DOWNSTREAM_TIMEOUT", "DOWNSTREAM_HTTP_5XX",
                 "DOWNSTREAM_CONNECTION_ERROR", "DOWNSTREAM_PROTOCOL_ERROR", "DOWNSTREAM_ERROR" ->
                    "inventory-service";
            default -> "order-service";
        };
    }

    private ExperimentFaultStatusView toStatusView(Map<String, Object> data) {
        return new ExperimentFaultStatusView(
                boolValue(data.get("experimentEnabled")),
                boolValue(data.get("active")),
                stringValue(data.get("experimentId")),
                stringValue(data.get("faultType")),
                intValue(data.get("delayMs")),
                instantValue(data.get("activatedAt")),
                instantValue(data.get("expiresAt")),
                longValue(data.get("remainingMs")),
                intValue(data.get("defaultDurationSeconds")) != null
                        ? intValue(data.get("defaultDurationSeconds")) : 0,
                intValue(data.get("maxDelayMs")) != null ? intValue(data.get("maxDelayMs")) : 0,
                intValue(data.get("maxDurationSeconds")) != null
                        ? intValue(data.get("maxDurationSeconds")) : 0,
                stringValue(data.get("note")),
                INVENTORY_STATUS_SOURCE,
                Instant.now());
    }

    private Map<String, Object> inventoryGet(String path) {
        try {
            Map<String, Object> envelope = inventoryClient.get()
                    .uri(path)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            return unwrapEnvelope(envelope);
        } catch (RestClientResponseException ex) {
            throw translateInventoryError(ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "无法连接库存服务，故障注入控制不可用: " + ex.getMessage());
        }
    }

    private Map<String, Object> inventoryPost(String path, Object payload) {
        try {
            RestClient.RequestBodySpec spec = inventoryClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON);
            if (payload != null) {
                spec = spec.body(payload);
            }
            Map<String, Object> envelope = spec.retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            return unwrapEnvelope(envelope);
        } catch (RestClientResponseException ex) {
            throw translateInventoryError(ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "无法连接库存服务，故障注入控制不可用: " + ex.getMessage());
        }
    }

    private Map<String, Object> unwrapEnvelope(Map<String, Object> envelope) {
        if (envelope == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "库存服务返回空响应");
        }
        if (!Integer.valueOf(200).equals(envelope.get("code"))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "库存服务返回非成功响应: " + envelope.get("message"));
        }
        Object data = envelope.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "库存服务响应 data 格式不正确");
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        dataMap.forEach((key, value) -> typed.put(String.valueOf(key), value));
        return typed;
    }

    private ResponseStatusException translateInventoryError(RestClientResponseException ex) {
        HttpStatusCode statusCode = ex.getStatusCode();
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String message = extractMessage(ex.getResponseBodyAsString());
        if (message == null || message.isBlank()) {
            message = "库存服务故障注入控制失败: HTTP " + statusCode.value();
        }
        return new ResponseStatusException(status != null ? status : HttpStatus.BAD_GATEWAY, message, ex);
    }

    private String extractMessage(String body) {
        JsonNode json = parseBody(body);
        return text(json, "message");
    }

    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ignored) {
            return null;
        }
    }

    private RestClient buildClient(RestClient.Builder builder,
                                   String baseUrl,
                                   int connectTimeoutMs,
                                   int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    private String text(JsonNode root, String... path) {
        JsonNode node = root;
        for (String segment : path) {
            if (node == null || node.isNull() || node.isMissingNode()) {
                return null;
            }
            node = node.get(segment);
        }
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asText();
    }

    private Integer intValue(JsonNode root, String... path) {
        String value = text(root, path);
        return parseInteger(value);
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return parseInteger(value != null ? String.valueOf(value) : null);
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private Instant instantValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private record OrderCheckoutBody(String sku, int quantity) {
    }

    private record HttpObservation(
            Integer httpStatus,
            String downstreamErrorType,
            long durationMs,
            String orderId,
            String orderStatus,
            Integer remainingStock,
            String message,
            JsonNode body,
            String transportError) {
    }
}

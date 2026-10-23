package com.microservice.rca.observability;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * 管理后端访问实验服务真实观测接口的客户端。
 * 返回数据包含服务名、原始 source、查询窗口，便于工具层标注证据来源。
 */
@Component
public class ObservabilityClient {

    private static final String ORDER_SERVICE = "order-service";
    private static final String INVENTORY_SERVICE = "inventory-service";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 2000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5000;

    private final RestClient.Builder builder;
    private final String orderBaseUrl;
    private final String inventoryBaseUrl;

    public ObservabilityClient(RestClient.Builder builder,
                               @Value("${services.order-base-url:http://localhost:8081}") String orderBaseUrl,
                               @Value("${services.inventory-base-url:http://localhost:8082}") String inventoryBaseUrl) {
        this.builder = builder.clone();
        this.orderBaseUrl = orderBaseUrl;
        this.inventoryBaseUrl = inventoryBaseUrl;
    }

    public List<ServiceQueryResult> queryLogs(String targetService,
                                              Instant start,
                                              Instant end,
                                              String requestId,
                                              int limit) {
        return queryServices(targetService, "/api/observability/logs", start, end, requestId, limit, null);
    }

    /**
     * timeout 为整次工具调用的预算；多个服务串行查询时共享该预算，
     * 单次 HTTP 读超时不超过当前剩余时间。
     */
    public List<ServiceQueryResult> queryLogs(String targetService,
                                              Instant start,
                                              Instant end,
                                              String requestId,
                                              int limit,
                                              Duration timeout) {
        return queryServices(targetService, "/api/observability/logs", start, end, requestId, limit, timeout);
    }

    public List<ServiceQueryResult> queryMetrics(String targetService, Instant start, Instant end) {
        return queryServices(targetService, "/api/observability/metrics", start, end, null, 0, null);
    }

    public List<ServiceQueryResult> queryMetrics(String targetService,
                                                 Instant start,
                                                 Instant end,
                                                 Duration timeout) {
        return queryServices(targetService, "/api/observability/metrics", start, end, null, 0, timeout);
    }

    private List<ServiceQueryResult> queryServices(String targetService,
                                                   String path,
                                                   Instant start,
                                                   Instant end,
                                                   String requestId,
                                                   int limit,
                                                   Duration timeout) {
        List<ServiceQueryResult> results = new ArrayList<>();
        Instant deadline = timeout != null ? Instant.now().plus(timeout) : null;
        for (String service : resolveServices(targetService)) {
            Integer readTimeoutMs = null;
            if (deadline != null) {
                long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
                if (remainingMs <= 0) {
                    results.add(new ServiceQueryResult(service, null,
                            "TOOL_TIMEOUT: 工具剩余预算已耗尽"));
                    continue;
                }
                readTimeoutMs = (int) Math.max(1, Math.min(DEFAULT_READ_TIMEOUT_MS, remainingMs));
            }
            results.add(queryOne(service, path, start, end, requestId, limit, readTimeoutMs));
        }
        return results;
    }

    private ServiceQueryResult queryOne(String service,
                                        String path,
                                        Instant start,
                                        Instant end,
                                        String requestId,
                                        int limit,
                                        Integer readTimeoutMs) {
        try {
            RestClient client = buildClient(
                    ORDER_SERVICE.equals(service) ? orderBaseUrl : inventoryBaseUrl, readTimeoutMs);
            Map<String, Object> body = client.get()
                    .uri(uriBuilder -> buildUri(uriBuilder, path, start, end, requestId, limit))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });

            if (body == null || !Integer.valueOf(200).equals(body.get("code"))) {
                String message = body != null && body.get("message") != null
                        ? String.valueOf(body.get("message"))
                        : "观测接口返回非预期响应";
                return new ServiceQueryResult(service, null, message);
            }
            Object data = body.get("data");
            if (!(data instanceof Map<?, ?> dataMap)) {
                return new ServiceQueryResult(service, null, "观测接口 data 字段格式不正确");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typedData = (Map<String, Object>) dataMap;
            return new ServiceQueryResult(service, typedData, null);
        } catch (Exception ex) {
            String prefix = hasTimeoutCause(ex) ? "TOOL_TIMEOUT: " : "";
            return new ServiceQueryResult(service, null,
                    prefix + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
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

    private java.net.URI buildUri(UriBuilder uriBuilder,
                                  String path,
                                  Instant start,
                                  Instant end,
                                  String requestId,
                                  int limit) {
        UriBuilder builder = uriBuilder.path(path)
                .queryParam("startTime", start.toString())
                .queryParam("endTime", end.toString());
        if (requestId != null && !requestId.isBlank()) {
            builder = builder.queryParam("requestId", requestId);
        }
        if (limit > 0) {
            builder = builder.queryParam("limit", limit);
        }
        return builder.build();
    }

    private List<String> resolveServices(String targetService) {
        if (targetService == null || targetService.isBlank() || "all".equalsIgnoreCase(targetService)) {
            return List.of(ORDER_SERVICE, INVENTORY_SERVICE);
        }
        if (ORDER_SERVICE.equals(targetService) || INVENTORY_SERVICE.equals(targetService)) {
            return List.of(targetService);
        }
        throw new IllegalArgumentException("不支持的服务: " + targetService
                + "，可选值: order-service, inventory-service, all");
    }

    private RestClient buildClient(String baseUrl, Integer readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int readTimeout = readTimeoutMs != null ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
        requestFactory.setConnectTimeout(Math.min(DEFAULT_CONNECT_TIMEOUT_MS, readTimeout));
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public record ServiceQueryResult(String serviceName, Map<String, Object> data, String error) {
    }
}

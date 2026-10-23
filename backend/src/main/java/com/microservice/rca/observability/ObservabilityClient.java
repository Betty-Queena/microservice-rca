package com.microservice.rca.observability;

import java.time.Instant;
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

    private final RestClient orderClient;
    private final RestClient inventoryClient;

    public ObservabilityClient(RestClient.Builder builder,
                               @Value("${services.order-base-url:http://localhost:8081}") String orderBaseUrl,
                               @Value("${services.inventory-base-url:http://localhost:8082}") String inventoryBaseUrl) {
        this.orderClient = buildClient(builder, orderBaseUrl);
        this.inventoryClient = buildClient(builder, inventoryBaseUrl);
    }

    public List<ServiceQueryResult> queryLogs(String targetService,
                                              Instant start,
                                              Instant end,
                                              String requestId,
                                              int limit) {
        return resolveServices(targetService).stream()
                .map(service -> queryOne(service, "/api/observability/logs", start, end, requestId, limit))
                .toList();
    }

    public List<ServiceQueryResult> queryMetrics(String targetService, Instant start, Instant end) {
        return resolveServices(targetService).stream()
                .map(service -> queryOne(service, "/api/observability/metrics", start, end, null, 0))
                .toList();
    }

    private ServiceQueryResult queryOne(String service,
                                        String path,
                                        Instant start,
                                        Instant end,
                                        String requestId,
                                        int limit) {
        try {
            RestClient client = ORDER_SERVICE.equals(service) ? orderClient : inventoryClient;
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
            return new ServiceQueryResult(service, null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
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

    private RestClient buildClient(RestClient.Builder builder, String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(5000);
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public record ServiceQueryResult(String serviceName, Map<String, Object> data, String error) {
    }
}

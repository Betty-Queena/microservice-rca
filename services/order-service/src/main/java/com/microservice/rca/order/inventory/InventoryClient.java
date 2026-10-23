package com.microservice.rca.order.inventory;

import com.microservice.rca.order.exception.InventoryRejectedException;
import com.microservice.rca.order.exception.InventoryUnavailableException;
import com.microservice.rca.order.observability.RequestIdContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 订单服务到库存服务的真实 HTTP 客户端。
 * 超时、下游错误和库存不足均显式区分，便于后续故障诊断实验。
 */
@Component
public class InventoryClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    public InventoryClient(RestClient.Builder builder,
                           ObjectMapper objectMapper,
                           @Value("${inventory.base-url}") String baseUrl,
                           @Value("${inventory.connect-timeout-ms:2000}") int connectTimeoutMs,
                           @Value("${inventory.read-timeout-ms:3000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
    }

    public InventoryReservation reserve(String sku, int quantity, String orderId) {
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri("/api/inventory/{sku}/reserve", sku)
                    .contentType(MediaType.APPLICATION_JSON);
            String requestId = RequestIdContext.current();
            if (requestId != null) {
                spec.header(RequestIdContext.HEADER, requestId);
            }

            InventoryEnvelope envelope = spec
                    .body(new ReserveRequest(quantity, orderId))
                    .retrieve()
                    .body(InventoryEnvelope.class);

            if (envelope == null || envelope.code() != 200 || envelope.data() == null) {
                throw new InventoryUnavailableException("库存服务返回了非预期响应", "PROTOCOL_ERROR");
            }
            return envelope.data();
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 409) {
                throw new InventoryRejectedException(extractMessage(ex));
            }
            if (status >= 500) {
                throw new InventoryUnavailableException("库存服务调用失败: HTTP " + status, "HTTP_5XX");
            }
            throw new InventoryUnavailableException("库存服务返回 HTTP " + status, "HTTP_4XX");
        } catch (ResourceAccessException ex) {
            if (isTimeout(ex)) {
                throw new InventoryUnavailableException("库存服务调用超时: " + ex.getMessage(), "TIMEOUT");
            }
            throw new InventoryUnavailableException("库存服务不可达: " + ex.getMessage(), "CONNECTION_ERROR");
        } catch (RestClientException ex) {
            // SimpleClientHttpRequestFactory 在读取响应阶段超时时，异常会在响应转换阶段被包装为
            // RestClientException，而不是 ResourceAccessException，需要单独按超时归类。
            if (isTimeout(ex)) {
                throw new InventoryUnavailableException("库存服务调用超时：读取响应超过订单服务配置的读超时", "TIMEOUT");
            }
            throw new InventoryUnavailableException("库存服务响应无法解析: " + ex.getMessage(), "PROTOCOL_ERROR");
        }
    }

    public boolean ping() {
        try {
            restClient.get().uri("/api/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String extractMessage(RestClientResponseException ex) {
        try {
            InventoryEnvelope envelope = objectMapper.readValue(ex.getResponseBodyAsString(), InventoryEnvelope.class);
            if (envelope.message() != null && !envelope.message().isBlank()) {
                return envelope.message();
            }
        } catch (Exception ignored) {
            // 下游响应无法解析时使用通用提示。
        }
        return "库存服务拒绝了本次请求";
    }
}

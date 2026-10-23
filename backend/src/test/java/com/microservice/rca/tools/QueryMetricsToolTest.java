package com.microservice.rca.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microservice.rca.observability.ObservabilityClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryMetricsToolTest {

    private static final Instant START = Instant.parse("2026-10-20T17:16:00Z");
    private static final Instant END = Instant.parse("2026-10-20T17:18:00Z");

    private final ObservabilityClient client = mock(ObservabilityClient.class);
    private final QueryMetricsTool tool = new QueryMetricsTool(client);

    @Test
    void aggregatesRealBucketsAcrossServices() {
        Map<String, Object> orderData = serviceData(
                "order-service RequestMetricsStore",
                Map.of("requestCount", 3, "serverErrorCount", 2, "clientErrorCount", 1,
                        "averageDurationMs", 1000.0, "maxDurationMs", 2000),
                List.of(Map.of("minute", "2026-10-20T17:17:00Z", "requestCount", 3,
                        "serverErrorCount", 2, "clientErrorCount", 1,
                        "totalDurationMs", 3000, "maxDurationMs", 2000)));
        Map<String, Object> inventoryData = serviceData(
                "inventory-service RequestMetricsStore",
                Map.of("requestCount", 4, "serverErrorCount", 1, "clientErrorCount", 1,
                        "averageDurationMs", 500.0, "maxDurationMs", 800),
                List.of(Map.of("minute", "2026-10-20T17:17:00Z", "requestCount", 4,
                        "serverErrorCount", 1, "clientErrorCount", 1,
                        "totalDurationMs", 2000, "maxDurationMs", 800)));
        when(client.queryMetrics(anyString(), any(), any())).thenReturn(List.of(
                new ObservabilityClient.ServiceQueryResult("order-service", orderData, null),
                new ObservabilityClient.ServiceQueryResult("inventory-service", inventoryData, null)));

        ToolResult result = tool.execute(context());

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.output().get("summary");
        assertThat(summary.get("requestCount")).isEqualTo(7L);
        assertThat(summary.get("serverErrorCount")).isEqualTo(3L);
        assertThat(summary.get("clientErrorCount")).isEqualTo(2L);
        assertThat((Double) summary.get("averageDurationMs")).isEqualTo(5000.0 / 7, org.assertj.core.data.Offset.offset(0.001));
        assertThat(summary.get("maxDurationMs")).isEqualTo(2000L);
        assertThat(result.output().get("evidenceIds")).asList()
                .containsExactly("metric:order-service:2026-10-20T17:17:00Z",
                        "metric:inventory-service:2026-10-20T17:17:00Z");
        assertThat(result.output().get("services")).asList().hasSize(2);
    }

    @Test
    void failsWhenEveryServiceQueryFails() {
        when(client.queryMetrics(anyString(), any(), any())).thenReturn(List.of(
                new ObservabilityClient.ServiceQueryResult("order-service", null, "连接被拒绝"),
                new ObservabilityClient.ServiceQueryResult("inventory-service", null, "连接被拒绝")));

        ToolResult result = tool.execute(context());

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("query_metrics 查询失败");
    }

    private Map<String, Object> serviceData(String source,
                                            Map<String, Object> summary,
                                            List<Map<String, Object>> buckets) {
        return Map.of(
                "source", source,
                "collectedAt", "2026-10-20T17:17:55Z",
                "summary", summary,
                "buckets", buckets);
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(UUID.randomUUID(), "all", START, END, Map.of());
    }
}

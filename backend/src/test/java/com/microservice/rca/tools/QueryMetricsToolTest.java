package com.microservice.rca.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microservice.rca.external.client.ExternalActuatorClient;
import com.microservice.rca.external.domain.ExternalService;
import com.microservice.rca.external.service.ExternalServiceRegistry;
import com.microservice.rca.observability.ObservabilityClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueryMetricsToolTest {

    private static final Instant START = Instant.parse("2026-10-20T17:16:00Z");
    private static final Instant END = Instant.parse("2026-10-20T17:18:00Z");

    private final ObservabilityClient client = mock(ObservabilityClient.class);
    private final ExternalServiceRegistry externalRegistry = mock(ExternalServiceRegistry.class);
    private final ExternalActuatorClient externalClient = mock(ExternalActuatorClient.class);
    private final QueryMetricsTool tool = new QueryMetricsTool(client, externalRegistry, externalClient);

    @BeforeEach
    void noExternalServiceByDefault() {
        when(externalRegistry.findByName(anyString())).thenReturn(Optional.empty());
    }

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

    @Test
    void passesRemainingBudgetToObservabilityClient() {
        when(client.queryMetrics(anyString(), any(), any(), any())).thenReturn(List.of(
                new ObservabilityClient.ServiceQueryResult("order-service",
                        serviceData("order-service RequestMetricsStore",
                                Map.of("requestCount", 1), List.of()), null)));
        Duration timeout = Duration.ofSeconds(3);

        ToolResult result = tool.execute(new ToolExecutionContext(
                UUID.randomUUID(), "all", START, END, Map.of(), timeout));

        assertThat(result.success()).isTrue();
        verify(client).queryMetrics("all", START, END, timeout);
    }

    @Test
    void externalServiceReturnsActuatorSnapshotWithoutFakeBuckets() {
        ExternalService service = externalService("petclinic");
        when(externalRegistry.findByName("petclinic")).thenReturn(Optional.of(service));
        when(externalClient.metrics(service)).thenReturn(
                new ExternalActuatorClient.MetricsSnapshot(true,
                        List.of(new ExternalActuatorClient.MetricSample(
                                "jvm.memory.used", "memory", "bytes",
                                List.of(new ExternalActuatorClient.Measurement("VALUE", 1024.0)))),
                        "snapshot", Instant.now(), List.of()));

        ToolResult result = tool.execute(new ToolExecutionContext(
                UUID.randomUUID(), "petclinic", START, END, Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("external")).isEqualTo(true);
        assertThat(result.output().get("timeWindowSupported")).isEqualTo(false);
        assertThat(result.output().get("evidenceIds")).asList()
                .containsExactly("metric:petclinic:jvm.memory.used");
        assertThat(result.output()).doesNotContainKey("services");
    }

    @Test
    void externalMetricsUnavailableIsExplicitFailure() {
        ExternalService service = externalService("petclinic");
        when(externalRegistry.findByName("petclinic")).thenReturn(Optional.of(service));
        when(externalClient.metrics(service)).thenReturn(
                ExternalActuatorClient.MetricsSnapshot.unavailable(
                        "指标端点未暴露", Instant.now()));

        ToolResult result = tool.execute(new ToolExecutionContext(
                UUID.randomUUID(), "petclinic", START, END, Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("不可用").contains("指标端点未暴露");
    }

    private ExternalService externalService(String name) {
        ExternalService service = new ExternalService();
        service.setId(UUID.randomUUID());
        service.setName(name);
        service.setBaseUrl("http://localhost:8083");
        service.setHealthPath("/actuator/health");
        service.setMetricsPath("/actuator/metrics");
        service.setEnabled(true);
        return service;
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

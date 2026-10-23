package com.microservice.rca.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microservice.rca.observability.ObservabilityClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryLogsToolTest {

    private static final Instant START = Instant.parse("2026-10-20T17:16:00Z");
    private static final Instant END = Instant.parse("2026-10-20T17:18:00Z");

    private final ObservabilityClient client = mock(ObservabilityClient.class);
    private final QueryLogsTool tool = new QueryLogsTool(client);

    @Test
    void mergesLogsAndExposesEvidenceSource() {
        Map<String, Object> orderData = Map.of(
                "source", "order-service RequestLogStore",
                "items", List.of(Map.of(
                        "id", "log-order-1",
                        "timestamp", "2026-10-20T17:17:40.377Z",
                        "serviceName", "order-service",
                        "requestId", "exp-1",
                        "status", 502,
                        "durationMs", 126,
                        "errorType", "DOWNSTREAM_HTTP_5XX")));
        Map<String, Object> inventoryData = Map.of(
                "source", "inventory-service RequestLogStore",
                "items", List.of(Map.of(
                        "id", "log-inventory-1",
                        "timestamp", "2026-10-20T17:17:40.379Z",
                        "serviceName", "inventory-service",
                        "requestId", "exp-1",
                        "status", 500,
                        "durationMs", 6,
                        "errorType", "HTTP_5XX")));
        when(client.queryLogs(anyString(), any(), any(), any(), anyInt())).thenReturn(List.of(
                new ObservabilityClient.ServiceQueryResult("order-service", orderData, null),
                new ObservabilityClient.ServiceQueryResult("inventory-service", inventoryData, null)));

        ToolResult result = tool.execute(context(Map.of("requestId", "exp-1", "limit", 50)));

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("itemCount")).isEqualTo(2);
        assertThat(result.output().get("evidenceIds")).asList()
                .containsExactly("log:order-service:log-order-1", "log:inventory-service:log-inventory-1");
        assertThat(result.output().get("dataSources")).asList().hasSize(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
        assertThat(items).extracting(item -> item.get("sourceService"))
                .containsExactly("order-service", "inventory-service");
    }

    @Test
    void keepsPartialFailureAsWarning() {
        when(client.queryLogs(anyString(), any(), any(), any(), anyInt())).thenReturn(List.of(
                new ObservabilityClient.ServiceQueryResult("order-service", null, "连接被拒绝"),
                new ObservabilityClient.ServiceQueryResult("inventory-service",
                        Map.of("source", "inventory-service RequestLogStore", "items", List.of()), null)));

        ToolResult result = tool.execute(context(Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("warnings")).asList().hasSize(1);
        assertThat(result.output().get("itemCount")).isEqualTo(0);
    }

    @Test
    void failsWhenNoDataSourceSucceeded() {
        when(client.queryLogs(anyString(), any(), any(), any(), anyInt())).thenReturn(List.of(
                new ObservabilityClient.ServiceQueryResult("order-service", null, "连接被拒绝"),
                new ObservabilityClient.ServiceQueryResult("inventory-service", null, "连接被拒绝")));

        ToolResult result = tool.execute(context(Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("query_logs 查询失败");
    }

    @Test
    void passesRemainingBudgetToObservabilityClient() {
        when(client.queryLogs(anyString(), any(), any(), any(), anyInt(), any())).thenReturn(List.of(
                new ObservabilityClient.ServiceQueryResult("order-service",
                        Map.of("source", "order-service RequestLogStore", "items", List.of()), null)));
        Duration timeout = Duration.ofSeconds(3);

        ToolResult result = tool.execute(new ToolExecutionContext(
                UUID.randomUUID(), "all", START, END, Map.of(), timeout));

        assertThat(result.success()).isTrue();
        verify(client).queryLogs("all", START, END, null, 50, timeout);
    }

    @Test
    void rejectsMissingTimeWindow() {
        ToolResult result = tool.execute(new ToolExecutionContext(
                UUID.randomUUID(), "all", null, null, Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("缺少时间窗口");
    }

    private ToolExecutionContext context(Map<String, Object> parameters) {
        return new ToolExecutionContext(UUID.randomUUID(), "all", START, END, parameters);
    }
}

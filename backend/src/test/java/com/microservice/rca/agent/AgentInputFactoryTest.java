package com.microservice.rca.agent;

import static com.microservice.rca.agent.AgentTestSupport.properties;
import static com.microservice.rca.agent.AgentTestSupport.tool;
import static org.assertj.core.api.Assertions.assertThat;

import com.microservice.rca.agent.AgentOrchestrator.AgentInput;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultSeverity;
import com.microservice.rca.external.domain.ExternalService;
import com.microservice.rca.external.service.ExternalServiceRegistry;
import com.microservice.rca.tools.DiagnosticToolRegistry;
import com.microservice.rca.tools.ToolResult;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentInputFactoryTest {

    @Test
    void whitelistExcludesExperimentPresetFields() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(Map.of())));
        AgentInputFactory factory = new AgentInputFactory(
                AgentTestSupport.json(), registry,
                AgentTestSupport.externalServiceRegistry(), properties(4, 3));
        DiagnosisTask task = new DiagnosisTask();
        task.setId(UUID.randomUUID());

        FaultEvent event = new FaultEvent();
        event.setId(UUID.randomUUID());
        event.setServiceName("inventory-service");
        event.setEventType("DOWNSTREAM_TIMEOUT");
        event.setSeverity(FaultSeverity.ERROR);
        event.setSummary("订单结算调用库存服务超时");
        event.setRequestId("exp-sanitized");
        event.setOccurredAt(Instant.parse("2026-10-21T02:00:00Z"));
        event.setExperimentFaultType("DELAY");
        event.setExperimentParameters("{\"delayMs\":3500}");
        event.setDetails("{\"scenario\":\"delay\"}");
        event.setObservedResult("{\"outcome\":\"DOWNSTREAM_TIMEOUT\",\"httpStatus\":502,"
                + "\"durationMs\":5410,\"experimentFaultType\":\"DELAY\","
                + "\"experimentParameters\":{\"delayMs\":3500},\"details\":{\"x\":1}}");

        AgentInput input = factory.create(task, event, Instant.parse("2026-10-21T02:05:00Z"));

        assertThat(input.observedResult())
                .containsEntry("outcome", "DOWNSTREAM_TIMEOUT")
                .containsEntry("httpStatus", 502)
                .containsEntry("durationMs", 5410)
                .doesNotContainKeys("experimentFaultType", "experimentParameters", "details");
        assertThat(input.observedResult().toString()).doesNotContain("DELAY").doesNotContain("3500");
        assertThat(input.windowStart()).isEqualTo(Instant.parse("2026-10-21T01:59:00Z"));
        assertThat(input.serviceKind()).isEqualTo("BUILT_IN");
        assertThat(input.allowedServices())
                .containsExactly("all", "order-service", "inventory-service");
        assertThat(input.availableTools()).extracting(DiagnosticToolRegistry.ToolDefinition::name)
                .containsExactly("query_logs");
    }

    @Test
    void externalEventGetsExternalScopeAndHealthTool() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(Map.of())),
                tool("query_metrics", ToolResult.success(Map.of())),
                tool("query_health", ToolResult.success(Map.of())));
        ExternalServiceRegistry externalRegistry = mock(ExternalServiceRegistry.class);
        ExternalService petclinic = new ExternalService();
        petclinic.setName("petclinic");
        petclinic.setLogSourceUrl("http://localhost:8083/logs");
        when(externalRegistry.findByName("petclinic")).thenReturn(Optional.of(petclinic));
        AgentInputFactory factory = new AgentInputFactory(
                AgentTestSupport.json(), registry, externalRegistry, properties(4, 3));
        DiagnosisTask task = new DiagnosisTask();
        task.setId(UUID.randomUUID());

        FaultEvent event = new FaultEvent();
        event.setId(UUID.randomUUID());
        event.setServiceName("petclinic");
        event.setEventType("EXTERNAL_ANOMALY");
        event.setSeverity(FaultSeverity.WARNING);
        event.setSummary("外部服务响应变慢");
        event.setOccurredAt(Instant.parse("2026-10-21T02:00:00Z"));
        event.setSource("EXTERNAL");
        // 即使外部事件意外带有实验字段，也不能进入模型输入。
        event.setExperimentFaultType("DELAY");
        event.setExperimentParameters("{\"delayMs\":3500}");
        event.setObservedResult("{\"outcome\":\"DELAY\",\"experimentFaultType\":\"DELAY\"}");

        AgentInput input = factory.create(task, event, Instant.parse("2026-10-21T02:05:00Z"));

        assertThat(input.serviceKind()).isEqualTo("EXTERNAL");
        assertThat(input.allowedServices()).containsExactly("petclinic");
        assertThat(input.availableTools()).extracting(DiagnosticToolRegistry.ToolDefinition::name)
                .containsExactlyInAnyOrder("query_logs", "query_metrics", "query_health");
        assertThat(input.unavailableTools()).isEmpty();
        assertThat(input.observedResult()).containsEntry("outcome", "DELAY")
                .doesNotContainKeys("experimentFaultType", "experimentParameters");
    }

    @Test
    void externalEventWithoutLogSourceDoesNotExposeQueryLogs() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(Map.of())),
                tool("query_metrics", ToolResult.success(Map.of())),
                tool("query_health", ToolResult.success(Map.of())));
        ExternalServiceRegistry externalRegistry = mock(ExternalServiceRegistry.class);
        ExternalService petclinic = new ExternalService();
        petclinic.setName("petclinic");
        when(externalRegistry.findByName("petclinic")).thenReturn(Optional.of(petclinic));
        AgentInputFactory factory = new AgentInputFactory(
                AgentTestSupport.json(), registry, externalRegistry, properties(4, 3));

        DiagnosisTask task = new DiagnosisTask();
        task.setId(UUID.randomUUID());
        FaultEvent event = new FaultEvent();
        event.setId(UUID.randomUUID());
        event.setServiceName("petclinic");
        event.setEventType("EXTERNAL_ANOMALY");
        event.setSeverity(FaultSeverity.WARNING);
        event.setSummary("外部服务响应变慢");
        event.setOccurredAt(Instant.parse("2026-10-21T02:00:00Z"));
        event.setSource("EXTERNAL");

        AgentInput input = factory.create(task, event, Instant.parse("2026-10-21T02:05:00Z"));

        assertThat(input.availableTools()).extracting(DiagnosticToolRegistry.ToolDefinition::name)
                .containsExactlyInAnyOrder("query_metrics", "query_health");
        assertThat(input.unavailableTools()).containsKey("query_logs");
        assertThat(input.unavailableTools().get("query_logs")).contains("未配置日志数据源");
    }

    @Test
    void invalidObservedResultBecomesEmptyMap() {
        AgentInputFactory factory = new AgentInputFactory(
                AgentTestSupport.json(),
                AgentTestSupport.registry(tool("query_logs", ToolResult.success(Map.of()))),
                AgentTestSupport.externalServiceRegistry(),
                properties(4, 3));
        DiagnosisTask task = new DiagnosisTask();
        task.setId(UUID.randomUUID());

        FaultEvent event = new FaultEvent();
        event.setId(UUID.randomUUID());
        event.setServiceName("order-service");
        event.setEventType("ERROR_RATE_SPIKE");
        event.setSeverity(FaultSeverity.ERROR);
        event.setSummary("订单服务错误率升高");
        event.setOccurredAt(Instant.parse("2026-10-21T02:00:00Z"));
        event.setObservedResult("not-json");

        AgentInput input = factory.create(task, event, Instant.parse("2026-10-21T02:05:00Z"));

        assertThat(input.observedResult()).isEmpty();
        assertThat(input.requestId()).isNull();
    }
}

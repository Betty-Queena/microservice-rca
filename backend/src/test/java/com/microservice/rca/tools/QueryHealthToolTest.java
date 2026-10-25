package com.microservice.rca.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microservice.rca.external.client.ExternalActuatorClient;
import com.microservice.rca.external.domain.ExternalService;
import com.microservice.rca.external.service.ExternalServiceRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryHealthToolTest {

    private final ExternalServiceRegistry registry = mock(ExternalServiceRegistry.class);
    private final ExternalActuatorClient client = mock(ExternalActuatorClient.class);
    private final QueryHealthTool tool = new QueryHealthTool(registry, client);

    @Test
    void exposesComponentStatusesAndComponentEvidenceIds() {
        ExternalService service = service();
        Instant collectedAt = Instant.parse("2026-10-25T02:00:00Z");
        when(registry.findByName("petclinic")).thenReturn(Optional.of(service));
        when(client.health(service)).thenReturn(new ExternalActuatorClient.HealthSnapshot(
                true, "DOWN", 503, 3013, "健康检查返回 HTTP 503", collectedAt,
                List.of(
                        new ExternalActuatorClient.HealthComponent(
                                "db", "DOWN", "java.sql.SQLException",
                                "Failed to obtain JDBC Connection", false),
                        new ExternalActuatorClient.HealthComponent(
                                "diskSpace", "UP", null, null, false)),
                false, 0));

        ToolResult result = tool.execute(new ToolExecutionContext(
                UUID.randomUUID(), "petclinic", collectedAt.minusSeconds(60),
                collectedAt, Map.of()));

        assertThat(result.success()).isTrue();
        String parentEvidenceId = "health:petclinic:" + collectedAt.toEpochMilli();
        assertThat(result.output().get("evidenceIds")).asList().containsExactly(
                parentEvidenceId,
                parentEvidenceId + ":db",
                parentEvidenceId + ":diskSpace");
        assertThat(result.output().get("componentEvidenceIds")).asList().containsExactly(
                parentEvidenceId + ":db",
                parentEvidenceId + ":diskSpace");
        assertThat(result.output().get("componentCount")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components =
                (List<Map<String, Object>>) result.output().get("components");
        assertThat(components)
                .filteredOn(component -> "db".equals(component.get("path")))
                .singleElement()
                .satisfies(component -> {
                    assertThat(component.get("path")).isEqualTo("db");
                    assertThat(component.get("status")).isEqualTo("DOWN");
                    assertThat(component.get("errorType")).isEqualTo("java.sql.SQLException");
                    assertThat(component.get("errorSummary"))
                            .isEqualTo("Failed to obtain JDBC Connection");
                    assertThat(component.get("evidenceId")).isEqualTo(parentEvidenceId + ":db");
                });
        assertThat(String.valueOf(result.output().get("dataNote"))).contains("脱敏");
    }

    @Test
    void keepsHealthOutputCompatibleWhenComponentsAreAbsent() {
        ExternalService service = service();
        Instant collectedAt = Instant.parse("2026-10-25T02:00:00Z");
        when(registry.findByName("petclinic")).thenReturn(Optional.of(service));
        when(client.health(service)).thenReturn(new ExternalActuatorClient.HealthSnapshot(
                true, "UP", 200, 20, "健康检查成功", collectedAt));

        ToolResult result = tool.execute(new ToolExecutionContext(
                UUID.randomUUID(), "petclinic", collectedAt.minusSeconds(60),
                collectedAt, Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("status")).isEqualTo("UP");
        assertThat(result.output().get("httpStatus")).isEqualTo(200);
        assertThat(result.output().get("components")).asList().isEmpty();
        assertThat(result.output().get("componentEvidenceIds")).asList().isEmpty();
        assertThat(result.output().get("evidenceIds")).asList()
                .containsExactly("health:petclinic:" + collectedAt.toEpochMilli());
    }

    private ExternalService service() {
        ExternalService service = new ExternalService();
        service.setId(UUID.randomUUID());
        service.setName("petclinic");
        service.setBaseUrl("http://localhost:8083");
        service.setHealthPath("/actuator/health");
        service.setEnabled(true);
        return service;
    }
}

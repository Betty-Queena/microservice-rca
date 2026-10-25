package com.microservice.rca.remediation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.agent.DiagnosisTaskRecovery;
import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.remediation.domain.RecoveryCodes;
import com.microservice.rca.testsupport.TestDatabaseGuard;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 启动恢复在真实 PostgreSQL 上的行为验证。
 *
 * <p>默认不执行，只有显式设置 RCA_V5_INTEGRATION=true 且连接到 rca_v5_* 临时库时运行。
 * 测试关闭自动启动恢复，显式调用 recover，避免测试时序不确定。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rca.remediation.recovery.enabled=false",
                "rca.test.expected-database-prefix=rca_v5_"
        })
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RCA_V5_INTEGRATION", matches = "true")
class RemediationRecoveryIntegrationTest {

    /**
     * 屏蔽诊断任务启动恢复，避免上下文启动阶段扫描并修改测试库中的诊断任务。
     * 本类只显式调用 RemediationRecovery，不会触发自动恢复。
     */
    @MockitoBean
    private DiagnosisTaskRecovery diagnosisTaskRecovery;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RemediationStore store;

    @Autowired
    private JsonSupport jsonSupport;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<UUID> eventIds = new ArrayList<>();

    @BeforeEach
    void requireTemporaryDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database)
                .as("启动恢复集成测试只能连接临时验证库")
                .startsWith("rca_v5_");
    }

    @AfterEach
    void cleanup() {
        eventIds.forEach(eventId -> {
            jdbc.update("delete from remediation_action where fault_event_id = ?", eventId);
            jdbc.update("delete from diagnosis_task where fault_event_id = ?", eventId);
            jdbc.update("delete from fault_event where id = ?", eventId);
        });
        eventIds.clear();
    }

    @Test
    void pendingIsRecoveredBeforeActionWithoutTouchingEvent() {
        EventTask eventTask = newEventTask();
        Instant now = Instant.now();
        UUID remediationId = insertRemediation(eventTask, "PENDING",
                now.minusSeconds(60), null, null, null);

        recovery(now).recover(now);

        Map<String, Object> row = remediation(remediationId);
        assertThat(row.get("status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(row.get("failure_code")).isEqualTo(RecoveryCodes.BEFORE_ACTION);
        assertThat((String) row.get("failure_reason"))
                .contains("动作确定尚未开始");
        assertThat(row.get("event_status_updated")).isEqualTo(false);
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("action_result")).isNull();
        assertThat(row.get("verification_result")).isNull();
        assertThat((String) row.get("audit_trace")).contains("RECOVERY_MARKED");
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);
        assertEventStillOpen(eventTask.eventId());
    }

    @Test
    void successfulActionWithoutPersistedVerificationIsUnknownAndPreservesAction() {
        EventTask eventTask = newEventTask();
        Instant now = Instant.now();
        UUID remediationId = insertRemediation(eventTask, "EXECUTING",
                now.minusSeconds(120), now.minusSeconds(90),
                "{\"success\":true,\"outcome\":\"DISABLED\"}", null);

        recovery(now).recover(now);

        Map<String, Object> row = remediation(remediationId);
        assertThat(row.get("status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(row.get("failure_code")).isEqualTo(RecoveryCodes.VERIFICATION_UNKNOWN);
        assertThat((String) row.get("failure_reason"))
                .contains("验证请求可能已执行");
        assertThat((String) row.get("action_result"))
                .contains("DISABLED");
        assertThat(row.get("verification_result")).isNull();
        assertThat(row.get("event_status_updated")).isEqualTo(false);
        assertEventStillOpen(eventTask.eventId());
    }

    @Test
    void cutoffExcludesRecordsCreatedDuringCurrentStartup() {
        EventTask eventTask = newEventTask();
        Instant cutoff = Instant.parse("2026-10-24T10:00:00Z");
        UUID remediationId = insertRemediation(eventTask, "PENDING",
                cutoff.plusSeconds(1), null, null, null);

        recovery(cutoff).recover(cutoff);

        Map<String, Object> row = remediation(remediationId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("failure_code")).isNull();
        assertThat(row.get("completed_at")).isNull();
        assertThat(((Number) row.get("version")).longValue()).isZero();
        assertEventStillOpen(eventTask.eventId());
    }

    @Test
    void repeatedRecoveryDoesNotModifyAlreadyRecoveredRows() {
        EventTask eventTask = newEventTask();
        Instant now = Instant.now();
        UUID remediationId = insertRemediation(eventTask, "EXECUTING",
                now.minusSeconds(120), now.minusSeconds(90), null, null);

        RemediationRecovery recovery = recovery(now);
        recovery.recover(now);
        Map<String, Object> first = remediation(remediationId);

        recovery.recover(now);
        Map<String, Object> second = remediation(remediationId);

        assertThat(second.get("status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(second.get("failure_code")).isEqualTo(RecoveryCodes.ACTION_UNKNOWN);
        assertThat(second.get("completed_at")).isEqualTo(first.get("completed_at"));
        assertThat(second.get("audit_trace")).isEqualTo(first.get("audit_trace"));
        assertThat(((Number) second.get("version")).longValue())
                .isEqualTo(((Number) first.get("version")).longValue());
    }

    private RemediationRecovery recovery(Instant cutoff) {
        return new RemediationRecovery(store, jsonSupport, objectMapper, cutoff);
    }

    private Map<String, Object> remediation(UUID remediationId) {
        return jdbc.queryForMap("""
                select status, failure_code, failure_reason, event_status_updated,
                       completed_at, action_result::text as action_result,
                       verification_result::text as verification_result,
                       audit_trace::text as audit_trace, version
                  from remediation_action
                 where id = ?
                """, remediationId);
    }

    private void assertEventStillOpen(UUID eventId) {
        assertThat(jdbc.queryForObject(
                "select status from fault_event where id = ?", String.class, eventId))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("""
                select count(*) from fault_event where id = ? and resolved_at is null
                """, Integer.class, eventId)).isEqualTo(1);
    }

    private EventTask newEventTask() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                insert into fault_event
                    (id, service_name, event_type, severity, summary, status,
                     occurred_at, source, experiment_id)
                values (?, 'inventory-service', 'DOWNSTREAM_HTTP_5XX', 'ERROR',
                        'Recovery integration test event', 'OPEN', ?, 'EXPERIMENT', ?)
                """, eventId, Timestamp.from(Instant.now()), UUID.randomUUID());

        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                insert into diagnosis_task
                    (id, fault_event_id, status, trigger_source, goal,
                     result_status, version)
                values (?, ?, 'SUCCEEDED', 'AGENT', 'Recovery integration test task',
                        'ROOT_CAUSE_IDENTIFIED', 0)
                """, taskId, eventId);
        eventIds.add(eventId);
        return new EventTask(eventId, taskId);
    }

    private UUID insertRemediation(EventTask eventTask,
                                   String status,
                                   Instant requestedAt,
                                   Instant startedAt,
                                   String actionResult,
                                   String verificationResult) {
        UUID remediationId = UUID.randomUUID();
        jdbc.update("""
                insert into remediation_action
                    (id, fault_event_id, diagnosis_task_id, action_type,
                     target_service, experiment_id, status, requested_by,
                     action_result, verification_result, event_status_updated,
                     requested_at, started_at, version)
                values (?, ?, ?, 'DISABLE_EXPERIMENT_FAULT', 'inventory-service',
                        ?, ?, 'recovery-test', cast(? as jsonb), cast(? as jsonb),
                        false, ?, ?, 0)
                """, remediationId, eventTask.eventId(), eventTask.taskId(),
                UUID.randomUUID(), status, actionResult, verificationResult,
                Timestamp.from(requestedAt),
                startedAt != null ? Timestamp.from(startedAt) : null);
        return remediationId;
    }

    private record EventTask(UUID eventId, UUID taskId) {
    }
}

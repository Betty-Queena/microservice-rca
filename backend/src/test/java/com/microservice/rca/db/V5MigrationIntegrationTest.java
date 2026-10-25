package com.microservice.rca.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microservice.rca.agent.DiagnosisTaskRecovery;
import com.microservice.rca.testsupport.TestDatabaseGuard;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * V5 迁移在干净数据库上的验证。
 *
 * <p>默认不执行，只有显式设置 RCA_V5_INTEGRATION=true 且连接到 rca_v5_* 临时库时运行。
 * 测试会写入少量临时数据并在用例结束后清理，不会连接 rca 业务库。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rca.remediation.recovery.enabled=false",
                "rca.test.expected-database-prefix=rca_v5_"
        })
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RCA_V5_INTEGRATION", matches = "true")
class V5MigrationIntegrationTest {

    /**
     * 屏蔽启动恢复监听器，避免上下文启动阶段扫描并修改诊断任务。
     */
    @MockitoBean
    private DiagnosisTaskRecovery diagnosisTaskRecovery;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireTemporaryDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database)
                .as("V5 集成测试只能连接临时验证库")
                .startsWith("rca_v5_");
    }

    @Test
    void flywayAppliedVersionFive() {
        Integer applied = jdbc.queryForObject("""
                select count(*) from flyway_schema_history
                 where version = '5' and success = true
                """, Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void remediationTableAndIndexesExist() {
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.columns
                 where table_name = 'remediation_action'
                 order by ordinal_position
                """, String.class);
        assertThat(columns).contains(
                "id", "fault_event_id", "diagnosis_task_id", "action_type",
                "target_service", "experiment_id", "status", "failure_code",
                "failure_reason", "requested_by", "operator_note", "idempotency_key",
                "action_result", "verification_result", "audit_trace",
                "verification_request_id", "verification_fault_event_id",
                "event_status_updated", "requested_at", "started_at",
                "completed_at", "version");

        List<String> indexes = jdbc.queryForList("""
                select indexname from pg_indexes
                 where tablename = 'remediation_action'
                """, String.class);
        assertThat(indexes).contains(
                "uk_remediation_active_event",
                "uk_remediation_idempotency",
                "idx_remediation_event_requested",
                "idx_remediation_status_requested");
    }

    @Test
    void twoActiveRemediationsForSameEventAreRejected() {
        UUID eventId = insertEvent();
        try {
            UUID taskId = insertTask(eventId);
            insertRemediation(eventId, taskId, "PENDING", null);

            assertThatThrownBy(() -> insertRemediation(eventId, taskId, "EXECUTING", null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            cleanup(eventId);
        }
    }

    @Test
    void idempotencyKeyIsUniquePerEvent() {
        UUID eventId = insertEvent();
        try {
            UUID taskId = insertTask(eventId);
            String key = UUID.randomUUID().toString();
            insertRemediation(eventId, taskId, "SUCCEEDED", key);

            assertThatThrownBy(() -> insertRemediation(eventId, taskId, "SUCCEEDED", key))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            cleanup(eventId);
        }
    }

    @Test
    void whitelistAndStatusConstraintsAreEnforced() {
        UUID eventId = insertEvent();
        try {
            UUID taskId = insertTask(eventId);

            assertThatThrownBy(() -> insertRemediationWithAction(
                    eventId, taskId, "DROP_TABLE", "PENDING"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertRemediation(
                    eventId, taskId, "DONE", null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            cleanup(eventId);
        }
    }

    private UUID insertEvent() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                insert into fault_event
                    (id, service_name, event_type, severity, summary, status,
                     occurred_at, source, experiment_id)
                values (?, 'inventory-service', 'DOWNSTREAM_HTTP_5XX', 'ERROR',
                        'V5 integration test event', 'OPEN', ?, 'EXPERIMENT', ?)
                """, eventId, Timestamp.from(Instant.now()), UUID.randomUUID());
        return eventId;
    }

    private UUID insertTask(UUID eventId) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                insert into diagnosis_task
                    (id, fault_event_id, status, trigger_source, goal,
                     result_status, version)
                values (?, ?, 'SUCCEEDED', 'AGENT', 'V5 integration test task',
                        'ROOT_CAUSE_IDENTIFIED', 0)
                """, taskId, eventId);
        return taskId;
    }

    private UUID insertRemediation(UUID eventId, UUID taskId,
                                   String status, String idempotencyKey) {
        return insertRemediationWithAction(eventId, taskId, "DISABLE_EXPERIMENT_FAULT", status,
                idempotencyKey);
    }

    private UUID insertRemediationWithAction(UUID eventId, UUID taskId,
                                             String actionType, String status) {
        return insertRemediationWithAction(eventId, taskId, actionType, status, null);
    }

    private UUID insertRemediationWithAction(UUID eventId, UUID taskId,
                                             String actionType, String status,
                                             String idempotencyKey) {
        UUID remediationId = UUID.randomUUID();
        jdbc.update("""
                insert into remediation_action
                    (id, fault_event_id, diagnosis_task_id, action_type,
                     target_service, experiment_id, status, requested_by,
                     idempotency_key, requested_at)
                values (?, ?, ?, ?, 'inventory-service', ?, ?, 'test', ?, ?)
                """, remediationId, eventId, taskId, actionType, UUID.randomUUID(),
                status, idempotencyKey, Timestamp.from(Instant.now()));
        return remediationId;
    }

    private void cleanup(UUID eventId) {
        jdbc.update("delete from remediation_action where fault_event_id = ?", eventId);
        jdbc.update("delete from diagnosis_task where fault_event_id = ?", eventId);
        jdbc.update("delete from fault_event where id = ?", eventId);
    }
}

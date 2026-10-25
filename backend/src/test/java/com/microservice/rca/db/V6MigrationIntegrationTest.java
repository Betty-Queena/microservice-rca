package com.microservice.rca.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microservice.rca.agent.DiagnosisTaskRecovery;
import com.microservice.rca.testsupport.TestDatabaseGuard;
import java.sql.Timestamp;
import java.time.Instant;
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
 * V6 迁移在干净数据库上的验证。
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
class V6MigrationIntegrationTest {

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
                .as("V6 集成测试只能连接临时验证库")
                .startsWith("rca_v5_");
    }

    @Test
    void flywayAppliedVersionSix() {
        Integer applied = jdbc.queryForObject("""
                select count(*) from flyway_schema_history
                 where version = '6' and success = true
                """, Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void recoveryRequiredIsAllowedAndActiveUniquenessIsPreserved() {
        UUID eventId = insertEvent();
        try {
            UUID taskId = insertTask(eventId);

            String statusConstraint = jdbc.queryForObject("""
                    select pg_get_constraintdef(c.oid)
                      from pg_constraint c
                      join pg_class t on t.oid = c.conrelid
                     where t.relname = 'remediation_action'
                       and c.conname = 'ck_remediation_status'
                    """, String.class);
            assertThat(statusConstraint)
                    .contains("PENDING", "EXECUTING", "SUCCEEDED",
                            "ACTION_FAILED", "VERIFICATION_FAILED",
                            "VERIFICATION_INCONCLUSIVE", "RECOVERY_REQUIRED");

            // RECOVERY_REQUIRED 是终态，不受活跃唯一索引约束，可保留多条历史记录。
            insertRemediation(eventId, taskId, "RECOVERY_REQUIRED");
            insertRemediation(eventId, taskId, "RECOVERY_REQUIRED");

            assertThatThrownBy(() -> insertRemediation(eventId, taskId, "DONE"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // 活跃唯一索引仍然只覆盖 PENDING/EXECUTING。
            insertRemediation(eventId, taskId, "PENDING");
            assertThatThrownBy(() -> insertRemediation(eventId, taskId, "EXECUTING"))
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
                        'V6 integration test event', 'OPEN', ?, 'EXPERIMENT', ?)
                """, eventId, Timestamp.from(Instant.now()), UUID.randomUUID());
        return eventId;
    }

    private UUID insertTask(UUID eventId) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                insert into diagnosis_task
                    (id, fault_event_id, status, trigger_source, goal,
                     result_status, version)
                values (?, ?, 'SUCCEEDED', 'AGENT', 'V6 integration test task',
                        'ROOT_CAUSE_IDENTIFIED', 0)
                """, taskId, eventId);
        return taskId;
    }

    private UUID insertRemediation(UUID eventId, UUID taskId, String status) {
        UUID remediationId = UUID.randomUUID();
        jdbc.update("""
                insert into remediation_action
                    (id, fault_event_id, diagnosis_task_id, action_type,
                     target_service, experiment_id, status, requested_by,
                     requested_at)
                values (?, ?, ?, 'DISABLE_EXPERIMENT_FAULT', 'inventory-service',
                        ?, ?, 'test', ?)
                """, remediationId, eventId, taskId, UUID.randomUUID(),
                status, Timestamp.from(Instant.now()));
        return remediationId;
    }

    private void cleanup(UUID eventId) {
        jdbc.update("delete from remediation_action where fault_event_id = ?", eventId);
        jdbc.update("delete from diagnosis_task where fault_event_id = ?", eventId);
        jdbc.update("delete from fault_event where id = ?", eventId);
    }
}

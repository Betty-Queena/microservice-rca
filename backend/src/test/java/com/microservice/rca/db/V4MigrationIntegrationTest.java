package com.microservice.rca.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microservice.rca.agent.DiagnosisTaskRecovery;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.repository.FaultEventRepository;
import com.microservice.rca.testsupport.TestDatabaseGuard;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * V4 迁移在干净数据库上的验证。
 *
 * <p>默认不执行，只有显式设置 RCA_V4_INTEGRATION=true 且连接到 rca_v4_* 临时库时运行。
 * 测试会写入少量临时数据并在用例结束后清理，不会连接 rca 业务库。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rca.remediation.recovery.enabled=false",
                "rca.test.expected-database-prefix=rca_v4_"
        })
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RCA_V4_INTEGRATION", matches = "true")
class V4MigrationIntegrationTest {

    /**
     * 屏蔽启动恢复监听器：避免上下文启动时扫描并修改数据库中的诊断任务。
     * 该监听器在测试的 @BeforeEach 库名校验之前执行。
     */
    @MockitoBean
    private DiagnosisTaskRecovery diagnosisTaskRecovery;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FaultEventRepository eventRepository;

    @BeforeEach
    void requireTemporaryDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database)
                .as("V4 集成测试只能连接临时验证库")
                .startsWith("rca_v4_");
    }

    @Test
    void flywayAppliedVersionFourAndVersionColumnsExist() {
        Integer applied = jdbc.queryForObject("""
                select count(*) from flyway_schema_history
                 where version = '4' and success = true
                """, Integer.class);
        assertThat(applied).isEqualTo(1);

        List<String> columns = jdbc.queryForList("""
                select table_name || '.' || column_name
                  from information_schema.columns
                 where table_name in ('fault_event', 'diagnosis_task')
                   and column_name = 'version'
                 order by table_name
                """, String.class);
        assertThat(columns).containsExactly("diagnosis_task.version", "fault_event.version");
    }

    @Test
    void partialUniqueIndexRejectsTwoActiveAgentTasks() {
        UUID eventId = insertEvent();
        try {
            insertTask(eventId, "PENDING", "AGENT");
            assertThatThrownBy(() -> insertTask(eventId, "RUNNING", "AGENT_RETRY"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            cleanup(eventId);
        }
    }

    @Test
    void terminalAndManualEvidenceTasksAreNotBlocked() {
        UUID eventId = insertEvent();
        try {
            insertTask(eventId, "SUCCEEDED", "AGENT");
            insertTask(eventId, "FAILED", "AGENT_RETRY");
            insertTask(eventId, "PENDING", "MANUAL_EVIDENCE");
            insertTask(eventId, "RUNNING", "MANUAL_EVIDENCE");

            Integer count = jdbc.queryForObject(
                    "select count(*) from diagnosis_task where fault_event_id = ?",
                    Integer.class, eventId);
            assertThat(count).isEqualTo(4);
        } finally {
            cleanup(eventId);
        }
    }

    @Test
    void optimisticLockingRejectsStaleEventUpdate() {
        UUID eventId = insertEvent();
        try {
            FaultEvent stale = eventRepository.findById(eventId).orElseThrow();
            stale.setStatus(FaultEventStatus.RESOLVED);

            // 模拟另一个事务先更新同一行：版本号 +1，stale 实体仍持有旧版本。
            jdbc.update("""
                    update fault_event
                       set status = 'CLOSED', version = version + 1
                     where id = ?
                    """, eventId);

            assertThatThrownBy(() -> eventRepository.save(stale))
                    .isInstanceOf(OptimisticLockingFailureException.class);

            String status = jdbc.queryForObject(
                    "select status from fault_event where id = ?", String.class, eventId);
            assertThat(status).isEqualTo("CLOSED");
        } finally {
            cleanup(eventId);
        }
    }

    private UUID insertEvent() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                insert into fault_event
                    (id, service_name, event_type, severity, summary, status, occurred_at, version)
                values (?, 'test-service', 'TEST_EVENT', 'WARNING', 'V4 integration test',
                        'OPEN', ?, 0)
                """, eventId, java.sql.Timestamp.from(Instant.now()));
        return eventId;
    }

    private void insertTask(UUID eventId, String status, String triggerSource) {
        jdbc.update("""
                insert into diagnosis_task
                    (id, fault_event_id, status, trigger_source, goal, version)
                values (?, ?, ?, ?, 'V4 integration test task', 0)
                """, UUID.randomUUID(), eventId, status, triggerSource);
    }

    private void cleanup(UUID eventId) {
        jdbc.update("delete from diagnosis_task where fault_event_id = ?", eventId);
        jdbc.update("delete from fault_event where id = ?", eventId);
    }
}

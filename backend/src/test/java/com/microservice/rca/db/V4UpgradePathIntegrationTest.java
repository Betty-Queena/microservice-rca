package com.microservice.rca.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.microservice.rca.agent.DiagnosisTaskRecovery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 验证 V3 -> V4 升级路径，以及升级时历史数据不被删除或覆盖。
 *
 * <p>启动时只迁移到 V3（spring.flyway.target=3），测试内再执行 V4。
 * 默认不执行，只有 RCA_V4_UPGRADE=true 且连接 rca_v4_upgrade 临时库时运行。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.target=3",
                "spring.jpa.hibernate.ddl-auto=none"
        })
@EnabledIfEnvironmentVariable(named = "RCA_V4_UPGRADE", matches = "true")
class V4UpgradePathIntegrationTest {

    /**
     * V3 库还没有 version 列，启动恢复监听器查询实体时会失败。
     * 这里替换为 mock，避免启动恢复逻辑干扰升级路径验证。
     */
    @MockitoBean
    private DiagnosisTaskRecovery diagnosisTaskRecovery;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void requireUpgradeDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database)
                .as("V4 升级测试只能连接 rca_v4_upgrade 临时库")
                .isEqualTo("rca_v4_upgrade");
    }

    @Test
    void upgradesFromV3ToV4WithoutLosingHistory() {
        Integer currentVersion = jdbc.queryForObject("""
                select max(version::int) from flyway_schema_history where success = true
                """, Integer.class);
        assertThat(currentVersion).isEqualTo(3);
        assertThat(columnExists("fault_event", "version")).isFalse();
        assertThat(columnExists("diagnosis_task", "version")).isFalse();

        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                insert into fault_event
                    (id, service_name, event_type, severity, summary, status, occurred_at)
                values (?, 'upgrade-service', 'UPGRADE_TEST', 'WARNING', 'V3 data', 'RESOLVED', ?)
                """, eventId, java.sql.Timestamp.from(Instant.now()));
        jdbc.update("""
                insert into diagnosis_task
                    (id, fault_event_id, status, trigger_source, goal)
                values (?, ?, 'SUCCEEDED', 'AGENT', 'V3 history task')
                """, UUID.randomUUID(), eventId);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        Integer migratedVersion = jdbc.queryForObject("""
                select max(version::int) from flyway_schema_history where success = true
                """, Integer.class);
        assertThat(migratedVersion).isEqualTo(4);
        assertThat(columnExists("fault_event", "version")).isTrue();
        assertThat(columnExists("diagnosis_task", "version")).isTrue();

        // 历史数据保留，version 从 0 开始。
        assertThat(jdbc.queryForObject(
                "select version from fault_event where id = ?", Long.class, eventId))
                .isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from diagnosis_task
                 where fault_event_id = ? and status = 'SUCCEEDED'
                """, Integer.class, eventId)).isEqualTo(1);

        List<String> indexes = jdbc.queryForList("""
                select indexname from pg_indexes
                 where tablename = 'diagnosis_task'
                   and indexname = 'uk_diagnosis_task_active_agent'
                """, String.class);
        assertThat(indexes).containsExactly("uk_diagnosis_task_active_agent");

        jdbc.update("delete from diagnosis_task where fault_event_id = ?", eventId);
        jdbc.update("delete from fault_event where id = ?", eventId);
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_name = ? and column_name = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }
}

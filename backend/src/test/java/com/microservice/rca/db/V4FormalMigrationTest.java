package com.microservice.rca.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.microservice.rca.agent.DiagnosisTaskRecovery;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 正式库 V4 迁移验证，只读取 schema，不写入业务数据。
 *
 * <p>默认不执行。只有显式设置 RCA_V4_FORMAL=true、连接 rca 库，
 * 且迁移前检查确认没有活跃诊断任务时才执行。测试只读取 schema：显式关闭
 * Flyway 与 Hibernate 校验，避免在数据库名称断言之前修改正式库。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // 该测试验证正式库现有 schema，绝不能由测试上下文触发迁移。
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "rca.remediation.recovery.enabled=false"
        })
@EnabledIfEnvironmentVariable(named = "RCA_V4_FORMAL", matches = "true")
class V4FormalMigrationTest {

    /**
     * 正式库测试只读 schema；替换启动恢复器，避免上下文启动时把遗留活跃任务标记为 FAILED。
     */
    @MockitoBean
    private DiagnosisTaskRecovery diagnosisTaskRecovery;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireProductionDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database).isEqualTo("rca");
    }

    @Test
    void appliesV4AndValidatesSchema() {
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

        List<String> indexes = jdbc.queryForList("""
                select indexname from pg_indexes
                 where tablename = 'diagnosis_task'
                   and indexname = 'uk_diagnosis_task_active_agent'
                """, String.class);
        assertThat(indexes).containsExactly("uk_diagnosis_task_active_agent");
    }
}

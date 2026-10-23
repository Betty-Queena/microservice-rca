package com.microservice.rca.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 正式库 V4 迁移验证，只读取 schema，不写入业务数据。
 *
 * <p>默认不执行。只有显式设置 RCA_V4_FORMAL=true、连接 rca 库，
 * 且迁移前检查确认没有活跃诊断任务时才执行。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "RCA_V4_FORMAL", matches = "true")
class V4FormalMigrationTest {

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

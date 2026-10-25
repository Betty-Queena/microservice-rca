package com.microservice.rca.testsupport;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 在 Spring 上下文 refresh 之前校验数据源指向的是隔离测试库。
 *
 * <p>测试类中的 {@code @BeforeEach} 数据库名断言发生在上下文启动之后，此时
 * Flyway 迁移、JPA 初始化和启动恢复监听器都已经执行，无法阻止它们访问或修改
 * 错误数据库。该 initializer 由 {@code @ContextConfiguration} 注册，在 DataSource、
 * Flyway 和业务 bean 创建之前执行，因此能在任何数据库写入之前终止测试。</p>
 *
 * <p>使用方必须通过 {@code rca.test.expected-database}（精确匹配）或
 * {@code rca.test.expected-database-prefix}（前缀匹配）声明预期库名；两项都未
 * 配置时直接失败，避免新测试类忘记声明约束后误连正式库。</p>
 */
public final class TestDatabaseGuard
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String EXACT_PROPERTY = "rca.test.expected-database";
    static final String PREFIX_PROPERTY = "rca.test.expected-database-prefix";

    /**
     * 所有允许的测试库都必须符合该命名空间；即使属性配置错误，也不能指向
     * 正式库 rca 或其他业务库。
     */
    private static final String TEST_DATABASE_NAMESPACE = "^rca_(v4|v5)_[a-z0-9_]+$";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String url = context.getEnvironment().getProperty("spring.datasource.url");
        String database = databaseName(url);
        String expected = trimToNull(
                context.getEnvironment().getProperty(EXACT_PROPERTY));
        String prefix = trimToNull(
                context.getEnvironment().getProperty(PREFIX_PROPERTY));

        if (expected == null && prefix == null) {
            throw new IllegalStateException(
                    "数据库集成测试未声明隔离库约束，拒绝启动。请设置 "
                            + EXACT_PROPERTY + " 或 " + PREFIX_PROPERTY);
        }
        if (database == null) {
            throw new IllegalStateException(
                    "无法从 spring.datasource.url 解析数据库名，拒绝启动数据库集成测试");
        }
        if (!database.matches(TEST_DATABASE_NAMESPACE)) {
            throw new IllegalStateException(
                    "拒绝连接非隔离测试库: " + database
                            + "（必须匹配 " + TEST_DATABASE_NAMESPACE + "）");
        }
        if (expected != null && !expected.equals(database)) {
            throw new IllegalStateException(
                    "数据库名不符合测试要求: 期望 " + expected + "，实际 " + database);
        }
        if (prefix != null && !database.startsWith(prefix)) {
            throw new IllegalStateException(
                    "数据库名不符合测试要求: 期望以 " + prefix + " 开头，实际 " + database);
        }
    }

    private String databaseName(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) {
            return null;
        }
        String name = url.substring(slash + 1);
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        int params = name.indexOf(';');
        if (params >= 0) {
            name = name.substring(0, params);
        }
        return name.isBlank() ? null : name;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

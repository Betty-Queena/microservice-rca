package com.microservice.rca.health;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 健康检查服务：数据库状态来自真实 SQL 探测，不返回缓存的假数据。
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final JdbcTemplate jdbcTemplate;
    private final String serviceName;
    private final String version;

    public HealthService(JdbcTemplate jdbcTemplate,
                         @Value("${spring.application.name}") String serviceName,
                         @Value("${app.version:0.1.0}") String version) {
        this.jdbcTemplate = jdbcTemplate;
        this.serviceName = serviceName;
        this.version = version;
    }

    public HealthData check() {
        long start = System.nanoTime();
        String databaseStatus = "UP";
        String message = "连接正常";

        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception ex) {
            databaseStatus = "DOWN";
            message = "数据库连接失败";
            log.warn("Database health check failed: {}", ex.getMessage());
        }

        long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        String status = "UP".equals(databaseStatus) ? "UP" : "DEGRADED";

        return new HealthData(
                serviceName,
                version,
                status,
                Instant.now(),
                uptimeSeconds,
                new DatabaseHealth(databaseStatus, latencyMs, message));
    }
}

package com.microservice.rca.experiment.api;

import java.time.Instant;

/**
 * 库存服务故障注入状态的代理视图。dataSource 标明数据实际来源。
 */
public record ExperimentFaultStatusView(
        boolean experimentEnabled,
        boolean active,
        String experimentId,
        String faultType,
        Integer delayMs,
        Instant activatedAt,
        Instant expiresAt,
        Long remainingMs,
        int defaultDurationSeconds,
        int maxDelayMs,
        int maxDurationSeconds,
        String note,
        String dataSource,
        Instant checkedAt) {
}

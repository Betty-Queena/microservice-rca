package com.microservice.rca.inventory.fault;

import java.time.Instant;

/**
 * 故障注入状态响应。实验开关与是否处于激活状态分开表示。
 */
public record FaultInjectionStatus(
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
        String note) {
}

package com.microservice.rca.inventory.fault;

import java.time.Duration;
import java.time.Instant;

/**
 * 当前生效的故障实验状态。实验 ID 由库存服务生成，用于关联故障事件。
 */
public record FaultInjectionState(
        String experimentId,
        FaultType faultType,
        Integer delayMs,
        Instant activatedAt,
        Instant expiresAt,
        String note) {

    public boolean activeAt(Instant now) {
        return now.isBefore(expiresAt);
    }

    public long remainingMs(Instant now) {
        return Math.max(0, Duration.between(now, expiresAt).toMillis());
    }
}

package com.microservice.rca.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * timeout 为本次工具调用允许消耗的最大时间，不得超过 Agent 整体剩余预算。
 * 为 null 时使用观测客户端的默认超时。
 */
public record ToolExecutionContext(
        UUID diagnosisTaskId,
        String serviceName,
        Instant windowStart,
        Instant windowEnd,
        Map<String, Object> parameters,
        Duration timeout) {

    public ToolExecutionContext(UUID diagnosisTaskId,
                                String serviceName,
                                Instant windowStart,
                                Instant windowEnd,
                                Map<String, Object> parameters) {
        this(diagnosisTaskId, serviceName, windowStart, windowEnd, parameters, null);
    }
}

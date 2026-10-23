package com.microservice.rca.diagnosis.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;

/**
 * 证据查询参数。时间窗口由管理后端校验，默认围绕事件发生时间。
 */
public record EvidenceQueryRequest(
        String requestId,
        Instant startTime,
        Instant endTime,
        @Min(1) @Max(200) Integer logLimit,
        String serviceName) {
}

package com.microservice.rca.external.api;

import java.time.Instant;
import java.util.UUID;

/**
 * 连通性检查结果。只返回健康状态与耗时，不返回 Actuator 健康详情，
 * 避免把数据库地址、磁盘路径等敏感信息透出到前端。
 */
public record ExternalServiceCheckView(
        UUID serviceId,
        String serviceName,
        boolean available,
        String status,
        int httpStatus,
        long latencyMs,
        String message,
        Instant checkedAt) {
}

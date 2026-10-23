package com.microservice.rca.order.observability;

import java.time.Instant;

/**
 * 一次真实业务请求的结构化日志记录。
 */
public record RequestLogEntry(
        String id,
        Instant timestamp,
        String serviceName,
        String requestId,
        String method,
        String path,
        int status,
        long durationMs,
        String errorType) {
}

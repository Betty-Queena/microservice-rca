package com.microservice.rca.order.observability;

import java.time.Instant;

/**
 * 按分钟聚合的真实请求指标。错误分为服务端错误（5xx）与客户端/业务错误（4xx）。
 */
public record MetricBucket(
        Instant minute,
        long requestCount,
        long serverErrorCount,
        long clientErrorCount,
        long totalDurationMs,
        long maxDurationMs) {

    public double averageDurationMs() {
        if (requestCount == 0) {
            return 0;
        }
        return (double) totalDurationMs / requestCount;
    }
}

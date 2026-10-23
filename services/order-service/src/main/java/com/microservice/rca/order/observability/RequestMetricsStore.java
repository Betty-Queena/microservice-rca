package com.microservice.rca.order.observability;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 内存分钟桶指标存储。只保留最近 N 分钟，支持按时间范围查询真实请求量、
 * 错误量与耗时；不使用随机数或静态数据。
 */
@Component
public class RequestMetricsStore {

    private final int retentionMinutes;
    private final ConcurrentMap<Instant, MutableBucket> buckets = new ConcurrentHashMap<>();

    public RequestMetricsStore(@Value("${observability.metrics.retention-minutes:180}") int retentionMinutes) {
        this.retentionMinutes = Math.max(10, retentionMinutes);
    }

    public void record(Instant timestamp, long durationMs, int status) {
        Instant minute = timestamp.truncatedTo(ChronoUnit.MINUTES);
        buckets.computeIfAbsent(minute, key -> new MutableBucket()).add(durationMs, status);
        cleanup(timestamp);
    }

    public List<MetricBucket> query(Instant start, Instant end) {
        Instant from = start.truncatedTo(ChronoUnit.MINUTES);
        Instant to = end.truncatedTo(ChronoUnit.MINUTES);
        return buckets.entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(from) && !entry.getKey().isAfter(to))
                .sorted(Comparator.comparing(entry -> entry.getKey()))
                .map(entry -> entry.getValue().snapshot(entry.getKey()))
                .toList();
    }

    public int retentionMinutes() {
        return retentionMinutes;
    }

    private void cleanup(Instant now) {
        Instant cutoff = now.minus(retentionMinutes, ChronoUnit.MINUTES);
        buckets.keySet().removeIf(minute -> minute.isBefore(cutoff));
    }

    private static final class MutableBucket {

        private long requestCount;
        private long serverErrorCount;
        private long clientErrorCount;
        private long totalDurationMs;
        private long maxDurationMs;

        private synchronized void add(long durationMs, int status) {
            requestCount++;
            totalDurationMs += durationMs;
            maxDurationMs = Math.max(maxDurationMs, durationMs);
            if (status >= 500) {
                serverErrorCount++;
            } else if (status >= 400) {
                clientErrorCount++;
            }
        }

        private synchronized MetricBucket snapshot(Instant minute) {
            return new MetricBucket(minute, requestCount, serverErrorCount, clientErrorCount,
                    totalDurationMs, maxDurationMs);
        }
    }
}

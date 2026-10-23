package com.microservice.rca.order.observability;

import com.microservice.rca.order.common.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 真实观测数据查询接口，供管理后端工具层调用。
 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private static final Duration MAX_QUERY_WINDOW = Duration.ofHours(24);
    private static final int MAX_LOG_LIMIT = 200;

    private final RequestLogStore logStore;
    private final RequestMetricsStore metricsStore;
    private final String serviceName;
    private final String logSource;
    private final String metricsSource;

    public ObservabilityController(RequestLogStore logStore,
                                   RequestMetricsStore metricsStore,
                                   @Value("${spring.application.name}") String serviceName,
                                   @Value("${observability.log.max-entries:5000}") int maxLogEntries) {
        this.logStore = logStore;
        this.metricsStore = metricsStore;
        this.serviceName = serviceName;
        this.logSource = serviceName + " RequestLogStore（有界内存，最多 " + Math.max(100, maxLogEntries)
                + " 条）+ logs/" + serviceName + "-requests.jsonl（滚动 JSONL）";
        this.metricsSource = serviceName + " RequestMetricsStore（内存分钟桶，保留 "
                + metricsStore.retentionMinutes() + " 分钟）";
    }

    @GetMapping("/logs")
    public ApiResponse<LogQueryResponse> logs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(required = false) String requestId,
            @RequestParam(defaultValue = "50") int limit) {
        Instant end = endTime != null ? endTime : Instant.now();
        Instant start = startTime != null ? startTime : end.minus(Duration.ofMinutes(15));
        validateWindow(start, end);
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_LOG_LIMIT));
        List<RequestLogEntry> entries = logStore.query(start, end, normalize(requestId), effectiveLimit);

        QueryWindow query = new QueryWindow(start, end, normalize(requestId), effectiveLimit,
                "最近最多 " + effectiveLimit + " 条真实业务请求日志，按时间升序返回");
        return ApiResponse.ok(new LogQueryResponse(logSource, serviceName, Instant.now(), query, entries));
    }

    @GetMapping("/metrics")
    public ApiResponse<MetricsQueryResponse> metrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime) {
        Instant end = endTime != null ? endTime : Instant.now();
        Instant start = startTime != null ? startTime : end.minus(Duration.ofMinutes(15));
        validateWindow(start, end);
        List<MetricBucket> buckets = metricsStore.query(start, end);
        MetricsSummary summary = summarize(buckets);

        QueryWindow query = new QueryWindow(start, end, null, null,
                "最近 " + metricsStore.retentionMinutes() + " 分钟内的真实请求分钟桶");
        return ApiResponse.ok(new MetricsQueryResponse(metricsSource, serviceName, Instant.now(), query,
                buckets, summary));
    }

    private void validateWindow(Instant start, Instant end) {
        if (!start.isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTime 必须早于 endTime");
        }
        if (Duration.between(start, end).compareTo(MAX_QUERY_WINDOW) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询时间范围不能超过 24 小时");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private MetricsSummary summarize(List<MetricBucket> buckets) {
        long requests = 0;
        long serverErrors = 0;
        long clientErrors = 0;
        long totalDuration = 0;
        long maxDuration = 0;
        for (MetricBucket bucket : buckets) {
            requests += bucket.requestCount();
            serverErrors += bucket.serverErrorCount();
            clientErrors += bucket.clientErrorCount();
            totalDuration += bucket.totalDurationMs();
            maxDuration = Math.max(maxDuration, bucket.maxDurationMs());
        }
        double average = requests == 0 ? 0 : (double) totalDuration / requests;
        return new MetricsSummary(requests, serverErrors, clientErrors, average, maxDuration);
    }

    public record LogQueryResponse(
            String source,
            String serviceName,
            Instant collectedAt,
            QueryWindow query,
            List<RequestLogEntry> items) {
    }

    public record MetricsQueryResponse(
            String source,
            String serviceName,
            Instant collectedAt,
            QueryWindow query,
            List<MetricBucket> buckets,
            MetricsSummary summary) {
    }

    public record QueryWindow(
            Instant startTime,
            Instant endTime,
            String requestId,
            Integer limit,
            String dataScope) {
    }

    public record MetricsSummary(
            long requestCount,
            long serverErrorCount,
            long clientErrorCount,
            double averageDurationMs,
            long maxDurationMs) {
    }
}

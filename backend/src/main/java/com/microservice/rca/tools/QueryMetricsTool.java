package com.microservice.rca.tools;

import com.microservice.rca.observability.ObservabilityClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * query_metrics：按服务、时间窗口查询实验服务的真实分钟级请求指标。
 */
@Component
public class QueryMetricsTool implements DiagnosticTool {

    private final ObservabilityClient observabilityClient;

    public QueryMetricsTool(ObservabilityClient observabilityClient) {
        this.observabilityClient = observabilityClient;
    }

    @Override
    public String name() {
        return "query_metrics";
    }

    @Override
    public String description() {
        return "按服务、时间窗口查询真实请求量、错误量与响应耗时分钟桶（只读）";
    }

    @Override
    public ToolResult execute(ToolExecutionContext context) {
        if (context.windowStart() == null || context.windowEnd() == null) {
            return ToolResult.failure("query_metrics 缺少时间窗口");
        }
        try {
            String target = context.serviceName() == null || context.serviceName().isBlank()
                    ? "all" : context.serviceName();
            List<ObservabilityClient.ServiceQueryResult> results = observabilityClient.queryMetrics(
                    target, context.windowStart(), context.windowEnd());

            List<Map<String, Object>> services = new ArrayList<>();
            List<String> dataSources = new ArrayList<>();
            List<String> evidenceIds = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            long totalRequests = 0;
            long totalServerErrors = 0;
            long totalClientErrors = 0;
            long totalDuration = 0;
            long maxDuration = 0;

            for (ObservabilityClient.ServiceQueryResult result : results) {
                if (result.error() != null) {
                    warnings.add(result.serviceName() + ": " + result.error());
                    continue;
                }
                Map<String, Object> data = result.data();
                Object source = data.get("source");
                if (source != null) {
                    dataSources.add(String.valueOf(source));
                }
                Object rawBuckets = data.get("buckets");
                List<Map<String, Object>> buckets = new ArrayList<>();
                if (rawBuckets instanceof List<?> bucketList) {
                    for (Object rawBucket : bucketList) {
                        if (rawBucket instanceof Map<?, ?> bucketMap) {
                            Map<String, Object> bucket = new LinkedHashMap<>();
                            bucketMap.forEach((key, value) -> bucket.put(String.valueOf(key), value));
                            buckets.add(bucket);
                            Object minute = bucket.get("minute");
                            if (minute != null) {
                                evidenceIds.add("metric:" + result.serviceName() + ":" + minute);
                            }
                        }
                    }
                }

                Map<String, Object> summary = asMap(data.get("summary"));
                long requests = longValue(summary.get("requestCount"));
                long serverErrors = longValue(summary.get("serverErrorCount"));
                long clientErrors = longValue(summary.get("clientErrorCount"));
                long duration = Math.round(doubleValue(summary.get("averageDurationMs")) * requests);
                long serviceMax = longValue(summary.get("maxDurationMs"));
                totalRequests += requests;
                totalServerErrors += serverErrors;
                totalClientErrors += clientErrors;
                totalDuration += duration;
                maxDuration = Math.max(maxDuration, serviceMax);

                Map<String, Object> serviceEntry = new LinkedHashMap<>();
                serviceEntry.put("serviceName", result.serviceName());
                serviceEntry.put("source", source);
                serviceEntry.put("collectedAt", data.get("collectedAt"));
                serviceEntry.put("summary", summary);
                serviceEntry.put("buckets", buckets);
                services.add(serviceEntry);
            }

            if (dataSources.isEmpty()) {
                return ToolResult.failure("query_metrics 查询失败: " + String.join("; ", warnings));
            }

            Map<String, Object> aggregate = new LinkedHashMap<>();
            aggregate.put("requestCount", totalRequests);
            aggregate.put("serverErrorCount", totalServerErrors);
            aggregate.put("clientErrorCount", totalClientErrors);
            aggregate.put("averageDurationMs", totalRequests == 0 ? 0 : (double) totalDuration / totalRequests);
            aggregate.put("maxDurationMs", maxDuration);

            Map<String, Object> query = new LinkedHashMap<>();
            query.put("service", target);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("tool", name());
            output.put("dataSources", dataSources);
            output.put("query", query);
            output.put("timeRange", Map.of(
                    "startTime", context.windowStart().toString(),
                    "endTime", context.windowEnd().toString()));
            output.put("collectedAt", Instant.now().toString());
            output.put("evidenceIds", evidenceIds);
            output.put("services", services);
            output.put("summary", aggregate);
            output.put("warnings", warnings);
            output.put("dataNote", "指标来自实验服务真实业务请求的分钟级聚合，不包含健康检查与实验控制请求");
            return ToolResult.success(output);
        } catch (Exception ex) {
            return ToolResult.failure("query_metrics 执行失败: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}

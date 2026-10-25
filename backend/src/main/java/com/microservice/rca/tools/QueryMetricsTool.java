package com.microservice.rca.tools;

import com.microservice.rca.external.client.ExternalActuatorClient;
import com.microservice.rca.external.domain.ExternalService;
import com.microservice.rca.external.service.ExternalServiceRegistry;
import com.microservice.rca.observability.ObservabilityClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * query_metrics：按服务、时间窗口查询实验服务的真实分钟级请求指标。
 */
@Component
public class QueryMetricsTool implements DiagnosticTool {

    private final ObservabilityClient observabilityClient;
    private final ExternalServiceRegistry externalServiceRegistry;
    private final ExternalActuatorClient externalActuatorClient;

    public QueryMetricsTool(ObservabilityClient observabilityClient,
                            ExternalServiceRegistry externalServiceRegistry,
                            ExternalActuatorClient externalActuatorClient) {
        this.observabilityClient = observabilityClient;
        this.externalServiceRegistry = externalServiceRegistry;
        this.externalActuatorClient = externalActuatorClient;
    }

    @Override
    public String name() {
        return "query_metrics";
    }

    @Override
    public String description() {
        return "查询真实指标：内置实验服务返回分钟级请求聚合，已注册外部服务返回 Actuator 只读指标快照";
    }

    @Override
    public ToolResult execute(ToolExecutionContext context) {
        if (context.windowStart() == null || context.windowEnd() == null) {
            return ToolResult.failure("query_metrics 缺少时间窗口");
        }
        try {
            String target = context.serviceName() == null || context.serviceName().isBlank()
                    ? "all" : context.serviceName();

            Optional<ExternalService> external = externalServiceRegistry.findByName(target);
            if (external.isPresent()) {
                return executeExternal(external.get(), context);
            }

            List<ObservabilityClient.ServiceQueryResult> results = context.timeout() == null
                    ? observabilityClient.queryMetrics(
                            target, context.windowStart(), context.windowEnd())
                    : observabilityClient.queryMetrics(
                            target, context.windowStart(), context.windowEnd(), context.timeout());

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

    private ToolResult executeExternal(ExternalService service, ToolExecutionContext context) {
        ExternalActuatorClient.MetricsSnapshot snapshot = externalActuatorClient.metrics(service);
        if (!snapshot.available()) {
            if (snapshot.connectivityFailure()) {
                return ToolResult.failure("query_metrics 不可用: " + snapshot.message(),
                        connectivityOutput(service, snapshot));
            }
            return ToolResult.failure("query_metrics 不可用: " + snapshot.message());
        }

        List<Map<String, Object>> metrics = new ArrayList<>();
        List<String> evidenceIds = new ArrayList<>();
        for (ExternalActuatorClient.MetricSample sample : snapshot.metrics()) {
            List<Map<String, Object>> measurements = sample.measurements().stream()
                    .map(measurement -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("statistic", measurement.statistic());
                        item.put("value", measurement.value());
                        return item;
                    })
                    .toList();
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("name", sample.name());
            metric.put("description", sample.description());
            metric.put("baseUnit", sample.baseUnit());
            metric.put("measurements", measurements);
            metrics.add(metric);
            evidenceIds.add("metric:" + service.getName() + ":" + sample.name());
        }

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("service", service.getName());

        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("metricCount", metrics.size());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tool", name());
        output.put("external", true);
        output.put("dataSources", List.of("external-actuator-metrics"));
        output.put("query", query);
        output.put("timeRange", Map.of(
                "startTime", context.windowStart() != null ? context.windowStart().toString() : "",
                "endTime", context.windowEnd() != null ? context.windowEnd().toString() : ""));
        output.put("timeWindowSupported", false);
        output.put("collectedAt", Instant.now().toString());
        output.put("evidenceIds", evidenceIds);
        output.put("metricCount", metrics.size());
        output.put("metrics", metrics);
        output.put("summary", aggregate);
        output.put("warnings", snapshot.warnings());
        output.put("dataNote",
                "Actuator 指标为本次采集快照，不支持任意历史时间窗口；指标缺失不代表未发生异常");
        return ToolResult.success(output);
    }

    private Map<String, Object> connectivityOutput(
            ExternalService service, ExternalActuatorClient.MetricsSnapshot snapshot) {
        String evidenceId = "connectivity:" + service.getName() + ":metrics:"
                + snapshot.collectedAt().toEpochMilli();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tool", name());
        output.put("external", true);
        output.put("service", service.getName());
        output.put("available", false);
        output.put("probe", "actuator-metrics");
        output.put("probeStatus", "FAILED");
        output.put("failureType", "CONNECTIVITY_FAILURE");
        output.put("connectivity", "UNREACHABLE");
        output.put("error", snapshot.message());
        output.put("collectedAt", snapshot.collectedAt().toString());
        output.put("evidenceIds", List.of(evidenceId));
        output.put("dataNote",
                "该证据仅表示本次指标探测未建立连接，不能据此断言进程已停止、"
                        + "服务已崩溃或网络配置错误；具体根因待核实");
        return output;
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

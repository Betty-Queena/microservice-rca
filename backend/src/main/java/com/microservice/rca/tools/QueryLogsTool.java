package com.microservice.rca.tools;

import com.microservice.rca.observability.ObservabilityClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * query_logs：按服务、时间窗口和 requestId 查询实验服务的真实业务请求日志。
 */
@Component
public class QueryLogsTool implements DiagnosticTool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ObservabilityClient observabilityClient;

    public QueryLogsTool(ObservabilityClient observabilityClient) {
        this.observabilityClient = observabilityClient;
    }

    @Override
    public String name() {
        return "query_logs";
    }

    @Override
    public String description() {
        return "按服务、时间窗口、requestId 查询真实业务请求日志（只读，最多 200 条）";
    }

    @Override
    public ToolResult execute(ToolExecutionContext context) {
        if (context.windowStart() == null || context.windowEnd() == null) {
            return ToolResult.failure("query_logs 缺少时间窗口");
        }
        try {
            String target = context.serviceName() == null || context.serviceName().isBlank()
                    ? "all" : context.serviceName();
            int limit = intParam(context.parameters(), "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
            String requestId = stringParam(context.parameters(), "requestId");

            List<ObservabilityClient.ServiceQueryResult> results = context.timeout() == null
                    ? observabilityClient.queryLogs(
                            target, context.windowStart(), context.windowEnd(), requestId, limit)
                    : observabilityClient.queryLogs(
                            target, context.windowStart(), context.windowEnd(), requestId, limit,
                            context.timeout());

            List<Map<String, Object>> items = new ArrayList<>();
            List<String> dataSources = new ArrayList<>();
            List<String> evidenceIds = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

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
                Object rawItems = data.get("items");
                if (rawItems instanceof List<?> itemList) {
                    for (Object rawItem : itemList) {
                        if (rawItem instanceof Map<?, ?> itemMap) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            itemMap.forEach((key, value) -> item.put(String.valueOf(key), value));
                            item.put("sourceService", result.serviceName());
                            items.add(item);
                            Object id = item.get("id");
                            if (id != null) {
                                evidenceIds.add("log:" + result.serviceName() + ":" + id);
                            }
                        }
                    }
                }
            }

            if (dataSources.isEmpty()) {
                return ToolResult.failure("query_logs 查询失败: " + String.join("; ", warnings));
            }

            items.sort(Comparator.comparing(item -> String.valueOf(item.get("timestamp"))));

            Map<String, Object> query = new LinkedHashMap<>();
            query.put("service", target);
            query.put("requestId", requestId);
            query.put("limit", limit);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("tool", name());
            output.put("dataSources", dataSources);
            output.put("query", query);
            output.put("timeRange", Map.of(
                    "startTime", context.windowStart().toString(),
                    "endTime", context.windowEnd().toString()));
            output.put("collectedAt", Instant.now().toString());
            output.put("evidenceIds", evidenceIds);
            output.put("itemCount", items.size());
            output.put("items", items);
            output.put("warnings", warnings);
            output.put("dataNote", "日志来自实验服务真实业务请求观测，不包含健康检查与实验控制请求");
            return ToolResult.success(output);
        } catch (Exception ex) {
            return ToolResult.failure("query_logs 执行失败: " + ex.getMessage());
        }
    }

    private int intParam(Map<String, Object> parameters, String key, int defaultValue, int min, int max) {
        if (parameters == null) {
            return defaultValue;
        }
        Object value = parameters.get(key);
        int parsed = defaultValue;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                parsed = defaultValue;
            }
        }
        return Math.max(min, Math.min(parsed, max));
    }

    private String stringParam(Map<String, Object> parameters, String key) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }
}

package com.microservice.rca.external.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.microservice.rca.external.domain.ExternalService;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 外部 Spring Boot 服务的只读证据采集。
 *
 * <p>健康与指标来自 Actuator；日志只能来自显式配置的只读日志数据源，
 * 不假设 Actuator 提供日志查询。任何不可用的情况都返回明确的不可用结果，
 * 不构造替代数据。</p>
 */
@Component
public class ExternalActuatorClient {

    private static final Pattern METRIC_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern EVIDENCE_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9._:-]{1,80}$");
    private static final Pattern HEALTH_STATUS_PATTERN =
            Pattern.compile("^[A-Za-z_]{1,32}$");
    private static final Pattern SAFE_COMPONENT_PATH_PATTERN =
            Pattern.compile("[^A-Za-z0-9._-]");
    private static final Pattern EXCEPTION_TYPE_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+)\\s*:\\s*(.*)$",
            Pattern.DOTALL);
    private static final Pattern JDBC_URL_PATTERN =
            Pattern.compile("(?i)jdbc:[^\\s,;\"']+");
    private static final Pattern URL_CREDENTIAL_PATTERN =
            Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@");
    private static final Pattern QUERY_SECRET_PATTERN =
            Pattern.compile("(?i)([?&](?:token|key|password|secret)=)[^&\\s]+");
    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)\\b(user|username|account|password|passwd|pwd|secret|token|api[_-]?key|authorization)\\b"
                    + "\\s*[=:]\\s*[^\\s,;]+");
    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern WINDOWS_PATH_PATTERN =
            Pattern.compile("(?i)\\b[A-Z]:[\\\\/][^\\s,;\"']+");
    private static final Pattern UNIX_PATH_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9._-])/(?:home|Users|var|opt|tmp|etc|root|srv|mnt|data|app)"
                    + "(?:/[^\\s,;\"']*)?");

    private static final int MAX_METRIC_SAMPLES = 12;
    private static final int MAX_LOG_ITEMS = 200;
    private static final int MAX_LOG_FIELDS = 20;
    private static final int MAX_LOG_VALUE_CHARS = 1000;
    private static final int MAX_HEALTH_COMPONENTS = 20;
    private static final int MAX_HEALTH_DEPTH = 4;
    private static final int MAX_ERROR_COMPONENTS = 5;
    private static final int MAX_COMPONENT_PATH_CHARS = 120;
    private static final int MAX_ERROR_TYPE_CHARS = 128;
    private static final int MAX_ERROR_SUMMARY_CHARS = 256;

    /**
     * 只读取与请求量、错误、耗时、JVM 和连接池相关的常用指标，避免把全部指标塞进提示词。
     * 连接池指标排在最前：数据库/下游连接失败时，超时和获取耗时通常比通用 JVM 指标更有诊断价值。
     * 是否真的存在这些指标仍由 Actuator 的 names 列表决定，不假设所有服务都使用 Hikari。
     */
    private static final List<String> PREFERRED_METRICS = List.of(
            "hikaricp.connections.timeout",
            "hikaricp.connections.acquire",
            "hikaricp.connections.active",
            "hikaricp.connections.pending",
            "jdbc.connections.active",
            "http.server.requests",
            "jvm.gc.pause",
            "jvm.memory.used",
            "jvm.memory.max",
            "process.cpu.usage",
            "system.cpu.usage",
            "jvm.threads.live",
            "jvm.classes.loaded",
            "logback.events");

    private final ExternalServiceUrlValidator validator;
    private final ExternalServiceHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ExternalActuatorClient(ExternalServiceUrlValidator validator,
                                  ExternalServiceHttpClient httpClient,
                                  ObjectMapper objectMapper) {
        this.validator = validator;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public HealthSnapshot health(ExternalService service) {
        Instant collectedAt = Instant.now();
        try {
            URI uri = validator.resolve(
                    validator.validateBaseUrl(service.getBaseUrl()), service.getHealthPath());
            ExternalServiceHttpClient.HttpResult result = httpClient.get(uri);
            // HTTP 503 等非 2xx 响应同样携带 Actuator 的 components 明细，必须读取响应体。
            HealthDetail detail = parseHealthDetail(result.body());
            boolean httpSuccess = result.status() >= 200 && result.status() < 300;
            String status = detail != null && detail.status() != null
                    ? detail.status() : (httpSuccess ? "UNKNOWN" : "DOWN");
            String message = httpSuccess
                    ? "健康检查成功" : "健康检查返回 HTTP " + result.status();
            return new HealthSnapshot(true, status, result.status(), result.latencyMs(),
                    message, collectedAt,
                    detail != null ? detail.components() : List.of(),
                    detail != null && detail.componentsTruncated(),
                    detail != null ? detail.omittedComponentCount() : 0);
        } catch (Exception ex) {
            return new HealthSnapshot(false, "UNREACHABLE", -1, 0,
                    safeMessage(ex), collectedAt, List.of(), false, 0);
        }
    }

    public MetricsSnapshot metrics(ExternalService service) {
        Instant collectedAt = Instant.now();
        if (blank(service.getMetricsPath())) {
            return MetricsSnapshot.unavailable("外部服务未配置指标端点", collectedAt);
        }
        try {
            URI baseUri = validator.validateBaseUrl(service.getBaseUrl());
            String metricsPath = service.getMetricsPath();
            ExternalServiceHttpClient.HttpResult namesResult =
                    httpClient.get(validator.resolve(baseUri, metricsPath));
            if (namesResult.status() < 200 || namesResult.status() >= 300) {
                return MetricsSnapshot.unavailable(
                        "指标端点返回 HTTP " + namesResult.status(), collectedAt);
            }

            Set<String> exposed = parseMetricNames(namesResult.body());
            List<String> preferredExposed = PREFERRED_METRICS.stream()
                    .filter(exposed::contains)
                    .toList();
            List<String> selected = preferredExposed.stream()
                    .limit(MAX_METRIC_SAMPLES)
                    .toList();
            if (selected.isEmpty()) {
                return MetricsSnapshot.unavailable(
                        "指标端点可达，但未暴露可读取的常用指标", collectedAt);
            }

            List<MetricSample> samples = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            if (preferredExposed.size() > selected.size()) {
                warnings.add("常用指标数量超过单次采样上限 " + MAX_METRIC_SAMPLES
                        + "，已按诊断优先级省略 " + (preferredExposed.size() - selected.size())
                        + " 个指标");
            }
            for (String metricName : selected) {
                if (!METRIC_NAME_PATTERN.matcher(metricName).matches()) {
                    continue;
                }
                URI metricUri = validator.resolve(baseUri, metricsPath + "/" + metricName);
                ExternalServiceHttpClient.HttpResult result = httpClient.get(metricUri);
                if (result.status() < 200 || result.status() >= 300) {
                    warnings.add(metricName + ": HTTP " + result.status());
                    continue;
                }
                MetricSample sample = parseMetricSample(metricName, result.body());
                if (sample == null) {
                    warnings.add(metricName + ": 响应不包含 measurements");
                    continue;
                }
                samples.add(sample);
            }
            if (samples.isEmpty()) {
                return new MetricsSnapshot(false, List.of(),
                        "指标端点可达，但读取到的指标均不可用", collectedAt, warnings);
            }
            return new MetricsSnapshot(true, List.copyOf(samples),
                    "指标为本次采集快照，不支持任意历史时间窗口", collectedAt, warnings);
        } catch (Exception ex) {
            return MetricsSnapshot.unavailable(safeMessage(ex), collectedAt);
        }
    }

    public LogSnapshot logs(ExternalService service, String requestId, int requestedLimit) {
        Instant collectedAt = Instant.now();
        if (blank(service.getLogSourceUrl())) {
            return LogSnapshot.unavailable(
                    "外部服务未配置日志数据源；Actuator 不提供日志查询，不能生成日志证据",
                    collectedAt);
        }
        int limit = Math.max(1, Math.min(requestedLimit, MAX_LOG_ITEMS));
        try {
            String logUrl = service.getLogSourceUrl();
            StringBuilder url = new StringBuilder(logUrl);
            url.append(logUrl.contains("?") ? "&" : "?").append("limit=").append(limit);
            if (!blank(requestId)) {
                url.append("&requestId=").append(
                        URLEncoder.encode(requestId, StandardCharsets.UTF_8));
            }
            // 日志地址允许 query，但仍复用同一套协议、主机、端口白名单校验。
            URI uri = validator.validateRequestUri(URI.create(url.toString()));
            ExternalServiceHttpClient.HttpResult result = httpClient.get(uri);
            if (result.status() < 200 || result.status() >= 300) {
                return LogSnapshot.unavailable("日志数据源返回 HTTP " + result.status(), collectedAt);
            }
            JsonNode root = objectMapper.readTree(result.body());
            JsonNode array = findLogArray(root);
            if (array == null) {
                return LogSnapshot.unavailable(
                        "日志数据源响应不是约定的 JSON 数组，未生成日志证据", collectedAt);
            }

            List<LogEntry> entries = new ArrayList<>();
            Set<String> usedEvidenceIds = new LinkedHashSet<>();
            int index = 0;
            for (JsonNode node : array) {
                if (entries.size() >= limit) {
                    break;
                }
                entries.add(toLogEntry(node, index, service.getName(), usedEvidenceIds));
                index++;
            }
            return new LogSnapshot(true, List.copyOf(entries),
                    "日志来自显式配置的只读日志数据源", collectedAt);
        } catch (Exception ex) {
            return LogSnapshot.unavailable(safeMessage(ex), collectedAt);
        }
    }

    private HealthDetail parseHealthDetail(String body) {
        if (blank(body)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            HealthCollector collector = new HealthCollector();
            JsonNode components = root.get("components");
            if (components != null && components.isObject()) {
                collectHealthComponents(components, "", 1, collector);
            }
            return new HealthDetail(parseRootStatus(root),
                    List.copyOf(collector.components),
                    collector.truncated,
                    collector.omittedComponentCount);
        } catch (Exception ignored) {
            // 响应不是 JSON 时只根据 HTTP 状态给出保守结论。
            return null;
        }
    }

    private void collectHealthComponents(JsonNode components,
                                         String parentPath,
                                         int depth,
                                         HealthCollector collector) {
        if (components == null || !components.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> iterator = components.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (depth > MAX_HEALTH_DEPTH) {
                collector.truncated = true;
                collector.omittedComponentCount++;
                continue;
            }
            JsonNode node = entry.getValue();
            if (node == null || !node.isObject()) {
                collector.truncated = true;
                continue;
            }
            String path = componentPath(parentPath, entry.getKey());
            String status = parseHealthStatus(node);
            boolean abnormal = !"UP".equalsIgnoreCase(status);
            boolean stored = collector.components.size() < MAX_HEALTH_COMPONENTS;
            String errorType = null;
            String errorSummary = null;
            boolean detailsOmitted = false;
            if (abnormal) {
                boolean errorSlot = collector.errorComponentCount < MAX_ERROR_COMPONENTS;
                if (errorSlot) {
                    ComponentError error = extractComponentError(node);
                    if (error != null) {
                        errorType = error.type();
                        errorSummary = error.summary();
                        collector.errorComponentCount++;
                    }
                } else {
                    detailsOmitted = hasComponentError(node);
                }
            }
            if (stored) {
                collector.components.add(new HealthComponent(
                        path, status, errorType, errorSummary, detailsOmitted));
            } else {
                collector.truncated = true;
                collector.omittedComponentCount++;
            }

            JsonNode nested = node.get("components");
            if (nested != null && nested.isObject()) {
                if (depth < MAX_HEALTH_DEPTH) {
                    collectHealthComponents(nested, path, depth + 1, collector);
                } else {
                    collector.truncated = true;
                    collector.omittedComponentCount++;
                }
            }
        }
    }

    private String parseRootStatus(JsonNode root) {
        JsonNode status = root.get("status");
        if (status != null && status.isTextual()
                && HEALTH_STATUS_PATTERN.matcher(status.asText()).matches()) {
            return status.asText();
        }
        // 根节点状态缺失时返回 null，由调用方根据 HTTP 状态做保守回退。
        return null;
    }

    private String parseHealthStatus(JsonNode node) {
        JsonNode status = node.get("status");
        if (status != null && status.isTextual()
                && HEALTH_STATUS_PATTERN.matcher(status.asText()).matches()) {
            return status.asText();
        }
        return "UNKNOWN";
    }

    private ComponentError extractComponentError(JsonNode component) {
        JsonNode details = component.get("details");
        String raw = firstText(details, "error", "exception", "message", "reason", "cause");
        if (raw == null) {
            raw = firstText(component, "error", "message");
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = normalizeWhitespace(raw);
        String errorType = null;
        String summary = normalized;
        java.util.regex.Matcher matcher = EXCEPTION_TYPE_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            errorType = truncate(matcher.group(1), MAX_ERROR_TYPE_CHARS);
            summary = matcher.group(2);
        }
        summary = truncate(normalizeWhitespace(redact(summary)), MAX_ERROR_SUMMARY_CHARS);
        return new ComponentError(errorType, summary.isBlank() ? null : summary);
    }

    private boolean hasComponentError(JsonNode component) {
        JsonNode details = component.get("details");
        return firstText(details, "error", "exception", "message", "reason", "cause") != null
                || firstText(component, "error", "message") != null;
    }

    private String firstText(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String componentPath(String parentPath, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        name = SAFE_COMPONENT_PATH_PATTERN.matcher(name).replaceAll("_");
        if (name.isBlank()) {
            name = "component";
        }
        name = truncate(name, 64);
        String path = parentPath == null || parentPath.isBlank()
                ? name : parentPath + "." + name;
        return truncate(path, MAX_COMPONENT_PATH_CHARS);
    }

    private String redact(String value) {
        String redacted = JDBC_URL_PATTERN.matcher(value).replaceAll("jdbc:[REDACTED]");
        redacted = URL_CREDENTIAL_PATTERN.matcher(redacted)
                .replaceAll("$1[REDACTED]@");
        redacted = QUERY_SECRET_PATTERN.matcher(redacted)
                .replaceAll("$1[REDACTED]");
        redacted = SECRET_ASSIGNMENT_PATTERN.matcher(redacted)
                .replaceAll("$1=[REDACTED]");
        redacted = BEARER_PATTERN.matcher(redacted).replaceAll("Bearer [REDACTED]");
        redacted = WINDOWS_PATH_PATTERN.matcher(redacted).replaceAll("[REDACTED_PATH]");
        redacted = UNIX_PATH_PATTERN.matcher(redacted).replaceAll("[REDACTED_PATH]");
        return redacted;
    }

    private String normalizeWhitespace(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private Set<String> parseMetricNames(String body) {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode array = root.get("names");
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    if (node.isTextual()) {
                        names.add(node.asText());
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败返回空集合，由调用方明确标记为不可用。
        }
        return names;
    }

    private MetricSample parseMetricSample(String fallbackName, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String name = root.path("name").isTextual() ? root.path("name").asText() : fallbackName;
            String description = root.path("description").isTextual()
                    ? truncate(root.path("description").asText(), 256) : null;
            String baseUnit = root.path("baseUnit").isTextual()
                    ? truncate(root.path("baseUnit").asText(), 32) : null;
            JsonNode measurements = root.get("measurements");
            if (measurements == null || !measurements.isArray() || measurements.isEmpty()) {
                return null;
            }
            List<Measurement> parsed = new ArrayList<>();
            for (JsonNode measurement : measurements) {
                String statistic = measurement.path("statistic").asText(null);
                JsonNode value = measurement.get("value");
                if (statistic == null || value == null || !value.isNumber()) {
                    continue;
                }
                parsed.add(new Measurement(truncate(statistic, 64), value.asDouble()));
            }
            if (parsed.isEmpty()) {
                return null;
            }
            return new MetricSample(name, description, baseUnit, List.copyOf(parsed));
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode findLogArray(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        for (String key : List.of("logs", "items", "records", "data")) {
            JsonNode node = root.get(key);
            if (node != null && node.isArray()) {
                return (ArrayNode) node;
            }
        }
        return null;
    }

    private LogEntry toLogEntry(JsonNode node, int index, String serviceName,
                                Set<String> usedEvidenceIds) {
        Map<String, Object> fields = new LinkedHashMap<>();
        String rawId = null;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
            int fieldCount = 0;
            while (iterator.hasNext() && fieldCount < MAX_LOG_FIELDS) {
                Map.Entry<String, JsonNode> field = iterator.next();
                String key = truncate(field.getKey(), 64);
                JsonNode value = field.getValue();
                Object sanitized = sanitizeLogValue(value);
                // Map.copyOf 不接受 null 值；JSON null 字段直接省略，避免整批日志不可用。
                if (sanitized != null) {
                    fields.put(key, sanitized);
                    if (rawId == null && ("id".equals(key) || "traceId".equals(key))) {
                        rawId = String.valueOf(sanitized);
                    }
                }
                fieldCount++;
            }
        } else if (node.isTextual()) {
            fields.put("message", truncate(node.asText(), MAX_LOG_VALUE_CHARS));
        } else {
            fields.put("value", truncate(node.toString(), MAX_LOG_VALUE_CHARS));
        }

        String suffix = sanitizeEvidenceId(rawId);
        if (suffix == null) {
            suffix = "idx-" + index;
        }
        String evidenceId = "log:" + serviceName + ":" + suffix;
        int duplicate = 1;
        while (!usedEvidenceIds.add(evidenceId)) {
            evidenceId = "log:" + serviceName + ":" + suffix + "-" + duplicate;
            duplicate++;
        }
        return new LogEntry(evidenceId, Map.copyOf(fields));
    }

    private Object sanitizeLogValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return truncate(value.asText(), MAX_LOG_VALUE_CHARS);
        }
        if (value.isNumber()) {
            return value.numberValue();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return truncate(value.toString(), MAX_LOG_VALUE_CHARS);
    }

    private String sanitizeEvidenceId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String candidate = rawId.trim();
        if (candidate.length() > 80) {
            candidate = candidate.substring(0, 80);
        }
        return EVIDENCE_ID_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "外部服务请求失败: " + ex.getClass().getSimpleName();
        }
        return truncate(message, 256);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record HealthSnapshot(boolean available,
                                 String status,
                                 int httpStatus,
                                 long latencyMs,
                                 String message,
                                 Instant collectedAt,
                                 List<HealthComponent> components,
                                 boolean componentsTruncated,
                                 int omittedComponentCount) {

        public HealthSnapshot {
            components = components == null ? List.of() : List.copyOf(components);
        }

        public HealthSnapshot(boolean available,
                              String status,
                              int httpStatus,
                              long latencyMs,
                              String message,
                              Instant collectedAt) {
            this(available, status, httpStatus, latencyMs, message, collectedAt,
                    List.of(), false, 0);
        }
    }

    public record HealthComponent(String path,
                                  String status,
                                  String errorType,
                                  String errorSummary,
                                  boolean detailsOmitted) {
    }

    private record HealthDetail(String status,
                                List<HealthComponent> components,
                                boolean componentsTruncated,
                                int omittedComponentCount) {
    }

    private record ComponentError(String type, String summary) {
    }

    private static final class HealthCollector {
        private final List<HealthComponent> components = new ArrayList<>();
        private boolean truncated;
        private int omittedComponentCount;
        private int errorComponentCount;
    }

    public record Measurement(String statistic, double value) {
    }

    public record MetricSample(String name,
                               String description,
                               String baseUnit,
                               List<Measurement> measurements) {
    }

    public record MetricsSnapshot(boolean available,
                                  List<MetricSample> metrics,
                                  String message,
                                  Instant collectedAt,
                                  List<String> warnings) {

        public static MetricsSnapshot unavailable(String message, Instant collectedAt) {
            return new MetricsSnapshot(false, List.of(), message, collectedAt, List.of());
        }
    }

    public record LogEntry(String evidenceId, Map<String, Object> fields) {
    }

    public record LogSnapshot(boolean available,
                              List<LogEntry> entries,
                              String message,
                              Instant collectedAt) {

        public static LogSnapshot unavailable(String message, Instant collectedAt) {
            return new LogSnapshot(false, List.of(), message, collectedAt);
        }
    }
}

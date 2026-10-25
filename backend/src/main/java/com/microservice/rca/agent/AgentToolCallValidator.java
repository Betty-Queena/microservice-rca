package com.microservice.rca.agent;

import com.microservice.rca.tools.DiagnosticToolRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 严格校验模型提出的工具调用。未注册工具、未知参数、非法服务或越界数值一律拒绝执行。
 */
@Component
public class AgentToolCallValidator {

    private static final Set<String> BUILT_IN_SERVICES =
            Set.of("all", "order-service", "inventory-service");
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Set<String> COMMON_KEYS =
            Set.of("service", "lookbackMinutes");

    public ValidatedCall validate(AgentOrchestrator.AgentInput input, String toolName,
                                  Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            throw new AgentValidationException("工具名为空");
        }
        if (input.availableTools().stream().noneMatch(tool -> tool.name().equals(toolName))) {
            throw new AgentValidationException("未注册的工具: " + toolName + "，可用工具: "
                    + input.availableTools().stream().map(DiagnosticToolRegistry.ToolDefinition::name).toList());
        }
        Map<String, Object> args = arguments != null ? arguments : Map.of();
        for (String key : args.keySet()) {
            if (!COMMON_KEYS.contains(key) && !allowedToolKeys(toolName).contains(key)) {
                throw new AgentValidationException("工具 " + toolName + " 不接受参数: " + key);
            }
        }

        boolean external = "EXTERNAL".equalsIgnoreCase(input.serviceKind());
        Set<String> allowedServices = input.allowedServices() == null
                || input.allowedServices().isEmpty()
                ? BUILT_IN_SERVICES
                : Set.copyOf(input.allowedServices());
        String defaultService = external && input.serviceName() != null
                ? input.serviceName() : "all";

        String service = stringArg(args, "service", defaultService);
        if (!allowedServices.contains(service)) {
            throw new AgentValidationException("不支持的服务: " + service
                    + "，可选值: " + String.join("/", input.allowedServices() != null
                    ? input.allowedServices() : List.copyOf(BUILT_IN_SERVICES)));
        }
        int lookbackMinutes = intArg(args, "lookbackMinutes", 30, 1, 60);
        int limit = 50;
        String requestId = null;
        if ("query_logs".equals(toolName)) {
            limit = intArg(args, "limit", 50, 1, 200);
            requestId = stringArg(args, "requestId", null);
            if (requestId != null && !REQUEST_ID_PATTERN.matcher(requestId).matches()) {
                throw new AgentValidationException("requestId 格式不合法，只允许 1-64 位字母、数字、点、下划线、冒号或连字符");
            }
        } else if ("query_health".equals(toolName)) {
            if (!external) {
                throw new AgentValidationException("query_health 仅支持外部服务诊断");
            }
            if (args.containsKey("lookbackMinutes")) {
                throw new AgentValidationException(
                        "query_health 不接受 lookbackMinutes，健康状态是单次快照");
            }
        } else if (!"query_metrics".equals(toolName)) {
            throw new AgentValidationException("该工具不支持 Agent 调用: " + toolName);
        }

        Instant end = input.windowEnd();
        Instant lookbackStart = end.minusSeconds((long) lookbackMinutes * 60);
        Instant start = lookbackStart.isBefore(input.windowStart()) ? input.windowStart() : lookbackStart;
        if (!start.isBefore(end)) {
            throw new AgentValidationException("工具查询时间窗口无效");
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        if (!"query_health".equals(toolName)) {
            parameters.put("lookbackMinutes", lookbackMinutes);
        }
        if ("query_logs".equals(toolName)) {
            parameters.put("limit", limit);
            if (requestId != null) {
                parameters.put("requestId", requestId);
            }
        }

        String signature = toolName + "|" + service + "|" + lookbackMinutes + "|"
                + limit + "|" + (requestId != null ? requestId : "");
        return new ValidatedCall(toolName, service, requestId, limit, lookbackMinutes,
                start, end, parameters, signature);
    }

    private Set<String> allowedToolKeys(String toolName) {
        return switch (toolName) {
            case "query_logs" -> Set.of("requestId", "limit");
            case "query_metrics" -> Set.of();
            default -> Set.of();
        };
    }

    private String stringArg(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String text)) {
            throw new AgentValidationException("参数 " + key + " 必须是字符串");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return defaultValue;
        }
        if (trimmed.length() > 128) {
            throw new AgentValidationException("参数 " + key + " 过长");
        }
        return trimmed;
    }

    private int intArg(Map<String, Object> args, String key, int defaultValue, int min, int max) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        long parsed;
        if (value instanceof Number number && number.doubleValue() == number.longValue()) {
            parsed = number.longValue();
        } else {
            throw new AgentValidationException("参数 " + key + " 必须是整数");
        }
        if (parsed < min || parsed > max) {
            throw new AgentValidationException("参数 " + key + " 超出范围: " + parsed
                    + "，允许 " + min + "-" + max);
        }
        return (int) parsed;
    }

    public record ValidatedCall(
            String toolName,
            String service,
            String requestId,
            int limit,
            int lookbackMinutes,
            Instant windowStart,
            Instant windowEnd,
            Map<String, Object> parameters,
            String signature) {
    }
}

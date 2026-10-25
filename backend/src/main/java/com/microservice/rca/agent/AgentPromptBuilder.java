package com.microservice.rca.agent;

import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.tools.DiagnosticToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 构造 DeepSeek 提示词。提示词中只出现白名单观测字段、工具定义和真实工具结果。
 * 不保存模型原始思维链，只要求输出决策摘要（reason）。
 */
@Component
public class AgentPromptBuilder {

    private static final int MAX_ITEMS_IN_PROMPT = 20;
    private static final int MAX_EVIDENCE_IDS_IN_PROMPT = 400;

    private final JsonSupport jsonSupport;

    public AgentPromptBuilder(JsonSupport jsonSupport) {
        this.jsonSupport = jsonSupport;
    }

    public String systemPrompt() {
        return builtInSystemPrompt();
    }

    public String systemPrompt(AgentOrchestrator.AgentInput input) {
        if (input != null && "EXTERNAL".equalsIgnoreCase(input.serviceKind())) {
            return externalSystemPrompt(input);
        }
        return builtInSystemPrompt();
    }

    private String builtInSystemPrompt() {
        return """
                你是微服务故障诊断 Agent。你只能通过只读诊断工具获取证据，并在证据充分时输出结构化根因报告。
                你的每个回合只能输出一个 JSON 对象，不要输出 Markdown、解释文字或思维链。
                JSON 中必须包含 action 字段，取值只能是 CALL_TOOL、FINAL_REPORT、INSUFFICIENT_EVIDENCE。

                可选动作：
                1. 调用工具：
                   {"action":"CALL_TOOL","tool":"query_logs","arguments":{"service":"all","requestId":"可选","limit":50,"lookbackMinutes":30},"reason":"决策摘要"}
                   query_logs 参数：service 可选 all/order-service/inventory-service；requestId 可选，最长 64；limit 1-200；lookbackMinutes 1-60。
                2. 调用工具：
                   {"action":"CALL_TOOL","tool":"query_metrics","arguments":{"service":"all","lookbackMinutes":30},"reason":"决策摘要"}
                   query_metrics 参数：service 同上；lookbackMinutes 1-60。
                3. 输出根因报告：
                   {"action":"FINAL_REPORT","report":{"rootCause":"根因描述","affectedServices":["order-service"],"hypothesis":"可验证的故障假设","supportingEvidenceIds":["log:order-service:xxx"],"contradictingEvidenceIds":[],"confidenceLevel":"HIGH","recommendedActions":["人工确认后的处置建议"],"limitations":["证据或分析限制"]},"reason":"决策摘要"}
                4. 证据不足：
                   {"action":"INSUFFICIENT_EVIDENCE","reason":"现有证据不足的具体原因","limitations":["缺少哪些证据"]}

                强制规则：
                - supportingEvidenceIds 中的每个 ID 必须原样出现在真实工具结果的 evidenceIds 中，禁止编造。
                - 不得假设未观察到的事实；不得把实验预设当作已知答案。
                - confidenceLevel 只能是 HIGH、MEDIUM、LOW，不输出精确概率。
                - 工具失败或证据不足时，必须选择 INSUFFICIENT_EVIDENCE，不能编造根因。
                - 不要请求执行命令、SQL 或任意 HTTP 请求；只能使用给定工具。
                - 不要重复提交完全相同的工具调用；请使用已有观察结果继续分析或结束调查。
                """;
    }

    private String externalSystemPrompt(AgentOrchestrator.AgentInput input) {
        String serviceName = input.serviceName() == null ? "" : input.serviceName();
        String allowed = input.allowedServices() == null || input.allowedServices().isEmpty()
                ? serviceName : String.join("/", input.allowedServices());
        List<String> toolNames = input.availableTools().stream()
                .map(DiagnosticToolRegistry.ToolDefinition::name)
                .toList();
        String availableToolText = toolNames.isEmpty() ? "无" : String.join("/", toolNames);
        boolean logsAvailable = toolNames.contains("query_logs");

        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                你是运行在故障分析平台中的诊断 Agent。本次诊断对象是已注册的外部 Spring Boot 服务：%s。
                该对象不属于内置故障注入实验，没有预设故障类型、预设实验参数或预设根因。
                你只能通过只读工具获取证据，并在证据充分时输出结构化根因报告。
                你的每个回合只能输出一个 JSON 对象，不要输出 Markdown、解释文字或思维链。
                JSON 中必须包含 action 字段，取值只能是 CALL_TOOL、FINAL_REPORT、INSUFFICIENT_EVIDENCE。

                可选动作：
                1. 查询 Actuator 健康快照：
                   {"action":"CALL_TOOL","tool":"query_health","arguments":{"service":"%s"},"reason":"决策摘要"}
                   query_health 返回整体状态和组件级状态；异常组件会包含脱敏后的 errorType/errorSummary，
                   并在 evidenceIds 中提供组件级证据 ID。不接受 lookbackMinutes。
                2. 查询 Actuator 指标快照：
                   {"action":"CALL_TOOL","tool":"query_metrics","arguments":{"service":"%s","lookbackMinutes":30},"reason":"决策摘要"}
                   query_metrics 返回当前暴露的指标快照，优先包含连接池超时/获取耗时等异常相关指标；
                   timeWindowSupported=false，不是历史时间序列。
                """.formatted(serviceName, allowed, allowed));
        if (logsAvailable) {
            prompt.append("""
                    3. 查询日志（该服务已显式配置日志数据源）：
                       {"action":"CALL_TOOL","tool":"query_logs","arguments":{"service":"%s","requestId":"可选","limit":50},"reason":"决策摘要"}
                       工具失败表示日志不可用，不能据此推断没有异常，更不能编造日志。
                    """.formatted(allowed));
        }
        int finalActionNo = logsAvailable ? 4 : 3;
        int insufficientActionNo = finalActionNo + 1;
        prompt.append("""
                %d. 输出根因报告：
                   {"action":"FINAL_REPORT","report":{"rootCause":"根因描述","affectedServices":["%s"],"hypothesis":"可验证的故障假设","supportingEvidenceIds":["health:%s:..."],"contradictingEvidenceIds":[],"confidenceLevel":"MEDIUM","recommendedActions":["仅建议人工确认后的处置"],"limitations":["证据或分析限制"]},"reason":"决策摘要"}
                %d. 证据不足：
                   {"action":"INSUFFICIENT_EVIDENCE","reason":"现有证据不足的具体原因","limitations":["缺少哪些证据"]}

                强制规则：
                - 只能使用本次 availableTools 中的只读工具（%s）；不能重启、停止、修改外部服务，也不能请求执行命令、SQL 或任意 HTTP 请求。
                - 不得把任何实验预设故障类型、实验参数、实验根因或历史实验结论当作本次诊断的证据。
                - Actuator 健康状态与指标都是单次快照，不能代表历史可用性或完整时间序列；缺少某个指标不代表该指标正常。
                - query_health 返回组件级状态时，应优先使用具体组件的 evidenceId（形如 health:服务:时间戳:组件路径），不要只引用整体状态。
                - 没有依赖拓扑工具，不得编造服务依赖或调用链。
                - supportingEvidenceIds 中的每个 ID 必须原样出现在真实工具结果的 evidenceIds 中，禁止编造。
                - affectedServices 只能填写 "%s"；不得填写未注册的服务。
                - confidenceLevel 只能是 HIGH、MEDIUM、LOW，不输出精确概率。
                - 如果只有整体 UP/DOWN 或通用 JVM/HTTP 指标，没有组件级证据，不得声称已经定位到具体根因；应输出 INSUFFICIENT_EVIDENCE。
                - query_health/query_metrics 返回 connectivity: 开头的证据时，只能说明本次探测未建立连接；可以得出“目标服务不可达”，但不得据此断言进程已停止、服务崩溃或网络配置错误，具体根因需人工核实。
                - 如果只有 connectivity 证据，没有组件级证据或其他成功证据，必须选择 INSUFFICIENT_EVIDENCE，并在 reason 中说明目标服务不可达、具体根因待核实。
                - 工具失败或证据不足时，必须选择 INSUFFICIENT_EVIDENCE，不能编造根因。
                - 不要重复提交完全相同的工具调用；请使用已有观察结果继续分析或结束调查。
                """.formatted(finalActionNo, serviceName, serviceName,
                insufficientActionNo, availableToolText, serviceName));
        String unavailableReason = input.unavailableTools().get("query_logs");
        if (!logsAvailable && unavailableReason != null && !unavailableReason.isBlank()) {
            prompt.append("- 本次日志不可用：").append(unavailableReason).append('\n');
        }
        return prompt.toString();
    }

    public String userPrompt(AgentOrchestrator.AgentInput input,
                             List<Observation> observations,
                             String validationFeedback,
                             int modelCallNo,
                             int toolCallCount) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("faultEventId", input.faultEventId().toString());
        event.put("serviceName", input.serviceName());
        event.put("serviceKind", input.serviceKind());
        event.put("allowedServices", input.allowedServices());
        event.put("eventType", input.eventType());
        event.put("severity", input.severity());
        event.put("summary", input.summary());
        event.put("observedResult", input.observedResult());
        event.put("requestId", input.requestId());
        event.put("windowStart", input.windowStart().toString());
        event.put("windowEnd", input.windowEnd().toString());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("modelCallNo", modelCallNo);
        state.put("maxModelCalls", input.limits().maxModelCalls());
        state.put("toolCallCount", toolCallCount);
        state.put("maxToolCalls", input.limits().maxToolCalls());

        List<Map<String, Object>> toolDefinitions = new ArrayList<>();
        for (DiagnosticToolRegistry.ToolDefinition tool : input.availableTools()) {
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("name", tool.name());
            definition.put("description", tool.description());
            toolDefinitions.add(definition);
        }

        Set<String> allEvidenceIds = new LinkedHashSet<>();
        List<Map<String, Object>> compactObservations = new ArrayList<>();
        for (Observation observation : observations) {
            allEvidenceIds.addAll(observation.evidenceIds());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("round", observation.round());
            item.put("tool", observation.toolName());
            item.put("invocationId", observation.invocationId());
            item.put("success", observation.success());
            item.put("latencyMs", observation.latencyMs());
            item.put("error", observation.error());
            item.put("evidenceIds", observation.evidenceIds());
            item.put("output", compactOutput(observation.output(), input.limits().maxToolResultChars()));
            compactObservations.add(item);
        }

        List<String> evidenceList = new ArrayList<>(allEvidenceIds);
        int omittedEvidenceIds = 0;
        if (evidenceList.size() > MAX_EVIDENCE_IDS_IN_PROMPT) {
            omittedEvidenceIds = evidenceList.size() - MAX_EVIDENCE_IDS_IN_PROMPT;
            evidenceList = evidenceList.subList(0, MAX_EVIDENCE_IDS_IN_PROMPT);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("investigationState", state);
        payload.put("availableTools", toolDefinitions);
        if (!input.unavailableTools().isEmpty()) {
            payload.put("unavailableTools", input.unavailableTools());
        }
        payload.put("observations", compactObservations);
        payload.put("knownEvidenceIds", evidenceList);
        if (omittedEvidenceIds > 0) {
            payload.put("omittedEvidenceIdCount", omittedEvidenceIds);
        }
        if (validationFeedback != null && !validationFeedback.isBlank()) {
            payload.put("validationFeedback", truncate(validationFeedback, 1000));
        }
        payload.put("requiredOutput", "只输出一个 JSON 对象，action 为 CALL_TOOL / FINAL_REPORT / INSUFFICIENT_EVIDENCE");

        String prompt = jsonSupport.write(payload);
        if (prompt.length() <= input.limits().maxPromptChars()) {
            return prompt;
        }

        // 提示词过长时，从最早的观察开始移除详细输出，但保留 evidenceIds 供模型引用。
        for (int index = 0; index < compactObservations.size(); index++) {
            Map<String, Object> item = compactObservations.get(index);
            item.put("output", Map.of(
                    "truncated", true,
                    "evidenceIds", item.get("evidenceIds"),
                    "note", "详细输出因提示词长度限制未发送"));
            compactObservations.set(index, item);
            prompt = jsonSupport.write(payload);
            if (prompt.length() <= input.limits().maxPromptChars()) {
                return prompt;
            }
        }

        // 最后的降级路径仍返回合法 JSON：只保留事件、工具定义和有限的证据 ID。
        payload.put("observations", List.of());
        payload.put("knownEvidenceIds", evidenceList.subList(0,
                Math.min(evidenceList.size(), 100)));
        payload.put("promptTruncated", true);
        prompt = jsonSupport.write(payload);
        if (prompt.length() <= input.limits().maxPromptChars()) {
            return prompt;
        }
        payload.remove("knownEvidenceIds");
        return jsonSupport.write(payload);
    }

    @SuppressWarnings("unchecked")
    private Object compactOutput(Map<String, Object> output, int maxChars) {
        if (output == null) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        output.forEach((key, value) -> {
            if (value instanceof List<?> list) {
                if (list.size() > MAX_ITEMS_IN_PROMPT) {
                    compact.put(key, new ArrayList<>(list.subList(0, MAX_ITEMS_IN_PROMPT)));
                    compact.put(key + "OmittedCount", list.size() - MAX_ITEMS_IN_PROMPT);
                } else {
                    compact.put(key, list);
                }
            } else if (value instanceof Map<?, ?> map) {
                Map<String, Object> typed = new LinkedHashMap<>();
                map.forEach((mapKey, mapValue) -> typed.put(String.valueOf(mapKey), mapValue));
                compact.put(key, typed);
            } else {
                compact.put(key, value);
            }
        });

        String serialized = jsonSupport.write(compact);
        if (serialized.length() <= maxChars) {
            return compact;
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("truncated", true);
        fallback.put("evidenceIds", output.get("evidenceIds"));
        fallback.put("summary", output.get("summary"));
        fallback.put("warnings", output.get("warnings"));
        fallback.put("note", "工具输出超过长度限制，完整内容保存在 tool_invocation");
        return fallback;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record Observation(
            int round,
            String toolName,
            java.util.UUID invocationId,
            boolean success,
            int latencyMs,
            String error,
            List<String> evidenceIds,
            Map<String, Object> output) {
    }
}

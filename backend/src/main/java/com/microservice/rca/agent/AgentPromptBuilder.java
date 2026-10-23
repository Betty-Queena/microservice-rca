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

    public String userPrompt(AgentOrchestrator.AgentInput input,
                             List<Observation> observations,
                             String validationFeedback,
                             int modelCallNo,
                             int toolCallCount) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("faultEventId", input.faultEventId().toString());
        event.put("serviceName", input.serviceName());
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

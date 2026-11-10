package com.microservice.rca.agent;

import com.microservice.rca.agent.AgentOrchestrator.AgentInput;
import com.microservice.rca.agent.AgentOrchestrator.AgentRunResult;
import com.microservice.rca.agent.AgentOrchestrator.RunStatus;
import com.microservice.rca.agent.AgentPromptBuilder.Observation;
import com.microservice.rca.tools.ToolExecutionContext;
import com.microservice.rca.tools.ToolInvocationService;
import com.microservice.rca.tools.ToolInvocationService.ToolInvocationOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 有限状态 Agent 编排器：
 * MODEL_DECISION -> (TOOL_EXECUTION -> OBSERVATION -> MODEL_DECISION)* -> FINAL_REPORT / INSUFFICIENT_EVIDENCE。
 *
 * <p>工具选择和查询范围由模型逐轮决定，不是固定调用顺序。所有循环均受模型轮数、
 * 工具次数、整体超时和提示/输出长度限制。</p>
 */
@Service
public class DefaultAgentOrchestrator implements AgentOrchestrator {

    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final long RETRY_BACKOFF_MS = 500L;

    private final AgentModelClient modelClient;
    private final AgentPromptBuilder promptBuilder;
    private final AgentDecisionParser decisionParser;
    private final AgentToolCallValidator toolCallValidator;
    private final AgentReportValidator reportValidator;
    private final ToolInvocationService toolInvocationService;
    private final AgentProperties properties;

    public DefaultAgentOrchestrator(AgentModelClient modelClient,
                                    AgentPromptBuilder promptBuilder,
                                    AgentDecisionParser decisionParser,
                                    AgentToolCallValidator toolCallValidator,
                                    AgentReportValidator reportValidator,
                                    ToolInvocationService toolInvocationService,
                                    AgentProperties properties) {
        this.modelClient = modelClient;
        this.promptBuilder = promptBuilder;
        this.decisionParser = decisionParser;
        this.toolCallValidator = toolCallValidator;
        this.reportValidator = reportValidator;
        this.toolInvocationService = toolInvocationService;
        this.properties = properties;
    }

    @Override
    public AgentRunResult diagnose(AgentInput input) {
        List<Map<String, Object>> trace = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        Set<String> knownEvidenceIds = new LinkedHashSet<>();
        Set<String> executedSignatures = new LinkedHashSet<>();
        String feedback = null;
        String lastTerminalReason = null;
        boolean lengthTruncationRetried = false;
        int modelCalls = 0;
        int toolCalls = 0;
        Instant deadline = Instant.now().plus(input.limits().overallTimeout());
        String modelName = modelClient.modelName();

        if (!modelClient.isConfigured()) {
            String reason = modelClient.unavailabilityReason();
            String errorType = properties.llmEnabled() ? "CONFIG_MISSING" : "LLM_DISABLED";
            trace.add(modelErrorTrace(0,
                    new AgentModelException(errorType, reason, false),
                    0, 0, modelName));
            return AgentRunResult.failed(reason, trace, modelName);
        }

        while (modelCalls < input.limits().maxModelCalls()) {
            Duration remaining = remaining(deadline);
            if (remaining.isZero()) {
                return AgentRunResult.failed(timeoutReason(input), trace, modelName);
            }

            modelCalls++;
            // 兜底原因只反映“最后一轮”发生了什么，避免把更早轮次的拒绝误报为最后决策。
            lastTerminalReason = null;
            String userPrompt = promptBuilder.userPrompt(
                    input, observations, feedback, modelCalls, toolCalls);
            if (userPrompt.length() > input.limits().maxPromptChars()) {
                return AgentRunResult.failed("提示词超过长度限制，已停止调用模型", trace, modelName);
            }

            AgentModelClient.ModelResponse response;
            long modelStartedAt = System.nanoTime();
            try {
                response = modelClient.complete(new AgentModelClient.ModelRequest(
                        promptBuilder.systemPrompt(input),
                        userPrompt,
                        properties.deepseek().temperature(),
                        properties.deepseek().maxTokens(),
                        remaining));
            } catch (AgentModelException ex) {
                trace.add(modelErrorTrace(modelCalls, ex, elapsedMs(modelStartedAt),
                        userPrompt.length(), modelName));
                if (ex.isRetryable() && modelCalls < input.limits().maxModelCalls()) {
                    feedback = "上一轮模型调用失败（" + ex.getFailureType() + "），请基于现有证据继续决策";
                    if (remaining(deadline).isZero()) {
                        return AgentRunResult.failed(timeoutReason(input), trace, modelName);
                    }
                    if (!sleepBeforeRetry()) {
                        return AgentRunResult.failed("模型重试被中断", trace, modelName);
                    }
                    if (remaining(deadline).isZero()) {
                        return AgentRunResult.failed(timeoutReason(input), trace, modelName);
                    }
                    continue;
                }
                return AgentRunResult.failed(truncate(ex.getMessage(), MAX_ERROR_LENGTH), trace, modelName);
            } catch (Exception ex) {
                trace.add(modelErrorTrace(modelCalls,
                        new AgentModelException("UNEXPECTED", "模型调用异常: " + ex.getClass().getSimpleName(), false),
                        elapsedMs(modelStartedAt), userPrompt.length(), modelName));
                return AgentRunResult.failed("模型调用异常: " + ex.getClass().getSimpleName(), trace, modelName);
            }

            int latencyMs = elapsedMs(modelStartedAt);
            if (remaining(deadline).isZero()) {
                // 模型已返回但整体预算耗尽：保留本轮决策记录后按超时终止，不再执行工具。
                trace.add(modelDecisionTrace(modelCalls, "DEADLINE_EXCEEDED", latencyMs,
                        userPrompt.length(), response, modelName,
                        "模型返回后诊断整体预算已耗尽", null));
                return AgentRunResult.failed(timeoutReason(input), trace, modelName);
            }
            AgentDecisionParser.Decision decision;
            try {
                decision = decisionParser.parse(response.content(), input.limits().maxOutputChars());
            } catch (AgentValidationException ex) {
                trace.add(modelDecisionTrace(modelCalls, "INVALID_OUTPUT", latencyMs,
                        userPrompt.length(), response, modelName, ex.getMessage(), null));
                lastTerminalReason = "INVALID_OUTPUT: " + truncate(ex.getMessage(), MAX_ERROR_LENGTH);
                if (response.lengthTruncated()) {
                    // finish_reason=length 说明输出被 max_tokens 截断，盲目原样重试只会重复消耗 Token。
                    // 只允许一次带明确指令的重试；仍截断或已无剩余轮数时终止并留下可审计原因。
                    if (lengthTruncationRetried
                            || modelCalls >= input.limits().maxModelCalls()) {
                        return AgentRunResult.failed(
                                "模型输出因长度截断（finish_reason=length），"
                                        + "未能在剩余模型调用轮数内形成完整有效决策",
                                trace, modelName);
                    }
                    lengthTruncationRetried = true;
                    feedback = "上一轮模型输出因 max_tokens 截断（finish_reason=length），"
                            + "JSON 不完整。请重新输出一个更精简、完整且符合约定的单个 JSON 对象，"
                            + "不要重复无关内容或思维链。";
                    continue;
                }
                feedback = "模型输出校验失败: " + ex.getMessage()
                        + "。请只输出符合约定的单个 JSON 对象。";
                continue;
            }

            if (decision instanceof AgentDecisionParser.CallTool callTool) {
                if (toolCalls >= input.limits().maxToolCalls()) {
                    trace.add(modelDecisionTrace(modelCalls, "CALL_TOOL_REJECTED", latencyMs,
                            userPrompt.length(), response, modelName,
                            "工具调用次数已达上限", callTool.reason()));
                    lastTerminalReason = "TOOL_LIMIT_REACHED";
                    feedback = "工具调用次数已达上限 " + input.limits().maxToolCalls()
                            + "，不得再调用工具，请输出 FINAL_REPORT 或 INSUFFICIENT_EVIDENCE。";
                    continue;
                }

                AgentToolCallValidator.ValidatedCall call;
                try {
                    call = toolCallValidator.validate(input, callTool.tool(), callTool.arguments());
                } catch (AgentValidationException ex) {
                    trace.add(toolRejectedTrace(modelCalls, callTool, latencyMs,
                            userPrompt.length(), response, modelName, ex.getMessage()));
                    lastTerminalReason = "TOOL_REJECTED: "
                            + truncate(ex.getMessage(), MAX_ERROR_LENGTH);
                    feedback = "工具调用被拒绝: " + ex.getMessage() + "。请修正参数或改用已注册工具。";
                    continue;
                }

                if (!executedSignatures.add(call.signature())) {
                    trace.add(toolRejectedTrace(modelCalls, callTool, latencyMs,
                            userPrompt.length(), response, modelName,
                            "重复的工具调用已被跳过: " + call.signature()));
                    lastTerminalReason = "DUPLICATE_TOOL_CALL: " + call.signature();
                    feedback = "检测到完全相同的重复工具调用，已跳过执行。请使用已有观察结果继续分析，"
                            + "或调整查询范围，或结束调查。";
                    continue;
                }

                // 每一轮模型调用都记录 usage 与耗时，便于审计完整核算 token。
                Map<String, Object> callDecisionTrace = modelDecisionTrace(modelCalls, "CALL_TOOL",
                        latencyMs, userPrompt.length(), response, modelName, null, callTool.reason());
                callDecisionTrace.put("tool", call.toolName());
                trace.add(callDecisionTrace);

                Duration toolBudget = remaining(deadline);
                if (toolBudget.isZero()) {
                    Map<String, Object> timeoutTrace = baseTrace("TOOL_EXECUTION", modelCalls);
                    timeoutTrace.put("tool", call.toolName());
                    timeoutTrace.put("service", call.service());
                    timeoutTrace.put("requestId", call.requestId());
                    timeoutTrace.put("status", "TIMEOUT");
                    timeoutTrace.put("errorType", "TOOL_TIMEOUT");
                    timeoutTrace.put("error", "诊断整体预算已耗尽，未执行工具调用");
                    timeoutTrace.put("latencyMs", 0);
                    timeoutTrace.put("evidenceCount", 0);
                    timeoutTrace.put("evidenceIds", List.of());
                    trace.add(timeoutTrace);
                    return AgentRunResult.failed(timeoutReason(input), trace, modelName);
                }

                toolCalls++;
                ToolInvocationOutcome outcome = toolInvocationService.invoke(
                        input.diagnosisTaskId(),
                        call.toolName(),
                        new ToolExecutionContext(input.diagnosisTaskId(), call.service(),
                                call.windowStart(), call.windowEnd(), call.parameters(), toolBudget),
                        toolCalls);

                List<String> evidenceIds = extractEvidenceIds(outcome.result().output());
                boolean hasNewEvidence = evidenceIds.stream()
                        .anyMatch(evidenceId -> !knownEvidenceIds.contains(evidenceId));
                // 失败的工具结果也可能携带真实探测证据（如 connectivity:），
                // 必须保留下来供模型、最终报告和审计引用；成功状态仍按工具结果单独判断。
                knownEvidenceIds.addAll(evidenceIds);
                Observation observation = new Observation(
                        modelCalls,
                        call.toolName(),
                        outcome.invocationId(),
                        outcome.result().success(),
                        outcome.latencyMs(),
                        outcome.result().errorMessage(),
                        evidenceIds,
                        outcome.result().output());
                observations.add(observation);
                trace.add(toolExecutionTrace(modelCalls, call, outcome, evidenceIds));
                // 仅对成功但未产生新 evidenceId 的查询给出软反馈：不阻断执行，
                // 但提示模型不要用相同或实质相同的范围反复取证。
                feedback = outcome.result().success() && !hasNewEvidence
                        ? "本次查询没有产生新的 evidenceId。请不要再用相同或实质相同的范围查询；"
                        + "请基于已有观察输出 FINAL_REPORT，或输出 INSUFFICIENT_EVIDENCE。"
                        : null;
                continue;
            }

            if (decision instanceof AgentDecisionParser.FinalReport finalReport) {
                if (knownEvidenceIds.isEmpty()) {
                    trace.add(modelDecisionTrace(modelCalls, "FINAL_REPORT_REJECTED", latencyMs,
                            userPrompt.length(), response, modelName,
                            "尚无真实证据，拒绝根因报告", finalReport.reason()));
                    lastTerminalReason = "FINAL_REPORT_REJECTED: 尚无真实证据";
                    feedback = "当前没有任何成功的工具证据，不能输出根因报告。"
                            + "请先调用已注册的只读工具（"
                            + input.availableTools().stream()
                                    .map(tool -> tool.name())
                                    .reduce((left, right) -> left + "/" + right)
                                    .orElse("无")
                            + "），或输出 INSUFFICIENT_EVIDENCE。";
                    continue;
                }
                try {
                    AgentReportValidator.ValidatedReport validated =
                            reportValidator.validate(finalReport.report(), knownEvidenceIds,
                                    input.allowedServices());
                    Map<String, Object> report = new LinkedHashMap<>(validated.report());
                    boolean external = "EXTERNAL".equalsIgnoreCase(input.serviceKind());
                    boolean componentEvidenceAvailable = hasComponentEvidence(observations);
                    boolean connectivityEvidenceOnly = external
                            && onlyConnectivityEvidence(observations);
                    boolean hypothesisOnly = external
                            && !componentEvidenceAvailable
                            && ("LOW".equalsIgnoreCase(validated.confidenceLevel())
                            || connectivityEvidenceOnly);
                    if (external) {
                        report.put("componentEvidenceAvailable", componentEvidenceAvailable);
                        report.put("assessment", hypothesisOnly
                                ? "LOW_CONFIDENCE_HYPOTHESIS" : "ROOT_CAUSE_IDENTIFIED");
                    }
                    if (hypothesisOnly) {
                        // 只有连通性探测失败证据时，不能据此断言进程停止或具体根因。
                        report.put("result", RunStatus.INSUFFICIENT_EVIDENCE.name());
                        report.put("assessmentNote", connectivityEvidenceOnly
                                ? "只有连通性探测失败证据，未获得组件级证据；"
                                + "目标服务可能不可达，具体根因待核实"
                                : "置信度为 LOW 且未获得组件级证据，该内容仅为可验证假设，未定位根因");
                    } else {
                        report.put("result", RunStatus.ROOT_CAUSE_IDENTIFIED.name());
                    }
                    report.put("model", modelName);
                    report.put("modelCallCount", modelCalls);
                    report.put("toolCallCount", toolCalls);
                    report.put("generatedAt", Instant.now().toString());
                    Map<String, Object> finalTrace = modelDecisionTrace(modelCalls,
                            "FINAL_REPORT_ACCEPTED", latencyMs, userPrompt.length(),
                            response, modelName, null, finalReport.reason());
                    finalTrace.put("componentEvidenceAvailable", componentEvidenceAvailable);
                    finalTrace.put("connectivityEvidenceOnly", connectivityEvidenceOnly);
                    finalTrace.put("assessment", report.get("assessment"));
                    trace.add(finalTrace);
                    if (hypothesisOnly) {
                        return AgentRunResult.lowConfidenceHypothesis(report, trace, modelName);
                    }
                    return AgentRunResult.rootCause(report, trace, modelName, validated.confidenceLevel());
                } catch (AgentValidationException ex) {
                    trace.add(modelDecisionTrace(modelCalls, "FINAL_REPORT_REJECTED", latencyMs,
                            userPrompt.length(), response, modelName, ex.getMessage(), finalReport.reason()));
                    lastTerminalReason = "FINAL_REPORT_REJECTED: "
                            + truncate(ex.getMessage(), MAX_ERROR_LENGTH);
                    feedback = "最终报告校验失败: " + ex.getMessage()
                            + "。只能引用工具结果中真实存在的 evidenceIds。";
                    continue;
                }
            }

            if (decision instanceof AgentDecisionParser.InsufficientEvidence insufficient) {
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("result", RunStatus.INSUFFICIENT_EVIDENCE.name());
                report.put("reason", truncate(insufficient.reason(), 1024));
                report.put("limitations", insufficient.limitations());
                report.put("observedEvidenceIds", new ArrayList<>(knownEvidenceIds));
                report.put("model", modelName);
                report.put("modelCallCount", modelCalls);
                report.put("toolCallCount", toolCalls);
                report.put("generatedAt", Instant.now().toString());
                report.put("disclaimer", "证据不足，未生成根因结论；处置建议未执行");
                trace.add(modelDecisionTrace(modelCalls, "INSUFFICIENT_EVIDENCE", latencyMs,
                        userPrompt.length(), response, modelName, null, insufficient.reason()));
                return AgentRunResult.insufficientEvidence(report, trace, modelName);
            }
        }

        return budgetExhaustedFallback(input, observations, knownEvidenceIds, trace,
                modelName, modelCalls, toolCalls, lastTerminalReason);
    }

    /**
     * 模型轮次耗尽时的确定性兜底。
     *
     * <p>如果已经执行过工具，说明系统获得过真实观察，不能把整个任务判为执行失败：
     * 保留已有 evidenceId，返回 INSUFFICIENT_EVIDENCE，并明确“模型未形成结论”。
     * 如果从未获得任何工具结果，仍按 FAILED 处理，避免把配置/模型故障伪装成证据不足。</p>
     */
    private AgentRunResult budgetExhaustedFallback(AgentInput input,
                                                   List<Observation> observations,
                                                   Set<String> knownEvidenceIds,
                                                   List<Map<String, Object>> trace,
                                                   String modelName,
                                                   int modelCalls,
                                                   int toolCalls,
                                                   String lastTerminalReason) {
        String budgetReason = "达到最大模型调用轮数（"
                + input.limits().maxModelCalls() + "）仍未形成可验证结论";
        if (observations.isEmpty()) {
            return AgentRunResult.failed(lastTerminalReason == null
                    ? budgetReason
                    : budgetReason + "；最后决策：" + lastTerminalReason, trace, modelName);
        }

        String evidenceNote = knownEvidenceIds.isEmpty()
                ? "已有工具调用但未获得可引用的 evidenceId"
                : "系统保留已有真实工具证据，但未自动推断根因";
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("result", RunStatus.INSUFFICIENT_EVIDENCE.name());
        report.put("reason", "模型调用次数达到上限（" + input.limits().maxModelCalls()
                + "），最后一轮未形成最终报告；" + evidenceNote
                + (lastTerminalReason == null ? "" : "。最后决策：" + lastTerminalReason));
        report.put("limitations", List.of(
                "未完成模型计划中的后续调查",
                "系统未根据已有证据自动生成根因结论",
                "如需结论请人工复核 observedEvidenceIds 或重新诊断"));
        report.put("observedEvidenceIds", new ArrayList<>(knownEvidenceIds));
        report.put("fallback", true);
        report.put("fallbackReason", "MODEL_BUDGET_EXHAUSTED");
        report.put("lastDecision", lastTerminalReason);
        report.put("model", modelName);
        report.put("modelCallCount", modelCalls);
        report.put("toolCallCount", toolCalls);
        report.put("generatedAt", Instant.now().toString());
        report.put("disclaimer", "证据不足，未生成根因结论；处置建议未执行");

        Map<String, Object> fallbackTrace = baseTrace("AGENT_FALLBACK", modelCalls);
        fallbackTrace.put("status", RunStatus.INSUFFICIENT_EVIDENCE.name());
        fallbackTrace.put("fallback", true);
        fallbackTrace.put("fallbackReason", "MODEL_BUDGET_EXHAUSTED");
        fallbackTrace.put("lastDecision", lastTerminalReason);
        fallbackTrace.put("modelCallCount", modelCalls);
        fallbackTrace.put("toolCallCount", toolCalls);
        fallbackTrace.put("evidenceCount", knownEvidenceIds.size());
        fallbackTrace.put("observedEvidenceIds", new ArrayList<>(knownEvidenceIds));
        trace.add(fallbackTrace);
        return AgentRunResult.insufficientEvidence(report, trace, modelName);
    }

    /**
     * 组件级证据包括健康快照的组件明细/组件证据 ID，以及连接池等组件指标。
     * 它们比整体 UP/DOWN 或通用 JVM 指标更接近具体故障组件。
     */
    private boolean hasComponentEvidence(List<Observation> observations) {
        for (Observation observation : observations) {
            if (!observation.success() || observation.output() == null) {
                continue;
            }
            if ("query_health".equals(observation.toolName())
                    && (nonEmptyList(observation.output().get("componentEvidenceIds"))
                    || nonEmptyList(observation.output().get("components")))) {
                return true;
            }
            if ("query_metrics".equals(observation.toolName())) {
                for (String evidenceId : observation.evidenceIds()) {
                    if (evidenceId.contains(":hikaricp.") || evidenceId.contains(":jdbc.")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 只有 connectivity: 证据意味着两次探测都没有建立连接。
     * 该证据可以说明不可达，但不足以断言进程停止、崩溃或具体根因。
     */
    private boolean onlyConnectivityEvidence(List<Observation> observations) {
        boolean sawConnectivity = false;
        for (Observation observation : observations) {
            for (String evidenceId : observation.evidenceIds()) {
                if (evidenceId.startsWith("connectivity:")) {
                    sawConnectivity = true;
                } else {
                    return false;
                }
            }
        }
        return sawConnectivity;
    }

    private boolean nonEmptyList(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    private List<String> extractEvidenceIds(Map<String, Object> output) {
        if (output == null) {
            return List.of();
        }
        Object raw = output.get("evidenceIds");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> evidenceIds = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String id && !id.isBlank() && id.length() <= 200) {
                evidenceIds.add(id);
            }
        }
        return evidenceIds;
    }

    private Map<String, Object> modelDecisionTrace(int round,
                                                   String decision,
                                                   int latencyMs,
                                                   int promptChars,
                                                   AgentModelClient.ModelResponse response,
                                                   String modelName,
                                                   String validationError,
                                                   String reason) {
        Map<String, Object> entry = baseTrace("MODEL_DECISION", round);
        entry.put("status", validationError == null ? "ACCEPTED" : "REJECTED");
        entry.put("decision", decision);
        entry.put("model", modelName);
        entry.put("latencyMs", latencyMs);
        entry.put("promptChars", promptChars);
        entry.put("responseChars", response.content() != null ? response.content().length() : 0);
        entry.put("finishReason", response.finishReason());
        entry.put("lengthTruncated", response.lengthTruncated());
        putUsage(entry, response);
        entry.put("reason", truncate(reason, MAX_REASON_LENGTH));
        entry.put("validationError", truncate(validationError, MAX_ERROR_LENGTH));
        return entry;
    }

    private Map<String, Object> modelErrorTrace(int round,
                                                AgentModelException ex,
                                                int latencyMs,
                                                int promptChars,
                                                String modelName) {
        Map<String, Object> entry = baseTrace("MODEL_DECISION", round);
        entry.put("status", "FAILED");
        entry.put("decision", "MODEL_ERROR");
        entry.put("model", modelName);
        // round=0 是调用前的配置/开关预检（CONFIG_MISSING、LLM_DISABLED），根本没有发起模型请求，
        // 不能计入模型调用次数；进入循环后的失败才代表真实发起过调用。
        entry.put("modelCallAttempted", round > 0);
        entry.put("latencyMs", latencyMs);
        entry.put("promptChars", promptChars);
        entry.put("errorType", ex.getFailureType());
        entry.put("error", truncate(ex.getMessage(), MAX_ERROR_LENGTH));
        entry.put("retryable", ex.isRetryable());
        entry.put("promptTokens", null);
        entry.put("completionTokens", null);
        entry.put("totalTokens", null);
        entry.put("usageAvailable", false);
        entry.put("usageNote", "模型调用失败，未获得 usage");
        return entry;
    }

    private void putUsage(Map<String, Object> entry, AgentModelClient.ModelResponse response) {
        if (response.usageAvailable()) {
            entry.put("promptTokens", response.promptTokens());
            entry.put("completionTokens", response.completionTokens());
            entry.put("totalTokens", response.totalTokens());
            entry.put("usageAvailable", true);
        } else {
            entry.put("promptTokens", null);
            entry.put("completionTokens", null);
            entry.put("totalTokens", null);
            entry.put("usageAvailable", false);
            entry.put("usageNote", "Provider 响应未包含 usage");
        }
    }

    private Map<String, Object> toolRejectedTrace(int round,
                                                  AgentDecisionParser.CallTool callTool,
                                                  int latencyMs,
                                                  int promptChars,
                                                  AgentModelClient.ModelResponse response,
                                                  String modelName,
                                                  String error) {
        Map<String, Object> entry = baseTrace("TOOL_REJECTED", round);
        entry.put("tool", callTool.tool());
        entry.put("arguments", callTool.arguments());
        entry.put("decision", "CALL_TOOL_REJECTED");
        entry.put("model", modelName);
        entry.put("reason", truncate(callTool.reason(), MAX_REASON_LENGTH));
        entry.put("error", truncate(error, MAX_ERROR_LENGTH));
        entry.put("modelLatencyMs", latencyMs);
        entry.put("promptChars", promptChars);
        entry.put("responseChars", response.content() != null ? response.content().length() : 0);
        putUsage(entry, response);
        return entry;
    }

    private Map<String, Object> toolExecutionTrace(int round,
                                                   AgentToolCallValidator.ValidatedCall call,
                                                   ToolInvocationOutcome outcome,
                                                   List<String> evidenceIds) {
        Map<String, Object> entry = baseTrace("TOOL_EXECUTION", round);
        entry.put("tool", call.toolName());
        entry.put("invocationId", outcome.invocationId() != null ? outcome.invocationId().toString() : null);
        entry.put("service", call.service());
        entry.put("requestId", call.requestId());
        entry.put("lookbackMinutes", call.lookbackMinutes());
        entry.put("windowStart", call.windowStart().toString());
        entry.put("windowEnd", call.windowEnd().toString());
        entry.put("status", outcome.result().success() ? "SUCCEEDED" : "FAILED");
        entry.put("latencyMs", outcome.latencyMs());
        entry.put("evidenceCount", evidenceIds.size());
        entry.put("evidenceIds", evidenceIds);
        entry.put("error", truncate(outcome.result().errorMessage(), MAX_ERROR_LENGTH));
        return entry;
    }

    private Map<String, Object> baseTrace(String type, int round) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("round", round);
        entry.put("at", Instant.now().toString());
        return entry;
    }

    private Duration remaining(Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() || left.isZero() ? Duration.ZERO : left;
    }

    private String timeoutReason(AgentInput input) {
        return "诊断整体超时（" + input.limits().overallTimeout().toSeconds()
                + " 秒），已停止模型与工具调用";
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private int elapsedMs(long startedAtNanos) {
        return (int) Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

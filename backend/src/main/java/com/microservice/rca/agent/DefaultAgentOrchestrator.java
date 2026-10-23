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
        int modelCalls = 0;
        int toolCalls = 0;
        Instant deadline = Instant.now().plus(input.limits().overallTimeout());
        String modelName = modelClient.modelName();

        if (!modelClient.isConfigured()) {
            String reason = "未配置 DEEPSEEK_API_KEY，Agent 未调用模型，也未生成任何诊断结论";
            trace.add(modelErrorTrace(0,
                    new AgentModelException("CONFIG_MISSING", reason, false),
                    0, 0, modelName));
            return AgentRunResult.failed(reason, trace, modelName);
        }

        while (modelCalls < input.limits().maxModelCalls()) {
            if (Instant.now().isAfter(deadline)) {
                return AgentRunResult.failed("诊断整体超时（"
                        + input.limits().overallTimeout().toSeconds() + " 秒）", trace, modelName);
            }

            modelCalls++;
            String userPrompt = promptBuilder.userPrompt(
                    input, observations, feedback, modelCalls, toolCalls);
            if (userPrompt.length() > input.limits().maxPromptChars()) {
                return AgentRunResult.failed("提示词超过长度限制，已停止调用模型", trace, modelName);
            }

            AgentModelClient.ModelResponse response;
            long modelStartedAt = System.nanoTime();
            try {
                response = modelClient.complete(new AgentModelClient.ModelRequest(
                        promptBuilder.systemPrompt(),
                        userPrompt,
                        properties.deepseek().temperature(),
                        properties.deepseek().maxTokens()));
            } catch (AgentModelException ex) {
                trace.add(modelErrorTrace(modelCalls, ex, elapsedMs(modelStartedAt),
                        userPrompt.length(), modelName));
                if (ex.isRetryable() && modelCalls < input.limits().maxModelCalls()) {
                    feedback = "上一轮模型调用失败（" + ex.getFailureType() + "），请基于现有证据继续决策";
                    if (!sleepBeforeRetry()) {
                        return AgentRunResult.failed("模型重试被中断", trace, modelName);
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
            AgentDecisionParser.Decision decision;
            try {
                decision = decisionParser.parse(response.content(), input.limits().maxOutputChars());
            } catch (AgentValidationException ex) {
                trace.add(modelDecisionTrace(modelCalls, "INVALID_OUTPUT", latencyMs,
                        userPrompt.length(), response, modelName, ex.getMessage(), null));
                feedback = "模型输出校验失败: " + ex.getMessage()
                        + "。请只输出符合约定的单个 JSON 对象。";
                continue;
            }

            if (decision instanceof AgentDecisionParser.CallTool callTool) {
                if (toolCalls >= input.limits().maxToolCalls()) {
                    trace.add(modelDecisionTrace(modelCalls, "CALL_TOOL_REJECTED", latencyMs,
                            userPrompt.length(), response, modelName,
                            "工具调用次数已达上限", callTool.reason()));
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
                    feedback = "工具调用被拒绝: " + ex.getMessage() + "。请修正参数或改用已注册工具。";
                    continue;
                }

                if (!executedSignatures.add(call.signature())) {
                    trace.add(toolRejectedTrace(modelCalls, callTool, latencyMs,
                            userPrompt.length(), response, modelName,
                            "重复的工具调用已被跳过: " + call.signature()));
                    feedback = "检测到完全相同的重复工具调用，已跳过执行。请使用已有观察结果继续分析，"
                            + "或调整查询范围，或结束调查。";
                    continue;
                }

                // 每一轮模型调用都记录 usage 与耗时，便于审计完整核算 token。
                Map<String, Object> callDecisionTrace = modelDecisionTrace(modelCalls, "CALL_TOOL",
                        latencyMs, userPrompt.length(), response, modelName, null, callTool.reason());
                callDecisionTrace.put("tool", call.toolName());
                trace.add(callDecisionTrace);

                toolCalls++;
                ToolInvocationOutcome outcome = toolInvocationService.invoke(
                        input.diagnosisTaskId(),
                        call.toolName(),
                        new ToolExecutionContext(input.diagnosisTaskId(), call.service(),
                                call.windowStart(), call.windowEnd(), call.parameters()),
                        toolCalls);

                List<String> evidenceIds = extractEvidenceIds(outcome.result().output());
                if (outcome.result().success()) {
                    knownEvidenceIds.addAll(evidenceIds);
                }
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
                feedback = null;
                continue;
            }

            if (decision instanceof AgentDecisionParser.FinalReport finalReport) {
                if (knownEvidenceIds.isEmpty()) {
                    trace.add(modelDecisionTrace(modelCalls, "FINAL_REPORT_REJECTED", latencyMs,
                            userPrompt.length(), response, modelName,
                            "尚无真实证据，拒绝根因报告", finalReport.reason()));
                    feedback = "当前没有任何成功的工具证据，不能输出根因报告。"
                            + "请先调用 query_logs 或 query_metrics，或输出 INSUFFICIENT_EVIDENCE。";
                    continue;
                }
                try {
                    AgentReportValidator.ValidatedReport validated =
                            reportValidator.validate(finalReport.report(), knownEvidenceIds);
                    Map<String, Object> report = new LinkedHashMap<>(validated.report());
                    report.put("result", RunStatus.ROOT_CAUSE_IDENTIFIED.name());
                    report.put("model", modelName);
                    report.put("modelCallCount", modelCalls);
                    report.put("toolCallCount", toolCalls);
                    report.put("generatedAt", Instant.now().toString());
                    trace.add(modelDecisionTrace(modelCalls, "FINAL_REPORT_ACCEPTED", latencyMs,
                            userPrompt.length(), response, modelName, null, finalReport.reason()));
                    return AgentRunResult.rootCause(report, trace, modelName, validated.confidenceLevel());
                } catch (AgentValidationException ex) {
                    trace.add(modelDecisionTrace(modelCalls, "FINAL_REPORT_REJECTED", latencyMs,
                            userPrompt.length(), response, modelName, ex.getMessage(), finalReport.reason()));
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

        return AgentRunResult.failed("达到最大模型调用轮数（"
                + input.limits().maxModelCalls() + "）仍未形成可验证结论", trace, modelName);
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
        // CONFIG_MISSING 表示根本没有发起模型请求，不能计入模型调用次数。
        entry.put("modelCallAttempted", !"CONFIG_MISSING".equals(ex.getFailureType()));
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

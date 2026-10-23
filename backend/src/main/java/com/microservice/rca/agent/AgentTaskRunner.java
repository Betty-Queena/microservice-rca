package com.microservice.rca.agent;

import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.domain.DiagnosisResultStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.repository.FaultEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异步执行单个诊断任务，并把状态、报告、失败原因和决策时间线持久化。
 * 无论成功、证据不足还是失败，都不会写入伪造的 root_cause。
 */
@Component
public class AgentTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskRunner.class);

    private final DiagnosisTaskRepository taskRepository;
    private final FaultEventRepository eventRepository;
    private final AgentInputFactory inputFactory;
    private final AgentOrchestrator orchestrator;
    private final AgentModelClient modelClient;
    private final JsonSupport jsonSupport;

    public AgentTaskRunner(DiagnosisTaskRepository taskRepository,
                           FaultEventRepository eventRepository,
                           AgentInputFactory inputFactory,
                           AgentOrchestrator orchestrator,
                           AgentModelClient modelClient,
                           JsonSupport jsonSupport) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.inputFactory = inputFactory;
        this.orchestrator = orchestrator;
        this.modelClient = modelClient;
        this.jsonSupport = jsonSupport;
    }

    @Async("agentExecutor")
    public void run(UUID taskId) {
        DiagnosisTask task = null;
        try {
            task = taskRepository.findById(taskId).orElse(null);
            if (task == null) {
                log.warn("Agent task {} not found, skip", taskId);
                return;
            }

            // 首次 RUNNING 保存也纳入异常保护：保存失败时任务不能永久停留在 PENDING。
            task.setStatus(DiagnosisStatus.RUNNING);
            task.setStartedAt(Instant.now());
            task.setFailureReason(null);
            taskRepository.save(task);

            FaultEvent event = eventRepository.findById(task.getFaultEventId()).orElse(null);
            if (event == null) {
                fail(task, "故障事件不存在，无法执行 Agent 诊断", null, null);
                return;
            }

            AgentOrchestrator.AgentInput input = inputFactory.create(task, event, Instant.now());
            task.setAgentInput(jsonSupport.write(input));
            task.setModelName(orchestratorModelName());
            taskRepository.save(task);

            AgentOrchestrator.AgentRunResult result = orchestrator.diagnose(input);
            applyResult(task, result);
        } catch (Exception ex) {
            String reason = "Agent 执行异常: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? " - " + ex.getMessage() : "");
            log.warn("Agent task {} failed: {}", taskId, ex.toString());
            if (task != null) {
                try {
                    fail(task, reason, currentTrace(task), orchestratorModelName());
                } catch (Exception persistEx) {
                    // 失败状态也无法持久化时，只能留给启动恢复兜底。
                    log.error("Failed to persist failure state for agent task {}", taskId, persistEx);
                }
            }
        }
    }

    private void applyResult(DiagnosisTask task, AgentOrchestrator.AgentRunResult result) {
        List<Map<String, Object>> trace = new ArrayList<>(result.decisionTrace());
        RunStats stats = RunStats.from(trace);
        trace.add(stats.toTraceEntry());
        task.setDecisionTrace(jsonSupport.write(trace));
        task.setModelName(result.modelName());
        task.setCompletedAt(Instant.now());

        switch (result.status()) {
            case ROOT_CAUSE_IDENTIFIED -> {
                task.setStatus(DiagnosisStatus.SUCCEEDED);
                task.setResultStatus(DiagnosisResultStatus.ROOT_CAUSE_IDENTIFIED);
                task.setConfidenceLevel(result.confidenceLevel());
                task.setRootCause(truncate(String.valueOf(result.report().get("rootCause")), 1024));
                task.setConclusion(truncate(String.valueOf(result.report().get("rootCause")), 2048));
                task.setReport(jsonSupport.write(withRunStats(result.report(), stats)));
                task.setFailureReason(null);
            }
            case INSUFFICIENT_EVIDENCE -> {
                task.setStatus(DiagnosisStatus.SUCCEEDED);
                task.setResultStatus(DiagnosisResultStatus.INSUFFICIENT_EVIDENCE);
                task.setConfidenceLevel(null);
                task.setRootCause(null);
                task.setConclusion(truncate("证据不足："
                        + result.report().getOrDefault("reason", "未形成可验证根因"), 2048));
                task.setReport(jsonSupport.write(withRunStats(result.report(), stats)));
                task.setFailureReason(null);
            }
            case FAILED -> {
                task.setStatus(DiagnosisStatus.FAILED);
                task.setResultStatus(null);
                task.setConfidenceLevel(null);
                task.setRootCause(null);
                task.setConclusion(null);
                task.setReport(null);
                task.setFailureReason(truncate(result.failureReason(), 1024));
            }
        }
        restoreEventAfterDiagnosis(task);
        taskRepository.save(task);
    }

    private void fail(DiagnosisTask task,
                      String reason,
                      List<Map<String, Object>> trace,
                      String modelName) {
        task.setStatus(DiagnosisStatus.FAILED);
        task.setResultStatus(null);
        task.setConfidenceLevel(null);
        task.setRootCause(null);
        task.setConclusion(null);
        task.setReport(null);
        task.setFailureReason(truncate(reason, 1024));
        task.setModelName(modelName);
        task.setCompletedAt(Instant.now());
        List<Map<String, Object>> entries = trace != null ? trace : new ArrayList<>();
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "AGENT_ERROR");
        error.put("at", Instant.now().toString());
        error.put("error", truncate(reason, 500));
        entries.add(error);
        entries.add(RunStats.from(entries).toTraceEntry());
        task.setDecisionTrace(jsonSupport.write(entries));
        restoreEventAfterDiagnosis(task);
        taskRepository.save(task);
    }

    /**
     * 诊断任务进入终态后把事件从 DIAGNOSING 恢复为 OPEN。
     * 使用条件更新，避免读-改-写覆盖人工同时写入的 RESOLVED/CLOSED。
     * AI 诊断完成不等于故障已解决：事件仍等待人工处置，只有人工确认后才进入 RESOLVED/CLOSED。
     */
    private void restoreEventAfterDiagnosis(DiagnosisTask task) {
        eventRepository.updateStatusIfCurrent(
                task.getFaultEventId(), FaultEventStatus.DIAGNOSING,
                FaultEventStatus.OPEN, Instant.now());
    }

    private Map<String, Object> withRunStats(Map<String, Object> report, RunStats stats) {
        Map<String, Object> enriched = new LinkedHashMap<>(report);
        stats.applyTo(enriched);
        return enriched;
    }

    /**
     * 从决策轨迹汇总模型调用次数、usage 与耗时。
     * MODEL_DECISION 表示一次模型调用；TOOL_REJECTED 也来自一次模型响应，
     * 但对应工具调用被拒绝，因此同样计入模型调用次数。
     */
    private record RunStats(int modelCallCount,
                            int modelCallsWithUsage,
                            int modelCallsWithoutUsage,
                            int promptTokens,
                            int completionTokens,
                            int totalTokens,
                            int modelLatencyMsTotal) {

        static RunStats from(List<Map<String, Object>> trace) {
            int calls = 0;
            int withUsage = 0;
            int withoutUsage = 0;
            int prompt = 0;
            int completion = 0;
            int latency = 0;
            for (Map<String, Object> entry : trace) {
                if (entry == null) {
                    continue;
                }
                String type = String.valueOf(entry.get("type"));
                if (!"MODEL_DECISION".equals(type) && !"TOOL_REJECTED".equals(type)) {
                    continue;
                }
                if (Boolean.FALSE.equals(entry.get("modelCallAttempted"))) {
                    continue;
                }
                calls++;
                Object rawLatency = entry.get("latencyMs") != null
                        ? entry.get("latencyMs") : entry.get("modelLatencyMs");
                latency += intValue(rawLatency);
                if (Boolean.TRUE.equals(entry.get("usageAvailable"))) {
                    withUsage++;
                    prompt += intValue(entry.get("promptTokens"));
                    completion += intValue(entry.get("completionTokens"));
                } else {
                    withoutUsage++;
                }
            }
            return new RunStats(calls, withUsage, withoutUsage, prompt, completion,
                    prompt + completion, latency);
        }

        Map<String, Object> toTraceEntry() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "AGENT_RUN_SUMMARY");
            entry.put("at", Instant.now().toString());
            applyTo(entry);
            return entry;
        }

        void applyTo(Map<String, Object> target) {
            target.put("modelCallCount", modelCallCount);
            target.put("modelCallsWithUsage", modelCallsWithUsage);
            target.put("modelCallsWithoutUsage", modelCallsWithoutUsage);
            target.put("promptTokens", promptTokens);
            target.put("completionTokens", completionTokens);
            target.put("totalTokens", totalTokens);
            target.put("modelLatencyMsTotal", modelLatencyMsTotal);
            target.put("usageNote", usageNote());
        }

        private String usageNote() {
            if (modelCallCount == 0) {
                return "本次运行未调用模型";
            }
            if (modelCallsWithoutUsage > 0) {
                return modelCallsWithoutUsage
                        + " 轮模型调用未返回 usage，Token 合计仅包含可获取轮次";
            }
            return "所有模型调用均返回 usage";
        }

        private static int intValue(Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        }
    }

    private List<Map<String, Object>> currentTrace(DiagnosisTask task) {
        if (task.getDecisionTrace() == null) {
            return new ArrayList<>();
        }
        try {
            var parsed = jsonSupport.read(task.getDecisionTrace());
            if (parsed != null && parsed.isArray()) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (var item : parsed) {
                    if (item.isObject()) {
                        Map<String, Object> typed = new LinkedHashMap<>();
                        item.fields().forEachRemaining(entry ->
                                typed.put(entry.getKey(), entry.getValue().isTextual()
                                        ? entry.getValue().asText() : entry.getValue()));
                        result.add(typed);
                    }
                }
                return result;
            }
        } catch (Exception ignored) {
            // 保留原有失败路径，不因时间线解析失败掩盖真实错误。
        }
        return new ArrayList<>();
    }

    private String orchestratorModelName() {
        try {
            return modelClient.modelName();
        } catch (Exception ex) {
            return "unknown";
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

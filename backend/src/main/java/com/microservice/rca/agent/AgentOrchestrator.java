package com.microservice.rca.agent;

import com.microservice.rca.tools.DiagnosticToolRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 诊断 Agent 编排契约。
 *
 * <p>实现必须由模型逐轮决定下一步动作，而不是固定调用工具后生成报告。
 * 输入 {@link AgentInput} 是白名单 DTO，不含 experiment_fault_type / experiment_parameters。</p>
 */
public interface AgentOrchestrator {

    AgentRunResult diagnose(AgentInput input);

    record AgentInput(
            UUID faultEventId,
            UUID diagnosisTaskId,
            String serviceName,
            String eventType,
            String severity,
            String summary,
            Map<String, Object> observedResult,
            String requestId,
            Instant windowStart,
            Instant windowEnd,
            List<DiagnosticToolRegistry.ToolDefinition> availableTools,
            Limits limits) {
    }

    record Limits(
            int maxModelCalls,
            int maxToolCalls,
            int maxOutputChars,
            int maxPromptChars,
            int maxToolResultChars,
            Duration overallTimeout) {
    }

    enum RunStatus {
        ROOT_CAUSE_IDENTIFIED,
        INSUFFICIENT_EVIDENCE,
        FAILED
    }

    record AgentRunResult(
            RunStatus status,
            Map<String, Object> report,
            String failureReason,
            List<Map<String, Object>> decisionTrace,
            String modelName,
            String confidenceLevel) {

        public static AgentRunResult rootCause(Map<String, Object> report,
                                               List<Map<String, Object>> trace,
                                               String modelName,
                                               String confidenceLevel) {
            return new AgentRunResult(RunStatus.ROOT_CAUSE_IDENTIFIED, report, null,
                    trace, modelName, confidenceLevel);
        }

        public static AgentRunResult insufficientEvidence(Map<String, Object> report,
                                                          List<Map<String, Object>> trace,
                                                          String modelName) {
            return new AgentRunResult(RunStatus.INSUFFICIENT_EVIDENCE, report, null,
                    trace, modelName, null);
        }

        public static AgentRunResult failed(String reason,
                                            List<Map<String, Object>> trace,
                                            String modelName) {
            return new AgentRunResult(RunStatus.FAILED, null, reason, trace, modelName, null);
        }
    }
}

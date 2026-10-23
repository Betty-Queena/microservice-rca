package com.microservice.rca.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservice.rca.diagnosis.api.DiagnosisTaskView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agent 诊断任务详情：执行状态、结论状态、结构化报告、白名单输入快照、
 * 模型决策时间线和工具调用审计。
 */
public record AgentTaskDetailView(
        UUID id,
        UUID faultEventId,
        String status,
        String resultStatus,
        String triggerSource,
        String goal,
        String conclusion,
        String rootCause,
        String confidenceLevel,
        String failureReason,
        String modelName,
        Instant startedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        JsonNode report,
        JsonNode agentInput,
        JsonNode decisionTrace,
        List<DiagnosisTaskView.ToolInvocationView> toolInvocations) {
}

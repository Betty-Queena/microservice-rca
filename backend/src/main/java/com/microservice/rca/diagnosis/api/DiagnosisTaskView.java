package com.microservice.rca.diagnosis.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 诊断任务摘要。任务执行状态与 resultStatus（诊断结论状态）分开表达。
 */
public record DiagnosisTaskView(
        UUID id,
        String status,
        String resultStatus,
        String triggerSource,
        String goal,
        String conclusion,
        String rootCause,
        Double confidence,
        String confidenceLevel,
        String failureReason,
        String modelName,
        Instant startedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        List<ToolInvocationView> toolInvocations) {

    public record ToolInvocationView(
            UUID id,
            String toolName,
            String status,
            Integer latencyMs,
            String errorMessage,
            Instant invokedAt,
            Integer sequenceNo,
            JsonNode inputPayload,
            JsonNode outputPayload) {
    }
}

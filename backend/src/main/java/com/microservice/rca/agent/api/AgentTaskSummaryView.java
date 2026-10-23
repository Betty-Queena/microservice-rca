package com.microservice.rca.agent.api;

import java.time.Instant;
import java.util.UUID;

public record AgentTaskSummaryView(
        UUID id,
        UUID faultEventId,
        String status,
        String resultStatus,
        String triggerSource,
        String rootCause,
        String confidenceLevel,
        String failureReason,
        String modelName,
        Instant createdAt,
        Instant completedAt) {
}

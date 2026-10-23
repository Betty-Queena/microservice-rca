package com.microservice.rca.diagnosis.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.domain.FaultSeverity;
import java.time.Instant;
import java.util.UUID;

/**
 * 故障事件视图。experimentFaultType 是实验预设参数，不是根因结论。
 */
public record FaultEventView(
        UUID id,
        String serviceName,
        String eventType,
        FaultSeverity severity,
        String summary,
        JsonNode details,
        FaultEventStatus status,
        Instant occurredAt,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        UUID experimentId,
        String experimentFaultType,
        JsonNode experimentParameters,
        JsonNode observedResult,
        String requestId,
        String source,
        JsonNode evidence,
        Instant evidenceCollectedAt) {
}

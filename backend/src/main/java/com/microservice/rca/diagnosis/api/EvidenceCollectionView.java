package com.microservice.rca.diagnosis.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EvidenceCollectionView(
        UUID eventId,
        UUID diagnosisTaskId,
        String diagnosisTaskStatus,
        boolean success,
        Instant collectedAt,
        Instant windowStart,
        Instant windowEnd,
        String requestId,
        List<ToolEvidenceView> tools,
        String note) {

    public record ToolEvidenceView(
            String toolName,
            UUID invocationId,
            boolean success,
            int latencyMs,
            Map<String, Object> output,
            String error) {
    }
}

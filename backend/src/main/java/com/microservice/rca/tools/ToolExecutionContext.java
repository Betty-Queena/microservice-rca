package com.microservice.rca.tools;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ToolExecutionContext(
        UUID diagnosisTaskId,
        String serviceName,
        Instant windowStart,
        Instant windowEnd,
        Map<String, Object> parameters) {
}

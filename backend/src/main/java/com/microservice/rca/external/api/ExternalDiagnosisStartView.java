package com.microservice.rca.external.api;

import java.time.Instant;
import java.util.UUID;

public record ExternalDiagnosisStartView(
        UUID externalServiceId,
        String externalServiceName,
        UUID faultEventId,
        UUID diagnosisTaskId,
        String status,
        String goal,
        Instant createdAt) {
}

package com.microservice.rca.external.api;

import java.time.Instant;
import java.util.UUID;

public record ExternalServiceView(
        UUID id,
        String name,
        String baseUrl,
        String healthPath,
        String metricsPath,
        String logSourceUrl,
        String description,
        boolean enabled,
        Instant lastCheckAt,
        String lastCheckStatus,
        String lastCheckMessage,
        Instant createdAt,
        Instant updatedAt) {
}

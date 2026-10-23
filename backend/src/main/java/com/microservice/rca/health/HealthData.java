package com.microservice.rca.health;

import java.time.Instant;

public record HealthData(
        String service,
        String version,
        String status,
        Instant timestamp,
        long uptimeSeconds,
        DatabaseHealth database) {
}

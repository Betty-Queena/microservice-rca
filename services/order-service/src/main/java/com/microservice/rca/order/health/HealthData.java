package com.microservice.rca.order.health;

import java.time.Instant;

public record HealthData(
        String service,
        String version,
        String status,
        Instant timestamp,
        DependencyHealth dependency) {

    public record DependencyHealth(String service, String status, String url) {
    }
}

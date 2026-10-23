package com.microservice.rca.inventory.health;

import java.time.Instant;

public record HealthData(String service, String version, String status, Instant timestamp) {
}

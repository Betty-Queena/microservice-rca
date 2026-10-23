package com.microservice.rca.health;

public record DatabaseHealth(String status, long latencyMs, String message) {
}

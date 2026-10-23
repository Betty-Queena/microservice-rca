package com.microservice.rca.inventory.fault;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EnableFaultRequest(
        @NotNull FaultType faultType,
        Integer delayMs,
        Integer durationSeconds,
        @Size(max = 200) String note) {
}

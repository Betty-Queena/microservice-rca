package com.microservice.rca.diagnosis.api;

import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateFaultEventStatusRequest(@NotNull FaultEventStatus status) {
}

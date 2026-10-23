package com.microservice.rca.diagnosis.api;

import com.microservice.rca.diagnosis.domain.FaultSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 创建故障事件。预设故障字段必须与观测结果分开保存，不能把预设类型当作根因。
 */
public record CreateFaultEventRequest(
        @NotBlank @Size(max = 128) String serviceName,
        @NotBlank @Size(max = 64) String eventType,
        FaultSeverity severity,
        @NotBlank @Size(max = 512) String summary,
        Instant occurredAt,
        Map<String, Object> details,
        UUID experimentId,
        @Size(max = 64) String experimentFaultType,
        Map<String, Object> experimentParameters,
        Map<String, Object> observedResult,
        @Size(max = 64) String requestId,
        @Pattern(regexp = "MANUAL|EXPERIMENT") String source) {
}

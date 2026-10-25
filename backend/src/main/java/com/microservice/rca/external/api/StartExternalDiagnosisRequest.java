package com.microservice.rca.external.api;

import com.microservice.rca.diagnosis.domain.FaultSeverity;
import jakarta.validation.constraints.Size;

/**
 * 从外部服务启动一次独立诊断。所有字段可选，未填写时使用外部服务默认事件描述。
 * 该请求不接受任意的服务地址，只能选择已注册的服务 ID。
 */
public record StartExternalDiagnosisRequest(
        @Size(max = 64) String eventType,
        FaultSeverity severity,
        @Size(max = 512) String summary,
        @Size(max = 64) String requestId) {
}

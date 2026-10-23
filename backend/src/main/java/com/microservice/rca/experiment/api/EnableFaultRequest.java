package com.microservice.rca.experiment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理后端转发给库存服务的故障注入参数。仅支持已实现的两种实验故障。
 */
public record EnableFaultRequest(
        @NotBlank(message = "不能为空")
        @Pattern(regexp = "DELAY|HTTP_500", message = "仅支持 DELAY 或 HTTP_500")
        String faultType,
        Integer delayMs,
        Integer durationSeconds,
        @Size(max = 200) String note) {
}

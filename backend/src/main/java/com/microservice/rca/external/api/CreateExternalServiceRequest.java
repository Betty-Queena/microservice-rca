package com.microservice.rca.external.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册外部 Spring Boot 服务。地址、协议、主机与端口由后端再次校验，
 * 前端提交的值不能绕过白名单。
 */
public record CreateExternalServiceRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 512) String baseUrl,
        @Size(max = 256) String healthPath,
        @Size(max = 256) String metricsPath,
        @Size(max = 512) String logSourceUrl,
        @Size(max = 512) String description,
        Boolean enabled) {
}

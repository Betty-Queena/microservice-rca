package com.microservice.rca.external.web;

import com.microservice.rca.common.ApiResponse;
import com.microservice.rca.external.api.CreateExternalServiceRequest;
import com.microservice.rca.external.api.ExternalDiagnosisStartView;
import com.microservice.rca.external.api.ExternalServiceCheckView;
import com.microservice.rca.external.api.ExternalServiceView;
import com.microservice.rca.external.api.StartExternalDiagnosisRequest;
import com.microservice.rca.external.service.ExternalServiceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部 Spring Boot 服务接入 API。
 *
 * <p>只提供注册、列表、连通性检查和独立诊断入口；不提供任何外部服务控制接口。</p>
 */
@RestController
@RequestMapping("/api/external-services")
public class ExternalServiceController {

    private final ExternalServiceService externalServiceService;

    public ExternalServiceController(ExternalServiceService externalServiceService) {
        this.externalServiceService = externalServiceService;
    }

    @GetMapping
    public ApiResponse<List<ExternalServiceView>> list(
            @RequestParam(defaultValue = "false") boolean enabledOnly) {
        return ApiResponse.ok(externalServiceService.list(enabledOnly));
    }

    @PostMapping
    public ApiResponse<ExternalServiceView> register(
            @Valid @RequestBody CreateExternalServiceRequest request) {
        return ApiResponse.ok(externalServiceService.register(request));
    }

    @GetMapping("/{serviceId}")
    public ApiResponse<ExternalServiceView> detail(@PathVariable UUID serviceId) {
        return ApiResponse.ok(externalServiceService.detail(serviceId));
    }

    @PostMapping("/{serviceId}/check")
    public ApiResponse<ExternalServiceCheckView> check(@PathVariable UUID serviceId) {
        return ApiResponse.ok(externalServiceService.check(serviceId));
    }

    /**
     * 无需故障注入记录，直接从已注册外部服务创建事件并启动 Agent 诊断。
     */
    @PostMapping("/{serviceId}/diagnosis")
    public ApiResponse<ExternalDiagnosisStartView> startDiagnosis(
            @PathVariable UUID serviceId,
            @Valid @RequestBody(required = false) StartExternalDiagnosisRequest request) {
        return ApiResponse.ok(externalServiceService.startDiagnosis(serviceId, request));
    }
}

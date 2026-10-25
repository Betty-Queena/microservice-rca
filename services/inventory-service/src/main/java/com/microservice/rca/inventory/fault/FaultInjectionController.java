package com.microservice.rca.inventory.fault;

import com.microservice.rca.inventory.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 故障注入实验控制接口。健康检查、观测查询和本控制器自身不受故障影响。
 */
@RestController
@RequestMapping("/api/experiments/faults")
public class FaultInjectionController {

    private final FaultInjectionService faultInjectionService;
    private final ExperimentAccessGuard accessGuard;

    public FaultInjectionController(FaultInjectionService faultInjectionService,
                                    ExperimentAccessGuard accessGuard) {
        this.faultInjectionService = faultInjectionService;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public ApiResponse<FaultInjectionStatus> status(HttpServletRequest request) {
        accessGuard.ensureAllowed(request, false);
        return ApiResponse.ok(faultInjectionService.status());
    }

    @PostMapping("/enable")
    public ApiResponse<FaultInjectionStatus> enable(@Valid @RequestBody EnableFaultRequest body,
                                                    HttpServletRequest request) {
        accessGuard.ensureAllowed(request, true);
        return ApiResponse.ok(faultInjectionService.enable(body));
    }

    /**
     * 关闭故障注入。请求体可选：提供 expectedExperimentId 时执行条件关闭，
     * 不提供时保持原有无条件关闭行为。
     */
    @PostMapping("/disable")
    public ApiResponse<FaultInjectionStatus> disable(
            @RequestBody(required = false) DisableFaultRequest body,
            HttpServletRequest request) {
        accessGuard.ensureAllowed(request, false);
        if (body == null || body.expectedExperimentId() == null) {
            return ApiResponse.ok(faultInjectionService.disable());
        }
        return ApiResponse.ok(faultInjectionService.disable(body.expectedExperimentId()));
    }

    @PostMapping("/reset")
    public ApiResponse<FaultInjectionStatus> reset(HttpServletRequest request) {
        accessGuard.ensureAllowed(request, false);
        return ApiResponse.ok(faultInjectionService.reset());
    }
}

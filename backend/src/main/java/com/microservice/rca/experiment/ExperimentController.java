package com.microservice.rca.experiment;

import com.microservice.rca.common.ApiResponse;
import com.microservice.rca.experiment.api.CheckoutRequest;
import com.microservice.rca.experiment.api.DisableFaultRequest;
import com.microservice.rca.experiment.api.EnableFaultRequest;
import com.microservice.rca.experiment.api.ExperimentExecutionView;
import com.microservice.rca.experiment.api.ExperimentFaultStatusView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 故障注入实验 API。实验控制请求不会进入库存服务的业务故障过滤器。
 */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @GetMapping("/faults")
    public ApiResponse<ExperimentFaultStatusView> status() {
        return ApiResponse.ok(experimentService.status());
    }

    @PostMapping("/faults/enable")
    public ApiResponse<ExperimentFaultStatusView> enable(@Valid @RequestBody EnableFaultRequest request) {
        return ApiResponse.ok(experimentService.enable(request));
    }

    /**
     * 关闭故障注入。请求体可选：提供 expectedExperimentId 时条件关闭对应实验，
     * 不提供时沿用原有无条件关闭行为，兼容现有前端和 Day 7 评测。
     */
    @PostMapping("/faults/disable")
    public ApiResponse<ExperimentFaultStatusView> disable(
            @RequestBody(required = false) DisableFaultRequest request) {
        if (request == null || request.expectedExperimentId() == null) {
            return ApiResponse.ok(experimentService.disable());
        }
        return ApiResponse.ok(experimentService.disable(request.expectedExperimentId()));
    }

    @PostMapping("/faults/reset")
    public ApiResponse<ExperimentFaultStatusView> reset() {
        return ApiResponse.ok(experimentService.reset());
    }

    @GetMapping("/inventory")
    public ApiResponse<java.util.List<java.util.Map<String, Object>>> inventory() {
        return ApiResponse.ok(experimentService.inventory());
    }

    /**
     * 使用当前激活的实验执行一次真实订单结算请求。
     */
    @PostMapping("/{experimentId}/execute")
    public ApiResponse<ExperimentExecutionView> execute(@PathVariable UUID experimentId,
                                                        @Valid @RequestBody CheckoutRequest request) {
        return ApiResponse.ok(experimentService.execute(experimentId, request));
    }

    /**
     * 无故障对照组：执行一次真实订单结算请求，用于验证恢复后的正常业务。
     */
    @PostMapping("/checkout")
    public ApiResponse<ExperimentExecutionView> checkout(@Valid @RequestBody CheckoutRequest request) {
        return ApiResponse.ok(experimentService.checkout(request));
    }
}

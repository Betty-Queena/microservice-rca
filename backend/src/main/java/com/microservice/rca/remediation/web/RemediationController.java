package com.microservice.rca.remediation.web;

import com.microservice.rca.common.ApiResponse;
import com.microservice.rca.remediation.api.CreateRemediationRequest;
import com.microservice.rca.remediation.api.RemediationView;
import com.microservice.rca.remediation.service.RemediationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控处置 API。
 *
 * <p>POST 在同一个请求中完成关闭与一次验证；如果客户端 HTTP 超时，
 * 可以通过处理 ID、事件历史或幂等键查询已有记录，不能靠重复 POST 猜测结果。</p>
 */
@RestController
@RequestMapping("/api")
public class RemediationController {

    private final RemediationService remediationService;

    public RemediationController(RemediationService remediationService) {
        this.remediationService = remediationService;
    }

    @PostMapping("/fault-events/{eventId}/remediations")
    public ApiResponse<RemediationView> create(
            @PathVariable UUID eventId,
            @Valid @RequestBody CreateRemediationRequest request) {
        return ApiResponse.ok(remediationService.createAndExecute(eventId, request));
    }

    @GetMapping("/fault-events/{eventId}/remediations")
    public ApiResponse<List<RemediationView>> list(@PathVariable UUID eventId) {
        return ApiResponse.ok(remediationService.list(eventId));
    }

    /**
     * HTTP 超时后按事件 + 幂等键查询已有记录。
     */
    @GetMapping("/fault-events/{eventId}/remediations/by-idempotency-key/{idempotencyKey}")
    public ApiResponse<RemediationView> getByIdempotencyKey(
            @PathVariable UUID eventId,
            @PathVariable String idempotencyKey) {
        return ApiResponse.ok(remediationService.getByEventAndIdempotencyKey(
                eventId, idempotencyKey));
    }

    @GetMapping("/remediations/{remediationId}")
    public ApiResponse<RemediationView> get(@PathVariable UUID remediationId) {
        return ApiResponse.ok(remediationService.get(remediationId));
    }
}

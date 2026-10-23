package com.microservice.rca.diagnosis.web;

import com.microservice.rca.common.ApiResponse;
import com.microservice.rca.diagnosis.api.CreateFaultEventRequest;
import com.microservice.rca.diagnosis.api.EvidenceCollectionView;
import com.microservice.rca.diagnosis.api.EvidenceQueryRequest;
import com.microservice.rca.diagnosis.api.FaultEventDetailView;
import com.microservice.rca.diagnosis.api.FaultEventPage;
import com.microservice.rca.diagnosis.api.FaultEventView;
import com.microservice.rca.diagnosis.api.UpdateFaultEventStatusRequest;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.service.EvidenceService;
import com.microservice.rca.diagnosis.service.FaultEventService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 故障事件管理 API：创建、列表、详情、状态流转与证据采集。
 */
@RestController
@RequestMapping("/api/fault-events")
public class FaultEventController {

    private final FaultEventService faultEventService;
    private final EvidenceService evidenceService;

    public FaultEventController(FaultEventService faultEventService,
                                EvidenceService evidenceService) {
        this.faultEventService = faultEventService;
        this.evidenceService = evidenceService;
    }

    @PostMapping
    public ApiResponse<FaultEventView> create(@Valid @RequestBody CreateFaultEventRequest request) {
        return ApiResponse.ok(faultEventService.create(request));
    }

    @GetMapping
    public ApiResponse<FaultEventPage> list(
            @RequestParam(required = false) FaultEventStatus status,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(faultEventService.list(status, serviceName, from, to, page, size));
    }

    @GetMapping("/{eventId}")
    public ApiResponse<FaultEventDetailView> detail(@PathVariable UUID eventId) {
        return ApiResponse.ok(faultEventService.detail(eventId));
    }

    @PatchMapping("/{eventId}/status")
    public ApiResponse<FaultEventView> updateStatus(@PathVariable UUID eventId,
                                                    @Valid @RequestBody UpdateFaultEventStatusRequest request) {
        return ApiResponse.ok(faultEventService.updateStatus(eventId, request.status()));
    }

    /**
     * 真实采集日志与指标证据，并写入故障事件与 tool_invocation 审计表。
     */
    @PostMapping("/{eventId}/evidence")
    public ApiResponse<EvidenceCollectionView> collectEvidence(
            @PathVariable UUID eventId,
            @Valid @RequestBody(required = false) EvidenceQueryRequest request) {
        return ApiResponse.ok(evidenceService.collect(eventId, request));
    }
}

package com.microservice.rca.diagnosis.service;

import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.api.EvidenceCollectionView;
import com.microservice.rca.diagnosis.api.EvidenceQueryRequest;
import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.tools.ToolExecutionContext;
import com.microservice.rca.tools.ToolInvocationService;
import com.microservice.rca.tools.ToolInvocationService.ToolInvocationOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 证据采集编排：创建真实诊断任务，调用诊断工具，并将工具输出快照写入故障事件。
 * 工具失败会显式记录在 tool_invocation 和事件证据中，不会生成模拟成功数据。
 */
@Service
public class EvidenceService {

    private static final int DEFAULT_LOG_LIMIT = 50;
    private static final int MAX_LOG_LIMIT = 200;
    private static final Duration MAX_WINDOW = Duration.ofHours(24);

    private final FaultEventService faultEventService;
    private final DiagnosisTaskRepository taskRepository;
    private final ToolInvocationService toolInvocationService;
    private final JsonSupport jsonSupport;

    public EvidenceService(FaultEventService faultEventService,
                           DiagnosisTaskRepository taskRepository,
                           ToolInvocationService toolInvocationService,
                           JsonSupport jsonSupport) {
        this.faultEventService = faultEventService;
        this.taskRepository = taskRepository;
        this.toolInvocationService = toolInvocationService;
        this.jsonSupport = jsonSupport;
    }

    public EvidenceCollectionView collect(UUID eventId, EvidenceQueryRequest request) {
        FaultEvent event = faultEventService.requireEvent(eventId);
        Instant now = Instant.now();
        Instant defaultStart = event.getOccurredAt() != null
                ? event.getOccurredAt().minusSeconds(60) : now.minusSeconds(900);

        Instant windowStart = request != null && request.startTime() != null
                ? request.startTime() : defaultStart;
        Instant windowEnd = request != null && request.endTime() != null
                ? request.endTime() : now;
        if (windowStart.isAfter(windowEnd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTime 不能晚于 endTime");
        }
        if (Duration.between(windowStart, windowEnd).compareTo(MAX_WINDOW) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "证据查询时间窗口不能超过 24 小时");
        }

        String requestId = request != null && request.requestId() != null && !request.requestId().isBlank()
                ? request.requestId().trim() : event.getRequestId();
        String serviceName = request != null && request.serviceName() != null
                && !request.serviceName().isBlank() ? request.serviceName().trim() : "all";
        int logLimit = request != null && request.logLimit() != null
                ? Math.max(1, Math.min(request.logLimit(), MAX_LOG_LIMIT)) : DEFAULT_LOG_LIMIT;

        DiagnosisTask task = new DiagnosisTask();
        task.setFaultEventId(eventId);
        task.setStatus(DiagnosisStatus.RUNNING);
        task.setTriggerSource("MANUAL_EVIDENCE");
        task.setGoal(truncate("采集故障事件 " + eventId + " 的真实日志与指标证据", 512));
        task.setCreatedBy("rca-console");
        task = taskRepository.save(task);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("requestId", requestId);
        parameters.put("limit", logLimit);
        parameters.put("serviceName", serviceName);

        ToolInvocationOutcome logs = toolInvocationService.invoke(
                task.getId(),
                "query_logs",
                new ToolExecutionContext(task.getId(), serviceName, windowStart, windowEnd, parameters),
                1);
        ToolInvocationOutcome metrics = toolInvocationService.invoke(
                task.getId(),
                "query_metrics",
                new ToolExecutionContext(task.getId(), serviceName, windowStart, windowEnd, parameters),
                2);

        boolean anySuccess = logs.result().success() || metrics.result().success();
        task.setStatus(anySuccess ? DiagnosisStatus.SUCCEEDED : DiagnosisStatus.FAILED);
        task.setCompletedAt(Instant.now());
        task = taskRepository.save(task);

        Instant collectedAt = Instant.now();
        List<EvidenceCollectionView.ToolEvidenceView> toolViews = new ArrayList<>();
        toolViews.add(toToolView(logs));
        toolViews.add(toToolView(metrics));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("eventId", eventId.toString());
        evidence.put("diagnosisTaskId", task.getId().toString());
        evidence.put("collectedAt", collectedAt.toString());
        evidence.put("windowStart", windowStart.toString());
        evidence.put("windowEnd", windowEnd.toString());
        evidence.put("requestId", requestId);
        evidence.put("serviceName", serviceName);
        evidence.put("logLimit", logLimit);
        evidence.put("tools", toolViews.stream().map(this::toEvidenceTool).toList());
        evidence.put("note", "工具输出为真实观测数据快照；采集失败的工具已记录错误，未使用模拟数据");
        faultEventService.saveEvidence(eventId, jsonSupport.write(evidence), collectedAt);

        return new EvidenceCollectionView(
                eventId,
                task.getId(),
                task.getStatus().name(),
                anySuccess,
                collectedAt,
                windowStart,
                windowEnd,
                requestId,
                toolViews,
                "证据来自 order-service / inventory-service 的真实观测接口；工具调用已写入 tool_invocation 审计表");
    }

    private EvidenceCollectionView.ToolEvidenceView toToolView(ToolInvocationOutcome outcome) {
        return new EvidenceCollectionView.ToolEvidenceView(
                outcome.toolName(),
                outcome.invocationId(),
                outcome.result().success(),
                outcome.latencyMs(),
                outcome.result().output(),
                outcome.result().errorMessage());
    }

    private Map<String, Object> toEvidenceTool(EvidenceCollectionView.ToolEvidenceView view) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("toolName", view.toolName());
        tool.put("invocationId", view.invocationId() != null ? view.invocationId().toString() : null);
        tool.put("success", view.success());
        tool.put("latencyMs", view.latencyMs());
        tool.put("output", view.output());
        tool.put("error", view.error());
        return tool;
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

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
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 证据采集编排：创建真实诊断任务，调用诊断工具，并将工具输出快照写入故障事件。
 * 工具失败会显式记录在 tool_invocation 和事件证据中，不会生成模拟成功数据。
 */
@Service
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

    private static final int DEFAULT_LOG_LIMIT = 50;
    private static final int MAX_LOG_LIMIT = 200;
    private static final Duration MAX_WINDOW = Duration.ofHours(24);
    /** 人工证据采集是同步接口，单次工具调用预算固定为 15 秒。 */
    private static final Duration MANUAL_TOOL_TIMEOUT = Duration.ofSeconds(15);

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

        boolean external = "EXTERNAL".equalsIgnoreCase(event.getSource());
        String requestId = request != null && request.requestId() != null && !request.requestId().isBlank()
                ? request.requestId().trim() : event.getRequestId();
        // 外部事件的证据范围固定为事件关联的服务，不能被请求参数改成其他服务。
        String serviceName = external
                ? event.getServiceName()
                : (request != null && request.serviceName() != null
                        && !request.serviceName().isBlank() ? request.serviceName().trim() : "all");
        int logLimit = request != null && request.logLimit() != null
                ? Math.max(1, Math.min(request.logLimit(), MAX_LOG_LIMIT)) : DEFAULT_LOG_LIMIT;

        DiagnosisTask task = new DiagnosisTask();
        task.setFaultEventId(eventId);
        task.setStatus(DiagnosisStatus.RUNNING);
        task.setTriggerSource("MANUAL_EVIDENCE");
        task.setGoal(external
                ? truncate("采集外部服务 " + serviceName + " 的真实 Actuator 健康/指标与日志证据", 512)
                : truncate("采集故障事件 " + eventId + " 的真实日志与指标证据", 512));
        task.setCreatedBy("rca-console");
        task = taskRepository.save(task);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("requestId", requestId);
        parameters.put("limit", logLimit);
        parameters.put("serviceName", serviceName);

        try {
            ToolInvocationOutcome health = null;
            int sequence = 1;
            if (external) {
                health = toolInvocationService.invoke(
                        task.getId(),
                        "query_health",
                        new ToolExecutionContext(task.getId(), serviceName, windowStart, windowEnd,
                                Map.of(), MANUAL_TOOL_TIMEOUT),
                        sequence++);
            }
            ToolInvocationOutcome logs = toolInvocationService.invoke(
                    task.getId(),
                    "query_logs",
                    new ToolExecutionContext(task.getId(), serviceName, windowStart, windowEnd,
                            parameters, MANUAL_TOOL_TIMEOUT),
                    sequence++);
            ToolInvocationOutcome metrics = toolInvocationService.invoke(
                    task.getId(),
                    "query_metrics",
                    new ToolExecutionContext(task.getId(), serviceName, windowStart, windowEnd,
                            parameters, MANUAL_TOOL_TIMEOUT),
                    sequence);

            boolean anySuccess = logs.result().success() || metrics.result().success()
                    || (health != null && health.result().success());
            Instant collectedAt = Instant.now();
            List<EvidenceCollectionView.ToolEvidenceView> toolViews = new ArrayList<>();
            if (health != null) {
                toolViews.add(toToolView(health));
            }
            toolViews.add(toToolView(logs));
            toolViews.add(toToolView(metrics));

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("eventId", eventId.toString());
            evidence.put("diagnosisTaskId", task.getId().toString());
            evidence.put("collectedAt", collectedAt.toString());
            evidence.put("windowStart", windowStart.toString());
            evidence.put("windowEnd", windowEnd.toString());
            evidence.put("timeWindowSupported", !external);
            evidence.put("requestId", requestId);
            evidence.put("serviceName", serviceName);
            evidence.put("logLimit", logLimit);
            evidence.put("tools", toolViews.stream().map(this::toEvidenceTool).toList());
            evidence.put("note", external
                    ? "外部服务证据仅来自 Actuator 健康/指标快照和显式配置的日志数据源；采集失败的工具已记录错误，未使用模拟数据"
                    : "工具输出为真实观测数据快照；采集失败的工具已记录错误，未使用模拟数据");

            // 先写证据，再落任务终态：避免出现 SUCCEEDED 但事件没有证据快照。
            faultEventService.saveEvidence(eventId, jsonSupport.write(evidence), collectedAt);
            task.setStatus(anySuccess ? DiagnosisStatus.SUCCEEDED : DiagnosisStatus.FAILED);
            task.setFailureReason(anySuccess ? null : failureSummary(toolViews));
            task.setCompletedAt(Instant.now());
            task = taskRepository.save(task);

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
                    external
                            ? "证据来自外部服务 " + serviceName
                                    + " 的只读 Actuator 快照与显式日志数据源；工具调用已写入 tool_invocation 审计表"
                            : "证据来自 order-service / inventory-service 的真实观测接口；工具调用已写入 tool_invocation 审计表");
        } catch (RuntimeException ex) {
            String reason = "证据采集失败: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? " - " + ex.getMessage() : "");
            log.warn("Evidence collection for event {} failed", eventId, ex);
            try {
                task.setStatus(DiagnosisStatus.FAILED);
                task.setFailureReason(truncate(reason, 1024));
                task.setCompletedAt(Instant.now());
                taskRepository.save(task);
            } catch (Exception persistEx) {
                // 终态持久化失败时保留原始异常，任务由启动恢复兜底。
                log.error("Failed to persist failed evidence task {}", task.getId(), persistEx);
                ex.addSuppressed(persistEx);
            }
            throw ex;
        }
    }

    private String failureSummary(List<EvidenceCollectionView.ToolEvidenceView> toolViews) {
        String detail = toolViews.stream()
                .map(tool -> tool.toolName() + "="
                        + (tool.error() != null ? tool.error() : "工具返回失败"))
                .collect(Collectors.joining("; "));
        return truncate("所有诊断工具调用失败: " + detail, 1024);
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

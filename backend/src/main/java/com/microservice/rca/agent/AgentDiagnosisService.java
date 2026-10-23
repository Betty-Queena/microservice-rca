package com.microservice.rca.agent;

import com.microservice.rca.agent.api.AgentTaskDetailView;
import com.microservice.rca.agent.api.AgentTaskSummaryView;
import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.api.DiagnosisTaskView;
import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.domain.ToolInvocation;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.repository.ToolInvocationRepository;
import com.microservice.rca.diagnosis.service.FaultEventService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 诊断任务生命周期：创建、异步提交、查询、重试。
 * 任务执行状态与诊断结论状态分开，重试会创建新的任务记录以保留审计历史。
 */
@Service
public class AgentDiagnosisService {

    private static final Set<DiagnosisStatus> ACTIVE_STATUSES =
            Set.of(DiagnosisStatus.PENDING, DiagnosisStatus.RUNNING);

    private final DiagnosisTaskRepository taskRepository;
    private final ToolInvocationRepository invocationRepository;
    private final FaultEventService faultEventService;
    private final AgentTaskRunner taskRunner;
    private final JsonSupport jsonSupport;

    public AgentDiagnosisService(DiagnosisTaskRepository taskRepository,
                                 ToolInvocationRepository invocationRepository,
                                 FaultEventService faultEventService,
                                 AgentTaskRunner taskRunner,
                                 JsonSupport jsonSupport) {
        this.taskRepository = taskRepository;
        this.invocationRepository = invocationRepository;
        this.faultEventService = faultEventService;
        this.taskRunner = taskRunner;
        this.jsonSupport = jsonSupport;
    }

    public AgentTaskDetailView startForEvent(UUID eventId) {
        FaultEvent event = faultEventService.requireEvent(eventId);
        if (taskRepository.existsByFaultEventIdAndStatusIn(eventId, ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该事件已有诊断任务正在执行，请等待完成或查询现有任务");
        }
        DiagnosisTask task = createTask(eventId, "AGENT");
        markEventDiagnosing(event);
        taskRunner.run(task.getId());
        return detail(task.getId());
    }

    public AgentTaskDetailView retry(UUID taskId) {
        DiagnosisTask original = requireTask(taskId);
        if (ACTIVE_STATUSES.contains(original.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务仍在执行，不能重试");
        }
        // 先把关联事件校验通过，再创建任务，避免留下永远 PENDING 的孤儿任务。
        FaultEvent event = faultEventService.requireEvent(original.getFaultEventId());
        // 重试创建新任务，原任务的成功/失败记录保持不变，便于审计历史。
        DiagnosisTask retry = createTask(original.getFaultEventId(), "AGENT_RETRY");
        markEventDiagnosing(event);
        taskRunner.run(retry.getId());
        return detail(retry.getId());
    }

    /**
     * 诊断任务启动时把待处理事件标记为诊断中。
     * 已解决/已关闭事件保持原状态，只有事件仍处于 OPEN 时才推进。
     */
    private void markEventDiagnosing(FaultEvent event) {
        if (event.getStatus() != FaultEventStatus.OPEN) {
            return;
        }
        try {
            faultEventService.updateStatus(event.getId(), FaultEventStatus.DIAGNOSING);
        } catch (ResponseStatusException ignored) {
            // 事件状态更新失败不应阻止真实诊断执行。
        }
    }

    public AgentTaskDetailView detail(UUID taskId) {
        DiagnosisTask task = requireTask(taskId);
        List<DiagnosisTaskView.ToolInvocationView> invocations =
                invocationRepository.findByDiagnosisTaskIdOrderBySequenceNoAsc(taskId)
                        .stream()
                        .map(this::toInvocationView)
                        .toList();
        return new AgentTaskDetailView(
                task.getId(),
                task.getFaultEventId(),
                task.getStatus().name(),
                task.getResultStatus() != null ? task.getResultStatus().name() : null,
                task.getTriggerSource(),
                task.getGoal(),
                task.getConclusion(),
                task.getRootCause(),
                task.getConfidenceLevel(),
                task.getFailureReason(),
                task.getModelName(),
                task.getStartedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt(),
                jsonSupport.read(task.getReport()),
                jsonSupport.read(task.getAgentInput()),
                jsonSupport.read(task.getDecisionTrace()),
                invocations);
    }

    public List<AgentTaskSummaryView> listForEvent(UUID eventId) {
        faultEventService.requireEvent(eventId);
        return taskRepository.findByFaultEventIdOrderByCreatedAtDesc(eventId).stream()
                .map(task -> new AgentTaskSummaryView(
                        task.getId(),
                        task.getFaultEventId(),
                        task.getStatus().name(),
                        task.getResultStatus() != null ? task.getResultStatus().name() : null,
                        task.getTriggerSource(),
                        task.getRootCause(),
                        task.getConfidenceLevel(),
                        task.getFailureReason(),
                        task.getModelName(),
                        task.getCreatedAt(),
                        task.getCompletedAt()))
                .toList();
    }

    private DiagnosisTask createTask(UUID eventId, String triggerSource) {
        DiagnosisTask task = new DiagnosisTask();
        task.setFaultEventId(eventId);
        task.setStatus(DiagnosisStatus.PENDING);
        task.setTriggerSource(triggerSource);
        task.setGoal("由 LLM Agent 基于真实日志与指标证据自主调查故障事件 " + eventId);
        task.setCreatedBy("rca-console");
        return taskRepository.save(task);
    }

    private DiagnosisTask requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "诊断任务不存在: " + taskId));
    }

    private DiagnosisTaskView.ToolInvocationView toInvocationView(ToolInvocation invocation) {
        return new DiagnosisTaskView.ToolInvocationView(
                invocation.getId(),
                invocation.getToolName(),
                invocation.getStatus().name(),
                invocation.getLatencyMs(),
                invocation.getErrorMessage(),
                invocation.getInvokedAt(),
                invocation.getSequenceNo(),
                jsonSupport.read(invocation.getInputPayload()),
                jsonSupport.read(invocation.getOutputPayload()));
    }
}

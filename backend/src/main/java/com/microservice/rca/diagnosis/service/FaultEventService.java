package com.microservice.rca.diagnosis.service;

import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.api.CreateFaultEventRequest;
import com.microservice.rca.diagnosis.api.DiagnosisTaskView;
import com.microservice.rca.diagnosis.api.FaultEventDetailView;
import com.microservice.rca.diagnosis.api.FaultEventPage;
import com.microservice.rca.diagnosis.api.FaultEventView;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.domain.FaultSeverity;
import com.microservice.rca.diagnosis.domain.ToolInvocation;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.repository.FaultEventRepository;
import com.microservice.rca.diagnosis.repository.ToolInvocationRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FaultEventService {

    private final FaultEventRepository eventRepository;
    private final DiagnosisTaskRepository taskRepository;
    private final ToolInvocationRepository invocationRepository;
    private final JsonSupport jsonSupport;

    public FaultEventService(FaultEventRepository eventRepository,
                             DiagnosisTaskRepository taskRepository,
                             ToolInvocationRepository invocationRepository,
                             JsonSupport jsonSupport) {
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
        this.invocationRepository = invocationRepository;
        this.jsonSupport = jsonSupport;
    }

    @Transactional
    public FaultEventView create(CreateFaultEventRequest request) {
        FaultEvent event = new FaultEvent();
        event.setServiceName(request.serviceName());
        event.setEventType(request.eventType());
        event.setSeverity(request.severity() != null ? request.severity() : FaultSeverity.WARNING);
        event.setSummary(request.summary());
        event.setDetails(jsonSupport.write(request.details()));
        event.setOccurredAt(request.occurredAt() != null ? request.occurredAt() : Instant.now());
        event.setStatus(FaultEventStatus.OPEN);
        event.setExperimentId(request.experimentId());
        event.setExperimentFaultType(request.experimentFaultType());
        event.setExperimentParameters(jsonSupport.write(request.experimentParameters()));
        event.setObservedResult(jsonSupport.write(request.observedResult()));
        event.setRequestId(request.requestId());
        event.setSource(request.source() != null ? request.source() : "MANUAL");
        return toView(eventRepository.save(event));
    }

    /**
     * 由真实请求观测创建事件。预设故障参数与观测结果分开保存，且不写入 root_cause。
     */
    @Transactional
    public FaultEvent createFromExperiment(String serviceName,
                                           String eventType,
                                           FaultSeverity severity,
                                           String summary,
                                           UUID experimentId,
                                           String presetFaultType,
                                           Map<String, Object> presetParameters,
                                           Map<String, Object> observedResult,
                                           String requestId,
                                           Map<String, Object> details) {
        FaultEvent event = new FaultEvent();
        event.setServiceName(serviceName);
        event.setEventType(eventType);
        event.setSeverity(severity);
        event.setSummary(summary);
        event.setDetails(jsonSupport.write(details));
        event.setOccurredAt(Instant.now());
        event.setStatus(FaultEventStatus.OPEN);
        event.setExperimentId(experimentId);
        event.setExperimentFaultType(presetFaultType);
        event.setExperimentParameters(jsonSupport.write(presetParameters));
        event.setObservedResult(jsonSupport.write(observedResult));
        event.setRequestId(requestId);
        event.setSource(experimentId != null ? "EXPERIMENT" : "MANUAL");
        return eventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public FaultEventPage list(FaultEventStatus status,
                               String serviceName,
                               Instant from,
                               Instant to,
                               int page,
                               int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from 不能晚于 to");
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));

        Specification<FaultEvent> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (serviceName != null && !serviceName.isBlank()) {
                predicates.add(cb.equal(root.get("serviceName"), serviceName.trim()));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Page<FaultEvent> result = eventRepository.findAll(specification,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "occurredAt")));
        return new FaultEventPage(result.getContent().stream().map(this::toView).toList(),
                result.getTotalElements(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public FaultEventDetailView detail(UUID eventId) {
        FaultEvent event = requireEvent(eventId);
        List<DiagnosisTaskView> tasks = taskRepository.findByFaultEventIdOrderByCreatedAtDesc(eventId)
                .stream()
                .map(this::toTaskView)
                .toList();
        return new FaultEventDetailView(toView(event), tasks);
    }

    @Transactional
    public FaultEventView updateStatus(UUID eventId, FaultEventStatus target) {
        FaultEvent event = requireEvent(eventId);
        FaultEventStatus current = event.getStatus();
        if (current == target) {
            return toView(event);
        }
        if (!isAllowedTransition(current, target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "不允许的状态流转: " + current + " -> " + target);
        }
        event.setStatus(target);
        if (target == FaultEventStatus.RESOLVED || target == FaultEventStatus.CLOSED) {
            event.setResolvedAt(Instant.now());
        }
        return toView(eventRepository.save(event));
    }

    @Transactional
    public FaultEventView saveEvidence(UUID eventId, String evidenceJson, Instant collectedAt) {
        FaultEvent event = requireEvent(eventId);
        event.setEvidence(evidenceJson);
        event.setEvidenceCollectedAt(collectedAt);
        return toView(eventRepository.save(event));
    }

    @Transactional(readOnly = true)
    public FaultEvent requireEvent(UUID eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "故障事件不存在: " + eventId));
    }

    public FaultEventView toView(FaultEvent event) {
        return new FaultEventView(
                event.getId(),
                event.getServiceName(),
                event.getEventType(),
                event.getSeverity(),
                event.getSummary(),
                jsonSupport.read(event.getDetails()),
                event.getStatus(),
                event.getOccurredAt(),
                event.getCreatedAt(),
                event.getUpdatedAt(),
                event.getResolvedAt(),
                event.getExperimentId(),
                event.getExperimentFaultType(),
                jsonSupport.read(event.getExperimentParameters()),
                jsonSupport.read(event.getObservedResult()),
                event.getRequestId(),
                event.getSource(),
                jsonSupport.read(event.getEvidence()),
                event.getEvidenceCollectedAt());
    }

    private DiagnosisTaskView toTaskView(DiagnosisTask task) {
        List<DiagnosisTaskView.ToolInvocationView> invocations =
                invocationRepository.findByDiagnosisTaskIdOrderBySequenceNoAsc(task.getId())
                        .stream()
                        .map(this::toInvocationView)
                        .toList();
        return new DiagnosisTaskView(
                task.getId(),
                task.getStatus().name(),
                task.getResultStatus() != null ? task.getResultStatus().name() : null,
                task.getTriggerSource(),
                task.getGoal(),
                task.getConclusion(),
                task.getRootCause(),
                task.getConfidence() != null ? task.getConfidence().doubleValue() : null,
                task.getConfidenceLevel(),
                task.getFailureReason(),
                task.getModelName(),
                task.getStartedAt(),
                task.getCreatedBy(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt(),
                invocations);
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

    private boolean isAllowedTransition(FaultEventStatus current, FaultEventStatus target) {
        return switch (current) {
            case OPEN -> target == FaultEventStatus.DIAGNOSING
                    || target == FaultEventStatus.RESOLVED
                    || target == FaultEventStatus.CLOSED;
            case DIAGNOSING -> target == FaultEventStatus.RESOLVED
                    || target == FaultEventStatus.CLOSED;
            case RESOLVED -> target == FaultEventStatus.CLOSED;
            case CLOSED -> false;
        };
    }
}

package com.microservice.rca.agent;

import static com.microservice.rca.agent.AgentTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microservice.rca.agent.api.AgentTaskDetailView;
import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.repository.ToolInvocationRepository;
import com.microservice.rca.diagnosis.service.FaultEventService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentDiagnosisServiceTest {

    private final DiagnosisTaskRepository taskRepository = mock(DiagnosisTaskRepository.class);
    private final ToolInvocationRepository invocationRepository = mock(ToolInvocationRepository.class);
    private final FaultEventService faultEventService = mock(FaultEventService.class);
    private final AgentTaskRunner taskRunner = mock(AgentTaskRunner.class);
    private final AtomicReference<DiagnosisTask> created = new AtomicReference<>();

    private FaultEvent event;
    private DiagnosisTask original;
    private AgentDiagnosisService service;

    @BeforeEach
    void setUp() {
        event = new FaultEvent();
        event.setId(UUID.randomUUID());
        event.setStatus(FaultEventStatus.OPEN);

        original = new DiagnosisTask();
        original.setId(UUID.randomUUID());
        original.setFaultEventId(event.getId());
        original.setStatus(DiagnosisStatus.FAILED);
        original.setTriggerSource("AGENT");
        original.setFailureReason("瞬时失败");

        when(taskRepository.save(any(DiagnosisTask.class))).thenAnswer(invocation -> {
            DiagnosisTask saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            created.set(saved);
            return saved;
        });
        when(taskRepository.findById(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            if (id.equals(original.getId())) {
                return Optional.of(original);
            }
            DiagnosisTask saved = created.get();
            return saved != null && id.equals(saved.getId()) ? Optional.of(saved) : Optional.empty();
        });
        when(invocationRepository.findByDiagnosisTaskIdOrderBySequenceNoAsc(any(UUID.class)))
                .thenReturn(List.of());
        when(faultEventService.requireEvent(event.getId())).thenReturn(event);

        service = new AgentDiagnosisService(taskRepository, invocationRepository,
                faultEventService, taskRunner, json());
    }

    @Test
    void retryCreatesNewTaskAndMarksOpenEventDiagnosing() {
        AgentTaskDetailView detail = service.retry(original.getId());

        DiagnosisTask retry = created.get();
        assertThat(retry).isNotNull();
        assertThat(retry.getId()).isNotEqualTo(original.getId());
        assertThat(retry.getFaultEventId()).isEqualTo(original.getFaultEventId());
        assertThat(retry.getTriggerSource()).isEqualTo("AGENT_RETRY");
        assertThat(retry.getStatus()).isEqualTo(DiagnosisStatus.PENDING);
        assertThat(detail.id()).isEqualTo(retry.getId());
        // 原任务的历史状态和失败原因不被覆盖。
        assertThat(original.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        assertThat(original.getFailureReason()).isEqualTo("瞬时失败");
        verify(faultEventService).updateStatus(event.getId(), FaultEventStatus.DIAGNOSING);
        verify(taskRunner).run(retry.getId());
    }

    @Test
    void retryDoesNotReopenResolvedOrClosedEvent() {
        event.setStatus(FaultEventStatus.RESOLVED);

        service.retry(original.getId());

        verify(faultEventService, never()).updateStatus(any(UUID.class), any(FaultEventStatus.class));
        assertThat(created.get()).isNotNull();
        verify(taskRunner).run(created.get().getId());
    }
}

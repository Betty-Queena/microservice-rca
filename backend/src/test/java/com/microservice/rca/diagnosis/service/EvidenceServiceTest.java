package com.microservice.rca.diagnosis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.microservice.rca.tools.ToolResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

class EvidenceServiceTest {

    private final FaultEventService faultEventService = mock(FaultEventService.class);
    private final DiagnosisTaskRepository taskRepository = mock(DiagnosisTaskRepository.class);
    private final ToolInvocationService toolInvocationService = mock(ToolInvocationService.class);
    private final List<DiagnosisTask> saves = new ArrayList<>();

    private final UUID eventId = UUID.randomUUID();
    private EvidenceService service;

    @BeforeEach
    void setUp() {
        FaultEvent event = new FaultEvent();
        event.setId(eventId);
        event.setOccurredAt(Instant.now().minusSeconds(120));
        when(faultEventService.requireEvent(eventId)).thenReturn(event);
        when(taskRepository.save(any(DiagnosisTask.class))).thenAnswer(invocation -> {
            DiagnosisTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId(UUID.randomUUID());
            }
            saves.add(task);
            return task;
        });

        JsonSupport jsonSupport = new JsonSupport(
                new ObjectMapper().findAndRegisterModules());
        service = new EvidenceService(
                faultEventService, taskRepository, toolInvocationService, jsonSupport);
    }

    @Test
    void writesEvidenceBeforeMarkingTaskSucceeded() {
        when(toolInvocationService.invoke(any(), eq("query_logs"), any(), eq(1)))
                .thenReturn(outcome("query_logs", true));
        when(toolInvocationService.invoke(any(), eq("query_metrics"), any(), eq(2)))
                .thenReturn(outcome("query_metrics", true));

        EvidenceCollectionView view = service.collect(eventId, new EvidenceQueryRequest(
                null, null, null, null, null));

        assertThat(view.success()).isTrue();
        assertThat(view.diagnosisTaskStatus()).isEqualTo("SUCCEEDED");
        assertThat(saves.get(saves.size() - 1).getStatus()).isEqualTo(DiagnosisStatus.SUCCEEDED);
        assertThat(saves.get(saves.size() - 1).getFailureReason()).isNull();

        InOrder order = inOrder(taskRepository, faultEventService);
        order.verify(taskRepository).save(any(DiagnosisTask.class));
        order.verify(faultEventService).saveEvidence(eq(eventId), anyString(), any(Instant.class));
        order.verify(taskRepository).save(any(DiagnosisTask.class));
    }

    @Test
    void marksTaskFailedWhenToolInvocationThrows() {
        when(toolInvocationService.invoke(any(), eq("query_logs"), any(), anyInt()))
                .thenThrow(new IllegalStateException("tool transport failure"));

        assertThatThrownBy(() -> service.collect(eventId, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool transport failure");

        DiagnosisTask last = saves.get(saves.size() - 1);
        assertThat(last.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        assertThat(last.getFailureReason()).contains("证据采集失败");
        assertThat(last.getCompletedAt()).isNotNull();
        verify(faultEventService, never()).saveEvidence(any(), anyString(), any());
    }

    @Test
    void marksTaskFailedWhenEvidenceWriteFails() {
        when(toolInvocationService.invoke(any(), eq("query_logs"), any(), eq(1)))
                .thenReturn(outcome("query_logs", true));
        when(toolInvocationService.invoke(any(), eq("query_metrics"), any(), eq(2)))
                .thenReturn(outcome("query_metrics", true));
        when(faultEventService.saveEvidence(eq(eventId), anyString(), any(Instant.class)))
                .thenThrow(new DataIntegrityViolationException("evidence write failed"));

        assertThatThrownBy(() -> service.collect(eventId, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        DiagnosisTask last = saves.get(saves.size() - 1);
        assertThat(last.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        assertThat(last.getFailureReason()).contains("证据采集失败");
    }

    @Test
    void allToolsFailedStillPersistsEvidenceAndFailedTask() {
        when(toolInvocationService.invoke(any(), eq("query_logs"), any(), eq(1)))
                .thenReturn(outcome("query_logs", false));
        when(toolInvocationService.invoke(any(), eq("query_metrics"), any(), eq(2)))
                .thenReturn(outcome("query_metrics", false));

        EvidenceCollectionView view = service.collect(eventId, null);

        assertThat(view.success()).isFalse();
        assertThat(view.diagnosisTaskStatus()).isEqualTo("FAILED");
        DiagnosisTask last = saves.get(saves.size() - 1);
        assertThat(last.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        assertThat(last.getFailureReason()).contains("所有诊断工具调用失败");
        verify(faultEventService).saveEvidence(eq(eventId), anyString(), any(Instant.class));
    }

    @Test
    void passesManualToolTimeoutToToolInvocation() {
        when(toolInvocationService.invoke(any(), eq("query_logs"), any(), eq(1)))
                .thenReturn(outcome("query_logs", true));
        when(toolInvocationService.invoke(any(), eq("query_metrics"), any(), eq(2)))
                .thenReturn(outcome("query_metrics", true));

        service.collect(eventId, null);

        ArgumentCaptor<ToolExecutionContext> captor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolInvocationService, times(2)).invoke(any(), anyString(), captor.capture(), anyInt());
        assertThat(captor.getAllValues()).allSatisfy(context ->
                assertThat(context.timeout()).isEqualTo(Duration.ofSeconds(15)));
    }

    private ToolInvocationOutcome outcome(String toolName, boolean success) {
        ToolResult result = success
                ? ToolResult.success(Map.of("tool", toolName))
                : ToolResult.failure("工具不可用");
        return new ToolInvocationOutcome(
                UUID.randomUUID(), toolName, result, 12);
    }
}

package com.microservice.rca.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.repository.FaultEventRepository;
import com.microservice.rca.diagnosis.service.FaultEventService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiagnosisTaskRecoveryTest {

    private static final Set<DiagnosisStatus> ACTIVE =
            Set.of(DiagnosisStatus.PENDING, DiagnosisStatus.RUNNING);

    private final DiagnosisTaskRepository taskRepository = mock(DiagnosisTaskRepository.class);
    private final FaultEventRepository eventRepository = mock(FaultEventRepository.class);
    private final FaultEventService faultEventService = mock(FaultEventService.class);

    private final DiagnosisTaskRecovery recovery =
            new DiagnosisTaskRecovery(taskRepository, eventRepository, faultEventService);

    @Test
    void marksActiveTasksFailedAndRestoresTheirEvents() {
        DiagnosisTask pending = task(DiagnosisStatus.PENDING);
        DiagnosisTask running = task(DiagnosisStatus.RUNNING);
        when(taskRepository.findByStatusIn(ACTIVE)).thenReturn(List.of(pending, running));
        when(taskRepository.failIfActive(any(UUID.class), eq(DiagnosisStatus.FAILED), eq(ACTIVE),
                anyString(), any(Instant.class))).thenReturn(1);

        recovery.recoverOrphanTasks();

        verify(taskRepository).failIfActive(eq(pending.getId()), eq(DiagnosisStatus.FAILED),
                eq(ACTIVE), anyString(), any(Instant.class));
        verify(taskRepository).failIfActive(eq(running.getId()), eq(DiagnosisStatus.FAILED),
                eq(ACTIVE), anyString(), any(Instant.class));
        verify(faultEventService).restoreOpenIfDiagnosing(pending.getFaultEventId());
        verify(faultEventService).restoreOpenIfDiagnosing(running.getFaultEventId());
    }

    @Test
    void doesNotRestoreEventWhenTaskAlreadyReachedTerminalState() {
        DiagnosisTask pending = task(DiagnosisStatus.PENDING);
        when(taskRepository.findByStatusIn(ACTIVE)).thenReturn(List.of(pending));
        // 条件更新返回 0 表示任务已被并发完成/取消，不能再改事件状态。
        when(taskRepository.failIfActive(any(UUID.class), eq(DiagnosisStatus.FAILED), eq(ACTIVE),
                anyString(), any(Instant.class))).thenReturn(0);

        recovery.recoverOrphanTasks();

        verify(faultEventService, never()).restoreOpenIfDiagnosing(any(UUID.class));
    }

    @Test
    void continuesRecoveryWhenOneTaskFailsToPersist() {
        DiagnosisTask broken = task(DiagnosisStatus.RUNNING);
        DiagnosisTask healthy = task(DiagnosisStatus.PENDING);
        when(taskRepository.findByStatusIn(ACTIVE)).thenReturn(List.of(broken, healthy));
        when(taskRepository.failIfActive(eq(broken.getId()), eq(DiagnosisStatus.FAILED), eq(ACTIVE),
                anyString(), any(Instant.class)))
                .thenThrow(new IllegalStateException("db error"));
        when(taskRepository.failIfActive(eq(healthy.getId()), eq(DiagnosisStatus.FAILED), eq(ACTIVE),
                anyString(), any(Instant.class))).thenReturn(1);

        recovery.recoverOrphanTasks();

        verify(faultEventService, times(1)).restoreOpenIfDiagnosing(any(UUID.class));
        verify(faultEventService).restoreOpenIfDiagnosing(healthy.getFaultEventId());
    }

    @Test
    void logsDiagnosingEventWithoutActiveTaskWithoutModifyingIt() {
        FaultEvent event = new FaultEvent();
        event.setId(UUID.randomUUID());
        event.setStatus(FaultEventStatus.DIAGNOSING);
        when(taskRepository.findByStatusIn(ACTIVE)).thenReturn(List.of());
        when(eventRepository.findByStatus(FaultEventStatus.DIAGNOSING)).thenReturn(List.of(event));
        when(taskRepository.existsByFaultEventIdAndStatusIn(event.getId(), ACTIVE)).thenReturn(false);

        recovery.recoverOrphanTasks();

        // 对账只读：不恢复事件、不保存任务、不改历史数据。
        verify(faultEventService, never()).restoreOpenIfDiagnosing(any(UUID.class));
        verify(taskRepository, never()).failIfActive(any(UUID.class), any(), any(), anyString(), any());
        verify(eventRepository, never()).save(any(FaultEvent.class));
    }

    @Test
    void doesNotFlagDiagnosingEventThatStillHasActiveTask() {
        FaultEvent event = new FaultEvent();
        event.setId(UUID.randomUUID());
        event.setStatus(FaultEventStatus.DIAGNOSING);
        when(taskRepository.findByStatusIn(ACTIVE)).thenReturn(List.of());
        when(eventRepository.findByStatus(FaultEventStatus.DIAGNOSING)).thenReturn(List.of(event));
        when(taskRepository.existsByFaultEventIdAndStatusIn(event.getId(), ACTIVE)).thenReturn(true);

        recovery.recoverOrphanTasks();

        verify(faultEventService, never()).restoreOpenIfDiagnosing(any(UUID.class));
        verify(eventRepository, never()).save(any(FaultEvent.class));
    }

    private DiagnosisTask task(DiagnosisStatus status) {
        DiagnosisTask task = new DiagnosisTask();
        task.setId(UUID.randomUUID());
        task.setFaultEventId(UUID.randomUUID());
        task.setStatus(status);
        task.setTriggerSource("AGENT");
        task.setGoal("测试任务");
        return task;
    }
}

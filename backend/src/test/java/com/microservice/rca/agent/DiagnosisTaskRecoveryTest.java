package com.microservice.rca.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.service.FaultEventService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiagnosisTaskRecoveryTest {

    private final DiagnosisTaskRepository taskRepository = mock(DiagnosisTaskRepository.class);
    private final FaultEventService faultEventService = mock(FaultEventService.class);

    private final DiagnosisTaskRecovery recovery =
            new DiagnosisTaskRecovery(taskRepository, faultEventService);

    @Test
    void marksPendingAndRunningTasksFailedAndRestoresTheirEvents() {
        DiagnosisTask pending = task(DiagnosisStatus.PENDING);
        DiagnosisTask running = task(DiagnosisStatus.RUNNING);
        when(taskRepository.findByStatusIn(Set.of(DiagnosisStatus.PENDING, DiagnosisStatus.RUNNING)))
                .thenReturn(List.of(pending, running));
        when(taskRepository.save(any(DiagnosisTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        recovery.recoverOrphanTasks();

        assertThat(pending.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        assertThat(running.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        assertThat(pending.getFailureReason()).contains("服务重启");
        assertThat(running.getFailureReason()).contains("服务重启");
        assertThat(pending.getCompletedAt()).isNotNull();
        assertThat(running.getCompletedAt()).isNotNull();
        verify(faultEventService).restoreOpenIfDiagnosing(pending.getFaultEventId());
        verify(faultEventService).restoreOpenIfDiagnosing(running.getFaultEventId());
    }

    @Test
    void doesNotRestoreEventWhenConditionalUpdateFindsItResolved() {
        DiagnosisTask pending = task(DiagnosisStatus.PENDING);
        when(taskRepository.findByStatusIn(Set.of(DiagnosisStatus.PENDING, DiagnosisStatus.RUNNING)))
                .thenReturn(List.of(pending));
        when(taskRepository.save(any(DiagnosisTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(faultEventService.restoreOpenIfDiagnosing(pending.getFaultEventId())).thenReturn(false);

        recovery.recoverOrphanTasks();

        assertThat(pending.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        // 事件是否变化由条件更新决定，恢复器不会直接覆盖任何事件状态。
        verify(faultEventService).restoreOpenIfDiagnosing(pending.getFaultEventId());
    }

    @Test
    void continuesRecoveryWhenOneTaskFailsToPersist() {
        DiagnosisTask broken = task(DiagnosisStatus.RUNNING);
        DiagnosisTask healthy = task(DiagnosisStatus.PENDING);
        when(taskRepository.findByStatusIn(Set.of(DiagnosisStatus.PENDING, DiagnosisStatus.RUNNING)))
                .thenReturn(List.of(broken, healthy));
        when(taskRepository.save(any(DiagnosisTask.class)))
                .thenThrow(new IllegalStateException("db error"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        recovery.recoverOrphanTasks();

        assertThat(healthy.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        verify(faultEventService, times(1)).restoreOpenIfDiagnosing(any(UUID.class));
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

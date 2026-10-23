package com.microservice.rca.agent;

import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.service.FaultEventService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时恢复上一次进程遗留的 PENDING/RUNNING 任务。
 *
 * <p>应用刚刚启动、尚未接收外部请求，此时仍处于 PENDING/RUNNING 的任务不可能由本进程执行，
 * 因此统一标记为 FAILED 并保留失败原因，避免前端永久看到“诊断中”。
 * 恢复只修改任务状态和事件状态，不删除任何诊断历史。</p>
 */
@Component
public class DiagnosisTaskRecovery {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisTaskRecovery.class);
    private static final Set<DiagnosisStatus> ACTIVE_STATUSES =
            Set.of(DiagnosisStatus.PENDING, DiagnosisStatus.RUNNING);
    private static final String FAILURE_REASON = "服务重启导致任务中断";

    private final DiagnosisTaskRepository taskRepository;
    private final FaultEventService faultEventService;

    public DiagnosisTaskRecovery(DiagnosisTaskRepository taskRepository,
                                 FaultEventService faultEventService) {
        this.taskRepository = taskRepository;
        this.faultEventService = faultEventService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanTasks() {
        List<DiagnosisTask> orphans = taskRepository.findByStatusIn(ACTIVE_STATUSES);
        if (orphans.isEmpty()) {
            return;
        }

        int recovered = 0;
        for (DiagnosisTask task : orphans) {
            try {
                task.setStatus(DiagnosisStatus.FAILED);
                task.setFailureReason(FAILURE_REASON);
                task.setCompletedAt(Instant.now());
                taskRepository.save(task);
                // 条件更新：只把仍为 DIAGNOSING 的事件恢复为 OPEN，不覆盖人工 RESOLVED/CLOSED。
                faultEventService.restoreOpenIfDiagnosing(task.getFaultEventId());
                recovered++;
            } catch (Exception ex) {
                // 单个任务恢复失败不能阻塞其他任务，保留现场等待人工排查。
                log.error("Failed to recover orphan diagnosis task {}", task.getId(), ex);
            }
        }
        log.warn("Recovered {} orphan diagnosis task(s) after restart", recovered);
    }
}

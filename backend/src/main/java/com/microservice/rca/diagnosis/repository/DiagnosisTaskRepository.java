package com.microservice.rca.diagnosis.repository;

import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisTaskRepository extends JpaRepository<DiagnosisTask, UUID> {

    List<DiagnosisTask> findByFaultEventIdOrderByCreatedAtDesc(UUID faultEventId);

    /**
     * 只有 Agent 触发的任务会阻塞同一事件上的后续 Agent 诊断。
     * MANUAL_EVIDENCE 是只读的证据采集，不应被误判为活跃 Agent 任务。
     */
    boolean existsByFaultEventIdAndTriggerSourceInAndStatusIn(
            UUID faultEventId,
            Collection<String> triggerSources,
            Collection<DiagnosisStatus> statuses);

    List<DiagnosisTask> findByStatusIn(Collection<DiagnosisStatus> statuses);
}

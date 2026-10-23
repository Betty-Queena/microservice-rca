package com.microservice.rca.diagnosis.repository;

import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    boolean existsByFaultEventIdAndStatusIn(UUID faultEventId, Collection<DiagnosisStatus> statuses);

    /**
     * 仅当任务仍处于活跃状态时落为 FAILED，避免并发完成/取消后的终态被覆盖。
     * 使用批量条件更新，跳过实体合并与@Version冲突。
     *
     * @return 实际更新行数；0 表示任务已进入终态或不存在
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DiagnosisTask t
               set t.status = :failedStatus,
                   t.failureReason = :reason,
                   t.completedAt = :now,
                   t.updatedAt = :now,
                   t.version = t.version + 1
             where t.id = :taskId and t.status in :activeStatuses
            """)
    int failIfActive(@Param("taskId") UUID taskId,
                     @Param("failedStatus") DiagnosisStatus failedStatus,
                     @Param("activeStatuses") Collection<DiagnosisStatus> activeStatuses,
                     @Param("reason") String reason,
                     @Param("now") Instant now);
}

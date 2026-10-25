package com.microservice.rca.remediation.repository;

import com.microservice.rca.remediation.domain.RemediationAction;
import com.microservice.rca.remediation.domain.RemediationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RemediationActionRepository extends JpaRepository<RemediationAction, UUID> {

    Optional<RemediationAction> findByFaultEventIdAndIdempotencyKey(
            UUID faultEventId, String idempotencyKey);

    Optional<RemediationAction> findFirstByFaultEventIdAndStatusInOrderByRequestedAtDesc(
            UUID faultEventId, Collection<RemediationStatus> statuses);

    Optional<RemediationAction> findFirstByFaultEventIdOrderByRequestedAtDesc(UUID faultEventId);

    List<RemediationAction> findByFaultEventIdOrderByRequestedAtDesc(UUID faultEventId);

    /**
     * 判断该故障事件是否已经存在指定终态的处置记录。
     *
     * <p>用于 SUCCEEDED 硬闸门：成功处置后不再允许新幂等键再次触发真实
     * checkout，即使事件因并发诊断竞态仍停留在 OPEN。</p>
     */
    boolean existsByFaultEventIdAndStatus(UUID faultEventId, RemediationStatus status);

    /**
     * 查询本次进程启动前创建、且仍处于活跃状态的处置记录。
     *
     * <p>优先使用 started_at 判断执行开始时间；尚未抢占执行权的 PENDING 记录
     * 使用 requested_at。严格使用小于号，避免把本次启动期间新建的记录误判为
     * 遗留记录。</p>
     */
    @Query("""
            select r from RemediationAction r
             where r.status in :statuses
               and ((r.startedAt is not null and r.startedAt < :cutoff)
                    or (r.startedAt is null and r.requestedAt < :cutoff))
             order by r.requestedAt asc
            """)
    List<RemediationAction> findInterruptedBefore(
            @Param("statuses") Collection<RemediationStatus> statuses,
            @Param("cutoff") Instant cutoff);

    /**
     * 抢占执行权。只有仍为 PENDING 的记录能进入 EXECUTING，避免重复执行。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RemediationAction r
               set r.status = :executing,
                   r.startedAt = :startedAt,
                   r.version = r.version + 1
             where r.id = :id and r.status = :pending
            """)
    int markExecuting(@Param("id") UUID id,
                      @Param("pending") RemediationStatus pending,
                      @Param("executing") RemediationStatus executing,
                      @Param("startedAt") Instant startedAt);

    /**
     * 追加审计轨迹。仅在执行中记录可更新，终态记录不会再被改写。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RemediationAction r
               set r.auditTrace = :auditTrace,
                   r.version = r.version + 1
             where r.id = :id and r.status = :executing
            """)
    int updateAuditTrace(@Param("id") UUID id,
                         @Param("executing") RemediationStatus executing,
                         @Param("auditTrace") String auditTrace);

    /**
     * 在验证前单独落库动作结果。这样即使后续验证失败或进程中断，
     * 也不会丢失“故障关闭动作已经成功”的事实。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RemediationAction r
               set r.actionResult = :actionResult,
                   r.auditTrace = :auditTrace,
                   r.version = r.version + 1
             where r.id = :id and r.status = :executing
            """)
    int recordActionResult(@Param("id") UUID id,
                           @Param("executing") RemediationStatus executing,
                           @Param("actionResult") String actionResult,
                           @Param("auditTrace") String auditTrace);

    /**
     * 写入终态。失败状态不会修改事件；SUCCEEDED 由 store 在同一数据库事务中
     * 条件更新事件状态，外部 HTTP 调用不在此事务内。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RemediationAction r
               set r.status = :status,
                   r.failureCode = :failureCode,
                   r.failureReason = :failureReason,
                   r.actionResult = :actionResult,
                   r.verificationResult = :verificationResult,
                   r.auditTrace = :auditTrace,
                   r.verificationRequestId = :verificationRequestId,
                   r.verificationFaultEventId = :verificationFaultEventId,
                   r.eventStatusUpdated = :eventStatusUpdated,
                   r.completedAt = :completedAt,
                   r.version = r.version + 1
             where r.id = :id and r.status = :executing
            """)
    int finishIfExecuting(@Param("id") UUID id,
                          @Param("executing") RemediationStatus executing,
                          @Param("status") RemediationStatus status,
                          @Param("failureCode") String failureCode,
                          @Param("failureReason") String failureReason,
                          @Param("actionResult") String actionResult,
                          @Param("verificationResult") String verificationResult,
                          @Param("auditTrace") String auditTrace,
                          @Param("verificationRequestId") String verificationRequestId,
                          @Param("verificationFaultEventId") UUID verificationFaultEventId,
                          @Param("eventStatusUpdated") boolean eventStatusUpdated,
                          @Param("completedAt") Instant completedAt);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RemediationAction r
               set r.eventStatusUpdated = :eventStatusUpdated,
                   r.version = r.version + 1
             where r.id = :id and r.status = :succeeded
            """)
    int markEventStatusUpdated(@Param("id") UUID id,
                               @Param("succeeded") RemediationStatus succeeded,
                               @Param("eventStatusUpdated") boolean eventStatusUpdated);

    /**
     * 把启动前遗留的活跃记录关闭为 RECOVERY_REQUIRED。
     *
     * <p>使用 id + expectedStatus + expectedVersion 三重条件，避免并发恢复或
     * 人工更新被覆盖。刻意不修改 action_result、verification_result、验证请求 ID、
     * requested_at 和 started_at，保留中断时的真实语义。条件不满足时返回 0，
     * 调用方跳过该记录即可。</p>
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RemediationAction r
               set r.status = :targetStatus,
                   r.failureCode = :failureCode,
                   r.failureReason = :failureReason,
                   r.auditTrace = :auditTrace,
                   r.eventStatusUpdated = false,
                   r.completedAt = :completedAt,
                   r.version = r.version + 1
             where r.id = :id
               and r.status = :expectedStatus
               and r.version = :expectedVersion
            """)
    int markInterrupted(@Param("id") UUID id,
                        @Param("expectedStatus") RemediationStatus expectedStatus,
                        @Param("expectedVersion") long expectedVersion,
                        @Param("targetStatus") RemediationStatus targetStatus,
                        @Param("failureCode") String failureCode,
                        @Param("failureReason") String failureReason,
                        @Param("auditTrace") String auditTrace,
                        @Param("completedAt") Instant completedAt);
}

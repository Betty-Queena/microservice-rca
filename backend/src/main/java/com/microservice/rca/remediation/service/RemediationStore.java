package com.microservice.rca.remediation.service;

import com.microservice.rca.diagnosis.service.FaultEventService;
import com.microservice.rca.remediation.domain.RemediationAction;
import com.microservice.rca.remediation.domain.RemediationStatus;
import com.microservice.rca.remediation.repository.RemediationActionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处置记录的短事务边界。
 *
 * <p>创建记录、抢占执行权、写入动作结果、写入终态分别是一个短事务。
 * 外部 HTTP 调用不经过该类，因此不会持有数据库连接或行锁。</p>
 */
@Service
public class RemediationStore {

    private static final List<RemediationStatus> ACTIVE_STATUSES =
            List.of(RemediationStatus.PENDING, RemediationStatus.EXECUTING);

    private final RemediationActionRepository repository;
    private final FaultEventService faultEventService;

    public RemediationStore(RemediationActionRepository repository,
                            FaultEventService faultEventService) {
        this.repository = repository;
        this.faultEventService = faultEventService;
    }

    @Transactional
    public RemediationAction create(RemediationAction action) {
        // 立即 flush，让数据库的唯一索引在创建阶段就暴露并发冲突。
        return repository.saveAndFlush(action);
    }

    @Transactional(readOnly = true)
    public Optional<RemediationAction> findById(UUID id) {
        return repository.findById(id);
    }

    /**
     * 只读判断该事件是否已有成功且验证通过的处置。
     * 该查询不会触发外部动作，也不改变事件或处置状态。
     */
    @Transactional(readOnly = true)
    public boolean hasSucceeded(UUID faultEventId) {
        return repository.existsByFaultEventIdAndStatus(
                faultEventId, RemediationStatus.SUCCEEDED);
    }

    @Transactional(readOnly = true)
    public Optional<RemediationAction> findByEventAndIdempotencyKey(UUID eventId, String key) {
        return repository.findByFaultEventIdAndIdempotencyKey(eventId, key);
    }

    @Transactional(readOnly = true)
    public Optional<RemediationAction> findActiveByEvent(UUID eventId) {
        return repository.findFirstByFaultEventIdAndStatusInOrderByRequestedAtDesc(
                eventId, ACTIVE_STATUSES);
    }

    @Transactional(readOnly = true)
    public List<RemediationAction> listByEvent(UUID eventId) {
        return repository.findByFaultEventIdOrderByRequestedAtDesc(eventId);
    }

    /**
     * 返回该事件最新创建的处置记录，用于判断是否存在需要人工核查的
     * RECOVERY_REQUIRED 记录。
     */
    @Transactional(readOnly = true)
    public Optional<RemediationAction> findLatestByEvent(UUID eventId) {
        return repository.findFirstByFaultEventIdOrderByRequestedAtDesc(eventId);
    }

    /**
     * 返回本次进程启动前遗留的 PENDING/EXECUTING 记录。查询本身不修改数据。
     */
    @Transactional(readOnly = true)
    public List<RemediationAction> findInterruptedBefore(Instant cutoff) {
        return repository.findInterruptedBefore(ACTIVE_STATUSES, cutoff);
    }

    /**
     * 条件关闭一条遗留活跃记录。
     *
     * @return 是否成功写入；false 表示状态或版本已变化，调用方应跳过
     */
    @Transactional
    public boolean markInterrupted(UUID id,
                                   RemediationStatus expectedStatus,
                                   long expectedVersion,
                                   RemediationStatus targetStatus,
                                   String failureCode,
                                   String failureReason,
                                   String auditTrace,
                                   Instant completedAt) {
        return repository.markInterrupted(
                id,
                expectedStatus,
                expectedVersion,
                targetStatus,
                failureCode,
                failureReason,
                auditTrace,
                completedAt) > 0;
    }

    /**
     * 条件抢占：只有 PENDING 记录可以进入 EXECUTING。
     *
     * @return 是否抢占成功；false 表示记录已被其他请求处理或已经终态
     */
    @Transactional
    public boolean claim(UUID id, Instant startedAt) {
        return repository.markExecuting(
                id, RemediationStatus.PENDING, RemediationStatus.EXECUTING, startedAt) > 0;
    }

    @Transactional
    public boolean updateAudit(UUID id, String auditTrace) {
        return repository.updateAuditTrace(
                id, RemediationStatus.EXECUTING, auditTrace) > 0;
    }

    /**
     * 单独落库动作结果，确保后续验证失败时仍保留动作已成功的事实。
     */
    @Transactional
    public boolean recordAction(UUID id, String actionResult, String auditTrace) {
        return repository.recordActionResult(
                id, RemediationStatus.EXECUTING, actionResult, auditTrace) > 0;
    }

    @Transactional
    public boolean finishFailed(UUID id,
                                RemediationStatus status,
                                String failureCode,
                                String failureReason,
                                String actionResult,
                                String verificationResult,
                                String auditTrace,
                                String verificationRequestId,
                                UUID verificationFaultEventId,
                                Instant completedAt) {
        return repository.finishIfExecuting(
                id,
                RemediationStatus.EXECUTING,
                status,
                failureCode,
                failureReason,
                actionResult,
                verificationResult,
                auditTrace,
                verificationRequestId,
                verificationFaultEventId,
                false,
                completedAt) > 0;
    }

    /**
     * 验证通过时，在同一个数据库事务中写入 SUCCEEDED 并尝试把事件从 OPEN 更新为
     * RESOLVED。条件更新失败（例如人工已关闭事件）不会覆盖人工状态，只记录
     * eventStatusUpdated=false。
     */
    @Transactional
    public FinishResult finishSucceeded(UUID id,
                                        UUID faultEventId,
                                        String actionResult,
                                        String verificationResult,
                                        String auditTrace,
                                        String verificationRequestId,
                                        UUID verificationFaultEventId,
                                        Instant completedAt) {
        int finished = repository.finishIfExecuting(
                id,
                RemediationStatus.EXECUTING,
                RemediationStatus.SUCCEEDED,
                null,
                null,
                actionResult,
                verificationResult,
                auditTrace,
                verificationRequestId,
                verificationFaultEventId,
                false,
                completedAt);
        if (finished == 0) {
            return new FinishResult(false, false);
        }
        boolean eventUpdated = faultEventService.resolveIfOpen(faultEventId);
        boolean flagUpdated = false;
        if (eventUpdated) {
            flagUpdated = repository.markEventStatusUpdated(
                    id, RemediationStatus.SUCCEEDED, true) > 0;
        }
        return new FinishResult(true, flagUpdated);
    }

    public record FinishResult(boolean terminalUpdated, boolean eventStatusUpdated) {
    }
}

package com.microservice.rca.remediation.service;

import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.domain.DiagnosisResultStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.service.FaultEventService;
import com.microservice.rca.remediation.RemediationProperties;
import com.microservice.rca.remediation.api.CreateRemediationRequest;
import com.microservice.rca.remediation.api.RemediationView;
import com.microservice.rca.remediation.domain.RecoveryCodes;
import com.microservice.rca.remediation.domain.RemediationAction;
import com.microservice.rca.remediation.domain.RemediationActionType;
import com.microservice.rca.remediation.domain.RemediationStatus;
import com.microservice.rca.remediation.service.RemediationExecutor.ActionOutcome;
import com.microservice.rca.remediation.service.RemediationExecutor.VerificationOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 受控处置编排。
 *
 * <p>流程：校验事件与诊断 -> 幂等创建 -> 抢占执行权 -> 条件关闭故障 ->
 * 一次真实验证 -> 写终态 -> 验证通过时条件更新事件为 RESOLVED。</p>
 *
 * <p>数据库写操作全部由 {@link RemediationStore} 的短事务完成；本类不持有事务，
 * 因此关闭故障和恢复验证的 HTTP 调用不会占用数据库连接或行锁。</p>
 */
@Service
public class RemediationService {

    private static final Logger log = LoggerFactory.getLogger(RemediationService.class);

    private static final Set<String> AGENT_TRIGGER_SOURCES = Set.of("AGENT", "AGENT_RETRY");
    private static final Set<DiagnosisStatus> ACTIVE_DIAGNOSIS_STATUSES =
            Set.of(DiagnosisStatus.PENDING, DiagnosisStatus.RUNNING);
    /**
     * ACTION_FAILED 中外部动作结果可能未知的失败码。这些失败码可能发生在
     * 关闭请求已发出但响应丢失之后，重试前必须人工核查。
     */
    private static final Set<String> AMBIGUOUS_ACTION_FAILURE_CODES =
            Set.of("INVENTORY_UNAVAILABLE", "ACTION_EXECUTION_ERROR", "INTERNAL_ERROR");
    private static final String DEFAULT_REQUESTED_BY = "local-operator";
    private static final int MAX_FAILURE_REASON = 1024;

    private final RemediationProperties properties;
    private final RemediationStore store;
    private final FaultEventService faultEventService;
    private final DiagnosisTaskRepository diagnosisTaskRepository;
    private final RemediationExecutor executor;
    private final JsonSupport jsonSupport;

    public RemediationService(RemediationProperties properties,
                              RemediationStore store,
                              FaultEventService faultEventService,
                              DiagnosisTaskRepository diagnosisTaskRepository,
                              RemediationExecutor executor,
                              JsonSupport jsonSupport) {
        this.properties = properties;
        this.store = store;
        this.faultEventService = faultEventService;
        this.diagnosisTaskRepository = diagnosisTaskRepository;
        this.executor = executor;
        this.jsonSupport = jsonSupport;
    }

    /**
     * 创建并同步执行一次处置。业务失败在记录创建后仍返回 HTTP 200，
     * 通过 status/failureCode 表达；未创建记录的参数或状态错误返回 4xx。
     */
    public RemediationView createAndExecute(UUID eventId, CreateRemediationRequest request) {
        ensureEnabled();
        NormalizedRequest normalized = validateRequest(request);

        FaultEvent event = faultEventService.requireEvent(eventId);

        // 幂等重放必须在事件状态校验之前处理：第一次处置成功后事件可能已 RESOLVED，
        // 客户端因 HTTP 超时重试相同 idempotencyKey 时仍应拿到已有终态记录。
        if (normalized.idempotencyKey() != null) {
            Optional<RemediationAction> existing = store.findByEventAndIdempotencyKey(
                    eventId, normalized.idempotencyKey());
            if (existing.isPresent()) {
                return replay(existing.get(), normalized);
            }
        }

        PreconditionFailure failure = checkPreconditions(event, normalized.diagnosisTaskId());
        if (failure != null) {
            throw new ResponseStatusException(failure.status(), failure.reason());
        }

        // 已完成的处置可能代表动作或验证已部分执行。仅靠前端提示不足以
        // 防止盲目重试，因此对“可能已产生外部副作用”的终态增加服务端闸门。
        // 相同 idempotencyKey 的重放已在上方返回，不会被这些闸门重复拦截。
        RemediationAction priorReview = requirePriorManualReview(eventId, normalized);

        Optional<RemediationAction> active = store.findActiveByEvent(eventId);
        if (active.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该事件已有进行中的处置，请查询现有处置记录");
        }

        Instant now = Instant.now();
        List<Map<String, Object>> audit = new ArrayList<>();
        addAudit(audit, "REQUEST_ACCEPTED", now,
                "已显式确认；目标 experimentId=" + event.getExperimentId() + " 来自故障事件");
        if (priorReview != null) {
            String step = priorReview.getStatus() == RemediationStatus.RECOVERY_REQUIRED
                    ? "PRIOR_RECOVERY_ACKNOWLEDGED"
                    : "PRIOR_MANUAL_REVIEW_ACKNOWLEDGED";
            addAudit(audit, step, now,
                    "操作者已确认核查前次处置 " + priorReview.getId()
                            + "（status=" + priorReview.getStatus()
                            + ", failureCode=" + displayCode(priorReview.getFailureCode())
                            + "）；本次 operatorNote 已记录");
        }

        String effectiveIdempotencyKey = normalized.idempotencyKey() != null
                ? normalized.idempotencyKey() : UUID.randomUUID().toString();
        RemediationAction action = new RemediationAction();
        action.setFaultEventId(eventId);
        action.setDiagnosisTaskId(normalized.diagnosisTaskId());
        action.setActionType(normalized.actionType());
        action.setTargetService(normalized.actionType().targetService());
        action.setExperimentId(event.getExperimentId());
        action.setStatus(RemediationStatus.PENDING);
        action.setRequestedBy(normalized.requestedBy());
        action.setOperatorNote(normalized.operatorNote());
        action.setIdempotencyKey(effectiveIdempotencyKey);
        action.setAuditTrace(jsonSupport.write(audit));
        action.setEventStatusUpdated(false);
        action.setRequestedAt(now);

        RemediationAction created;
        try {
            created = store.create(action);
        } catch (DataIntegrityViolationException ex) {
            // 应用层先查后写存在竞争窗口；数据库部分唯一索引是最终兜底。
            return resolveCreateConflict(eventId, normalized);
        }

        return execute(created.getId(), eventId, normalized.diagnosisTaskId(), audit);
    }

    public List<RemediationView> list(UUID eventId) {
        faultEventService.requireEvent(eventId);
        return store.listByEvent(eventId).stream().map(this::toView).toList();
    }

    public RemediationView get(UUID remediationId) {
        return store.findById(remediationId)
                .map(this::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "处置记录不存在: " + remediationId));
    }

    public RemediationView getByEventAndIdempotencyKey(UUID eventId, String idempotencyKey) {
        faultEventService.requireEvent(eventId);
        String key = trimToNull(idempotencyKey);
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idempotencyKey 不能为空");
        }
        requireMaxLength(key, 64, "idempotencyKey");
        return store.findByEventAndIdempotencyKey(eventId, key)
                .map(this::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "该事件下不存在指定幂等键的处置记录"));
    }

    private RemediationView execute(UUID remediationId,
                                    UUID eventId,
                                    UUID diagnosisTaskId,
                                    List<Map<String, Object>> audit) {
        if (!store.claim(remediationId, Instant.now())) {
            // 已被其他请求抢占或已经进入终态，直接返回已有记录，不重复执行。
            return loadView(remediationId);
        }

        String actionResultJson = null;
        String verificationResultJson = null;
        String verificationRequestId = null;
        UUID verificationFaultEventId = null;
        try {
            if (!properties.enabled()) {
                return finishActionFailed(remediationId, "REMEDIATION_DISABLED",
                        "受控处置开关已关闭，未执行动作", actionResultJson, audit,
                        Instant.now());
            }

            FaultEvent event = faultEventService.requireEvent(eventId);
            PreconditionFailure failure = checkPreconditions(event, diagnosisTaskId);
            if (failure != null) {
                addAudit(audit, "PRECONDITIONS_FAILED", Instant.now(), failure.reason());
                Map<String, Object> result = preconditionResult(failure, Instant.now());
                actionResultJson = jsonSupport.write(result);
                return finishActionFailed(remediationId, failure.code(), failure.reason(),
                        actionResultJson, audit, Instant.now());
            }

            addAudit(audit, "PRECONDITIONS_CHECKED", Instant.now(),
                    "事件仍为 OPEN，诊断任务有效且无活跃 Agent 任务");
            if (!store.updateAudit(remediationId, jsonSupport.write(audit))) {
                return loadView(remediationId);
            }

            ActionOutcome actionOutcome = executor.executeAction(event);
            actionResultJson = jsonSupport.write(actionOutcome.actionResult());
            addAudit(audit, actionOutcome.success() ? "FAULT_DISABLED" : "ACTION_FAILED",
                    Instant.now(), actionOutcome.failureReason());
            if (!store.recordAction(remediationId, actionResultJson, jsonSupport.write(audit))) {
                return loadView(remediationId);
            }
            if (!actionOutcome.success()) {
                return finishActionFailed(remediationId,
                        actionOutcome.failureCode(), actionOutcome.failureReason(),
                        actionResultJson, audit, Instant.now());
            }

            // 动作成功后只验证一次，不自动重试。
            VerificationOutcome verification = executor.verify(event);
            verificationResultJson = jsonSupport.write(verification.verificationResult());
            verificationRequestId = verification.requestId();
            verificationFaultEventId = verification.faultEventId();
            addAudit(audit, verification.verified() ? "VERIFICATION_PASSED"
                            : "INCONCLUSIVE".equals(verification.result())
                            ? "VERIFICATION_INCONCLUSIVE" : "VERIFICATION_FAILED",
                    Instant.now(), verification.failureReason());

            if (verification.verified()) {
                // 事件状态条件更新与 SUCCEEDED 在同一短事务中完成，
                // 更新结果通过 eventStatusUpdated 单独暴露。
                addAudit(audit, "EVENT_STATUS_UPDATE_REQUESTED", Instant.now(),
                        "仅当事件仍为 OPEN 时条件更新为 RESOLVED");
                var finishResult = store.finishSucceeded(
                        remediationId,
                        eventId,
                        actionResultJson,
                        verificationResultJson,
                        jsonSupport.write(audit),
                        verificationRequestId,
                        verificationFaultEventId,
                        Instant.now());
                if (!finishResult.terminalUpdated()) {
                    log.warn("Remediation {} was already terminal before SUCCEEDED was written",
                            remediationId);
                } else if (!finishResult.eventStatusUpdated()) {
                    log.warn("Remediation {} verified recovery, but fault event {} was not OPEN"
                                    + " when resolving it; human status is preserved",
                            remediationId, eventId);
                }
                return loadView(remediationId);
            }

            RemediationStatus terminalStatus = "INCONCLUSIVE".equals(verification.result())
                    ? RemediationStatus.VERIFICATION_INCONCLUSIVE
                    : RemediationStatus.VERIFICATION_FAILED;
            return finishVerificationFailed(remediationId, terminalStatus,
                    verification.failureCode(), verification.failureReason(),
                    actionResultJson, verificationResultJson, audit,
                    verificationRequestId, verificationFaultEventId, Instant.now());
        } catch (RuntimeException ex) {
            log.error("Remediation {} failed unexpectedly", remediationId, ex);
            String reason = truncate("处置执行异常: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? " - " + ex.getMessage() : ""),
                    MAX_FAILURE_REASON);
            addAudit(audit, "EXECUTION_ERROR", Instant.now(), reason);
            try {
                store.finishFailed(
                        remediationId,
                        RemediationStatus.ACTION_FAILED,
                        "INTERNAL_ERROR",
                        reason,
                        actionResultJson,
                        verificationResultJson,
                        jsonSupport.write(audit),
                        verificationRequestId,
                        verificationFaultEventId,
                        Instant.now());
            } catch (Exception persistEx) {
                // 无法落库时保留 EXECUTING，由阶段 3 的启动恢复兜底。
                log.error("Failed to persist unexpected remediation failure {}", remediationId,
                        persistEx);
                ex.addSuppressed(persistEx);
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "处置执行异常，请通过处置 ID 查询最终状态", ex);
        }
    }

    private RemediationView finishActionFailed(UUID remediationId,
                                               String failureCode,
                                               String failureReason,
                                               String actionResultJson,
                                               List<Map<String, Object>> audit,
                                               Instant completedAt) {
        store.finishFailed(
                remediationId,
                RemediationStatus.ACTION_FAILED,
                failureCode,
                truncate(failureReason, MAX_FAILURE_REASON),
                actionResultJson,
                null,
                jsonSupport.write(audit),
                null,
                null,
                completedAt);
        return loadView(remediationId);
    }

    private RemediationView finishVerificationFailed(UUID remediationId,
                                                     RemediationStatus status,
                                                     String failureCode,
                                                     String failureReason,
                                                     String actionResultJson,
                                                     String verificationResultJson,
                                                     List<Map<String, Object>> audit,
                                                     String verificationRequestId,
                                                     UUID verificationFaultEventId,
                                                     Instant completedAt) {
        store.finishFailed(
                remediationId,
                status,
                failureCode,
                truncate(failureReason, MAX_FAILURE_REASON),
                actionResultJson,
                verificationResultJson,
                jsonSupport.write(audit),
                verificationRequestId,
                verificationFaultEventId,
                completedAt);
        return loadView(remediationId);
    }

    private RemediationView resolveCreateConflict(UUID eventId, NormalizedRequest normalized) {
        if (normalized.idempotencyKey() != null) {
            Optional<RemediationAction> existing = store.findByEventAndIdempotencyKey(
                    eventId, normalized.idempotencyKey());
            if (existing.isPresent()) {
                return replay(existing.get(), normalized);
            }
        }
        Optional<RemediationAction> active = store.findActiveByEvent(eventId);
        if (active.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该事件已有进行中的处置，请查询现有处置记录");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "处置记录创建冲突，请刷新后重试");
    }

    private RemediationView replay(RemediationAction existing, NormalizedRequest request) {
        if (existing.getActionType() != request.actionType()
                || !request.diagnosisTaskId().equals(existing.getDiagnosisTaskId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "idempotencyKey 已被不同的动作或诊断任务使用");
        }
        return toView(existing);
    }

    private PreconditionFailure checkPreconditions(FaultEvent event, UUID diagnosisTaskId) {
        if (!"EXPERIMENT".equals(event.getSource())) {
            return new PreconditionFailure(HttpStatus.CONFLICT, "EVENT_NOT_EXPERIMENT",
                    "仅支持 source=EXPERIMENT 的故障事件");
        }
        if (event.getExperimentId() == null) {
            return new PreconditionFailure(HttpStatus.CONFLICT, "EXPERIMENT_ID_MISSING",
                    "故障事件缺少 experimentId，不能执行受控处置");
        }
        if (event.getStatus() != FaultEventStatus.OPEN) {
            return new PreconditionFailure(HttpStatus.CONFLICT, "EVENT_NOT_OPEN",
                    "只有 OPEN 状态的故障事件可以执行处置，当前状态: " + event.getStatus());
        }

        DiagnosisTask task = diagnosisTaskRepository.findById(diagnosisTaskId).orElse(null);
        if (task == null) {
            return new PreconditionFailure(HttpStatus.NOT_FOUND, "DIAGNOSIS_TASK_NOT_FOUND",
                    "诊断任务不存在: " + diagnosisTaskId);
        }
        if (!event.getId().equals(task.getFaultEventId())) {
            return new PreconditionFailure(HttpStatus.CONFLICT, "DIAGNOSIS_TASK_MISMATCH",
                    "诊断任务不属于该故障事件");
        }
        if (!AGENT_TRIGGER_SOURCES.contains(task.getTriggerSource())) {
            return new PreconditionFailure(HttpStatus.CONFLICT, "DIAGNOSIS_NOT_AGENT_TASK",
                    "诊断任务不是 Agent 根因诊断");
        }
        if (task.getStatus() != DiagnosisStatus.SUCCEEDED) {
            return new PreconditionFailure(HttpStatus.CONFLICT, "DIAGNOSIS_NOT_SUCCEEDED",
                    "诊断任务尚未成功完成，当前状态: " + task.getStatus());
        }
        if (task.getResultStatus() != DiagnosisResultStatus.ROOT_CAUSE_IDENTIFIED) {
            return new PreconditionFailure(HttpStatus.CONFLICT, "DIAGNOSIS_NOT_ROOT_CAUSE",
                    "诊断任务未给出根因结论，不能执行处置");
        }
        if (diagnosisTaskRepository.existsByFaultEventIdAndTriggerSourceInAndStatusIn(
                event.getId(), AGENT_TRIGGER_SOURCES, ACTIVE_DIAGNOSIS_STATUSES)) {
            return new PreconditionFailure(HttpStatus.CONFLICT, "AGENT_TASK_RUNNING",
                    "该事件仍有 Agent 诊断正在执行，请等待完成后再处置");
        }
        // 成功处置是终态事实，不因事件状态竞态、eventStatusUpdated=false 或
        // 客户端更换幂等键而消失。该检查同时用于 execute() 的二次前置校验，
        // 保证并发窗口在真实外部动作之前被拦截。
        if (store.hasSucceeded(event.getId())) {
            return new PreconditionFailure(HttpStatus.CONFLICT, "EVENT_ALREADY_REMEDIATED",
                    "该事件已有成功且验证通过的处置，系统不会重复执行 checkout；"
                            + "请人工核查故障事件状态");
        }
        return null;
    }

    private Map<String, Object> preconditionResult(PreconditionFailure failure, Instant at) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("outcome", "PRECONDITION_FAILED");
        result.put("failureCode", failure.code());
        result.put("failureReason", failure.reason());
        result.put("checkedAt", at.toString());
        return result;
    }

    /**
     * 对该事件最新一条可能已产生外部副作用的终态处置增加人工核查闸门。
     *
     * <p>SUCCEEDED 是硬闸门，在 {@link #checkPreconditions(FaultEvent, UUID)} 中直接拒绝；
     * 本方法只处理其余需要操作者显式确认的终态：</p>
     * <ul>
     *   <li>RECOVERY_REQUIRED：除 INTERRUPTED_BEFORE_ACTION 外均要求确认；</li>
     *   <li>VERIFICATION_FAILED / VERIFICATION_INCONCLUSIVE：动作已成功，真实 checkout
     *       已尝试，但结果未证明安全；</li>
     *   <li>ACTION_FAILED 且失败码表示外部结果可能未知。</li>
     * </ul>
     *
     * <p>该闸门只能证明调用方声明已核查，不能证明外部状态真的安全；订单侧重复
     * checkout 的最终防护仍需要订单服务的幂等键。</p>
     */
    private RemediationAction requirePriorManualReview(UUID eventId,
                                                       NormalizedRequest request) {
        Optional<RemediationAction> latest = store.findLatestByEvent(eventId);
        if (latest.isEmpty()) {
            return null;
        }
        RemediationAction prior = latest.get();
        if (!requiresManualReview(prior)) {
            return null;
        }
        if (!request.recoveryAcknowledged()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    manualReviewRequiredMessage(prior));
        }
        if (request.operatorNote() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "确认核查前次处置结果时必须提供非空 operatorNote");
        }
        return prior;
    }

    private boolean requiresManualReview(RemediationAction prior) {
        return switch (prior.getStatus()) {
            case RECOVERY_REQUIRED ->
                    RecoveryCodes.requiresManualExternalReview(prior.getFailureCode());
            case VERIFICATION_FAILED, VERIFICATION_INCONCLUSIVE -> true;
            case ACTION_FAILED -> prior.getFailureCode() == null
                    || AMBIGUOUS_ACTION_FAILURE_CODES.contains(prior.getFailureCode());
            default -> false;
        };
    }

    private String manualReviewRequiredMessage(RemediationAction prior) {
        String prefix = "该事件最新处置 " + prior.getId() + " 处于 " + prior.getStatus()
                + "（failureCode=" + displayCode(prior.getFailureCode()) + "），";
        String detail;
        if (prior.getStatus() == RemediationStatus.RECOVERY_REQUIRED) {
            detail = "上次动作或验证结果不完整，可能已产生外部副作用。";
        } else if (prior.getStatus() == RemediationStatus.ACTION_FAILED) {
            detail = "关闭动作的外部结果无法确认，验证未执行。";
        } else {
            detail = "上次关闭动作已成功，真实 checkout 已尝试但结果未证明恢复安全；"
                    + "再次处置会再执行一次真实 checkout。";
        }
        return prefix + detail
                + "必须先人工核查实验故障、订单和库存状态，再设置 "
                + "recoveryAcknowledged=true 并提供非空 operatorNote 重新发起处置；"
                + "系统不会自动重试";
    }

    private String displayCode(String code) {
        return code == null ? "UNKNOWN" : code;
    }

    private void ensureEnabled() {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "受控处置默认关闭，请设置 RCA_REMEDIATION_ENABLED=true 后重启后端");
        }
    }

    private NormalizedRequest validateRequest(CreateRemediationRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        if (request.actionType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "actionType 不能为空");
        }
        if (request.actionType() != RemediationActionType.DISABLE_EXPERIMENT_FAULT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的处置动作");
        }
        if (request.diagnosisTaskId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "diagnosisTaskId 不能为空");
        }
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "必须显式确认 confirmed=true 才能执行处置");
        }

        String idempotencyKey = trimToNull(request.idempotencyKey());
        requireMaxLength(idempotencyKey, 64, "idempotencyKey");
        String requestedBy = trimToNull(request.requestedBy());
        if (requestedBy == null) {
            requestedBy = DEFAULT_REQUESTED_BY;
        }
        requireMaxLength(requestedBy, 128, "requestedBy");
        String operatorNote = trimToNull(request.operatorNote());
        requireMaxLength(operatorNote, 512, "operatorNote");
        boolean recoveryAcknowledged = Boolean.TRUE.equals(request.recoveryAcknowledged());

        return new NormalizedRequest(request.actionType(), request.diagnosisTaskId(),
                idempotencyKey, requestedBy, operatorNote, recoveryAcknowledged);
    }

    private void requireMaxLength(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 长度不能超过 " + max);
        }
    }

    private RemediationView loadView(UUID remediationId) {
        return store.findById(remediationId)
                .map(this::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "处置记录读取失败: " + remediationId));
    }

    private RemediationView toView(RemediationAction action) {
        return new RemediationView(
                action.getId(),
                action.getFaultEventId(),
                action.getDiagnosisTaskId(),
                action.getActionType(),
                action.getTargetService(),
                action.getExperimentId(),
                action.getStatus(),
                action.getFailureCode(),
                action.getFailureReason(),
                action.getRequestedBy(),
                action.getOperatorNote(),
                action.getIdempotencyKey(),
                jsonSupport.read(action.getActionResult()),
                jsonSupport.read(action.getVerificationResult()),
                jsonSupport.read(action.getAuditTrace()),
                action.getVerificationRequestId(),
                action.getVerificationFaultEventId(),
                action.isEventStatusUpdated(),
                action.getRequestedAt(),
                action.getStartedAt(),
                action.getCompletedAt());
    }

    private void addAudit(List<Map<String, Object>> audit,
                          String step,
                          Instant at,
                          String detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", step);
        entry.put("at", at.toString());
        if (detail != null && !detail.isBlank()) {
            entry.put("detail", truncate(detail, 512));
        }
        audit.add(entry);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record NormalizedRequest(
            RemediationActionType actionType,
            UUID diagnosisTaskId,
            String idempotencyKey,
            String requestedBy,
            String operatorNote,
            boolean recoveryAcknowledged) {
    }

    private record PreconditionFailure(HttpStatus status, String code, String reason) {
    }
}

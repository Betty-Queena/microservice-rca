package com.microservice.rca.remediation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.remediation.domain.RecoveryCodes;
import com.microservice.rca.remediation.domain.RemediationAction;
import com.microservice.rca.remediation.domain.RemediationStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 后端进程启动后，把本次启动前遗留的 PENDING/EXECUTING 处置记录关闭为
 * {@link RemediationStatus#RECOVERY_REQUIRED}。
 *
 * <p>恢复过程只读查询并写终态，不依赖 RemediationExecutor、ExperimentService、
 * Agent 或 FaultEventService，因而不可能重新关闭故障、重新执行结算验证、
 * 重新调用 Agent 或修改故障事件。</p>
 *
 * <p><strong>部署限制：</strong>本实现只支持单后端实例连接同一数据库。表结构没有
 * owner/instance/heartbeat 字段，无法判断某条活跃记录是否仍由另一个实例执行，
 * 因此多实例部署时必须关闭该恢复任务，或另行设计任务所有权机制。状态 + 版本
 * 条件更新只能避免覆盖并发修改，不能识别存活实例。</p>
 */
@Component
@ConditionalOnProperty(prefix = "rca.remediation.recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RemediationRecovery {

    private static final Logger log = LoggerFactory.getLogger(RemediationRecovery.class);
    private static final int MAX_FAILURE_REASON = 1024;
    private static final int MAX_AUDIT_DETAIL = 512;

    private final RemediationStore store;
    private final JsonSupport jsonSupport;
    private final ObjectMapper objectMapper;
    private final Instant startupCutoff;

    @Autowired
    public RemediationRecovery(RemediationStore store,
                               JsonSupport jsonSupport,
                               ObjectMapper objectMapper) {
        this(store, jsonSupport, objectMapper, Instant.now());
    }

    /**
     * 测试用构造器。startupCutoff 在应用启动早期固定，避免把本次启动期间创建的
     * 记录误判为上次进程遗留。查询使用严格的 requestedAt/startedAt &lt; cutoff。
     */
    RemediationRecovery(RemediationStore store,
                        JsonSupport jsonSupport,
                        ObjectMapper objectMapper,
                        Instant startupCutoff) {
        this.store = store;
        this.jsonSupport = jsonSupport;
        this.objectMapper = objectMapper;
        this.startupCutoff = startupCutoff;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.warn("Remediation startup recovery is enabled under a single-backend-instance "
                + "assumption; records created before {} may be marked RECOVERY_REQUIRED. "
                + "Disable rca.remediation.recovery.enabled if multiple instances share "
                + "the same database.", startupCutoff);
        recover(startupCutoff);
    }

    /**
     * 执行一次恢复扫描。可重复调用：已经是终态的记录不会被查询选中，
     * 状态 + 版本条件更新也会拒绝并发重复写入。
     */
    void recover(Instant cutoff) {
        List<RemediationAction> candidates;
        try {
            candidates = store.findInterruptedBefore(cutoff);
        } catch (RuntimeException ex) {
            // 启动恢复失败不能阻止应用启动；记录日志后等待人工核查数据库。
            log.error("Failed to query interrupted remediation records before {}", cutoff, ex);
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            log.info("No interrupted remediation records found before {}", cutoff);
            return;
        }

        Instant recoveredAt = Instant.now();
        int marked = 0;
        for (RemediationAction action : candidates) {
            try {
                // 防御性判断：查询已经限制状态，但如果数据在读取后被并发修改，
                // 仍不要对终态记录做无意义更新。
                if (action.getStatus() == null || !action.getStatus().active()) {
                    continue;
                }
                RecoveryDecision decision = classify(action);
                String auditTrace = appendRecoveryAudit(action, decision, cutoff, recoveredAt);
                boolean updated = store.markInterrupted(
                        action.getId(),
                        action.getStatus(),
                        action.getVersion(),
                        RemediationStatus.RECOVERY_REQUIRED,
                        decision.code(),
                        truncate(decision.reason(), MAX_FAILURE_REASON),
                        auditTrace,
                        recoveredAt);
                if (updated) {
                    marked++;
                    log.warn("Interrupted remediation {} (previousStatus={}, recoveryCode={}) "
                                    + "was marked RECOVERY_REQUIRED; manual external review "
                                    + "is required before a new remediation",
                            action.getId(), action.getStatus(), decision.code());
                } else {
                    log.warn("Interrupted remediation {} changed concurrently or was already "
                                    + "terminal; recovery update skipped", action.getId());
                }
            } catch (RuntimeException ex) {
                // 单条记录失败不影响其他记录，且不会触发任何外部副作用。
                log.error("Failed to recover interrupted remediation {}; manual review required",
                        action.getId(), ex);
            }
        }
        log.warn("Remediation startup recovery marked {} of {} interrupted record(s) as {}",
                marked, candidates.size(), RemediationStatus.RECOVERY_REQUIRED);
    }

    private RecoveryDecision classify(RemediationAction action) {
        List<String> issues = new ArrayList<>();
        if (action.getStatus() == RemediationStatus.PENDING) {
            if (action.getStartedAt() != null) {
                issues.add("PENDING 记录已有 started_at");
            }
            if (action.getActionResult() != null) {
                issues.add("PENDING 记录已有 action_result");
            }
            if (action.getVerificationResult() != null) {
                issues.add("PENDING 记录已有 verification_result");
            }
            if (action.getFailureCode() != null) {
                issues.add("PENDING 记录已有 failure_code");
            }
            if (action.getFailureReason() != null) {
                issues.add("PENDING 记录已有 failure_reason");
            }
            if (action.getVerificationRequestId() != null) {
                issues.add("PENDING 记录已有 verification_request_id");
            }
            if (action.getVerificationFaultEventId() != null) {
                issues.add("PENDING 记录已有 verification_fault_event_id");
            }
            if (action.getCompletedAt() != null) {
                issues.add("PENDING 记录已有 completed_at");
            }
            if (action.isEventStatusUpdated()) {
                issues.add("PENDING 记录已标记 event_status_updated");
            }
            if (!issues.isEmpty()) {
                return inconsistent(issues);
            }
            return new RecoveryDecision(
                    RecoveryCodes.BEFORE_ACTION,
                    "处置在抢占执行权前中断，动作确定尚未开始。系统未重新执行关闭故障、"
                            + "结算验证或 Agent；如仍需要处置，请人工确认后使用新的幂等键发起。",
                    "NOT_STARTED",
                    "NOT_STARTED",
                    List.of());
        }

        if (action.getStartedAt() == null) {
            issues.add("EXECUTING 记录缺少 started_at");
        }
        if (action.getFailureCode() != null) {
            issues.add("活跃记录已有 failure_code");
        }
        if (action.getFailureReason() != null) {
            issues.add("活跃记录已有 failure_reason");
        }
        if (action.getVerificationResult() != null) {
            issues.add("动作结果未确认但已有 verification_result");
        }
        if (action.getVerificationRequestId() != null) {
            issues.add("活跃记录已有 verification_request_id");
        }
        if (action.getVerificationFaultEventId() != null) {
            issues.add("活跃记录已有 verification_fault_event_id");
        }
        if (action.getCompletedAt() != null) {
            issues.add("活跃记录已有 completed_at");
        }
        if (action.isEventStatusUpdated()) {
            issues.add("活跃记录已标记 event_status_updated");
        }
        if (!issues.isEmpty()) {
            return inconsistent(issues);
        }

        String rawActionResult = action.getActionResult();
        if (rawActionResult == null || rawActionResult.isBlank()) {
            return new RecoveryDecision(
                    RecoveryCodes.ACTION_UNKNOWN,
                    "动作结果尚未落库，无法确认条件关闭接口是否已被调用，外部状态未知。"
                            + "系统未重试关闭故障、结算验证或 Agent；请先人工核查实验故障状态"
                            + "和订单/库存状态，确认无副作用后再考虑新的处置。",
                    "UNKNOWN",
                    "NOT_STARTED",
                    List.of());
        }

        JsonNode actionResult = jsonSupport.read(rawActionResult);
        JsonNode success = actionResult != null && actionResult.isObject()
                ? actionResult.get("success") : null;
        if (success == null || !success.isBoolean()) {
            return new RecoveryDecision(
                    RecoveryCodes.ACTION_RESULT_UNREADABLE,
                    "action_result 已存在但无法读取 success 布尔值，动作结果无法确认。"
                            + "系统未重试关闭故障、结算验证或 Agent；请先人工核查实验故障状态"
                            + "和订单/库存状态，确认无副作用后再考虑新的处置。",
                    "UNKNOWN",
                    "NOT_STARTED",
                    List.of());
        }

        String outcome = text(actionResult, "outcome");
        if (!success.booleanValue()) {
            return new RecoveryDecision(
                    RecoveryCodes.ACTION_FAILED,
                    "action_result 已记录动作失败"
                            + (outcome != null ? "（outcome=" + outcome + "）" : "")
                            + "，验证未执行。外部状态仍可能受部分失败操作影响；系统未重试"
                            + "关闭故障、结算验证或 Agent。请先人工核查实验故障状态和订单/库存"
                            + "状态后再考虑新的处置。",
                    "FAILED",
                    "NOT_STARTED",
                    List.of());
        }

        return new RecoveryDecision(
                RecoveryCodes.VERIFICATION_UNKNOWN,
                "action_result 已记录动作成功"
                        + (outcome != null ? "（outcome=" + outcome + "）" : "")
                        + "，但验证结果未落库，验证请求可能已执行，订单/库存外部状态未知。"
                        + "系统未重试验证、关闭故障或 Agent；请先人工核查结算结果、订单和"
                        + "库存状态，确认无重复副作用后再考虑新的处置。",
                "SUCCEEDED",
                "UNKNOWN",
                List.of());
    }

    private RecoveryDecision inconsistent(List<String> issues) {
        String detail = "活跃记录字段与状态不一致：" + String.join("；", issues)
                + "。系统不重试关闭故障、结算验证或 Agent，也不覆盖 action_result/"
                + "verification_result；请人工核查数据库记录、实验故障状态和订单/库存状态。";
        return new RecoveryDecision(
                RecoveryCodes.STATE_INCONSISTENT,
                detail,
                "UNKNOWN",
                "UNKNOWN",
                List.copyOf(issues));
    }

    private String appendRecoveryAudit(RemediationAction action,
                                       RecoveryDecision decision,
                                       Instant cutoff,
                                       Instant recoveredAt) {
        ArrayNode audit = readAuditArray(action.getAuditTrace());
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("step", "RECOVERY_MARKED");
        entry.put("at", recoveredAt.toString());
        entry.put("previousStatus", action.getStatus().name());
        entry.put("recoveryCode", decision.code());
        entry.put("actionState", decision.actionState());
        entry.put("verificationState", decision.verificationState());
        entry.put("manualReviewRequired", true);
        entry.put("processStartupCutoff", cutoff.toString());
        entry.put("recoveredAt", recoveredAt.toString());
        entry.put("actionResultRecorded", action.getActionResult() != null);
        entry.put("verificationResultRecorded", action.getVerificationResult() != null);
        entry.put("detail", truncate(decision.reason(), MAX_AUDIT_DETAIL));
        if (!decision.consistencyIssues().isEmpty()) {
            ArrayNode issueNode = entry.putArray("consistencyIssues");
            decision.consistencyIssues().forEach(issueNode::add);
        }
        putIfNotNull(entry, "previousFailureCode", action.getFailureCode());
        putIfNotNull(entry, "previousFailureReason", action.getFailureReason());
        if (action.getCompletedAt() != null) {
            entry.put("previousCompletedAt", action.getCompletedAt().toString());
        }
        if (action.isEventStatusUpdated()) {
            entry.put("previousEventStatusUpdated", true);
        }
        putIfNotNull(entry, "previousVerificationRequestId", action.getVerificationRequestId());
        if (action.getVerificationFaultEventId() != null) {
            entry.put("previousVerificationFaultEventId",
                    action.getVerificationFaultEventId().toString());
        }
        audit.add(entry);
        return jsonSupport.write(audit);
    }

    /**
     * 保留已有审计轨迹。已有 JSON 对象、标量或无法解析的内容不会被丢弃，
     * 而是包在 previousAuditTrace 中，避免恢复动作覆盖原始证据。
     */
    private ArrayNode readAuditArray(String auditTrace) {
        ArrayNode audit = objectMapper.createArrayNode();
        JsonNode existing = jsonSupport.read(auditTrace);
        if (existing == null || existing.isNull()) {
            return audit;
        }
        if (existing.isArray()) {
            audit.addAll((ArrayNode) existing);
        } else {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.set("previousAuditTrace", existing);
            audit.add(wrapper);
        }
        return audit;
    }

    private void putIfNotNull(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record RecoveryDecision(String code,
                                    String reason,
                                    String actionState,
                                    String verificationState,
                                    List<String> consistencyIssues) {
    }
}

package com.microservice.rca.remediation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservice.rca.remediation.domain.RemediationActionType;
import com.microservice.rca.remediation.domain.RemediationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 处置记录视图。
 *
 * <p>actionResult、verificationResult 和 eventStatusUpdated 分别表示：
 * 动作是否执行成功、真实恢复验证是否通过、事件状态是否成功更新。
 * 业务失败也返回 HTTP 200，失败原因放在 status/failureCode/failureReason。</p>
 */
public record RemediationView(
        UUID id,
        UUID faultEventId,
        UUID diagnosisTaskId,
        RemediationActionType actionType,
        String targetService,
        UUID experimentId,
        RemediationStatus status,
        String failureCode,
        String failureReason,
        String requestedBy,
        String operatorNote,
        String idempotencyKey,
        JsonNode actionResult,
        JsonNode verificationResult,
        JsonNode auditTrace,
        String verificationRequestId,
        UUID verificationFaultEventId,
        boolean eventStatusUpdated,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt) {
}

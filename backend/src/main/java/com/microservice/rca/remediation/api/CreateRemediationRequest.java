package com.microservice.rca.remediation.api;

import com.microservice.rca.remediation.domain.RemediationActionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 创建处置请求。
 *
 * <p>刻意不包含 experimentId、sku、quantity、命令、URL、SQL 等字段：
 * 处置目标和验证参数只能从服务端持久化的故障事件读取，避免前端或 LLM 覆盖。</p>
 *
 * @param recoveryAcknowledged 当该事件最新处置处于 RECOVERY_REQUIRED（中断码表明可能已产生
 *                             外部副作用）、VERIFICATION_FAILED/VERIFICATION_INCONCLUSIVE，
 *                             或 ACTION_FAILED 且失败码表明关闭动作结果可能未知时，需要显式为
 *                             true，并同时提供非空 operatorNote。它表示操作者已人工核查外部
 *                             状态，不是身份认证；普通首次处置可省略。已有 SUCCEEDED 记录时
 *                             该字段不能绕过重复处置闸门。
 */
public record CreateRemediationRequest(
        @NotNull(message = "不能为空") RemediationActionType actionType,
        @NotNull(message = "不能为空") UUID diagnosisTaskId,
        @NotNull(message = "必须显式确认") Boolean confirmed,
        @Size(max = 64) String idempotencyKey,
        @Size(max = 128) String requestedBy,
        @Size(max = 512) String operatorNote,
        Boolean recoveryAcknowledged) {

    /**
     * 兼容未携带 recoveryAcknowledged 的既有调用方和测试。
     */
    public CreateRemediationRequest(RemediationActionType actionType,
                                    UUID diagnosisTaskId,
                                    Boolean confirmed,
                                    String idempotencyKey,
                                    String requestedBy,
                                    String operatorNote) {
        this(actionType, diagnosisTaskId, confirmed,
                idempotencyKey, requestedBy, operatorNote, null);
    }
}

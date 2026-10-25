package com.microservice.rca.remediation.domain;

/**
 * 处置执行状态。动作执行结果、恢复验证结果和事件状态更新结果分别记录，
 * 不能仅凭事件被人工标记为 RESOLVED 推断恢复验证成功。
 *
 * <p>{@link #RECOVERY_REQUIRED} 是启动恢复写入的终态：进程中断时动作或验证结果
 * 不完整，系统不会自动重试或继续执行，必须人工核查。它不代表动作失败，也不代表
 * 恢复成功。该状态不进入活跃处置唯一索引，因此不会永久阻塞后续人工处置。</p>
 */
public enum RemediationStatus {

    PENDING,
    EXECUTING,
    SUCCEEDED,
    ACTION_FAILED,
    VERIFICATION_FAILED,
    VERIFICATION_INCONCLUSIVE,
    RECOVERY_REQUIRED;

    /**
     * 系统不会再次自动修改的状态。注意 RECOVERY_REQUIRED 仍是“需要人工核查”的
     * 终态，而不是无需处理的状态。
     */
    public boolean terminal() {
        return this != PENDING && this != EXECUTING;
    }

    public boolean active() {
        return this == PENDING || this == EXECUTING;
    }
}

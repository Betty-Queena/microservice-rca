package com.microservice.rca.remediation.domain;

/**
 * 启动恢复写入 {@link RemediationStatus#RECOVERY_REQUIRED} 时使用的原因码。
 *
 * <p>这些原因码只描述进程中断时已知或未知的执行阶段，不代表动作失败，
 * 也不代表恢复成功。除 {@link #BEFORE_ACTION} 外，其他情况都可能存在外部副作用，
 * 必须人工核查后才能考虑新的处置。</p>
 */
public final class RecoveryCodes {

    /** PENDING 记录：动作确定尚未开始。 */
    public static final String BEFORE_ACTION = "INTERRUPTED_BEFORE_ACTION";

    /** EXECUTING 且 action_result 未落库：无法确认关闭接口是否已调用。 */
    public static final String ACTION_UNKNOWN = "INTERRUPTED_ACTION_UNKNOWN";

    /** EXECUTING 且 action_result 存在但无法读取 success 布尔值。 */
    public static final String ACTION_RESULT_UNREADABLE =
            "INTERRUPTED_ACTION_RESULT_UNREADABLE";

    /** EXECUTING 且 action_result 明确记录 success=false，验证未执行。 */
    public static final String ACTION_FAILED = "INTERRUPTED_ACTION_FAILED";

    /** EXECUTING 且动作成功，但验证结果未落库，验证可能已执行。 */
    public static final String VERIFICATION_UNKNOWN = "INTERRUPTED_VERIFICATION_UNKNOWN";

    /** 活跃记录的字段组合自相矛盾，无法可靠推断执行阶段。 */
    public static final String STATE_INCONSISTENT = "INTERRUPTED_STATE_INCONSISTENT";

    private RecoveryCodes() {
    }

    /**
     * 该中断原因是否可能已产生外部副作用。只有动作确定未开始的情况可以不经过
     * 人工核查直接重新发起处置。
     */
    public static boolean requiresManualExternalReview(String code) {
        // 原因码为空说明记录状态本身异常，按“需要核查”处理，不能放行盲目重试。
        return !BEFORE_ACTION.equals(code);
    }
}

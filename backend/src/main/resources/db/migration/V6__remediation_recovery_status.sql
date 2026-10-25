-- ============================================================
-- V6: Day 8 启动恢复终态
-- 只扩展状态约束，不新增列、索引，也不回填历史数据。
-- RECOVERY_REQUIRED 表示进程中断后由启动恢复关闭的终态，需要人工核查；
-- 它不代表动作失败，也不代表恢复成功，系统不会自动重试外部副作用操作。
-- ============================================================

ALTER TABLE remediation_action
    DROP CONSTRAINT ck_remediation_status;

ALTER TABLE remediation_action
    ADD CONSTRAINT ck_remediation_status CHECK (
        status IN (
            'PENDING', 'EXECUTING', 'SUCCEEDED',
            'ACTION_FAILED', 'VERIFICATION_FAILED',
            'VERIFICATION_INCONCLUSIVE', 'RECOVERY_REQUIRED'
        )
    );

COMMENT ON COLUMN remediation_action.status IS
    '处置状态；RECOVERY_REQUIRED 表示进程中断后由启动恢复关闭的终态，需人工核查，系统不会自动重试';

COMMENT ON COLUMN remediation_action.failure_code IS
    '失败或中断原因码；status=RECOVERY_REQUIRED 时为 INTERRUPTED_*';

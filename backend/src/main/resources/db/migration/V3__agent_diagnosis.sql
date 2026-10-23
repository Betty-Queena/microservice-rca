-- ============================================================
-- V3: LLM Agent 诊断任务扩展
-- 不修改已执行的 V1/V2；任务执行状态与诊断结果状态分离。
-- ============================================================

ALTER TABLE diagnosis_task
    ADD COLUMN result_status     VARCHAR(32),
    ADD COLUMN confidence_level  VARCHAR(16),
    ADD COLUMN failure_reason    VARCHAR(1024),
    ADD COLUMN report            JSONB,
    ADD COLUMN agent_input       JSONB,
    ADD COLUMN decision_trace    JSONB,
    ADD COLUMN model_name        VARCHAR(128),
    ADD COLUMN started_at        TIMESTAMPTZ;

ALTER TABLE diagnosis_task
    ADD CONSTRAINT ck_diagnosis_task_result_status
        CHECK (result_status IS NULL OR result_status IN ('ROOT_CAUSE_IDENTIFIED', 'INSUFFICIENT_EVIDENCE')),
    ADD CONSTRAINT ck_diagnosis_task_confidence_level
        CHECK (confidence_level IS NULL OR confidence_level IN ('HIGH', 'MEDIUM', 'LOW'));

CREATE INDEX idx_diagnosis_task_result_status ON diagnosis_task (result_status);
CREATE INDEX idx_diagnosis_task_created_at ON diagnosis_task (created_at DESC);

COMMENT ON COLUMN diagnosis_task.result_status IS
    '诊断结果状态：ROOT_CAUSE_IDENTIFIED / INSUFFICIENT_EVIDENCE；与任务执行状态分离';
COMMENT ON COLUMN diagnosis_task.confidence_level IS
    '定性置信等级 HIGH / MEDIUM / LOW，不使用未校准的精确概率';
COMMENT ON COLUMN diagnosis_task.failure_reason IS
    '模型调用失败、超时、校验失败等可审计原因；不保存模型原始思维链';
COMMENT ON COLUMN diagnosis_task.report IS
    '经过证据 ID 校验的结构化诊断报告或证据不足说明';
COMMENT ON COLUMN diagnosis_task.agent_input IS
    'Agent 白名单输入快照，不包含 experiment_fault_type / experiment_parameters';
COMMENT ON COLUMN diagnosis_task.decision_trace IS
    '模型决策摘要、工具执行结果、耗时与错误的时间线';
COMMENT ON COLUMN diagnosis_task.model_name IS
    '实际调用的模型名称，不记录 API Key';
COMMENT ON COLUMN diagnosis_task.started_at IS
    'Agent 开始执行时间';

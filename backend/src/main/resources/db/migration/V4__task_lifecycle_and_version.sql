-- ============================================================
-- V4: Agent 任务生命周期约束与乐观锁
-- 不修改已执行的 V1-V3；不删除、不回填业务数据。
-- 历史行的 version 统一从 0 开始，后续由 JPA @Version 维护。
-- ============================================================

ALTER TABLE fault_event
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE diagnosis_task
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN fault_event.version IS
    '乐观锁版本号，由 JPA @Version 维护；条件更新时手工递增';
COMMENT ON COLUMN diagnosis_task.version IS
    '乐观锁版本号，由 JPA @Version 维护';

-- 同一故障事件同一时间最多一个活跃 Agent 任务。
-- 条件必须与 DiagnosisStatus / triggerSource 的实际取值一致：
--   status: PENDING, RUNNING
--   trigger_source: AGENT, AGENT_RETRY
-- MANUAL_EVIDENCE 不在索引范围内，因此手工证据采集可以与 AI 诊断并行。
CREATE UNIQUE INDEX uk_diagnosis_task_active_agent
    ON diagnosis_task (fault_event_id)
    WHERE status IN ('PENDING', 'RUNNING')
      AND trigger_source IN ('AGENT', 'AGENT_RETRY');

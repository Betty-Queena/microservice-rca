-- ============================================================
-- V2: 故障事件与故障注入实验、实际观测结果关联
-- 不修改已执行的 V1；所有新增字段可空或有默认值。
-- ============================================================

ALTER TABLE fault_event
    ADD COLUMN experiment_id          UUID,
    ADD COLUMN experiment_fault_type  VARCHAR(64),
    ADD COLUMN experiment_parameters  JSONB,
    ADD COLUMN observed_result        JSONB,
    ADD COLUMN request_id             VARCHAR(64),
    ADD COLUMN source                 VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN evidence               JSONB,
    ADD COLUMN evidence_collected_at  TIMESTAMPTZ,
    ADD COLUMN updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN resolved_at            TIMESTAMPTZ;

ALTER TABLE fault_event
    ADD CONSTRAINT ck_fault_event_source CHECK (source IN ('MANUAL', 'EXPERIMENT'));

CREATE INDEX idx_fault_event_request_id ON fault_event (request_id);
CREATE INDEX idx_fault_event_experiment_id ON fault_event (experiment_id);
CREATE INDEX idx_fault_event_occurred_at ON fault_event (occurred_at DESC);

COMMENT ON COLUMN fault_event.experiment_fault_type IS
    '实验预设故障类型，仅用于实验设计对照，不作为根因结论';
COMMENT ON COLUMN fault_event.observed_result IS
    '真实请求观测结果，由订单服务 HTTP 响应与耗时生成';
COMMENT ON COLUMN fault_event.evidence IS
    'query_logs / query_metrics 工具采集的真实证据快照';

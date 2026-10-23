-- ============================================================
-- V1: 故障诊断系统最小数据模型
-- Day 1 仅创建基础表，事件上报与诊断流程在后续迭代实现。
-- ============================================================

CREATE TABLE fault_event (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_name VARCHAR(128) NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    severity     VARCHAR(16)  NOT NULL,
    summary      VARCHAR(512) NOT NULL,
    details      JSONB,
    status       VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    occurred_at  TIMESTAMPTZ  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_fault_event_severity CHECK (severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL')),
    CONSTRAINT ck_fault_event_status CHECK (status IN ('OPEN', 'DIAGNOSING', 'RESOLVED', 'CLOSED'))
);

CREATE INDEX idx_fault_event_service_time ON fault_event (service_name, occurred_at DESC);
CREATE INDEX idx_fault_event_status ON fault_event (status);

CREATE TABLE diagnosis_task (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fault_event_id UUID         NOT NULL REFERENCES fault_event (id) ON DELETE CASCADE,
    status         VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    trigger_source VARCHAR(64)  NOT NULL DEFAULT 'MANUAL',
    goal           VARCHAR(512) NOT NULL,
    conclusion     VARCHAR(2048),
    root_cause     VARCHAR(1024),
    confidence     NUMERIC(4, 3),
    created_by     VARCHAR(128),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT ck_diagnosis_task_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_diagnosis_task_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX idx_diagnosis_task_event ON diagnosis_task (fault_event_id);
CREATE INDEX idx_diagnosis_task_status ON diagnosis_task (status);

CREATE TABLE tool_invocation (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagnosis_task_id UUID         NOT NULL REFERENCES diagnosis_task (id) ON DELETE CASCADE,
    tool_name         VARCHAR(128) NOT NULL,
    input_payload     JSONB,
    output_payload    JSONB,
    status            VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    latency_ms        INTEGER,
    error_message     VARCHAR(1024),
    invoked_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sequence_no       INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT ck_tool_invocation_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_tool_invocation_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX idx_tool_invocation_task ON tool_invocation (diagnosis_task_id, sequence_no);
CREATE INDEX idx_tool_invocation_tool ON tool_invocation (tool_name);

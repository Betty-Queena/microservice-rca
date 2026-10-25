-- ============================================================
-- V5: Day 8 受控处置记录
-- 不修改 V1-V4；表只记录后端白名单动作的执行与真实恢复验证结果。
-- 处置动作和恢复验证结果分别保存在 action_result / verification_result。
-- ============================================================

CREATE TABLE remediation_action (
    id                         UUID PRIMARY KEY,
    fault_event_id             UUID         NOT NULL REFERENCES fault_event (id),
    diagnosis_task_id          UUID         NOT NULL REFERENCES diagnosis_task (id),
    action_type                VARCHAR(64)  NOT NULL,
    target_service             VARCHAR(128) NOT NULL,
    experiment_id              UUID         NOT NULL,
    status                     VARCHAR(32)  NOT NULL,
    failure_code               VARCHAR(64),
    failure_reason             VARCHAR(1024),
    requested_by               VARCHAR(128) NOT NULL,
    operator_note              VARCHAR(512),
    idempotency_key            VARCHAR(64),
    action_result              JSONB,
    verification_result        JSONB,
    audit_trace                JSONB,
    verification_request_id    VARCHAR(64),
    verification_fault_event_id UUID REFERENCES fault_event (id),
    event_status_updated       BOOLEAN      NOT NULL DEFAULT FALSE,
    requested_at               TIMESTAMPTZ  NOT NULL,
    started_at                 TIMESTAMPTZ,
    completed_at               TIMESTAMPTZ,
    version                    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_remediation_status CHECK (
        status IN ('PENDING', 'EXECUTING', 'SUCCEEDED',
                   'ACTION_FAILED', 'VERIFICATION_FAILED', 'VERIFICATION_INCONCLUSIVE')
    ),
    CONSTRAINT ck_remediation_action_type CHECK (
        action_type IN ('DISABLE_EXPERIMENT_FAULT')
    )
);

-- 同一事件同一时间最多一条活跃处置；终态记录不受此约束。
CREATE UNIQUE INDEX uk_remediation_active_event
    ON remediation_action (fault_event_id)
    WHERE status IN ('PENDING', 'EXECUTING');

-- 同一事件下幂等键唯一；幂等键为空时允许多条记录。
CREATE UNIQUE INDEX uk_remediation_idempotency
    ON remediation_action (fault_event_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_remediation_event_requested
    ON remediation_action (fault_event_id, requested_at DESC);

CREATE INDEX idx_remediation_status_requested
    ON remediation_action (status, requested_at);

COMMENT ON TABLE remediation_action IS
    '人工确认后的受控处置记录；动作由后端白名单定义，不执行 LLM 生成的命令';

COMMENT ON COLUMN remediation_action.action_type IS
    '白名单动作类型，当前仅 DISABLE_EXPERIMENT_FAULT';

COMMENT ON COLUMN remediation_action.target_service IS
    '动作目标服务，由服务端动作定义写入，不接受请求参数';

COMMENT ON COLUMN remediation_action.experiment_id IS
    '从 fault_event.experiment_id 复制的处置目标快照';

COMMENT ON COLUMN remediation_action.action_result IS
    '动作执行结果，例如处置前后 experimentId、故障类型和 active 状态';

COMMENT ON COLUMN remediation_action.verification_result IS
    '恢复验证结果，例如 HTTP 状态、耗时、outcome、faultObserved 和验证请求 ID';

COMMENT ON COLUMN remediation_action.audit_trace IS
    '操作与验证关键步骤时间线，不包含密钥、请求头或完整响应原文';

COMMENT ON COLUMN remediation_action.verification_request_id IS
    '恢复验证真实结算请求的 requestId';

COMMENT ON COLUMN remediation_action.verification_fault_event_id IS
    '恢复验证再次观察到故障时新建的故障事件 ID';

COMMENT ON COLUMN remediation_action.event_status_updated IS
    '验证通过后是否成功把故障事件条件更新为 RESOLVED';

COMMENT ON COLUMN remediation_action.version IS
    '乐观锁版本号，由 JPA @Version 维护';

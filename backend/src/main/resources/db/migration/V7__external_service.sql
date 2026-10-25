-- ============================================================
-- V7: 外部 Spring Boot 服务接入
-- 不修改已执行的 V1-V6；新增表与可空外键，历史数据不受影响。
-- 外部服务第一版只用于只读证据采集和诊断，不提供任何控制接口。
-- ============================================================

CREATE TABLE external_service (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(64)  NOT NULL,
    base_url            VARCHAR(512) NOT NULL,
    health_path         VARCHAR(256) NOT NULL DEFAULT '/actuator/health',
    metrics_path        VARCHAR(256),
    log_source_url      VARCHAR(512),
    description         VARCHAR(512),
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    last_check_at       TIMESTAMPTZ,
    last_check_status   VARCHAR(32),
    last_check_message  VARCHAR(512),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_external_service_name
        CHECK (name ~ '^[A-Za-z0-9._-]{2,64}$'),
    CONSTRAINT ck_external_service_base_url
        CHECK (base_url ~ '^https?://')
);

-- 名称按大小写不敏感唯一，避免 Agent 服务目录出现大小写歧义。
CREATE UNIQUE INDEX uk_external_service_name_lower
    ON external_service (lower(name));

COMMENT ON TABLE external_service IS
    '已注册的第三方 Spring Boot 服务；第一版仅用于只读证据采集与诊断';
COMMENT ON COLUMN external_service.base_url IS
    '服务基地址，仅允许 http/https、白名单主机和端口';
COMMENT ON COLUMN external_service.log_source_url IS
    '可选的只读日志查询地址；未配置时日志证据明确记为不可用';
COMMENT ON COLUMN external_service.last_check_status IS
    '最近一次连通性检查结果：UP/DOWN/UNREACHABLE/UNKNOWN';

ALTER TABLE fault_event
    ADD COLUMN external_service_id UUID;

ALTER TABLE fault_event
    ADD CONSTRAINT fk_fault_event_external_service
        FOREIGN KEY (external_service_id) REFERENCES external_service (id);

ALTER TABLE fault_event
    DROP CONSTRAINT ck_fault_event_source;

ALTER TABLE fault_event
    ADD CONSTRAINT ck_fault_event_source
        CHECK (source IN ('MANUAL', 'EXPERIMENT', 'EXTERNAL'));

-- 外部事件必须关联已注册服务；实验/人工事件不受影响。
ALTER TABLE fault_event
    ADD CONSTRAINT ck_fault_event_external_ref
        CHECK (source <> 'EXTERNAL' OR external_service_id IS NOT NULL);

CREATE INDEX idx_fault_event_external_service
    ON fault_event (external_service_id);

COMMENT ON COLUMN fault_event.external_service_id IS
    'source=EXTERNAL 时关联的外部服务；外部诊断不使用实验预设字段';

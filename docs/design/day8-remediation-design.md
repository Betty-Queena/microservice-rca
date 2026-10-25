# Day 8 受控处置与恢复验证设计

> 状态：设计稿，待确认。本文只描述设计，不代表已经实现。
>
> 范围：仅面向本地实验环境。处置动作由后端白名单定义，不执行 LLM 生成的 Shell、
> SQL、Docker 命令或任意 HTTP 请求。

## 1. 现状核查

### 1.1 已具备的能力

- 实验故障注入：inventory-service 进程内维护单一 active 故障，支持 `DELAY` 和
  `HTTP_500`，有随机 `experimentId`、默认 120 秒有效期和最大 600 秒限制。
- 实验请求：backend 通过 `POST /api/experiments/{experimentId}/execute` 发起真实
  订单结算请求，并将真实响应分类为 `SUCCESS`、`SLOW_RESPONSE`、
  `DOWNSTREAM_HTTP_5XX` 等结果。
- 故障事件：实验结果出现故障时自动创建 `fault_event`，保存 `experimentId`、
  预设故障类型、实际观测结果、`requestId` 和 `details`（含 `sku`、`quantity`）。
- Agent 诊断：诊断任务状态为 `PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED`，
  诊断结果为 `ROOT_CAUSE_IDENTIFIED` 或 `INSUFFICIENT_EVIDENCE`；报告包含
  `rootCause`、证据 ID、置信度、`recommendedActions` 等字段。
- 事件状态机：`OPEN -> RESOLVED/CLOSED`，`DIAGNOSING -> RESOLVED/CLOSED`，
  `RESOLVED -> CLOSED`，`CLOSED` 不可再流转。
- 前端已展示故障事件、观测结果、Agent 报告和“建议处置（人工确认后执行）”，但
  `recommendedActions` 只作为文本展示。
- Flyway 迁移已到 V4，下一次迁移为 V5。

### 1.2 与 Day 8 设想的差异和缺口

1. **没有真正可执行的处置动作**
   - Agent 报告里的 `recommendedActions` 是自由文本列表，没有 actionType、目标
     experimentId 或执行参数，不能也不应该直接解析成命令。
   - 现有前端只能“标记已解决/关闭事件”，只改数据库状态，没有恢复验证。

2. **现有故障关闭接口不能按 experimentId 条件关闭**
   - `backend/.../experiment/ExperimentController.java:40-43` 的
     `POST /api/experiments/faults/disable` 没有请求体。
   - `backend/.../experiment/ExperimentService.java:74-76` 直接代理 inventory 的
     无参数关闭接口。
   - `services/inventory-service/.../fault/FaultInjectionService.java:62-64` 的
     `disable()` 直接 `active.set(null)`，没有 compare-and-set，也没有校验
     `expectedExperimentId`。
   - 因此如果直接复用该接口做处置，可能把另一个并发实验的故障也关闭。

3. **诊断不会自动触发**
   - 现有流程是：实验执行产生事件后，由人工点击“启动AI诊断”。Day 8 沿用这个
     事实，不把诊断改成自动触发。

4. **没有处置记录、恢复验证和审计表**
   - V1-V4 中没有 remediation 相关表；事件详情只返回事件和诊断任务。

5. **没有入站鉴权**
   - 本次核查的 backend 代码和 Day 7 评测文档均确认当前没有入站鉴权过滤器。
   - 处置接口不能把 `requestedBy` 当作已认证身份，只能作为审计备注。
   - 安全边界只能是“本地环境 + 服务端状态校验 + 显式 confirmed + 白名单动作”。

6. **库存会永久扣减**
   - `InventoryStore.reserve` 成功后会扣减库存；重复恢复验证可能耗尽库存，随后
     返回 409 `BUSINESS_CONFLICT`（`faultObserved=false`）。
   - 这种情况既不能当作故障仍在，也不能当作恢复成功，必须单独标记为“验证不确定”。

## 2. 推荐的处置闭环

```text
用户注入故障
  -> 执行实验，产生 fault_event（现有能力）
  -> 用户启动 AI 诊断（现有能力）
  -> 根因报告生成，事件恢复为 OPEN（现有能力）
  -> 前端展示根因、证据和“受控处置”卡片
  -> 用户阅读风险提示并显式确认
  -> POST /api/fault-events/{eventId}/remediations
  -> 后端校验事件、诊断、目标实验和并发状态
  -> 后端条件关闭 event.experimentId 对应的实验故障
  -> 后端用事件 details 中的 sku/quantity 发起真实结算请求
  -> 记录处置结果、验证结果和审计轨迹
  -> 验证通过时条件更新事件为 RESOLVED
  -> 前端刷新事件状态并展示处置历史和验证证据
```

关键约束：

- 用户确认是执行的必要条件，诊断完成不会自动触发处置。
- 请求只提交 `actionType`，不提交任意命令、URL、SQL 或实验 ID；实验 ID 从事件
  读取，避免客户端或模型篡改目标。
- 目前只支持一个动作：`DISABLE_EXPERIMENT_FAULT`（关闭本次实验故障注入）。
- 处置动作可以被理解为“实验环境的模拟恢复”，不是生产环境的自动修复。

## 3. 处置状态机

### 3.1 状态定义

| 状态 | 含义 | 是否终态 |
| --- | --- | --- |
| `PENDING` | 已受理，审计记录已创建，尚未开始执行 | 否 |
| `EXECUTING` | 已抢占执行权，正在关闭故障或做恢复验证 | 否 |
| `SUCCEEDED` | 故障已关闭，且真实结算请求验证通过 | 是 |
| `ACTION_FAILED` | 目标校验、条件关闭或执行前状态检查失败 | 是 |
| `VERIFICATION_FAILED` | 动作已执行，但真实验证仍观察到故障 | 是 |
| `VERIFICATION_INCONCLUSIVE` | 动作已执行，但验证条件不满足，无法证明恢复 | 是 |
| `RECOVERY_REQUIRED` | 进程中断后由启动恢复关闭；动作或验证结果不完整，必须人工核查，不代表动作失败或恢复成功 | 是 |

### 3.2 状态转换

| 当前状态 | 目标状态 | 条件 |
| --- | --- | --- |
| `PENDING` | `EXECUTING` | 通过条件更新抢占成功，且事件仍为 OPEN、诊断仍为成功根因 |
| `PENDING` | `ACTION_FAILED` | 抢占时发现事件/诊断状态已变化 |
| `EXECUTING` | `SUCCEEDED` | 故障关闭成功或已经 inactive，且恢复验证为 PASS |
| `EXECUTING` | `ACTION_FAILED` | 实验 ID 不匹配、关闭接口 409、关闭后仍 active、事件状态变化 |
| `EXECUTING` | `VERIFICATION_FAILED` | 验证请求仍返回 `faultObserved=true`、5xx、超时或无法连接 |
| `EXECUTING` | `VERIFICATION_INCONCLUSIVE` | 业务冲突、请求被拒、缺少 sku/quantity 等，无法证明故障是否解除 |
| `PENDING` / `EXECUTING` | `RECOVERY_REQUIRED` | 启动恢复发现本次启动前遗留的活跃记录；使用 `id + status + version` 条件更新 |

启动恢复不重新执行任何外部操作。它只根据已经落库的字段推断中断阶段，并用
`failureCode` 区分：

| failureCode | 含义 | actionState / verificationState |
| --- | --- | --- |
| `INTERRUPTED_BEFORE_ACTION` | PENDING，动作确定尚未开始 | `NOT_STARTED` / `NOT_STARTED` |
| `INTERRUPTED_ACTION_UNKNOWN` | EXECUTING，`action_result` 未落库，无法确认关闭接口是否已调用 | `UNKNOWN` / `NOT_STARTED` |
| `INTERRUPTED_ACTION_RESULT_UNREADABLE` | EXECUTING，`action_result` 存在但无法读取 `success` | `UNKNOWN` / `NOT_STARTED` |
| `INTERRUPTED_ACTION_FAILED` | EXECUTING，`action_result` 明确记录 `success=false`，验证未执行 | `FAILED` / `NOT_STARTED` |
| `INTERRUPTED_VERIFICATION_UNKNOWN` | EXECUTING，动作成功但验证结果未落库，验证可能已执行 | `SUCCEEDED` / `UNKNOWN` |
| `INTERRUPTED_STATE_INCONSISTENT` | 活跃记录字段组合自相矛盾，无法可靠推断执行阶段 | `UNKNOWN` / `UNKNOWN` |

`RECOVERY_REQUIRED` 不进入 `uk_remediation_active_event` 的部分唯一索引。因此它不会
永久阻止后续处置，但除 `INTERRUPTED_BEFORE_ACTION` 外，新的处置必须显式确认已人工
核查外部状态，并填写非空 `operatorNote`，否则服务端拒绝创建。

终态不可再变；失败后如果需要重试，必须新建一条 remediation 记录。这样历史审计
不会被覆盖。

### 3.3 与 fault_event 状态的关系

- 处置前要求事件为 `OPEN`；`DIAGNOSING`、`RESOLVED`、`CLOSED` 均拒绝。
- 处置过程不把事件改为 `DIAGNOSING`，也不新增事件状态。
- 只有 `SUCCEEDED` 才尝试把事件从 `OPEN` 条件更新为 `RESOLVED`。
- 如果验证通过时事件已被人工关闭或已解决，保留人工状态，只记录
  `eventStatusUpdated=false` 和告警，不覆盖。
- `ACTION_FAILED`、`VERIFICATION_FAILED`、`VERIFICATION_INCONCLUSIVE` 均不修改
  事件状态。
- `RECOVERY_REQUIRED` 也不修改事件状态；启动恢复不会调用 Agent、关闭接口或验证
  请求，不会把事件标记为 `RESOLVED`。

## 4. API 设计

### 4.1 创建并执行处置

`POST /api/fault-events/{eventId}/remediations`

请求体：

```json
{
  "actionType": "DISABLE_EXPERIMENT_FAULT",
  "diagnosisTaskId": "7f0c2f65-6d5f-4d1b-9c0e-2f5f7f7a8b11",
  "confirmed": true,
  "idempotencyKey": "3d6f1e2a-9b4c-4f0e-8a71-5c2d9e0b1f23",
  "requestedBy": "local-operator",
  "operatorNote": "人工确认关闭本次 HTTP_500 实验",
  "recoveryAcknowledged": false
}
```

字段约束：

- `actionType`：必填，当前只允许 `DISABLE_EXPERIMENT_FAULT`；其他值返回 400。
- `diagnosisTaskId`：必填，必须属于该事件，且状态为
  `SUCCEEDED + ROOT_CAUSE_IDENTIFIED`。
- `confirmed`：必须显式为 `true`；缺失或 false 返回 400。
- `idempotencyKey`：可选，最长 64；相同事件下相同 key 的重放返回已有记录，不再次
  执行。前端每次确认弹窗生成一个 UUID。
- `requestedBy`：可选，最长 128；仅用于审计，不是认证身份；为空时后端写
  `local-operator`。
- `operatorNote`：可选，最长 512；不得作为命令或参数使用。若前次处置为需要人工
  核查的 `RECOVERY_REQUIRED`，该字段必须非空。
- `recoveryAcknowledged`：可选布尔。仅当该事件最新处置为 `RECOVERY_REQUIRED` 且
  `failureCode` 不是 `INTERRUPTED_BEFORE_ACTION` 时，必须显式为 `true`，否则返回
  409；为 `true` 时仍必须提供非空 `operatorNote`，否则返回 400。它只表示操作者
  声明已核查外部状态，不是身份认证，也不能替代订单服务的幂等能力。
  `INTERRUPTED_BEFORE_ACTION` 表示动作确定未开始，可直接新建处置。
- 相同 `idempotencyKey` 的重放始终返回已有记录，不受 `recoveryAcknowledged` 影响，
  也不会重新执行任何外部操作。

明确禁止：请求 DTO 不包含 `command`、`shell`、`sql`、`url`、`experimentId`、
`delayMs`、`faultType` 等可执行参数。实验 ID 只能从事件读取。

成功响应（HTTP 200，`ApiResponse<RemediationView>`）：

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "id": "f2c9a2d0-1f3e-4a6b-8c7d-9e0f1a2b3c4d",
    "faultEventId": "1d4a8c10-11c2-4f8e-9a7b-2c3d4e5f6a7b",
    "diagnosisTaskId": "7f0c2f65-6d5f-4d1b-9c0e-2f5f7f7a8b11",
    "actionType": "DISABLE_EXPERIMENT_FAULT",
    "targetService": "inventory-service",
    "experimentId": "e8c1b2a3-4d5e-4f60-8a71-9b2c3d4e5f60",
    "status": "SUCCEEDED",
    "failureCode": null,
    "failureReason": null,
    "requestedBy": "local-operator",
    "operatorNote": "人工确认关闭本次 HTTP_500 实验",
    "actionResult": {
      "outcome": "DISABLED",
      "observedExperimentIdBefore": "e8c1b2a3-4d5e-4f60-8a71-9b2c3d4e5f60",
      "observedFaultTypeBefore": "HTTP_500",
      "activeAfter": false
    },
    "verificationResult": {
      "verified": true,
      "criteriaVersion": "day8-v1",
      "sku": "SKU-1001",
      "quantity": 1,
      "httpStatus": 200,
      "durationMs": 118,
      "outcome": "SUCCESS",
      "faultObserved": false,
      "requestId": "exp-7d1e...",
      "faultEventId": null,
      "slowThresholdMs": 3000,
      "checkedAt": "2026-10-24T20:10:00Z"
    },
    "eventStatusUpdated": true,
    "auditTrace": [
      {"step": "REQUEST_ACCEPTED", "at": "2026-10-24T20:09:58Z"},
      {"step": "PRECONDITIONS_CHECKED", "at": "2026-10-24T20:09:58Z"},
      {"step": "FAULT_DISABLED", "at": "2026-10-24T20:09:59Z"},
      {"step": "VERIFICATION_PASSED", "at": "2026-10-24T20:10:00Z"},
      {"step": "EVENT_RESOLVED", "at": "2026-10-24T20:10:00Z"}
    ],
    "requestedAt": "2026-10-24T20:09:58Z",
    "startedAt": "2026-10-24T20:09:58Z",
    "completedAt": "2026-10-24T20:10:00Z"
  },
  "timestamp": "2026-10-24T20:10:00Z"
}
```

说明：

- 处置被受理后，即使动作失败或验证失败，也返回 HTTP 200，失败信息放在
  `status`、`failureCode`、`failureReason` 和结果 JSON 中。这样审计记录和失败原因
  可以稳定返回，前端不会误以为请求根本没有发生。
- 只有“没有创建审计记录”的请求错误才使用 4xx/5xx。
- `diagnosisTaskId` 只是流程校验，不把报告中的 `recommendedActions` 当作动作参数。
- `experimentId` 从 `fault_event.experiment_id` 读取，不采用请求中的值。

### 4.2 查询处置历史

`GET /api/fault-events/{eventId}/remediations`

返回该事件的处置记录，按 `requestedAt` 倒序。用于前端审计列表和页面刷新。

`GET /api/remediations/{remediationId}`

返回单条处置记录，便于前端轮询或直接定位；只读。

### 4.3 错误处理

| HTTP | 场景 | 处理 |
| --- | --- | --- |
| 400 | `confirmed` 非 true、actionType 缺失/不支持、字段超长 | `ApiResponse.error(400, ...)`，不创建记录 |
| 404 | 事件或诊断任务不存在 | 不创建记录 |
| 409 | 事件不是 OPEN、source 不是 EXPERIMENT、没有 experimentId | 不创建记录 |
| 409 | 诊断任务不属于该事件、不是 `SUCCEEDED + ROOT_CAUSE_IDENTIFIED` | 不创建记录 |
| 409 | 已存在 `PENDING/EXECUTING` 处置 | 不创建记录；前端提示刷新 |
| 409 | idempotencyKey 已存在但 actionType/diagnosisTaskId 不同 | 不创建记录 |
| 409 | 最新处置为 `RECOVERY_REQUIRED`（非 `INTERRUPTED_BEFORE_ACTION`）且未设置 `recoveryAcknowledged=true` | 不创建记录；提示先人工核查外部状态 |
| 400 | 已设置 `recoveryAcknowledged=true` 但 `operatorNote` 为空 | 不创建记录 |
| 409 | 执行前发现实验 ID 已被其他实验替换 | 已创建记录标记 `ACTION_FAILED`，返回 200 |
| 502/503 | backend 无法连接 order-service/inventory-service | 已创建记录标记对应失败，返回 200 |
| 500 | 未预期异常 | 尽量标记 `ACTION_FAILED`；无法落库时保留 `EXECUTING`，由下次启动恢复标记 `RECOVERY_REQUIRED` |

`PENDING/EXECUTING` 记录唯一性由数据库部分唯一索引保证，不能只依赖“先查后写”。

### 4.4 幂等语义

- 同一事件、同一 `idempotencyKey`：
  - actionType 和 diagnosisTaskId 相同：返回已有记录，不重复关闭、不重复验证。
  - 内容不同：返回 409，防止 key 被错误复用。
- 无 `idempotencyKey`：由后端生成一个；相同用户的双击如果复用了同一个 key，仍然
  只会执行一次。
- 并发创建：依赖 `(fault_event_id, idempotency_key)` 唯一索引；捕获唯一约束冲突后
  重新读取已有记录返回。
- 同一事件同时只允许一个活跃处置：依赖
  `UNIQUE (fault_event_id) WHERE status IN ('PENDING','EXECUTING')`。
- 重试必须新建处置记录，不复用失败记录，保证审计历史完整。
- `RECOVERY_REQUIRED` 不进入活跃唯一索引，但相同 `idempotencyKey` 仍返回已有记录，
  不会重新执行；使用新 key 重试时，除 `INTERRUPTED_BEFORE_ACTION` 外必须显式确认
  已人工核查外部状态。
- 启动恢复自身也幂等：`RECOVERY_REQUIRED` 已不再是活跃状态，后续扫描不会重复
  修改；写入使用 `id + expectedStatus + expectedVersion` 条件更新。

## 5. 数据库设计（V5）

新增迁移：`backend/src/main/resources/db/migration/V5__remediation_action.sql`

```sql
CREATE TABLE remediation_action (
    id UUID PRIMARY KEY,
    fault_event_id UUID NOT NULL REFERENCES fault_event (id),
    diagnosis_task_id UUID NOT NULL REFERENCES diagnosis_task (id),
    action_type VARCHAR(64) NOT NULL,
    target_service VARCHAR(128) NOT NULL,
    experiment_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_code VARCHAR(64),
    failure_reason VARCHAR(1024),
    requested_by VARCHAR(128) NOT NULL,
    operator_note VARCHAR(512),
    idempotency_key VARCHAR(64),
    action_result JSONB,
    verification_result JSONB,
    audit_trace JSONB,
    verification_request_id VARCHAR(64),
    verification_fault_event_id UUID REFERENCES fault_event (id),
    event_status_updated BOOLEAN NOT NULL DEFAULT FALSE,
    requested_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_remediation_status CHECK (
        status IN ('PENDING', 'EXECUTING', 'SUCCEEDED',
                   'ACTION_FAILED', 'VERIFICATION_FAILED', 'VERIFICATION_INCONCLUSIVE')
    ),
    CONSTRAINT ck_remediation_action_type CHECK (
        action_type IN ('DISABLE_EXPERIMENT_FAULT')
    )
);

CREATE UNIQUE INDEX uk_remediation_active_event
    ON remediation_action (fault_event_id)
    WHERE status IN ('PENDING', 'EXECUTING');

CREATE UNIQUE INDEX uk_remediation_idempotency
    ON remediation_action (fault_event_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_remediation_event_requested
    ON remediation_action (fault_event_id, requested_at DESC);
```

字段说明：

- `target_service`：由服务端动作定义写入，当前固定为 `inventory-service`，不接受
  请求参数。
- `experiment_id`：从事件复制，作为本次处置目标的不可变审计快照。
- `action_result`：动作阶段结果，包括处置前后实验 ID、故障类型和 active 状态。
- `verification_result`：真实验证请求的 HTTP 状态、耗时、outcome、faultObserved、
  requestId 和新建事件 ID；不保存完整响应体。
- `audit_trace`：按时间排序的关键步骤，不包含密钥、请求头或完整响应原文。
- `verification_fault_event_id`：验证请求如果再次观察到故障，指向新建的故障事件。
- `event_status_updated`：记录验证通过后是否成功条件更新事件状态。
- `version`：JPA `@Version` 乐观锁字段，和现有表保持一致。

注意事项：

- 不新增 `fault_event` 字段，避免影响现有事件 API 和 Day 7 评测。
- JSON 字段沿用现有 `@JdbcTypeCode(SqlTypes.JSON)` + `String` 的写法。
- 表只新增，不修改 V1-V4；Flyway 迁移顺序保持向后兼容。

### 5.1 V6 启动恢复状态扩展

新增迁移：`backend/src/main/resources/db/migration/V6__remediation_recovery_status.sql`

```sql
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
```

- 只扩展状态约束，不新增列、索引，不回填历史数据。
- 复用 `failure_code`、`failure_reason`、`action_result`、`verification_result` 和
  `audit_trace` 表达中断原因与证据，避免新增语义重复字段。
- `RECOVERY_REQUIRED` 仍为终态；恢复不会覆盖原始动作/验证结果，只在
  `audit_trace` 末尾追加 `RECOVERY_MARKED` 条目。

## 6. 安全、幂等与并发

### 6.1 安全约束

- **白名单动作**：`RemediationActionType` 当前只有一个枚举值
  `DISABLE_EXPERIMENT_FAULT`；服务端不接受自由文本动作名。
- **目标不可由客户端指定**：只使用 `fault_event.experiment_id`；请求中没有
  experimentId、命令、URL、SQL、Docker 参数。
- **LLM 不参与执行**：不解析 `recommendedActions`，不把模型输出作为动作或参数；
  模型只负责诊断和展示。
- **显式确认**：`confirmed` 必须为 true；前端还需要二次确认弹窗。
- **服务端状态校验**：事件状态、source、experimentId、诊断任务归属和终态必须
  在创建记录时校验，并在执行前再次校验。
- **本地环境边界**：沿用现有无入站鉴权的限制；不把 backend 暴露到不可信网络。
  `requestedBy` 仅表示审计备注。
- **不自动执行**：诊断完成、事件创建、报告生成都不会触发处置。
- **资源释放**：HTTP 调用使用现有客户端和超时；不新增连接池或线程池。

可选的加固项（需要确认）：

- 增加 `rca.remediation.enabled` 配置开关，默认 false，只有显式打开才允许处置。
- 如果不想增加配置，则至少保留 `confirmed`、白名单、目标校验和本地部署约束。

### 6.2 幂等

- 前端每次确认生成 `idempotencyKey`，双击使用同一个 key。
- 数据库唯一索引是最终防线，不依赖应用层先查后写。
- 已存在记录时返回已有结果，不重复调用关闭接口和验证请求。
- 失败后重试必须新建记录；旧记录保留，便于审计。

### 6.3 并发

- **同一事件并发处置**：部分唯一索引只允许一个 `PENDING/EXECUTING` 记录；第二个
  请求返回 409。
- **实验被替换**：执行时重新读取当前实验状态；如果 active 的 experimentId 与事件
  不一致，标记 `ACTION_FAILED`，不关闭新实验。
- **条件关闭**：inventory 的关闭接口增加 `expectedExperimentId`，使用
  `compareAndSet`；不匹配返回 409，避免误关其他实验。
- **事件状态并发**：仅在验证通过后，用事件当前版本和 `OPEN` 状态条件更新为
  `RESOLVED`；如果人工已关闭/解决，则不覆盖并记录告警。
- **诊断重试并发**：执行前再次检查事件是否仍为 OPEN，且不存在活跃 Agent 任务；
  不满足则 `ACTION_FAILED`。
- **事务边界**：不在数据库事务中执行 HTTP 调用。创建记录、抢占执行权、写入终态
  分别使用短事务；HTTP 阶段不持有数据库锁。
- **为什么不需要全局实验锁**：目标实验 ID 条件关闭已经能阻止误关其他实验；并发
  新实验最多导致验证失败，不会导致“错误地标记为已恢复”。

### 6.4 现有 disable 接口的兼容改造

为保持 Day 7 和现有前端兼容，采用“可选请求体”：

```http
POST /api/experiments/faults/disable
Content-Type: application/json

{"expectedExperimentId": "e8c1b2a3-4d5e-4f60-8a71-9b2c3d4e5f60"}
```

- 有 `expectedExperimentId`：条件关闭；不匹配返回 409。
- 无请求体：保持原来的无条件关闭行为，供现有前端和 Day 7 runner 使用。
- inventory 内部保留无参数 `disable()`，新增
  `disable(String expectedExperimentId)`；无参数版本继续调用无条件逻辑。
- backend `ExperimentService` 增加
  `disable(UUID expectedExperimentId)`；原 `disable()` 保持兼容。
- 处置流程只调用带 expectedExperimentId 的版本。

### 6.5 启动恢复与单实例限制

- 配置项：`rca.remediation.recovery.enabled`，默认 `true`；环境变量
  `RCA_REMEDIATION_RECOVERY_ENABLED`。
- 触发时机：`ApplicationReadyEvent`。组件构造时记录 `startupCutoff`，扫描条件严格为
  `started_at < startupCutoff`（PENDING 使用 `requested_at < startupCutoff`），因此
  本次启动期间新建的记录不会被误标记。
- 恢复动作只读取处置记录并条件写入 `RECOVERY_REQUIRED`；不依赖
  `RemediationExecutor`、`ExperimentService`、Agent 或 `FaultEventService`，不会
  关闭故障、执行 checkout、调用 Agent 或修改事件状态。
- 单实例假设：V5/V6 没有 owner、instance、heartbeat 字段，无法判断活跃记录是否仍在
  另一个后端实例执行。启动恢复只支持单后端实例连接同一数据库；多实例部署必须关闭
  该恢复任务，或另行设计任务所有权机制。状态 + 版本 CAS 只能防止并发覆盖，不能
  识别存活实例。
- 失败隔离：查询失败记录日志后不阻止应用启动；单条记录更新失败不影响其他记录。
  恢复没有自动重试，失败记录留给人工核查。

## 7. 恢复验证判定

### 7.1 验证输入

- `sku`、`quantity` 从 `fault_event.details` 读取，和产生该故障事件时使用的业务
  请求一致。
- 不从请求参数覆盖，避免用户用另一个 SKU 绕过验证。
- 如果 `details` 缺少 sku/quantity，直接 `VERIFICATION_INCONCLUSIVE`，
  failureCode=`VERIFICATION_INPUT_MISSING`。
- 验证请求固定调用现有 `POST /api/experiments/checkout`（backend 内部走真实
  order-service -> inventory-service），不使用模拟状态或数据库改状态代替。

### 7.2 PASS

同时满足：

- HTTP 状态为 2xx；
- `outcome = SUCCESS`；
- `faultObserved = false`；
- `faultEventId` 为空；
- 未发生超时/连接异常。

`ObservationClassifier` 已将超过 `slowResponseThresholdMs`（默认 3000ms）的 2xx
归为 `SLOW_RESPONSE`，因此 `outcome=SUCCESS` 已经隐含耗时低于慢响应阈值。

PASS 后：

- `remediation.status = SUCCEEDED`；
- 尝试把事件从 OPEN 条件更新为 RESOLVED；
- 记录 `verificationResult.verified=true` 和验证请求 ID。

### 7.3 FAIL

出现以下任一情况：

- `faultObserved = true`（包括 `SLOW_RESPONSE`、`DOWNSTREAM_HTTP_5XX`、
  `DOWNSTREAM_TIMEOUT`、`ORDER_SERVICE_UNREACHABLE` 等）；
- HTTP 状态为 5xx；
- 验证请求超时、连接失败或无法获得真实业务响应；
- 验证请求过程中创建了新的故障事件。

结果：

- `remediation.status = VERIFICATION_FAILED`；
- 不修改事件状态；
- 将新建的故障事件 ID 写入 `verification_fault_event_id`；
- failureCode 例如 `FAULT_STILL_OBSERVED`、`ORDER_SERVICE_UNREACHABLE`。

### 7.4 INCONCLUSIVE

出现以下任一情况：

- HTTP 409 `BUSINESS_CONFLICT`，典型原因是库存耗尽；
- HTTP 4xx `REQUEST_REJECTED`，例如参数或业务规则拒绝；
- `faultObserved=false` 但 outcome 不是 SUCCESS；
- 缺少 sku/quantity；
- 验证响应缺少必要字段。

结果：

- `remediation.status = VERIFICATION_INCONCLUSIVE`；
- 不修改事件状态；
- 不把“没有观察到系统故障”误写成“恢复已验证”；
- failureCode 例如 `BUSINESS_REJECTED`、`VERIFICATION_INPUT_MISSING`；
- 前端提示“需要人工判断或补充库存后再验证”。

库存耗尽是已知风险：默认 SKU-1001 有 50 件，重复演示最终可能耗尽；这会被明确
记录为 INCONCLUSIVE，而不是成功。

## 8. 前端交互方案

### 8.1 事件详情页新增“处置与验证”

在 `frontend/src/views/FaultEventDetailView.vue` 增加一个新的 tab（例如
`name="remediation"`，标题“处置与验证”），不新建路由。页面加载时并行请求：

- `GET /api/fault-events/{eventId}/remediations`
- 现有事件详情和诊断任务接口不变。

### 8.2 可处置状态

前端只做展示层判断，最终以后端校验为准：

- 事件 `source === 'EXPERIMENT'` 且 `experimentId` 存在；
- 事件状态为 `OPEN`；
- 存在 `SUCCEEDED + ROOT_CAUSE_IDENTIFIED` 的诊断任务；
- 当前没有 `PENDING/EXECUTING` 处置记录。

不满足时显示原因，例如“需先完成 AI 根因诊断”“事件已解决/关闭”“已有处置正在执行”。

### 8.3 确认弹窗

按钮文案：“确认处置：关闭本次实验故障注入”。

弹窗必须展示：

- 事件摘要和目标事件 ID；
- 本次 `experimentId`；
- 动作说明：“仅关闭该 experimentId 对应的实验故障注入”；
- 明确提示：“不会执行 Shell、SQL、Docker 或任意 HTTP；不会自动修复生产环境”；
- 验证方式：“关闭后用原业务 SKU 发起一次真实结算请求”；
- 取消/确认两个操作；确认后才发送 POST。

### 8.4 结果展示

- 处置中：按钮禁用，显示“执行中”，防止重复点击。
- 终态卡片：
  - `SUCCEEDED`：绿色“故障已关闭，恢复验证通过”，展示 HTTP 状态、耗时、outcome、
    `faultObserved=false`；
  - `ACTION_FAILED`：红色“处置执行失败”，展示 failureCode/failureReason；
  - `VERIFICATION_FAILED`：红色“验证仍观察到故障”，展示验证请求和新建事件链接；
  - `VERIFICATION_INCONCLUSIVE`：黄色“验证不确定”，说明库存/业务冲突等原因。
- 审计历史表格：状态、动作、实验 ID、操作人备注、请求时间、完成时间、
  failureCode、验证请求 ID。
- 完成后刷新事件详情和处置历史；如果事件已变 RESOLVED，顶部状态同步。

### 8.5 现有“标记已解决”的处理

- 保留现有接口和按钮，避免破坏已有演示和事件管理能力。
- 对 `source=EXPERIMENT` 的事件，在按钮旁增加“人工状态变更（未做真实恢复验证）”
  提示；人工标记不能替代处置验证。
- 不做自动拦截，避免影响已有 Day 7 数据和人工事件管理；但文档和界面必须明确
  区分“人工标记”和“验证通过”。

### 8.6 API 模块

新增 `frontend/src/api/remediations.js`：

```js
createRemediation(eventId, payload)
fetchRemediations(eventId)
fetchRemediation(remediationId)
```

复用现有 Axios 实例 `frontend/src/api/http.js`；处置请求单独设置 30 秒超时，避免
默认 15 秒短于后端可能的验证耗时。

## 9. 预计改动文件

### 9.1 后端新增

```text
backend/src/main/resources/db/migration/V5__remediation_action.sql
backend/src/main/java/com/microservice/rca/remediation/domain/RemediationAction.java
backend/src/main/java/com/microservice/rca/remediation/domain/RemediationActionType.java
backend/src/main/java/com/microservice/rca/remediation/domain/RemediationStatus.java
backend/src/main/java/com/microservice/rca/remediation/repository/RemediationActionRepository.java
backend/src/main/java/com/microservice/rca/remediation/api/CreateRemediationRequest.java
backend/src/main/java/com/microservice/rca/remediation/api/RemediationView.java
backend/src/main/java/com/microservice/rca/remediation/service/RemediationService.java
backend/src/main/java/com/microservice/rca/remediation/service/RemediationExecutor.java
backend/src/main/java/com/microservice/rca/remediation/service/RemediationRecovery.java
backend/src/main/java/com/microservice/rca/remediation/web/RemediationController.java
```

### 9.2 后端修改

```text
backend/src/main/java/com/microservice/rca/experiment/ExperimentController.java
  可选请求体的 disable 接口
backend/src/main/java/com/microservice/rca/experiment/ExperimentService.java
  新增 disable(UUID expectedExperimentId)，保留无参数 disable()
backend/src/main/java/com/microservice/rca/experiment/api/DisableFaultRequest.java
  新增 DTO（或放在 experiment/api 下）
backend/src/main/java/com/microservice/rca/diagnosis/service/FaultEventService.java
  新增 resolveIfOpen(UUID eventId)，条件更新事件状态
```

`FaultEventService.resolveIfOpen` 使用实体读取 + JPA 乐观锁 + 仅允许 OPEN 的方式，
保证不覆盖并发人工状态；不修改现有 `updateStatus` 的公共行为。

### 9.3 inventory-service 修改

```text
services/inventory-service/src/main/java/com/microservice/rca/inventory/fault/FaultInjectionController.java
  接受可选请求体
services/inventory-service/src/main/java/com/microservice/rca/inventory/fault/FaultInjectionService.java
  新增 disable(String expectedExperimentId)，compare-and-set
services/inventory-service/src/main/java/com/microservice/rca/inventory/fault/DisableFaultRequest.java
  新增 DTO
```

### 9.4 前端新增/修改

```text
frontend/src/api/remediations.js                 新增
frontend/src/views/FaultEventDetailView.vue      新增“处置与验证”tab、确认弹窗、历史列表
frontend/src/views/FaultInjectionView.vue        可选：手动关闭时带上当前 experimentId
```

### 9.5 测试

```text
backend/src/test/java/com/microservice/rca/remediation/RemediationServiceTest.java
backend/src/test/java/com/microservice/rca/remediation/RemediationRecoveryTest.java
backend/src/test/java/com/microservice/rca/experiment/ExperimentServiceTest.java（或等价单元测试）
services/inventory-service/src/test/java/com/microservice/rca/inventory/fault/FaultInjectionServiceTest.java
backend/src/test/java/com/microservice/rca/db/V5MigrationTest.java（或扩展迁移测试）
```

前端当前没有单元测试基础设施，本轮不新增测试框架；前端交互采用手工验收。

## 10. 分阶段实施与验收

### 阶段 1：条件关闭与数据库基础

内容：

- V5 迁移；
- inventory 条件关闭；
- backend 可选请求体代理；
- `FaultInjectionServiceTest` 增加匹配/不匹配/重复关闭用例；
- 迁移测试。

验收：

- 无请求体的旧关闭接口行为不变；
- 带匹配 expectedExperimentId 关闭成功；
- 带不匹配 ID 返回 409，且原 active 故障仍存在；
- 同一事件只能插入一条活跃 remediation（由数据库约束验证）。

### 阶段 2：处置核心服务

内容：

- 实体、仓库、请求/响应 DTO；
- `RemediationService`：校验、幂等、创建、抢占、动作执行、验证、终态落库；
- `FaultEventService.resolveIfOpen`；
- 控制器和错误映射；
- Mockito 单元测试覆盖 PASS/FAIL/INCONCLUSIVE、ID 不匹配、幂等、并发冲突。

验收：

- 未确认、动作不支持、诊断未完成、事件状态不符均被拒绝；
- 同一事件并发请求只有一个进入执行；
- 相同 idempotencyKey 不重复执行；
- 实验 ID 不匹配不会关闭其他实验；
- 验证通过才把事件置为 RESOLVED；
- 验证失败/不确定时事件保持 OPEN，且记录失败原因。

### 阶段 3：启动恢复与审计查询

内容：

- `RemediationRecovery`；
- V6 状态约束扩展；
- 复用现有 `GET` 历史/单条/幂等键查询接口；
- 恢复单元测试、Repository 集成测试、V6 迁移测试和查询接口测试。

验收：

- 重启后遗留的 PENDING/EXECUTING 记录标记为 `RECOVERY_REQUIRED`，通过
  `INTERRUPTED_*` 原因码区分中断阶段；
- 不自动重试关闭故障、checkout 或 Agent，不修改事件状态，不覆盖原始
  `action_result`、`verification_result` 和已有审计条目；
- 恢复幂等：已终态记录不重复修改，更新使用 `id + expectedStatus + expectedVersion`；
- 除 `INTERRUPTED_BEFORE_ACTION` 外，新建处置必须显式确认人工核查并填写
  `operatorNote`；
- 历史列表、单条查询和幂等键查询能够展示 `RECOVERY_REQUIRED`、`failureCode`、
  `failureReason`、`action_result`、`verification_result` 和 `RECOVERY_MARKED` 审计。

### 阶段 4：前端处置交互

内容：

- `remediations.js`；
- 事件详情页 tab、确认弹窗、结果卡片、历史表格；
- 事件状态刷新；
- 现有手动状态按钮的“未验证”提示。

验收：

- 无资格时不显示可执行按钮或显示明确原因；
- 必须经过二次确认才发请求；
- 双击不会重复执行；
- 成功、失败、不确定三种结果都有清晰展示；
- 成功后事件顶部状态和诊断报告状态一致。

### 阶段 5：端到端手工验收与回归

内容：

- 手工执行 HTTP_500 场景：注入 -> 执行 -> 诊断 -> 确认处置 -> 验证通过 -> 事件
  RESOLVED；
- 手工执行 DELAY 场景；
- 手工制造条件不匹配，确认不会关闭其他实验；
- 检查 Day 7 评测的 disable 无请求体调用仍然可用；
- 更新 README/答辩说明。

验收：

- 真实业务请求证明恢复，而不是仅改数据库状态；
- 审计记录可追溯操作、目标、结果和验证证据；
- Day 7 评测代码无需修改即可继续使用。

## 11. 对现有功能的风险与规避

| 风险 | 影响 | 规避 |
| --- | --- | --- |
| 修改 disable 接口影响 Day 7/前端 | 旧调用失败 | 请求体可选；无请求体保持原行为；回归验证 |
| 处置关闭了别的实验 | 实验状态被破坏 | 只用事件 experimentId；inventory 条件 compare-and-set |
| 并发重复处置 | 重复验证、重复扣库存 | 部分唯一活跃索引 + idempotency key |
| 数据库事务包裹 HTTP | 连接长时间占用、锁竞争 | 短事务分段；HTTP 在事务外 |
| 验证库存耗尽 | 409 被误判为成功/失败 | 单独 `VERIFICATION_INCONCLUSIVE` |
| 验证请求新建故障事件 | 误以为恢复成功 | PASS 要求 `faultObserved=false` 且 `faultEventId=null` |
| 人工先标记 RESOLVED/CLOSED | 处置无法更新事件 | 仅 OPEN 可处置；验证通过也不覆盖人工状态 |
| 诊断重试并发 | 处置和诊断状态竞争 | 执行前二次检查事件 OPEN 且无活跃 Agent 任务 |
| 无鉴权 | 任意本地调用者可触发处置 | 本地部署 + confirmed + 白名单 + 状态校验；如需要再加配置开关 |
| LLM 报告被误当作命令 | 安全风险 | 只展示 `recommendedActions`，不解析、不执行 |
| 服务重启遗留执行中记录 | 前端永久显示执行中 | 启动恢复标记 `RECOVERY_REQUIRED`，不自动重试，不覆盖原始结果 |
| 恢复后盲目重新处置 | 重复 checkout、重复扣减库存 | 非 `INTERRUPTED_BEFORE_ACTION` 必须确认人工核查并提供 `operatorNote`；订单侧仍需幂等键 |
| 多后端实例共享数据库 | 误标记其他实例仍在执行的记录 | 启动恢复默认仅支持单实例；多实例必须关闭 `RCA_REMEDIATION_RECOVERY_ENABLED` 或设计任务所有权 |
| 前端请求超时 | 页面显示未知，但后端可能成功 | 处置请求单独 30s 超时；刷新历史获取终态 |
| V5 迁移失败 | 启动失败 | 只新增表/索引；在测试库先验证；不修改 V1-V4 |

## 12. 需要确认的问题

1. **处置执行方式**：建议采用同步执行（后端在同一个请求中完成关闭和一次验证，
   短事务分段）。是否接受同步方案，还是希望改成异步任务 + 轮询？
2. **人工“标记已解决”**：建议保留接口和按钮，但对实验事件增加“未验证”提示，
   不强制拦截。是否接受？还是要求实验事件必须先通过处置验证才能标记 RESOLVED？
3. **验证业务参数**：建议只使用故障事件 `details` 中的 sku/quantity，不允许请求
   覆盖。是否接受？如果库存耗尽，是否允许手工补充库存后再次发起新的处置记录？
4. **安全开关**：是否增加 `rca.remediation.enabled`（默认关闭）作为额外保护，
   还是在本地实验环境中只依赖 `confirmed` + 白名单 + 本地部署约束？
5. **处置范围**：当前只支持 `source=EXPERIMENT` 且带 experimentId 的事件，
   `MANUAL` 事件不提供处置动作。是否确认这个范围？
6. **失败语义**：业务冲突/请求拒绝（409/4xx）建议记为
   `VERIFICATION_INCONCLUSIVE`，而不是 `SUCCEEDED` 或 `VERIFICATION_FAILED`。
   是否确认？

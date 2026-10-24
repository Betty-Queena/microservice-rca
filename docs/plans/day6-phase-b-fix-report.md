# Day 6 Phase B 修复报告与验收结论（B1–B6 完成）

- 执行日期：2026-10-24
- 当前状态：B1–B6 已实施并提交；Day 6 收尾验收已完成，存在遗留限制（见第 9 节）
- 测试结果：`backend` `mvn test` 共 106 项，100 通过、0 失败、0 错误、6 跳过，BUILD SUCCESS（用户手动执行）
- 构建结果：`frontend` `npm run build` 成功，存在非阻断性大 chunk 警告（用户手动执行）
- 真实运行：PostgreSQL 与 backend 健康检查正常；HTTP 500 故障注入、事件状态流转、真实 DeepSeek Agent 诊断均已完成验收（用户手动执行）
- 文档更新：2026-10-24 仅更新验收文档；本轮未修改生产代码、测试代码或配置，未运行任何命令，未自动提交 Git

---

## 1. 原始问题与根因

### 1.1 活跃任务定义过宽

`AgentDiagnosisService` 用 `existsByFaultEventIdAndStatusIn` 判断活跃任务，不区分 `triggerSource`。手工证据采集任务（`MANUAL_EVIDENCE`）处于 RUNNING 时会被误认为 Agent 任务，导致 AI 诊断被 409 拒绝；`retry` 也没有检查同一事件是否已有其他活跃 Agent 任务。

### 1.2 先查询再插入存在竞争

活跃检查与任务创建是两个独立操作，两个并发请求可能同时通过检查并各创建一个 PENDING 任务。原先数据库没有对应唯一约束，无法兜底。

### 1.3 线程池拒绝后任务永久 PENDING

`taskRunner.run()` 是 `@Async` 提交。线程池满时 `TaskRejectedException` 会同步抛回调用方，但此时任务已经落库、事件已经被标记为 DIAGNOSING。原代码没有捕获该异常，任务和事件会永久停在中间状态。

### 1.4 `AgentTaskRunner` 首次保存不在异常保护内

任务查询、设置 RUNNING、首次保存都在 `try` 之外。数据库异常时 `run()` 直接退出，任务停在 PENDING，没有任何失败原因。

### 1.5 进程重启后孤儿任务无人处理

数据库中的 PENDING/RUNNING 任务不会随进程重启自动结束，前端会一直显示“诊断中”，关联事件也可能停在 DIAGNOSING。

### 1.6 证据采集异常后任务可能停在 RUNNING

`EvidenceService.collect` 在创建 RUNNING 任务后直接调用工具，没有异常保护；使用真实工具时，只要出现未捕获异常，任务就不会进入终态。原实现还先写任务终态、后写证据，可能出现“任务 SUCCEEDED 但事件没有证据快照”。

### 1.7 事件恢复采用读-改-写

任务结束时先读取事件、判断是否为 DIAGNOSING，再保存。若人工在读取与保存之间把事件改为 RESOLVED/CLOSED，后台保存可能把人工处置覆盖回 OPEN。

---

## 2. 各阶段实际修改

### B1：活跃任务判定与并发启动控制

- `DiagnosisTaskRepository` 新增：
  - `existsByFaultEventIdAndTriggerSourceInAndStatusIn(...)`；
  - `findByStatusIn(...)`（供 B3 使用）。
- `AgentDiagnosisService`：
  - 新增 `AGENT_TRIGGER_SOURCES = {AGENT, AGENT_RETRY}`；
  - `startForEvent`、`retry` 统一调用 `ensureNoActiveAgentTask`；
  - `MANUAL_EVIDENCE` 不再阻塞 AI 诊断；
  - 创建任务时捕获 `DataIntegrityViolationException` 并返回 409，为 B4 唯一索引兜底预留入口。
- 说明：真正的并发原子性仍依赖 B4 数据库唯一索引；当前 B1 只收紧了查询范围和错误映射。

### B2：异步拒绝、首次保存失败与异常兜底

- `AgentDiagnosisService`：
  - 新增 `submit(task, event)`，捕获 `TaskRejectedException`；
  - 线程池拒绝时立即把任务标记为 FAILED、写入失败原因和完成时间，条件恢复事件，返回 503；
  - 提交失败时的任务终态保存也有异常保护，失败则记录日志等待启动恢复兜底。
- `AgentTaskRunner.run`：
  - 任务查询和首次 RUNNING 保存移入 `try`；
  - 失败时尽力持久化 FAILED 和失败原因；失败状态本身保存失败时记录日志，避免异常逃逸到线程池。
- `FaultEventRepository` / `FaultEventService`：
  - 新增条件更新 `updateStatusIfCurrent` 和 `restoreOpenIfDiagnosing`；
  - 仅当事件仍为 DIAGNOSING 时改为 OPEN。

### B3：启动恢复、事件条件恢复与证据采集终态

- 新增 `DiagnosisTaskRecovery`：
  - 监听 `ApplicationReadyEvent`；
  - 查询所有 PENDING/RUNNING 任务，逐条标记 FAILED，失败原因“服务重启导致任务中断”；
  - 逐条调用条件恢复，单个任务失败不影响其他任务；
  - 不删除任何任务、事件或工具调用记录。
- `EvidenceService`：
  - 工具调用、证据组装和证据写入纳入异常保护；
  - 调整顺序为“先写证据，再写任务终态”；
  - 工具或证据写入异常时任务标记 FAILED 并保留原因；
  - 工具全部失败时保留证据快照，任务 FAILED，失败原因包含各工具错误。
- `AgentTaskRunner`：
  - 任务结束恢复事件改为条件更新，不再读-改-写。
- `FaultEventRepository.updateStatusIfCurrent` 增加 `@Transactional`，保证在非事务调用方（异步 runner）中也可执行。

### B5：Agent 整体预算传递与动态超时

- `AgentModelClient.ModelRequest` 增加 `Duration timeout`，保留原 4 参数构造方法并默认 `null`（沿用固定超时）。
- `DeepSeekAgentModelClient`：
  - 保存 `RestClient.Builder` 与默认客户端；
  - 每次模型调用按剩余预算构造独立 `RestClient`，读超时取 `min(配置读超时, 剩余预算)`，连接超时不超过读超时；
  - 预算已耗尽时不发起请求，直接抛 `TIMEOUT`（`retryable=false`）；
  - TIMEOUT 错误信息记录本次实际使用的读超时。
- `ToolExecutionContext` 增加 `Duration timeout`，保留原 5 参数构造方法并默认 `null`。
- `ObservabilityClient`：
  - 新增 `queryLogs(..., Duration timeout)` 与 `queryMetrics(..., Duration timeout)`；
  - 多个服务串行查询共享同一工具预算，每次 HTTP 读超时取 `min(5 秒, 当前剩余预算)`，连接超时不超过读超时；
  - 剩余预算耗尽时不再发起该服务的请求，直接返回 `TOOL_TIMEOUT` 错误；
  - 普通网络超时也标记为 `TOOL_TIMEOUT` 前缀，便于上层区分；
  - 原 5 参数 / 3 参数方法保留，行为与默认超时一致。
- `DefaultAgentOrchestrator`：
  - 统一使用 `remaining(deadline)` 判断预算；
  - 模型调用传入当前剩余预算；
  - 模型返回后若预算耗尽，记录 `DEADLINE_EXCEEDED` 决策轨迹并按超时失败，不再执行工具；
  - 工具执行前保留预算兜底：若预算耗尽，记录 `TOOL_EXECUTION` / `errorType=TOOL_TIMEOUT` 轨迹，不调用工具（常规情况下模型返回后的耗尽已由上一条判定拦截）；
  - 模型重试的退避等待前后都检查预算；
  - 原有安全校验（未注册工具、重复调用、证据校验）和错误轨迹保持不变。
- `EvidenceService`：人工证据采集为同步接口，两次工具调用均使用固定的 15 秒预算。

### B6：任务终态一致性与事件状态竞争保护

- `DiagnosisTaskRepository` 新增：
  - `existsByFaultEventIdAndStatusIn(...)`；
  - `failIfActive(...)`：批量条件更新，仅当任务仍为 PENDING/RUNNING 时落为 FAILED，并递增 `version`、刷新 `updatedAt`/`completedAt`。
- `FaultEventRepository` 新增 `findByStatus(...)`（只读对账使用）。
- `AgentTaskRunner`：
  - `applyResult` 与 `fail` 都先按任务 ID 重新加载任务；
  - 任务已是 SUCCEEDED/FAILED/CANCELLED 时跳过写入，避免终态被覆盖（含并发取消、完成与失败竞争）；
  - 先持久化任务终态，再条件恢复事件状态；
  - 事件恢复异常仅记录日志，不再触发 `fail()` 覆盖成功结果；
  - 首次 RUNNING 保存与 agentInput 保存均使用返回值，避免 `@Version` 下继续使用旧版本实体；
  - 外层异常兜底改为按任务 ID 调用 `fail`，并捕获持久化异常。
- `DiagnosisTaskRecovery`：
  - 注入 `FaultEventRepository`；
  - 孤儿任务使用 `failIfActive` 条件更新，更新行数为 0 时不恢复事件；
  - 启动时额外执行只读对账：DIAGNOSING 事件若已无 PENDING/RUNNING 任务，仅输出 WARN 日志提示人工确认，**不修改历史数据**。
- `FaultEventService`：
  - 公共 `updateStatus` 拒绝目标状态 DIAGNOSING（400），避免人工制造无法恢复的状态；
  - 新增内部条件推进方法 `markDiagnosing`，仅 OPEN → DIAGNOSING；
  - `resolvedAt` 仅在事件首次进入 RESOLVED 时写入，CLOSED 不再覆盖已有值；
  - 状态机移除 OPEN → DIAGNOSING 的公共流转。
- `AgentDiagnosisService.markEventDiagnosing` 改用 `markDiagnosing`，捕获包括乐观锁冲突在内的所有异常，确保事件状态更新失败不会留下未提交的 PENDING 任务。
- 前端 `FaultEventDetailView.vue` 移除“标记诊断中”按钮，与公共接口限制保持一致。

---

## 3. 数据库迁移检查结果（只读）

### 3.1 当前 Flyway 状态

| 版本 | 描述 | 状态 | 安装时间 |
| --- | --- | --- | --- |
| V1 | init schema | 成功 | 2026-10-21 00:44:23 |
| V2 | fault event experiment evidence | 成功 | 2026-10-21 01:14:03 |
| V3 | agent diagnosis | 成功 | 2026-10-21 01:56:28 |

### 3.2 数据规模

- `fault_event`：12
- `diagnosis_task`：10
- `tool_invocation`：22

### 3.3 唯一约束冲突检查

```sql
SELECT fault_event_id, count(*)
  FROM diagnosis_task
 WHERE status IN ('PENDING','RUNNING')
   AND trigger_source IN ('AGENT','AGENT_RETRY')
 GROUP BY fault_event_id
HAVING count(*) > 1;
```

结果：**0 行，无重复活跃 Agent 任务，可以安全创建部分唯一索引。**

当前任务分布：

| 状态 | 来源 | 数量 |
| --- | --- | --- |
| FAILED | AGENT | 3 |
| SUCCEEDED | AGENT | 3 |
| SUCCEEDED | AGENT_RETRY | 1 |
| SUCCEEDED | MANUAL_EVIDENCE | 3 |

当前没有 PENDING/RUNNING 任务，启动恢复不会修改现有任务。

### 3.4 发现一处历史状态不一致（需要确认）

存在 1 个 DIAGNOSING 事件，但没有任何活跃 Agent 任务：

- 事件 ID：`b57bd168-9659-4ecc-8d35-310257a42969`
- 状态：DIAGNOSING
- 创建时间：2026-10-22 12:22:24 UTC

该事件不是本轮迁移造成的，也不会被唯一索引阻塞，但按现有公共状态机无法从 DIAGNOSING 回到 OPEN。B6 已实现启动对账，但按本轮约束只输出 WARN、不修改数据；处理方式仍待人工确认（见第 9 节）。V4 迁移完成后复核：状态仍为 `DIAGNOSING`，`version = 0`，`updated_at` 未变化。

### 3.5 现有结构检查

- `diagnosis_task` 和 `fault_event` 均**没有 version 列**。
- 现有索引中不存在 `uk_diagnosis_task_active_agent`。
- 表规模很小（10/12 行），V4 加列和建索引的锁表时间可忽略。

### 3.6 B4 实际迁移内容

迁移文件：`backend/src/main/resources/db/migration/V4__task_lifecycle_and_version.sql`

1. `fault_event`、`diagnosis_task` 增加 `version BIGINT NOT NULL DEFAULT 0`，历史行业务字段不回填。
2. `FaultEvent`、`DiagnosisTask` 增加 JPA `@Version`；`FaultEventRepository.updateStatusIfCurrent` 在 JPQL 批量更新中同步 `version = version + 1`。
3. 创建部分唯一索引：

```sql
CREATE UNIQUE INDEX uk_diagnosis_task_active_agent
    ON diagnosis_task (fault_event_id)
    WHERE status IN ('PENDING', 'RUNNING')
      AND trigger_source IN ('AGENT', 'AGENT_RETRY');
```

4. 乐观锁冲突由 `GlobalExceptionHandler` 统一映射为 HTTP 409，不暴露为 500。

**迁移风险：低。** 无重复活跃任务；加列有默认值；索引只覆盖活跃 Agent 任务，不影响 MANUAL_EVIDENCE 和历史终态任务。

### 3.7 正式迁移前置复核

正式迁移前再次执行只读检查，结果与 3.3 一致：

- 重复活跃 Agent 任务：0 行。
- PENDING/RUNNING 任务：0 条，启动恢复不会改写现有任务。
- 旧后端：IntelliJ 启动的 `RcaBackendApplication`（PID 72620）仍在运行并保持 1 个空闲 JDBC 连接。未主动停止；旧进程运行的是启动时加载的旧代码，不感知 V4，但 V4 为增量变更，不影响其现有读写。新代码需要重启后端后才生效。
- 备份：`pg_dump -Fc` 已完成，文件位于
  `C:\Users\y\Documents\AI\backups\rca\rca_pre_v4_20261024-011933.dump`，
  已用 `pg_restore -l` 校验可读（41 个 TOC 条目）。

### 3.8 临时库验证结果

| 数据库 | 验证内容 | 结果 |
| --- | --- | --- |
| `rca_v4_verify` | 空库执行 V1→V4；JPA `ddl-auto=validate`；version 列；部分唯一索引；终态/MANUAL_EVIDENCE 不阻塞；过期实体更新抛 `OptimisticLockingFailureException` | 4 tests，全部通过 |
| `rca_v4_upgrade` | 先迁移到 V3，写入历史事件/任务，再执行 V4；校验历史数据保留、version 默认 0、索引创建 | 1 test，通过 |

说明：

- 升级路径测试启动时只迁移到 V3，而实体已包含 `version` 映射，启动恢复监听会查询不存在的列。测试中用 `@MockitoBean` 替换 `DiagnosisTaskRecovery`，仅用于隔离升级路径，不改变生产逻辑。
- `rca_v4_verify` 与 `rca_v4_upgrade` 保持隔离，未连接 `rca`。临时库暂未删除，等待确认后再清理。

### 3.9 正式库迁移结果

- Flyway：`flyway_schema_history` 新增 V4，`success = true`，当前版本 4。
- 结构：两张表的 `version` 均为 `bigint NOT NULL DEFAULT 0`；
  `uk_diagnosis_task_active_agent` 的谓词与代码取值完全一致。
- 数据：迁移前后 `fault_event` 12 条、`diagnosis_task` 10 条、`tool_invocation` 22 条，未删除、未改写。
- 任务状态仍为 FAILED 3 条、SUCCEEDED 7 条，无 PENDING/RUNNING。
- 历史事件 `b57bd168-...` 未被修改（见 3.4）。

迁移实际耗时约 0.033 秒，未出现锁等待或错误。

---

## 4. 测试结果

### 4.1 最终测试结果（用户手动执行）

| 范围 | 结果 |
| --- | --- |
| `backend` `mvn test`（Day 6 最终） | 106 tests，100 passed，0 failed，0 errors，6 skipped，BUILD SUCCESS |
| `frontend` `npm run build` | 构建成功；存在非阻断性大 chunk 警告 |
| `V4MigrationIntegrationTest`（`rca_v4_verify`） | 4 tests，全部通过（B4 阶段记录） |
| `V4UpgradePathIntegrationTest`（`rca_v4_upgrade`） | 1 test，通过（B4 阶段记录） |
| `V4FormalMigrationTest`（`rca`，只读校验） | 1 test，通过（B4 阶段记录） |

说明：

- B4 阶段记录的 87 项是 B5/B6 测试加入前的数量；最终 106 项已包含 B5/B6 新增与修改的测试。
- 6 个 skipped 为默认关闭的数据库集成测试，未计入 100 个通过项；它们需要显式开启并连接对应数据库。
- 大 chunk 警告不影响构建成功，属于前端打包优化项。

### 4.2 B5/B6 新增或修改的测试（已随最终 `mvn test` 通过）

以下测试在最终 106 项中执行并通过：

- `DefaultAgentOrchestratorTest`
  - 剩余预算同时传递给模型与工具；
  - 预算初始已耗尽时不调用模型；
  - 模型返回后预算耗尽时记录 `DEADLINE_EXCEEDED`、不执行工具（`e76215c` 修正断言）；
  - 原模型失败测试补充 `errorType=TIMEOUT` 断言。
- `DeepSeekAgentModelClientTest`
  - 使用剩余预算作为读超时；
  - 预算已耗尽时不发起 HTTP 请求。
- `QueryLogsToolTest` / `QueryMetricsToolTest`
  - 工具层将 timeout 传递给 ObservabilityClient。
- `EvidenceServiceTest`
  - 人工证据采集两次工具调用均使用 15 秒预算。
- `AgentTaskRunnerTest`
  - 取消后成功/失败路径不覆盖终态；
  - 事件恢复异常不影响已持久化的成功终态；
  - 并发完成后失败路径不覆盖 SUCCEEDED。
- `DiagnosisTaskRecoveryTest`
  - 活跃任务条件更新成功并恢复事件；
  - 条件更新返回 0 时不恢复事件；
  - 单个任务更新异常不影响其他任务；
  - 无活跃任务的 DIAGNOSING 事件只读告警、不写库；
  - 仍有活跃任务的 DIAGNOSING 事件不产生恢复写入。
- `FaultEventServiceTest`
  - 公共接口拒绝 DIAGNOSING；
  - `markDiagnosing` 使用条件更新；
  - 首次 RESOLVED 写入 `resolvedAt`；
  - CLOSED 不覆盖已有 `resolvedAt`。
- `AgentDiagnosisServiceTest`
  - 事件状态更新发生乐观锁冲突时任务仍会提交执行。

### 4.3 B1–B4 阶段测试明细（已执行）

- `AgentDiagnosisServiceTest`
  - `startRejectsWhenActiveAgentTaskExists`
  - `startAllowsWhenOnlyManualEvidenceIsRunning`
  - `retryRejectsWhenAnotherAgentTaskIsActive`
  - `concurrentInsertConflictIsReturnedAs409`
  - `marksFailedAndRestoresEventWhenExecutorRejects`
- `AgentTaskRunnerTest`
  - `persistsFailedWhenInitialRunningSaveFails`
  - 成功/失败路径均验证条件恢复调用
- `DiagnosisTaskRecoveryTest`
  - PENDING/RUNNING 任务恢复为 FAILED
  - 条件更新返回未命中时不覆盖事件
  - 单个任务恢复失败不影响其他任务
- `EvidenceServiceTest`
  - 证据先于任务终态写入
  - 工具调用异常 → 任务 FAILED
  - 证据写入失败 → 任务 FAILED
  - 工具全部失败 → 保留证据、任务 FAILED
- `FaultEventServiceTest`
  - 条件恢复的 true/false 分支
- `GlobalExceptionHandlerTest`
  - 乐观锁冲突 → HTTP 409 与统一中文提示
- `V4MigrationIntegrationTest`（真实 PostgreSQL）
  - V1→V4 全量迁移与 version 列
  - 同一事件第二个活跃 AGENT/AGENT_RETRY 任务被唯一索引拒绝
  - 终态任务与 MANUAL_EVIDENCE 任务不被阻塞
  - 过期 `FaultEvent` 保存抛 `OptimisticLockingFailureException`
- `V4UpgradePathIntegrationTest`（真实 PostgreSQL）
  - V3 历史数据在升级后保留，version 默认 0，索引创建
- `V4FormalMigrationTest`（真实 PostgreSQL）
  - 正式库 V4 记录、列结构与索引存在

未覆盖/限制（与最终测试结果对齐）：

- 并发启动测试是单元级模拟 + 数据库唯一约束验证，未做多线程同时打真实 HTTP 接口的压测。
- 启动恢复的单元测试使用 mock repository；真实数据库上的恢复未在非空活跃任务集上演练。
- B5 的真实超时行为未做专项验证：单元测试覆盖了预算传递与“预算耗尽后不再执行”的语义，但没有在真实 DeepSeek/观测服务延迟下验证 180 秒整体预算耗尽的表现。
- `V4UpgradePathIntegrationTest` 需要全新的 `rca_v4_upgrade` 库（首次运行到 V3，测试内升级到 V4），重复执行前需重建临时库。

---

## 5. Git 提交记录（本地）

| 阶段 | Commit | 说明 |
| --- | --- | --- |
| B1 | `908ab16` | 收紧 Agent 活跃任务判定并拒绝并发重复启动 |
| B2 | `17bc622` | 处理线程池拒绝与任务首次保存失败，避免任务永久挂起 |
| B3 | `0b534cb` | 启动恢复孤儿诊断任务并保证证据采集异常后的终态 |
| B4 | `c24af58` | 新增 V4 迁移，约束每个事件最多一个活跃 Agent 任务 |
| B5 | `1841cc3` | 将诊断整体预算传递到模型与工具调用 |
| B6 | `fe7ee12` | 修复诊断任务终态覆盖与事件状态一致性 |
| 文档 | `cbf1787` | 补充 B5/B6 修复报告与手动验证清单 |
| B5 测试修正 | `fd1cb2d` | 修正 B5 测试中的 AtomicBoolean 断言写法 |
| 文档 | `8b650bc` | 补充 B5 测试修正提交记录 |
| B5 测试语义修正 | `e76215c` | 修正预算耗尽测试的超时语义断言 |

未 push。

本轮（Day 6 收尾）仅修改验收文档，未创建新的 Git 提交。

---

## 6. 剩余风险

1. **后端进程与新代码一致性**：B4 迁移期间存在 IntelliJ 启动的旧后端进程（PID 72620）。Day 6 验收已完成健康检查与真实 Agent 诊断，说明后端可用；但若当前进程未按最新 B5/B6 提交重新编译启动，则预算控制与终态保护的修复不会被真实运行验证到。建议确认进程启动时间与构建产物一致，必要时重启一次。
2. **任务乐观锁兜底**：`applyResult`/`fail` 已重新加载并跳过终态，保存冲突时由外层异常兜底再次调用 `fail`；如果冲突持续，任务仍可能停留在 RUNNING，等待启动恢复处理。
3. **多实例部署风险**：启动恢复与只读对账假设单实例。若多个后端实例共享数据库，一个实例启动会把其他实例正在执行的任务误判为孤儿。`failIfActive` 只能保证不覆盖终态，不能区分“其他实例正在执行”。
4. **历史 DIAGNOSING 事件**：`b57bd168-...` 仍无活跃任务。B6 只在对账时输出 WARN，公共接口已禁止人工置为 DIAGNOSING，因此该事件当前无法通过 UI 恢复；等待人工确认处理方式。B6 未修改该事件，也未执行任何 `UPDATE fault_event SET status='OPEN'`。
5. **事件恢复失败后的补账**：任务终态已持久化、但事件条件恢复失败时，事件会停在 DIAGNOSING；当前只记录 WARN，并由启动对账提示人工处理，没有自动补偿。
6. **取消流程尚不存在**：`CANCELLED` 终态保护已实现，但后端没有取消接口；本轮通过单元测试模拟取消竞争。
7. **公共接口行为变化**：`PUT /api/fault-events/{id}/status` 不再接受 `DIAGNOSING`（400），前端对应按钮已移除。若有外部脚本调用该值，需要同步调整。
8. **超时语义**：工具预算取整体剩余预算，ObservabilityClient 单次 HTTP 读超时仍上限 5 秒；多服务串行总计受剩余预算约束。模型超时可在预算内重试，重试前会检查剩余预算。
9. **ObservabilityClient 每次调用重建 RestClient**：为支持动态读超时，默认路径也会按服务构造客户端，带来很小的对象创建开销；未做性能压测。
10. **B5 真实超时未专项验证**：B5/B6 的单元测试已随最终 `mvn test` 全部通过；但 180 秒整体预算在真实慢模型/慢工具条件下耗尽的表现，尚未做专项运行验证。验收中的真实诊断约 16 秒完成，未触发超时路径。
11. **迁移测试可重复性**：`V4UpgradePathIntegrationTest` 依赖全新 V3 临时库；`V4FormalMigrationTest` 只在已迁移到 V4 的 `rca` 上做只读校验。两者都不参与默认 `mvn test`。

---

## 7. Day 6 验收结论

### 7.1 验收结果（均为用户手动执行，非本轮文档更新产生）

| 序号 | 验收项 | 结果 | 分类 |
| --- | --- | --- | --- |
| 1 | 后端 `mvn test` | 106 项，100 通过、0 失败、0 错误、6 跳过，BUILD SUCCESS | 已通过测试 |
| 2 | 前端 `npm run build` | 构建成功，仅有非阻断性大 chunk 警告 | 已通过构建 |
| 3 | PostgreSQL 与 backend 健康检查 | 正常，数据库状态 UP | 已通过真实运行验证 |
| 4 | 前端故障事件、诊断任务列表与详情 | 显示正常 | 已通过真实运行验证 |
| 5 | HTTP 500 故障注入 | 注入成功，order-service 返回 HTTP 502，故障事件正常持久化 | 已通过真实运行验证 |
| 6 | 事件状态流转 | OPEN → RESOLVED → CLOSED 正常，刷新后持久化正确 | 已通过真实运行验证 |
| 7 | 手动“标记诊断中”按钮（B6） | 已移除 | 已通过真实运行验证 |
| 8 | 真实 DeepSeek Agent 诊断 | `deepseek-flash`，模型调用 4 次、工具调用 3 次、Token 10824，约 16 秒；定位 inventory-service HTTP 500 导致 order-service HTTP 502；任务成功、报告生成、事件自动恢复为 OPEN | 已通过真实运行验证 |

说明：

- 上述数据来自用户手动验收结果，文档只做记录，未补造任何测试数据。
- 第 8 项说明“诊断成功”不等于“故障自动解决”：事件按设计恢复为 OPEN，等待人工处置。

### 7.2 B1–B6 目标达成情况

| 阶段 | 目标 | 验证状态 |
| --- | --- | --- |
| B1 | 收紧活跃 Agent 任务判定、并发启动返回 409 | 已通过测试（单元 + 数据库唯一约束测试）；未做真实并发压测 |
| B2 | 线程池拒绝与首次保存失败时任务进入终态 | 已通过测试 |
| B3 | 启动恢复孤儿任务、证据采集异常后进入终态 | 已通过测试；真实库恢复未在非空任务集上演练 |
| B4 | V4 迁移与每事件唯一活跃 Agent 任务约束 | 已通过测试并完成正式库迁移 |
| B5 | 180 秒整体预算传递到模型与工具、超时语义正确 | 已通过测试；真实超时未专项验证 |
| B6 | 终态不被覆盖、事件条件恢复、历史 DIAGNOSING 只读对账 | 已通过测试；真实诊断中事件已自动恢复为 OPEN |

**结论**：Day 6 Phase B 的修复目标已在测试与真实运行两个层面达到可验收状态，未发现阻塞性问题；仍存在第 9 节的遗留限制，不影响当前单实例、人工处置场景的使用。

---

## 8. Day 6 完成情况总结

### 8.1 已完成内容

- B1–B6 六项修复全部实施并本地提交，未 push。
- V4 数据库迁移已在正式库执行，历史数据保留，迁移耗时约 0.033 秒。
- 后端测试 106 项（100 通过、6 跳过），前端构建成功。
- 真实链路验收完成：故障注入 → 事件持久化 → DeepSeek Agent 诊断 → 报告生成 → 事件恢复 OPEN。
- 手动“标记诊断中”入口已移除，事件状态只能由诊断流程或人工处置推进。

### 8.2 当前系统能力边界

已具备：

- 故障事件的记录、查询、详情与状态流转（OPEN / DIAGNOSING / RESOLVED / CLOSED）；
- 人工证据采集与 AI Agent 自动诊断；
- 诊断任务的预算控制、终态保护和启动恢复；
- 诊断结论、工具调用轨迹与 Token 用量持久化。

尚不具备：

- 故障自动修复或自动关闭；
- 历史异常事件的自愈处理；
- 多实例部署下的安全启动恢复；
- 诊断任务取消接口与前端自动化测试。

---

## 9. 尚未验证与遗留限制

1. **B5 真实超时未专项验证**：单元测试覆盖了预算传递和“预算耗尽后不再执行工具”；验收中的真实诊断约 16 秒完成，未触发 180 秒预算耗尽路径。
2. **历史异常事件未处理**：`b57bd168-9659-4ecc-8d35-310257a42969` 仍为 DIAGNOSING 且无活跃任务。当前只能通过启动对账输出 WARN，公共接口已禁止人工置为 DIAGNOSING，需要人工确认后受控处理；本轮未修改该数据。
3. **自动处置尚未实现**：诊断完成后只生成报告并把事件恢复为 OPEN，不会自动修复、自动关闭或触发外部工单。
4. **事件恢复失败无自动补偿**：任务终态已持久化但事件条件恢复失败时，事件可能停在 DIAGNOSING，目前仅记录 WARN。
5. **多实例部署风险**：启动恢复假设单实例，其他实例正在执行的任务可能被误判为孤儿。
6. **取消流程不完整**：后端没有取消接口，`CANCELLED` 终态保护仅通过单元测试模拟。
7. **前端缺少自动化测试**：当前只做了构建与人工页面验收，大 chunk 警告也未处理。
8. **并发未做真实压测**：并发启动、乐观锁冲突等由单元测试和数据库约束验证，未做多线程 HTTP 压测。
9. **迁移测试可重复性**：`V4UpgradePathIntegrationTest` 依赖全新 V3 临时库，不参与默认 `mvn test`。
10. **临时库待清理**：`rca_v4_verify`、`rca_v4_upgrade` 仍在，需确认后删除。
11. **后端进程与代码一致性**：建议确认当前后端进程启动时间与最新提交一致。

---

## 10. 下一阶段开发优先级建议

### P0：收尾与数据一致性

1. 人工核实历史 DIAGNOSING 事件 `b57bd168-...` 的实际状态，确认后用受控脚本或迁移处理；禁止无条件批量改为 OPEN。
2. 确认后端进程已加载最新 B5/B6 代码；如需重启，重启后复核启动恢复日志与事件状态。
3. 确认并清理临时数据库 `rca_v4_verify`、`rca_v4_upgrade`。

### P1：可靠性与可运维性

4. 增加事件状态对账补偿：定时任务对“任务已终态但事件仍为 DIAGNOSING”的情况做条件更新，仍不覆盖人工 RESOLVED/CLOSED。
5. 增加诊断任务取消接口，复用现有 `CANCELLED` 终态保护。
6. 多实例安全：引入实例标识、心跳或任务租约，避免启动时误判其他实例的任务为孤儿。
7. 补充 B5 真实超时演练：用可控的模型/工具延迟验证 180 秒预算耗尽、重试边界和决策轨迹。

### P2：可测试性与前端质量

8. 补充前端组件与端到端测试，覆盖列表、详情、状态流转和按钮移除后的回归。
9. 处理前端大 chunk 警告（按路由拆包或依赖拆分）。
10. 将数据库集成测试纳入可重复执行的 CI 流程，提供临时库初始化脚本。

### P3：能力扩展

11. 在人工确认前提下探索自动处置：告警/工单联动、Runbook 建议、自动修复审批流。
12. 扩展观测数据源（如链路追踪），提升证据充分性与诊断可信度。

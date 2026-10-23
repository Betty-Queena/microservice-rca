# Day 6 Phase B 修复报告（B1–B3 已完成，B4–B6 待确认）

- 执行日期：2026-10-24
- 当前状态：B1、B2、B3 已实施并提交；按约定在 B4 数据库迁移前暂停
- 验证：后端 `mvn clean test`（80 个测试全部通过）
- 未执行：Flyway V4、B5 动态超时、B6 乐观锁与终态顺序调整

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

该事件不是本轮迁移造成的，也不会被唯一索引阻塞，但按现有公共状态机无法从 DIAGNOSING 回到 OPEN。建议在 B6 或人工确认后处理，本轮未修改该业务数据。

### 3.5 现有结构检查

- `diagnosis_task` 和 `fault_event` 均**没有 version 列**。
- 现有索引中不存在 `uk_diagnosis_task_active_agent`。
- 表规模很小（10/12 行），V4 加列和建索引的锁表时间可忽略。

### 3.6 B4 拟执行内容（尚未执行）

1. `fault_event`、`diagnosis_task` 增加 `version BIGINT NOT NULL DEFAULT 0`。
2. 实体增加 `@Version`。
3. 创建部分唯一索引：

```sql
CREATE UNIQUE INDEX uk_diagnosis_task_active_agent
    ON diagnosis_task (fault_event_id)
    WHERE status IN ('PENDING', 'RUNNING')
      AND trigger_source IN ('AGENT', 'AGENT_RETRY');
```

4. 在干净数据库和现有数据库升级路径上分别验证迁移（使用独立测试库，不改动当前 `rca` 数据）。

**迁移风险：低。** 无重复活跃任务；加列有默认值；索引只覆盖活跃 Agent 任务，不影响 MANUAL_EVIDENCE 和历史终态任务。

### 3.7 待确认事项

1. 是否按上述方案执行 V4？
2. 历史 DIAGNOSING 事件 `b57bd168-...` 是否允许由 B6 的启动对账/人工操作恢复为 OPEN？本轮未自动修改。
3. B4 验证如需新建临时数据库（如 `rca_v4_verify`），是否允许？不会改动现有 `rca` 库。

---

## 4. 测试结果

| 范围 | 结果 |
| --- | --- |
| `backend` `mvn clean test` | 80 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS |

新增/重点测试：

- `AgentDiagnosisServiceTest`（7 个）
  - `startRejectsWhenActiveAgentTaskExists`
  - `startAllowsWhenOnlyManualEvidenceIsRunning`
  - `retryRejectsWhenAnotherAgentTaskIsActive`
  - `concurrentInsertConflictIsReturnedAs409`
  - `marksFailedAndRestoresEventWhenExecutorRejects`
- `AgentTaskRunnerTest`（9 个）
  - `persistsFailedWhenInitialRunningSaveFails`
  - 成功/失败路径均验证条件恢复调用
- `DiagnosisTaskRecoveryTest`（3 个）
  - PENDING/RUNNING 任务恢复为 FAILED
  - 条件更新返回未命中时不覆盖事件
  - 单个任务恢复失败不影响其他任务
- `EvidenceServiceTest`（4 个）
  - 证据先于任务终态写入
  - 工具调用异常 → 任务 FAILED
  - 证据写入失败 → 任务 FAILED
  - 工具全部失败 → 保留证据、任务 FAILED
- `FaultEventServiceTest`（2 个）
  - 条件恢复的 true/false 分支

未覆盖/限制：

- 并发启动测试目前是单元级模拟，真正的数据库唯一约束验证需在 B4 完成后进行。
- 启动恢复测试使用 mock repository，未验证真实数据库查询。
- 未调用 DeepSeek API，未做真实模型集成测试。
- 前端未改动。

---

## 5. Git 提交记录（本地）

| 阶段 | Commit | 说明 |
| --- | --- | --- |
| B1 | `908ab16` | 收紧 Agent 活跃任务判定并拒绝并发重复启动 |
| B2 | `17bc622` | 处理线程池拒绝与任务首次保存失败，避免任务永久挂起 |
| B3 | `0b534cb` | 启动恢复孤儿诊断任务并保证证据采集异常后的终态 |

未 push。

---

## 6. 剩余风险

1. **数据库唯一约束尚未生效**：B1 的“先查询再插入”在极端并发下仍可能重复创建任务，B4 完成后才由数据库兜底。
2. **多实例部署风险**：启动恢复假设单实例。若多个后端实例共享数据库，一个实例启动会把其他实例正在执行的任务误判为孤儿。
3. **历史 DIAGNOSING 事件**：`b57bd168-...` 仍无活跃任务，公共 API 无法恢复，等待确认处理方式。
4. **事件恢复失败后的补账**：任务已 FAILED 但事件恢复抛异常时，事件会停在 DIAGNOSING，当前只能等下一次操作或后续对账。
5. **终态写入顺序与乐观锁未调整**：成功报告仍可能在事件恢复异常时被 `fail()` 覆盖；这是 B6 的范围。
6. **动态超时未实现**：整体预算和模型/工具超时仍是固定值，B5 处理。
7. **测试限制**：真实数据库约束、Flyway V4、并发竞争仍需集成测试验证。

---

## 7. 下一步验收建议

1. 确认第 3.7 节的三项问题。
2. B4 完成后：
   - 在临时库验证 V1→V4 全量迁移；
   - 在现有库执行 V4 前再次运行冲突检查；
   - 验证并发启动只有一个任务成功，另一个返回 409。
3. B5：为模型调用、工具调用和整体执行增加可配置超时，并测试慢模型/慢工具/预算耗尽。
4. B6：先落任务终态再恢复事件、事件乐观锁、人工 RESOLVED/CLOSED 竞争保护、`resolvedAt` 与手工 DIAGNOSING 收口。
5. 全部完成后运行三个 Java 模块 `mvn clean test` 与前端 `npm run build`，再补最终报告与验收走查。

# Day 3 验证报告：DeepSeek Agent 自主诊断

| 项目 | 内容 |
| --- | --- |
| 项目 | 基于 LLM 的微服务故障分析与处置系统 |
| 日期 | 2026-10-21 |
| 范围 | DeepSeek 兼容 API 接入、模型驱动多轮工具调用、证据约束根因分析、诊断任务 API、前端 Agent 展示 |
| 结论 | 代码、Mock 测试、数据库迁移脚本校验与前端构建完成；当前环境无 `DEEPSEEK_API_KEY`，**未执行真实 DeepSeek 调用**，未伪造真实模型结果 |

## A. 修改文件

### A.1 后端 Agent 与诊断任务

| 文件 | 说明 |
| --- | --- |
| `backend/src/main/java/com/microservice/rca/agent/AgentProperties.java` | DeepSeek 与 Agent 预算配置，Key 只读环境变量 |
| `backend/src/main/java/com/microservice/rca/agent/AgentModelClient.java` | 模型客户端契约与请求/响应模型 |
| `backend/src/main/java/com/microservice/rca/agent/DeepSeekAgentModelClient.java` | DeepSeek `/chat/completions` 客户端、超时与 HTTP 错误映射 |
| `backend/src/main/java/com/microservice/rca/agent/AgentModelException.java` | 可重试/不可重试的模型错误 |
| `backend/src/main/java/com/microservice/rca/agent/AgentValidationException.java` | 模型输出、工具参数、报告校验异常 |
| `backend/src/main/java/com/microservice/rca/agent/AgentOrchestrator.java` | Agent 编排契约、白名单输入、运行结果 |
| `backend/src/main/java/com/microservice/rca/agent/DefaultAgentOrchestrator.java` | 有限状态多轮编排器 |
| `backend/src/main/java/com/microservice/rca/agent/AgentInputFactory.java` | 白名单输入构造与 `observed_result` 二次过滤 |
| `backend/src/main/java/com/microservice/rca/agent/AgentPromptBuilder.java` | 系统提示词、观察结果、证据 ID 与反馈构造 |
| `backend/src/main/java/com/microservice/rca/agent/AgentDecisionParser.java` | 解析单个 JSON 决策并校验 `action` |
| `backend/src/main/java/com/microservice/rca/agent/AgentToolCallValidator.java` | 只读工具白名单、参数范围与重复调用签名 |
| `backend/src/main/java/com/microservice/rca/agent/AgentReportValidator.java` | 报告字段、服务名、置信等级与真实 evidenceId 校验 |
| `backend/src/main/java/com/microservice/rca/agent/AgentAsyncConfig.java` | `agentExecutor` 异步线程池 |
| `backend/src/main/java/com/microservice/rca/agent/AgentTaskRunner.java` | 异步执行任务并持久化状态、报告、失败原因与时间线 |
| `backend/src/main/java/com/microservice/rca/agent/AgentDiagnosisService.java` | 启动、查询、列表、重试与任务视图映射 |
| `backend/src/main/java/com/microservice/rca/agent/web/AgentDiagnosisController.java` | 诊断任务 REST API |
| `backend/src/main/java/com/microservice/rca/agent/api/AgentTaskDetailView.java` | 任务详情 DTO |
| `backend/src/main/java/com/microservice/rca/agent/api/AgentTaskSummaryView.java` | 任务摘要 DTO |
| `backend/src/main/java/com/microservice/rca/diagnosis/domain/DiagnosisResultStatus.java` | `ROOT_CAUSE_IDENTIFIED` / `INSUFFICIENT_EVIDENCE` |
| `backend/src/main/java/com/microservice/rca/diagnosis/domain/DiagnosisTask.java` | 新增 V3 字段映射 |
| `backend/src/main/java/com/microservice/rca/diagnosis/repository/DiagnosisTaskRepository.java` | 活动任务冲突查询 |
| `backend/src/main/java/com/microservice/rca/diagnosis/api/DiagnosisTaskView.java` | 摘要视图补充结论状态 |
| `backend/src/main/java/com/microservice/rca/diagnosis/service/FaultEventService.java` | 事件详情任务视图适配 |
| `backend/src/main/java/com/microservice/rca/RcaBackendApplication.java` | 启用 `@ConfigurationPropertiesScan` |
| `backend/src/main/resources/application.yml` | DeepSeek 与 Agent 预算配置 |
| `backend/src/main/resources/db/migration/V3__agent_diagnosis.sql` | V3 数据库迁移 |

### A.2 后端测试

- `backend/src/test/java/com/microservice/rca/agent/AgentTestSupport.java`
- `backend/src/test/java/com/microservice/rca/agent/DefaultAgentOrchestratorTest.java`
- `backend/src/test/java/com/microservice/rca/agent/AgentToolCallValidatorTest.java`
- `backend/src/test/java/com/microservice/rca/agent/AgentReportValidatorTest.java`
- `backend/src/test/java/com/microservice/rca/agent/AgentInputFactoryTest.java`
- `backend/src/test/java/com/microservice/rca/agent/AgentTaskRunnerTest.java`
- `backend/src/test/java/com/microservice/rca/agent/DeepSeekAgentModelClientTest.java`

### A.3 前端与配置文档

- `frontend/src/api/agentDiagnosis.js`
- `frontend/src/views/FaultEventDetailView.vue`
- `frontend/src/router/index.js`
- `.env.example`
- `README.md`
- `docs/architecture/system-design.md`
- `docs/plans/day3-report.md`

V1、V2 迁移文件未修改。

## B. 架构与技术选型

### B.1 为什么使用有限状态编排器

当前 Agent 流程是单个受预算约束的循环：

```text
MODEL_DECISION
  -> CALL_TOOL -> TOOL_EXECUTION -> OBSERVATION -> MODEL_DECISION
  -> FINAL_REPORT
  -> INSUFFICIENT_EVIDENCE
  -> FAILED（模型/超时/预算）
```

评估过 LangGraph4j 1.8.4：本地 Maven 缓存中存在该版本且兼容 Java 17，但当前流程不需要图检查点、并行分支、多 Agent 协作或复杂状态持久化。引入图框架及传递依赖会增加构建、调试和答辩现场演示的风险。因此实现清晰的有限状态机，并在 `AgentOrchestrator` 接口处保持可替换；如果后续需要复杂图编排，可迁移到 LangGraph4j，而不改变上层诊断任务 API。

这不是“固定调用两个工具再生成报告”：模型每一轮都可以选择 `CALL_TOOL`（`query_logs` 或 `query_metrics`）、`FINAL_REPORT` 或 `INSUFFICIENT_EVIDENCE`，工具结果和错误会作为下一轮观察输入。测试中覆盖了仅日志、仅指标、日志→指标→报告、重复调用跳过、未注册工具拒绝等不同路径。

### B.2 组件职责

| 组件 | 职责 |
| --- | --- |
| `AgentDiagnosisService` | 创建 PENDING 任务、异步提交、查询详情/历史、失败重试 |
| `AgentTaskRunner` | 将任务置为 RUNNING，构造白名单输入，调用编排器，持久化结果 |
| `DefaultAgentOrchestrator` | 多轮模型决策、工具执行、观察反馈、预算与超时控制 |
| `AgentPromptBuilder` | 只发送事件白名单字段、工具定义、真实工具结果与证据 ID |
| `AgentDecisionParser` | 解析单个 JSON 对象；非法输出转为反馈而不是执行 |
| `AgentToolCallValidator` | 校验工具注册、参数键、服务范围、数值范围、requestId |
| `AgentReportValidator` | 校验报告结构与真实证据 ID |
| `DeepSeekAgentModelClient` | 调用 DeepSeek 兼容 API，映射鉴权/余额/限流/超时错误 |

## C. 实际工具调用流程

### C.1 启动与异步执行

1. 前端或调用方 `POST /api/fault-events/{eventId}/diagnosis`。
2. 后端检查同一事件是否已有 `PENDING` / `RUNNING` 任务；有则返回 409。
3. 创建 `diagnosis_task`（`trigger_source=AGENT`，状态 `PENDING`），事件状态从 `OPEN` 尽力更新为 `DIAGNOSING`。
4. `AgentTaskRunner` 异步执行：状态置 `RUNNING`，记录 `started_at`。
5. `AgentInputFactory` 从数据库读取事件，只保留白名单字段，保存 `agent_input` 快照。
6. 编排器进入模型决策循环；每次真实工具调用写入 `tool_invocation`。
7. 成功、证据不足或失败均写入 `status`、`result_status`、`report`、`failure_reason`、`decision_trace` 和 `completed_at`。

### C.2 模型决策协议

模型每轮只输出一个 JSON：

```json
{"action":"CALL_TOOL","tool":"query_logs","arguments":{"service":"all","requestId":"exp-abc123","limit":20},"reason":"先查看两个服务的请求日志"}
```

```json
{"action":"FINAL_REPORT","report":{"rootCause":"...","affectedServices":["inventory-service"],"hypothesis":"...","supportingEvidenceIds":["log:order-service:log-1"],"contradictingEvidenceIds":[],"confidenceLevel":"HIGH","recommendedActions":["人工确认后检查实例负载"],"limitations":["缺少调用链"]},"reason":"证据已充分"}
```

```json
{"action":"INSUFFICIENT_EVIDENCE","reason":"只有单条日志，无法区分根因","limitations":["缺少调用链与配置变更数据"]}
```

### C.3 工具与证据

- 可用工具仅来自 `DiagnosticToolRegistry`，且只允许 `query_logs`、`query_metrics`。
- `query_logs` 证据 ID 形如 `log:<service>:<request-log-id>`。
- `query_metrics` 证据 ID 形如 `metric:<service>:<minute>`。
- 只有成功工具输出中的 `evidenceIds` 才进入已知证据集合。
- 重复的“工具 + 服务 + 范围 + requestId + limit”签名不会再次执行。
- 工具失败只作为失败观察反馈，不进入根因证据。

### C.4 预算与循环终止

- 最大模型调用轮数默认 6（上限 12）。
- 最大工具调用次数默认 6（上限 12）。
- 单次模型输出 12000 字符、提示词 60000 字符、单次工具输出进入提示词 6000 字符。
- 整体超时默认 180 秒（上限 600 秒）。
- 模型可重试错误（429/5xx/网络超时）会消耗下一轮模型预算继续尝试，不会无限重试；整体仍受最大轮数和超时限制。
- 达到轮数上限仍未形成结论时任务失败，不生成根因。

## D. 数据库迁移

新增 `V3__agent_diagnosis.sql`，不修改 V1/V2：

```sql
ALTER TABLE diagnosis_task
    ADD COLUMN result_status     VARCHAR(32),
    ADD COLUMN confidence_level  VARCHAR(16),
    ADD COLUMN failure_reason    VARCHAR(1024),
    ADD COLUMN report            JSONB,
    ADD COLUMN agent_input       JSONB,
    ADD COLUMN decision_trace    JSONB,
    ADD COLUMN model_name        VARCHAR(128),
    ADD COLUMN started_at        TIMESTAMPTZ;
```

约束：

- `result_status` 只允许 `ROOT_CAUSE_IDENTIFIED` / `INSUFFICIENT_EVIDENCE` 或 NULL。
- `confidence_level` 只允许 `HIGH` / `MEDIUM` / `LOW` 或 NULL。
- 新增 `idx_diagnosis_task_result_status`、`idx_diagnosis_task_created_at`。

任务执行状态与结论状态分离：

| 场景 | status | result_status | root_cause |
| --- | --- | --- | --- |
| 形成根因 | `SUCCEEDED` | `ROOT_CAUSE_IDENTIFIED` | 有 |
| 证据不足 | `SUCCEEDED` | `INSUFFICIENT_EVIDENCE` | 无 |
| 模型/工具/超时失败 | `FAILED` | NULL | 无 |

`tool_invocation` 复用 V1 表，不新增字段；Agent 的每次真实调用仍按 `sequence_no` 记录输入、输出、状态、耗时和错误。

**实际校验**：使用 PostgreSQL 16 容器 `rca-postgres`，在事务中执行 V3 脚本后回滚，`ALTER TABLE ×2 / CREATE INDEX ×2 / COMMENT ×8` 全部成功，随后 `ROLLBACK`。本次未启动 Spring Boot 应用，因此 V3 尚未写入数据库的 `flyway_schema_history`；开发者首次启动后端时会由 Flyway 正式应用。

## E. 测试结果与真实 DeepSeek 调用

### E.1 执行命令与结果

```bash
# 后端
cd backend && mvn -o test
# 当前包 com.microservice.rca：55 个测试通过
# 其中 Day 3 新增 38 个

# 实验服务
cd services/inventory-service && mvn -o test   # 17 个通过
cd services/order-service && mvn -o test       # 7 个通过

# 前端
cd frontend && npm run build                   # 构建成功
```

合计：**79 个测试通过**（Day 2 原有 41 个 + Day 3 新增 38 个），前端生产构建成功。

### E.2 A–J 覆盖对照

| 编号 | 要求 | 覆盖测试 |
| --- | --- | --- |
| A | 模型选择 `query_logs` 并执行真实工具 | `DefaultAgentOrchestratorTest.executesQueryLogsChosenByModel`，验证工具调用持久化 |
| B | 模型选择 `query_metrics` | `DefaultAgentOrchestratorTest.executesQueryMetricsChosenByModel` |
| C | 多轮选择工具并结束调查 | `DefaultAgentOrchestratorTest.supportsMultiRoundInvestigation`，两次真实工具调用后报告 |
| D | 未注册工具/非法参数拒绝 | `AgentToolCallValidatorTest` 9 项；`DefaultAgentOrchestratorTest.rejectsUnregisteredToolBeforeAnyExecution` |
| E | 不存在的 evidenceId 不进入有效证据链 | `AgentReportValidatorTest` + `DefaultAgentOrchestratorTest.rejectsNonExistentEvidenceAndNeverReturnsItAsRootCause` |
| F | 实验预设字段不进入 LLM 输入 | `AgentInputFactoryTest.whitelistExcludesExperimentPresetFields` + `DefaultAgentOrchestratorTest.modelInputDoesNotContainExperimentPresetFields`，捕获实际提示词断言无 `experimentFaultType` / `DELAY` / `3500` |
| G | 模型失败、超时、工具失败正确处理 | `DefaultAgentOrchestratorTest.handlesModelFailureWithoutFabricatingResult`、`handlesToolFailureAndDoesNotTurnItIntoRootCause`；`DeepSeekAgentModelClientTest.mapsTimeoutAsRetryable`、`mapsInsufficientBalance`、`mapsAuthenticationFailureWithoutLeakingKey`；`AgentTaskRunnerTest` 失败持久化 |
| H | 任务状态与工具调用记录持久化 | `AgentTaskRunnerTest` 验证状态、结论状态、报告、时间线、模型名、时间戳；`DefaultAgentOrchestratorTest` 验证 `ToolInvocationRepository.save` |
| I | 证据不足拒绝确定根因 | `DefaultAgentOrchestratorTest.returnsInsufficientEvidenceWithoutRootCause`、`AgentTaskRunnerTest.persistsInsufficientEvidenceWithoutRootCause` |
| J | 原有 41 项测试继续通过 | 后端原 17、库存 17、订单 7 全部通过 |

### E.3 真实 DeepSeek 调用结果

**未执行真实 DeepSeek 调用。**

原因：

```text
env:DEEPSEEK_API_KEY=absent
.env:key-absent
```

同时确认网络可达（不带密钥访问 `https://api.deepseek.com/` 返回 HTTP 401，说明域名与代理链路可用），但缺少 Key，无法完成鉴权。未使用静态答案或伪造响应冒充真实调用。

已通过以下方式验证模型客户端与决策逻辑：

- `ScriptedModelClient` 按脚本返回 `CALL_TOOL` / `FINAL_REPORT` / `INSUFFICIENT_EVIDENCE`，验证编排器行为和预算。
- 本地 JDK `HttpServer` Mock DeepSeek `/chat/completions`，验证请求体（`model`、`max_tokens`、`stream=false`、`response_format=json_object`、Bearer 头）、成功响应解析、402/401/超时/缺失 `choices` 的错误映射。
- 已验证 API Key 不出现在错误消息中。

待配置真实 Key 后，建议先执行 1 次最小真实调用（见 H 节），不要直接批量评测。

## F. 诊断报告样例

> **重要：以下为 Mock 模型客户端测试样例，用于验证报告结构与证据 ID 校验，不是真实 DeepSeek 输出，也不是真实数据库中的诊断记录。**

### F.1 根因报告样例（Mock）

```json
{
  "rootCause": "库存服务响应延迟导致订单结算超时",
  "affectedServices": ["inventory-service"],
  "hypothesis": "库存服务在真实请求中耗时超过订单服务读取超时",
  "supportingEvidenceIds": ["log:order-service:log-1"],
  "contradictingEvidenceIds": [],
  "confidenceLevel": "HIGH",
  "recommendedActions": ["人工确认后检查库存服务实例负载"],
  "limitations": ["当前仅有日志与分钟级指标，没有调用链数据"],
  "disclaimer": "诊断结论由 LLM 基于真实工具证据生成，仅供人工确认，不自动执行处置",
  "result": "ROOT_CAUSE_IDENTIFIED",
  "model": "mock-deepseek",
  "modelCallCount": 2,
  "toolCallCount": 1,
  "generatedAt": "2026-10-21T02:00:00Z"
}
```

该样例经过 `AgentReportValidator`：`log:order-service:log-1` 必须存在于 Mock 工具输出的 `evidenceIds` 中；若改成不存在的 ID，报告会被拒绝，并反馈模型重新决策。

### F.2 证据不足样例（Mock）

```json
{
  "result": "INSUFFICIENT_EVIDENCE",
  "reason": "工具未返回足以区分根因的证据",
  "limitations": ["缺少调用链与配置变更数据"],
  "observedEvidenceIds": [],
  "model": "mock-deepseek",
  "modelCallCount": 1,
  "toolCallCount": 0,
  "disclaimer": "证据不足，未生成根因结论；处置建议未执行"
}
```

任务记录会是 `status=SUCCEEDED`、`result_status=INSUFFICIENT_EVIDENCE`、`root_cause=null`、`confidence_level=null`，前端显示证据不足警告，不显示根因卡片。

### F.3 真实 DeepSeek 结果

**无。** 当前环境无 Key，未生成任何真实模型报告。不得将 F.1/F.2 标注为真实 DeepSeek 结果。

## G. 失败处理与安全限制

### G.1 模型与工具失败

| 场景 | 行为 |
| --- | --- |
| 未配置 `DEEPSEEK_API_KEY` | 任务 `FAILED`，原因“未配置 DEEPSEEK_API_KEY，Agent 未调用模型，也未生成任何诊断结论” |
| 401/403 | `AUTH_FAILED`，不可重试，失败原因不含 Key |
| 402 | `INSUFFICIENT_BALANCE`，不可重试 |
| 429 / 5xx | `RATE_LIMITED` / `PROVIDER_ERROR`，作为可重试错误占用下一轮模型预算；不无限重试 |
| 网络超时 | `TIMEOUT`，可重试，但仍受最大轮数与 180 秒整体超时限制 |
| 模型输出非法 JSON / 字段缺失 | 记录 `INVALID_OUTPUT`，反馈模型下一轮修正，不执行工具 |
| 未注册工具 / 非法参数 | `TOOL_REJECTED`，不执行，不写 `tool_invocation` |
| 工具执行异常/失败 | `TOOL_EXECUTION` 状态 `FAILED`，错误进入观察，不转成根因 |
| 报告引用不存在证据 | `FINAL_REPORT_REJECTED`，不返回该报告；模型可重试或输出证据不足 |

### G.2 安全边界

- 只读工具：`query_logs`、`query_metrics`；无 Shell、SQL、任意 HTTP、自动修复工具。
- 工具参数白名单与范围校验；`requestId` 仅允许字母、数字、点、下划线、冒号、连字符，最长 64。
- 服务范围固定为 `all` / `order-service` / `inventory-service`。
- 最终报告只接受真实工具输出中的 `evidenceIds`；支持与反驳证据不得重叠。
- 实验预设字段不进入模型输入；前端仍将预设与实际观测分开展示。
- 根因、置信度、处置建议只作为人工确认依据；系统不自动执行处置。
- 不保存模型原始思维链，只保存决策摘要、工具观察和错误时间线。
- API Key 只从环境变量读取，不打印、不写数据库、不写仓库。

### G.3 未执行/未覆盖说明

- 当前环境未启动 Spring Boot 后端，因此未做浏览器端到端点击验证；前端已完成生产构建和代码路径检查。
- 未执行真实 DeepSeek 调用，原因为缺少 Key。
- H 项的持久化由 Mockito 仓库测试和实体字段断言验证；真实 PostgreSQL 的 Flyway 应用与 JPA 校验需开发者首次启动后端时完成。
- 未执行 `mvn package`、`mvn clean package`，未提交 Git。

## H. Day 4 建议

1. **真实 DeepSeek 冒烟测试**：在安全环境配置 `DEEPSEEK_API_KEY` 后，先跑 1 个事件、限制 3 次模型调用与 2 次工具调用，核对真实返回、token 用量与费用，再扩大到实验集。
2. **数据库集成验证**：启动 PostgreSQL 与后端，确认 Flyway V3 正式应用、`ddl-auto=validate` 通过，创建真实任务并检查 `diagnosis_task` / `tool_invocation` 数据。
3. **端到端演示脚本**：故障注入 → 真实请求 → 事件详情 → 启动 AI 诊断 → 轮询时间线 → 根因/证据不足 → 人工处置建议，固定一套可重复命令。
4. **提示词与评测**：准备少量标注事件，评估工具选择准确性、证据引用准确性和 `INSUFFICIENT_EVIDENCE` 判定；记录模型版本、温度、token 成本。
5. **工具扩展**：按需增加 `query_traces`、`query_service_status`、`query_config_diff`，继续沿用只读、白名单、evidenceId 与审计约束。
6. **前端完善**：将诊断任务历史做成独立页面，支持按事件/状态筛选；增加任务耗时与 token 统计，但不得展示虚构指标。
7. **演示保障**：提前检查代理、额度、Key 有效期；准备“无 Key 时明确失败”的降级演示，避免现场伪造诊断结果。

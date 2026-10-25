# Day 7 故障诊断评测体系设计与实施计划

- 阶段：设计（本轮不实现功能）
- 日期：2026-10-24
- 调研方式：仅按需阅读故障注入、事件创建、Agent 诊断、报告/证据、指标统计相关代码与配置；未启动服务、未执行测试、未调用 DeepSeek、未修改生产代码/测试代码/配置、未提交 Git
- 目标：建立可重复、可量化、成本可控的故障诊断评测体系，为毕业论文提供可信实验数据

---

## 1. 现有能力调研结论

### 1.1 故障注入与真实请求链路

| 能力 | 现有实现 | 关键事实 |
| --- | --- | --- |
| 故障开关 | `POST /api/experiments/faults/enable`、`disable`、`reset` | 故障保存在 inventory-service 内存中，默认禁用，需 `EXPERIMENT_FAULT_INJECTION_ENABLED=true`；默认 120 秒自动过期 |
| 故障类型 | `FaultType.DELAY`、`FaultType.HTTP_500` | 仅两种；`delayMs` 上限默认 4500 |
| 真实请求 | `POST /api/experiments/{experimentId}/execute` | order-service 真实调用 inventory-service，非模拟 |
| 无故障对照 | `POST /api/experiments/checkout` | 无激活故障时执行真实结算请求，不创建事件 |
| 观测分类 | `ObservationClassifier` | 根据 HTTP 状态、下游错误标记、耗时判定“观测到了什么”，不推断根因 |

关键配置与行为：

- order-service 的 `inventory.read-timeout-ms` 在 `application.yml` 中默认 **5000 ms**（代码注解默认值 3000 被配置覆盖）。
- inventory-service 的 `EXPERIMENT_FAULT_INJECTION_MAX_DELAY_MS` 默认 **4500 ms**。
- 管理后端 `experiment.slow-response-threshold-ms` 默认 **3000 ms**。

由此得到一个必须在评测设计中正视的结论：

- `HTTP_500`：inventory 过滤器短路返回 500，不扣减库存；order 映射为 502，响应头 `X-Downstream-Error-Type: HTTP_5XX`；事件类型 `DOWNSTREAM_HTTP_5XX`。
- `DELAY 3500`：order 读超时 5000 ms 未触发，请求约 3.5 秒成功；但耗时超过后端慢响应阈值 3000 ms，分类为 `SLOW_RESPONSE`，仍创建事件（严重级别 WARNING），库存正常扣减。
- 默认配置下 **DELAY 无法制造 `DOWNSTREAM_TIMEOUT`**：最大延迟 4500 ms 小于 order 读超时 5000 ms。若论文需要“超时”样本，必须显式调整环境变量（如 order `INVENTORY_READ_TIMEOUT_MS=3000`，或提高 inventory `EXPERIMENT_FAULT_INJECTION_MAX_DELAY_MS`），不能只改 case 文件。
- 正常请求：返回 200、`faultObserved=false`、`faultEventId=null`，不进入诊断。

### 1.2 事件创建与分类结果

`ExperimentService.runCheckout` 的产物：

| 场景 | HTTP 状态 | `outcome` | `faultObserved` | 事件类型 | 事件服务名 | 库存副作用 |
| --- | --- | --- | --- | --- | --- | --- |
| HTTP_500 | 502 | `DOWNSTREAM_HTTP_5XX` | true | `DOWNSTREAM_HTTP_5XX` | inventory-service | 无 |
| DELAY 3500 | 200 | `SLOW_RESPONSE` | true | `SLOW_RESPONSE` | inventory-service | 扣减 |
| DELAY 6000（需调配置） | 502 | `DOWNSTREAM_TIMEOUT` | true | `DOWNSTREAM_TIMEOUT` | inventory-service | 延迟后仍扣减 |
| 正常 | 200 | `SUCCESS` | false | 不创建 | — | 扣减 |
| 库存不足 | 409 | `BUSINESS_CONFLICT` | false | 不创建 | — | 无 |

事件同时保存 `experiment_fault_type`、`experiment_parameters` 和 `observed_result`，但 Agent 输入白名单只保留 `outcome/httpStatus/durationMs/downstreamErrorType/orderStatus/responseMessage/transportError/observedAt`，**预设故障类型不会进入模型提示词**。这是评测公平性的基础。

`ExperimentExecutionView` 直接返回 `outcome`、`faultObserved`、`httpStatus`、`durationMs`、`downstreamErrorType`、`faultEventId`，可作为评测 Harness 的观测校验输入。

### 1.3 Agent 诊断任务与结果持久化

| 能力 | 接口 | 说明 |
| --- | --- | --- |
| 启动诊断 | `POST /api/fault-events/{eventId}/diagnosis` | 创建 PENDING 任务并异步执行；同一事件仅允许一个活跃 Agent 任务 |
| 查询详情 | `GET /api/diagnosis/tasks/{taskId}` | 返回状态、结论、报告、Agent 输入、决策轨迹、工具调用列表 |
| 查询列表 | `GET /api/diagnosis/tasks?faultEventId=...` | 返回该事件的历史任务 |
| 重试 | `POST /api/diagnosis/tasks/{taskId}/retry` | 创建新任务，保留旧记录 |

任务终态：`SUCCEEDED`、`FAILED`、`CANCELLED`。结论状态 `resultStatus`：`ROOT_CAUSE_IDENTIFIED`、`INSUFFICIENT_EVIDENCE`（任务本身仍为 SUCCEEDED）。

### 1.4 根因报告数据结构

`AgentReportValidator` 强制要求的字段：

| 字段 | 约束 |
| --- | --- |
| `rootCause` | 3–1024 字符 |
| `affectedServices` | 1–2 个，仅允许 order-service / inventory-service |
| `hypothesis` | 3–1024 字符 |
| `supportingEvidenceIds` | 至少 1 个，必须来自真实工具结果 |
| `contradictingEvidenceIds` | 可为空，不得与支持证据重复 |
| `confidenceLevel` | HIGH / MEDIUM / LOW |
| `recommendedActions` | 0–10 条 |
| `limitations` | 0–10 条 |

报告不包含结构化的“故障类型”字段。评测中的故障类型正确性只能基于 `rootCause`、`hypothesis` 和 `affectedServices` 文本做规则判断；这是当前评测体系的主要限制之一（见 2.12）。

### 1.5 工具轨迹、耗时与 Token 统计

- 工具调用记录落库 `tool_invocation`：工具名、入参、输出、状态、`latency_ms`、`sequence_no`、`invoked_at`。
- 决策轨迹 `decision_trace` 包含 `MODEL_DECISION`、`TOOL_EXECUTION`、`TOOL_REJECTED`、`MODEL_DECISION/DEADLINE_EXCEEDED`、`AGENT_ERROR` 等条目。
- 任务结束时追加 `AGENT_RUN_SUMMARY`，包含：
  - `modelCallCount`、`modelCallsWithUsage`、`modelCallsWithoutUsage`
  - `promptTokens`、`completionTokens`、`totalTokens`
  - `modelLatencyMsTotal`、`usageNote`
- 报告 JSON 中也会写入同样的统计字段，便于直接读取，无需解析轨迹。
- 证据 ID 格式：
  - 日志：`log:<service>:<logId>`
  - 指标：`metric:<service>:<minute>`

### 1.6 现有测试与评测基础

- 后端 20 个测试类、106 项测试，覆盖 Agent 编排、报告校验、工具、事件状态、迁移与恢复。
- 仓库中**没有**评测脚本、样本定义、批量运行器或结果报告。
- `docs/plans/day6-project-review.md` 已指出论文评测证据偏薄，并建议矩阵：HTTP_500、DELAY、正常对照，各运行 5–10 次，记录根因正确率、证据命中率、耗时、Token、失败原因。
- 当前能力足以支撑黑盒评测：注入 → 执行 → 事件 → 诊断 → 报告/轨迹均可通过现有 HTTP API 获取，无需修改生产代码。

---

## 2. 评测体系设计

### 2.1 设计原则

1. **黑盒评测**：只调用现有 HTTP API，不直接改数据库、不修改生产代码，避免评测逻辑污染系统。
2. **标签先于运行**：期望根因、期望观测、证据匹配规则在 case 文件中预先定义，运行后不得根据模型输出修改标签。
3. **确定性判分**：用规则匹配报告文本和证据 ID，不让 LLM 给 LLM 打分；原始报告保留，供人工抽检复核。
4. **可重复**：固定 case、固定顺序、固定等待时间、记录环境指纹；支持从原始结果重新判分（rescore），无需重复调用 LLM。
5. **成本可控**：默认只跑少量样本；真实运行必须显式确认；支持硬上限与预估；正常场景不调用 LLM。
6. **失败不隐藏**：诊断失败、超时、证据不足、环境异常分别记录，不把失败样本简单丢弃。
7. **与论文口径一致**：同时报告总体指标、分场景指标和失败案例，避免只展示成功样本。

### 2.2 场景与样本矩阵

#### P0 基线矩阵（默认配置，无需改环境）

| 场景 ID | 注入 | 预期观测 | 预期事件 | 预期根因服务 | 预期故障类别 | 是否调用 LLM |
| --- | --- | --- | --- | --- | --- | --- |
| `http500` | `HTTP_500` | 502，`DOWNSTREAM_HTTP_5XX` | 是，ERROR | inventory-service | `HTTP_500` | 是 |
| `delay-slow` | `DELAY 3500ms` | 200，`SLOW_RESPONSE`，耗时约 3500ms | 是，WARNING | inventory-service | `DELAY_SLOW` | 是 |
| `normal` | 无 | 200，`SUCCESS` | 否 | — | `NONE` | 否 |

说明：

- `delay-slow` 的判定依赖 order 读超时 5000ms 与后端慢阈值 3000ms；若实际环境不同，Harness 必须报 `CONFIG_MISMATCH`，而不是把结果算作模型错误。
- `normal` 是假阳性对照：只要创建了事件，就记为系统误报，不进入 LLM 调用。
- HTTP_500 不扣库存；DELAY 与 normal 每次扣 1 件库存。

#### P1 扩展矩阵（需明确配置变更）

| 场景 ID | 注入 | 前提 | 预期观测 | 预期故障类别 |
| --- | --- | --- | --- | --- |
| `delay-timeout` | `DELAY 6000ms` | order `INVENTORY_READ_TIMEOUT_MS=3000`，inventory `MAX_DELAY_MS>=6000` | 502，`DOWNSTREAM_TIMEOUT` | `DELAY_TIMEOUT` |
| `delay-below-threshold` | `DELAY 1500ms` | 默认配置 | 200，`SUCCESS`，无事件 | `NONE`（阈值对照） |
| `http500-repeat` | `HTTP_500` | 默认配置 | 同 P0 | `HTTP_500`（用于增加重复次数） |

`delay-timeout` 是“延迟导致下游超时”的强场景，但需要改环境变量；是否纳入论文主矩阵需先确认，不能与 P0 基线混在同一环境批次里。

### 2.3 样本定义格式

建议使用 JSON（避免引入 YAML 依赖），一个 suite 文件包含多个 case 与默认参数：

```json
{
  "suiteId": "day7-baseline-v1",
  "labelVersion": "1.0",
  "labelSource": "注入配置 + ObservationClassifier 预期输出，运行前冻结",
  "defaults": {
    "repeat": 3,
    "cooldownSeconds": 5,
    "waitAfterExecutionMs": 2000,
    "faultDurationSeconds": 120,
    "sku": "SKU-1001",
    "quantity": 1,
    "diagnosisTimeoutSeconds": 240
  },
  "cases": [
    {
      "caseId": "http500",
      "scenario": "HTTP_500",
      "injection": {"faultType": "HTTP_500", "delayMs": null},
      "expected": {
        "faultObserved": true,
        "outcome": "DOWNSTREAM_HTTP_5XX",
        "httpStatus": 502,
        "eventExpected": true,
        "rootCauseService": "inventory-service",
        "faultCategory": "HTTP_500"
      },
      "judge": {
        "serviceAliases": ["inventory-service", "库存服务", "库存"],
        "categoryKeywords": ["HTTP 500", "500", "5xx", "内部错误", "服务器错误"],
        "riskKeywords": ["连接拒绝", "DNS"],
        "evidenceMatchers": ["log:inventory-service:*", "log:order-service:*"],
        "minEvidenceMatchers": 1
      },
      "diagnosis": true
    },
    {
      "caseId": "delay-slow",
      "scenario": "DELAY",
      "injection": {"faultType": "DELAY", "delayMs": 3500},
      "expected": {
        "faultObserved": true,
        "outcome": "SLOW_RESPONSE",
        "httpStatus": 200,
        "minDurationMs": 3000,
        "eventExpected": true,
        "rootCauseService": "inventory-service",
        "faultCategory": "DELAY_SLOW"
      },
      "judge": {
        "serviceAliases": ["inventory-service", "库存服务", "库存"],
        "categoryKeywords": ["延迟", "慢响应", "响应时间", "耗时", "delay"],
        "riskKeywords": ["超时", "timeout"],
        "evidenceMatchers": ["log:inventory-service:*", "log:order-service:*"],
        "minEvidenceMatchers": 1
      },
      "diagnosis": true
    },
    {
      "caseId": "normal",
      "scenario": "NORMAL",
      "injection": null,
      "expected": {
        "faultObserved": false,
        "outcome": "SUCCESS",
        "eventExpected": false
      },
      "judge": null,
      "diagnosis": false
    }
  ]
}
```

字段含义：

- `expected.outcome` / `httpStatus`：由现有 `ObservationClassifier` 在给定配置下的确定性输出推导，不是模型输出。
- `expected.faultCategory`：注入故障的类别标签，`DELAY_SLOW` 与 `DELAY_TIMEOUT` 分开，避免把“慢响应”和“超时”混为一谈。
- `judge.serviceAliases`：根因服务同义词，命中任意一个即认为提到期望服务。
- `judge.categoryKeywords`：故障类别关键词；命中任意一个即认为类别正确。
- `judge.riskKeywords`：不作为自动失败条件，只记录风险标记，供人工复核（例如慢响应场景出现“超时”可能是不精确表述，也可能是“未超时”的否定句）。
- `judge.evidenceMatchers`：支持证据 ID 前缀匹配；`minEvidenceMatchers` 为至少覆盖的类别数。
- `diagnosis`：是否启动 LLM 诊断；正常对照固定为 false。

### 2.4 Ground truth 来源

| 标签 | 来源 | 是否会被模型看到 |
| --- | --- | --- |
| 注入故障类型 | Harness 的 enable 请求 / case 文件 | 否（Agent 输入白名单已剔除） |
| 期望观测结果 | 按当前配置对 `ObservationClassifier` 的确定性推演，预先写入 case | 否 |
| 期望根因服务 | 注入位置 = inventory-service | 否 |
| 期望证据类型 | 预先定义 `log:<service>:*` / `metric:<service>:*` | 否 |
| 诊断报告内容 | 运行后从任务详情读取 | 是模型自己的输出，仅用于判分，不作为标签 |

禁止事项：

- 不得用 `rootCause`、`hypothesis`、`affectedServices` 反推期望答案。
- 不得在看过模型输出后修改 case 的期望值；如需修正标签，必须新建 `labelVersion` 并说明原因，旧结果按旧标签重算。
- 不得把“事件服务名”直接当作标签来源；事件服务名是系统分类结果，只用于观测一致性检查。

### 2.5 真实诊断结果获取流程

每个可诊断样本：

1. 预检：`GET /api/health`、`GET /api/experiments/faults`、库存余量；确认无激活故障、无同事件活跃任务、`DEEPSEEK_API_KEY` 已配置。
2. 开启故障：`POST /api/experiments/faults/enable`，记录 `experimentId`；normal 场景跳过并调用 `disable` 确保无残留。
3. 执行真实请求：`POST /api/experiments/{experimentId}/execute`（normal 用 `/checkout`），记录完整 `ExperimentExecutionView`。
4. 立即关闭故障：`POST /api/experiments/faults/disable`，并 `GET` 复核 `active=false`；避免影响后续样本。
5. 校验观测：对比 `faultObserved`、`outcome`、`httpStatus`、`durationMs` 与 case 期望；不一致记为 `OBSERVATION_MISMATCH`。
6. 等待日志落盘：默认 `waitAfterExecutionMs=2000`（DELAY 场景 inventory 请求日志在延迟结束后才写入）。
7. 启动诊断：`POST /api/fault-events/{faultEventId}/diagnosis`，记录 `taskId`。
8. 轮询终态：`GET /api/diagnosis/tasks/{taskId}`，间隔 1–2 秒，截止 `diagnosisTimeoutSeconds`（默认 240 秒 = 180 秒 Agent 预算 + 60 秒余量）。
9. 抓取结果：任务详情中的 `report`、`agentInput`、`decisionTrace`、`toolInvocations` 原样保存。
10. 判分与记录：按 2.6–2.8 计算指标，写入样本行。
11. 冷却：等待 `cooldownSeconds`，避免指标窗口与日志窗口相互污染。

失败与清理：

- 无论成功失败，`finally` 中必须关闭故障并复核。
- 诊断失败不自动重试；重试会改变样本语义，如需重试必须作为新样本记录。
- 事件与任务不自动删除；结果中保留 `eventId/taskId`，便于追溯。
- 若连续 2 个样本出现环境级错误，整轮 suite 中止，避免继续消耗。

### 2.6 判分维度与规则

单个可诊断样本的记录字段与判分：

| 维度 | 规则 | 说明 |
| --- | --- | --- |
| `observationPass` | `faultObserved`、`outcome`、`httpStatus` 与期望一致；`minDurationMs` 满足 | 环境/注入有效性，不计入模型准确率 |
| `taskOutcome` | `SUCCEEDED / INSUFFICIENT_EVIDENCE / FAILED / HARNESS_TIMEOUT / INVALID_ENV` | 任务级结果 |
| `serviceCorrect` | 期望服务别名出现在 `rootCause + hypothesis + affectedServices` 中 | 文本规则 |
| `categoryCorrect` | 期望类别关键词命中任意一个 | 文本规则 |
| `evidenceCoverage` | 命中的证据类别数 / `minEvidenceMatchers`（上限 1.0） | 证据 ID 前缀匹配 |
| `strictCorrect` | `taskOutcome=SUCCEEDED` 且 `resultStatus=ROOT_CAUSE_IDENTIFIED` 且 `serviceCorrect` 且 `categoryCorrect` 且 `evidenceCoverage>=1.0` | 论文主指标 |
| `riskFlags` | 命中 `riskKeywords`、服务名冲突、证据为空等 | 只提示，不自动判错 |

补充规则：

- `INSUFFICIENT_EVIDENCE`：`strictCorrect=false`，但单独统计“证据不足率”，并记录 `report.reason` 与 `limitations`。
- `FAILED`：所有准确率维度为 false；按 `failureReason` 分类（见 2.9）。
- 正常对照不参与准确率分母；单独统计 `FALSE_ALARM`（创建了事件/启动了诊断）与 `NORMAL_PASS`。
- `confidenceLevel` 只记录分布，不参与正确性判断；避免把模型的自信程度当作准确率。
- 同一 case 多次运行的准确率按 `strictCorrect` 求平均；同时报告 `serviceAccuracy`、`categoryAccuracy`、`evidenceCoverageAvg`。

文本判分的已知限制：

- 关键词命中无法可靠处理否定句（“并未发生超时”）。P0 只记录 `riskFlags` 并保留原文；P1 增加简单否定检测；最终论文需人工抽检一定比例（建议 20%）并报告一致率。
- `affectedServices` 受 validator 限制只能包含两个已知服务，服务名判断相对可靠；`rootCause` 文本仍是主要判断来源。

### 2.7 证据支持评估

证据来源与验证：

1. `supportingEvidenceIds` 由 `AgentReportValidator` 保证来自真实工具结果，Harness 再做一次不变量检查：
   - 每个 ID 必须出现在某个成功工具调用的 `evidenceIds` 中；
   - 否则记 `EVIDENCE_GROUNDING_VIOLATION`（属于系统缺陷，不是模型判分问题）。
2. 证据类别覆盖：按 `log:<service>:*`、`metric:<service>:*` 与 case 的 `evidenceMatchers` 计算 `evidenceCoverage`。
3. 证据数量：`supportingEvidenceCount`、`contradictingEvidenceCount`。
4. 证据来源服务：统计支持证据涉及的服务集合，检查是否覆盖期望根因服务。
5. 工具使用情况：`toolExecutionCount`、`toolFailureCount`、`toolRejectionCount`、`toolSuccessRate`。
6. P1 增强：从 `tool_invocations[].outputPayload` 中检查信号级证据，例如：
   - HTTP_500：inventory 日志条目 `status=500`，或 order 日志 `status=502` 且 `errorType=HTTP_5XX`；
   - DELAY：inventory 日志 `durationMs>=3000` 且 `status=200`；
   - 正常：不应存在故障证据。
   信号级匹配比 ID 前缀更严格，但需要解析工具输出结构，放入 P1。

### 2.8 指标定义

| 指标 | 来源 / 计算 |
| --- | --- |
| `wallClockMs` | `task.completedAt - task.createdAt` |
| `executionMs` | `task.completedAt - task.startedAt`（模型 + 工具执行） |
| `observationDurationMs` | 实验执行返回的 `durationMs` |
| `modelCallCount` | `AGENT_RUN_SUMMARY.modelCallCount`（缺省时按轨迹统计） |
| `toolExecutionCount` | `decisionTrace` 中 `TOOL_EXECUTION` 数量，或 `toolInvocations` 行数 |
| `toolFailureCount` | `toolInvocations` 中 `status=FAILED` 的数量 |
| `promptTokens` / `completionTokens` / `totalTokens` | `AGENT_RUN_SUMMARY` 或 `report` 中同名字段 |
| `modelCallsWithoutUsage` | 无 usage 的模型调用数；大于 0 时 Token 指标需标注“不完整” |
| `modelLatencyMsTotal` | `AGENT_RUN_SUMMARY.modelLatencyMsTotal` |
| `toolLatencyMsTotal` | `sum(toolInvocations[].latencyMs)` |
| `estimatedCost` | 可选，由命令行传入每百万 Token 单价计算；默认只记录 Token，不硬编码价格 |

聚合统计（按 case 与总体）：

- 样本数、有效样本数、环境异常数
- 诊断成功率、根因识别率、证据不足率、失败率
- `strictAccuracy`、`serviceAccuracy`、`categoryAccuracy`
- `evidenceCoverageAvg`
- 耗时：均值、中位数、P95
- Token：均值、总计
- 失败原因分布

### 2.9 失败、超时与无故障处理

| 状态 | 触发条件 | 处理 |
| --- | --- | --- |
| `NORMAL_PASS` | 正常场景无事件、无诊断 | 计入对照通过 |
| `FALSE_ALARM` | 正常场景创建了事件 | 记为系统误报，不计入模型准确率 |
| `OBSERVATION_MISMATCH` | 注入成功但观测结果与预期不符 | 记为环境/配置问题，样本无效 |
| `INVALID_ENV` | 服务不可用、故障注入未启用、库存不足、409、实验未激活 | 排除出准确率分母，整轮统计中单独报告 |
| `START_FAILED` | 诊断启动接口报错（409/503/500） | 记录错误体；不自动重试 |
| `HARNESS_TIMEOUT` | 轮询超过 `diagnosisTimeoutSeconds` 仍未终态 | 记录当前状态与任务 ID；后续可人工查询 |
| `INSUFFICIENT_EVIDENCE` | 任务 SUCCEEDED 但 `resultStatus=INSUFFICIENT_EVIDENCE` | 记失败样本，保留原因 |
| `FAILED` | 任务 FAILED | 按失败原因分类 |
| `BUDGET_TIMEOUT` | `failureReason` 含“超时/预算/TIMEOUT” | 与模型错误区分统计 |
| `CONFIG_MISSING` | `failureReason` 含 `DEEPSEEK_API_KEY` | 记为环境配置问题，不计模型能力 |
| `MODEL_ERROR` | 模型调用失败（非超时、非配置） | 记录错误类型 |
| `AGENT_ERROR` | 编排/持久化异常 | 记录错误信息 |

重试策略：

- GET 查询允许最多 3 次短重试（仅网络错误/5xx），间隔 1 秒。
- POST 启动诊断不自动重试；重试会产生新任务，改变样本语义。
- 判定为 `INVALID_ENV` 的样本不重跑；需要修正环境后重新开一轮 suite。

### 2.10 报告格式

输出目录：`eval/results/run-<UTC时间戳>/`

| 文件 | 内容 |
| --- | --- |
| `manifest.json` | suiteId、labelVersion、开始/结束时间、Harness 版本、服务地址、模型名、case 数量、实际诊断次数、环境前提、Git commit（可选）、配置快照（不含密钥） |
| `samples.jsonl` | 每行一个样本，包含 case、重复序号、观测、任务终态、报告、判分结果、耗时/Token/工具调用 |
| `summary.json` | 按 case 与总体的聚合指标、失败分布、Token 汇总 |
| `summary.csv` | 扁平表，UTF-8 with BOM（`utf-8-sig`），便于 Excel/论文直接使用 |
| `raw/<caseId>-<n>.json` | 原始响应快照：实验执行结果、事件详情、任务详情（可选，便于复核） |
| `failures.md` | 失败/证据不足样本列表与原因摘要（P1） |

`summary.csv` 建议列：

```text
suiteId,caseId,repeat,scenario,expectedCategory,expectedService,
observedOutcome,httpStatus,observationPass,taskStatus,resultStatus,
diagnosisOutcome,serviceCorrect,categoryCorrect,evidenceCoverage,strictCorrect,
riskFlags,supportingEvidenceCount,contradictingEvidenceCount,
modelCallCount,toolExecutionCount,toolFailureCount,promptTokens,
completionTokens,totalTokens,wallClockMs,executionMs,modelLatencyMsTotal,
toolLatencyMsTotal,failureReason,eventId,taskId,startedAt,completedAt
```

### 2.11 成本控制

控制手段：

1. `plan` 模式：只读预检 + 打印案例矩阵、预计诊断次数、预计 Token，不产生故障、不启动诊断。
2. `--yes` 确认：没有该参数只做 plan，避免误触发真实调用。
3. `--max-diagnosis-runs` 硬上限（默认 6）。
4. `--repeats` 默认 1（冒烟）；基线建议 3；论文最终 5–10。
5. 正常对照不调用 LLM。
6. 每轮结束统计实际 Token；超过 `--token-budget` 软上限时停止后续样本（不能中断已发起的调用）。
7. 连续 2 个环境错误即中止。
8. 不自动重试失败诊断；重试需人工新建 suite。

### 2.12 评测效度与局限

1. **服务名提示**：`DOWNSTREAM_HTTP_5XX` / `SLOW_RESPONSE` 事件的 `serviceName` 已经是 inventory-service，模型输入也包含该字段。因此“根因服务准确率”会偏高，论文中应明确说明；更有区分度的是“故障类别判断”和“证据覆盖”。
2. **文本判分**：报告没有结构化故障类型字段，只能按关键词判断，存在否定句误判。P0 用人工抽检兜底，P2 再考虑结构化字段（需改生产代码）。
3. **样本规模小**：基线每场景 3 次，只能作为可行性数据；论文结论建议 5–10 次并报告方差。
4. **模型非确定性**：temperature 0.1 仍可能产生不同报告；同一 case 多次运行必须全部保留，不能只报最好一次。
5. **单模型**：当前仅 DeepSeek；跨模型对比需要额外 API 与成本，不在 Day 7 P0。
6. **配置漂移**：DELAY 的预期结果依赖 order 读超时、inventory 最大延迟、后端慢阈值；Harness 只能通过行为和文档校验，无法直接读取 order 配置。
7. **无自动处置**：评测只覆盖“分析与建议”，不覆盖自动修复/回滚，论文中不得宣称闭环处置。
8. **事件/任务数据增长**：每轮会新增事件、任务、工具调用记录；不自动删除，需在论文实验后统一归档。

---

## 3. 实施计划

### 3.1 P0：最小可行评测（黑盒 Harness）

目标：在不改生产代码的前提下，跑通 3 个场景、输出 JSON/CSV、完成 1 次真实冒烟与 3 次基线。

| 文件 | 职责 | 最小可行实现 |
| --- | --- | --- |
| `eval/README.md` | 使用说明、环境前提、成本控制、结果字段解释 | 说明服务地址、`EXPERIMENT_FAULT_INJECTION_ENABLED=true`、`DEEPSEEK_API_KEY`、库存前提、命令示例 |
| `eval/cases/day7-baseline.json` | 基线样本与 ground truth | 3 个 case：`http500`、`delay-slow`、`normal`；默认 repeat=3；判分关键词与证据匹配器 |
| `eval/run_eval.py` | 单文件 Harness | Python 3 标准库（`urllib`）；子命令 `plan` / `run` / `rescore`；预检、注入、执行、关闭、启动诊断、轮询、判分、写报告 |
| `eval/tests/test_scoring.py` | 判分逻辑单元测试 | 纯函数测试：服务命中/未命中、类别命中、证据覆盖、失败状态、正常误报、CSV 序列化；使用固定 fixture，不联网 |
| `eval/results/.gitignore` | 结果目录管理 | 忽略原始运行结果，保留目录说明；是否提交精选 summary 由用户决定 |

P0 关键实现细节：

- HTTP 客户端只用标准库，避免新增依赖；JSON 读写用 `json`，CSV 用 `csv`。
- API 基址通过 `--backend-url` 传入，默认 `http://localhost:8080`。
- 每个 case 的运行顺序固定；重复序号写入样本。
- 判分函数与网络层分离，便于 `rescore` 和单元测试。
- `plan` 模式允许读取 `GET /api/health`、`GET /api/experiments/faults`、库存，不写任何数据。
- `run` 模式必须带 `--yes`；每轮 `finally` 关闭故障。
- 结果中禁止写入任何密钥；只记录模型名、服务地址、case 与报告文本。

P0 测试方案：

1. 离线单元测试：`python -m unittest discover -s eval/tests -v`（用户手动执行）。
2. `plan` 模式：确认输出 3 个 case、预计 6 次诊断（3 次重复 × 2 个故障场景）、无副作用。
3. 真实冒烟：`--repeats 1`，2 次诊断，人工核对事件、任务、报告、CSV。
4. 基线：`--repeats 3`，6 次诊断，生成 summary。
5. `rescore`：对同一原始目录重新判分，结果应与首次 summary 一致。

P0 预期产出：

- 一份可复现的基线结果目录（manifest、samples.jsonl、summary.json、summary.csv）。
- 论文可引用的基础指标：严格准确率、服务准确率、类别准确率、证据覆盖率、平均耗时、模型/工具调用次数、Token。
- 失败案例清单与原因。

P0 风险：

- 评测脚本误留故障：通过 `finally` + disable 复核缓解。
- 库存被耗尽：预检余量，按样本数校验；HTTP_500 不扣库存，DELAY/normal 扣 1。
- 事件/任务污染数据库：保留 ID、不自动删除；如需清理另行确认。
- 关键词判分误判：保留原文与 riskFlags，人工抽检。
- 配置与预期不符：预检 + `OBSERVATION_MISMATCH`，不把环境问题算作模型错误。

P0 真实 DeepSeek 调用次数：

- 冒烟：2 次诊断（HTTP_500 ×1、DELAY ×1），约 8 次模型调用、约 2.2 万 Token（按 Day 6 实测约 4 次调用/10.8k Token 估算）。
- 基线：6 次诊断（2 场景 ×3 重复），约 24 次模型调用、约 6.5 万 Token。
- 正常对照：0 次调用。

### 3.2 P1：可分析性与扩展矩阵

目标：让评测结果更适合写论文，并覆盖“延迟导致超时”的强场景。

| 文件 | 职责 | 最小可行实现 |
| --- | --- | --- |
| `eval/rca_eval/client.py` | HTTP 客户端与重试 | 从 `run_eval.py` 拆出；GET 重试、超时、错误分类 |
| `eval/rca_eval/scoring.py` | 判分与指标 | 否定词处理、信号级证据匹配、失败分类 |
| `eval/rca_eval/report.py` | 报告生成 | summary JSON/CSV/Markdown、按 case 聚合、P95 |
| `eval/rca_eval/models.py` | 数据结构 | case/sample 数据类与校验 |
| `eval/cases/day7-extended.json` | 扩展矩阵 | `delay-timeout`（需环境变更）、`delay-below-threshold`、更多重复 |
| `eval/tests/test_client.py` | 客户端测试 | 本地 `http.server` 模拟 200/409/超时，不联网 |
| `docs/plans/day7-evaluation-results.md` | 结果记录模板 | 记录每轮环境、命令、指标、失败分析（执行后填写） |

P1 关键点：

- 信号级证据匹配：解析 `toolInvocations[].outputPayload`，检查 `items[].status/durationMs/errorType`，而不是只看证据 ID 前缀。
- 否定句处理：在关键词前后 10 个字符内出现“不/未/没有/并非”时降级为 riskFlag，不直接判正确。
- 支持 `--cases` 选择性执行，避免每次都跑全矩阵。
- 支持 `rescore` 从 `raw/` 重建样本，不调用模型。
- Markdown 报告包含：环境指纹、场景表、总体指标、分场景指标、失败案例、结论限制。

P1 调用估算：

- `delay-timeout` + `delay-slow` + `http500` 各 5 次 = 15 次诊断，约 60 次模型调用、约 16 万 Token。
- 若加入 `delay-below-threshold` 与正常对照，不增加 LLM 调用。
- 注意：`delay-timeout` 需要改 order/inventory 环境变量并重启服务，属于人工操作，不在 Harness 内自动修改。

### 3.3 P2：离线回归与能力扩展（可选）

| 方向 | 内容 | 说明 |
| --- | --- | --- |
| 离线回放 | 把真实 `raw/` 结果固化为 fixture，CI 中只跑判分与报告生成 | 零 LLM 成本，防止 Harness 回归 |
| 论文图表 | 准确率、耗时、Token 的分组柱状图/箱线图 | 可由 summary.json 生成，需确认图表规范 |
| 结构化故障类别 | 在报告 schema 增加 `faultCategory` 字段 | 需改 prompt、validator、前端与测试，属于生产代码变更，建议单独评审 |
| 新故障类型 | 连接拒绝、下游 4xx、部分失败 | 需改 inventory 故障注入，超出 Day 7 范围 |
| 多模型对比 | 接入第二个模型 | 需要额外 API、成本与配置，暂不建议 |

P2 不设固定调用次数；默认不执行真实 LLM，仅在人工确认后进行对比实验。

---

## 4. 验收标准

Day 7 评测体系完成的最低标准：

1. `plan` 模式无副作用，能输出 3 个基线 case、重复次数、预计诊断次数与 Token。
2. `run --repeats 1 --yes` 能完成 HTTP_500、DELAY、正常三个场景，正常场景不调用 LLM。
3. 每个故障样本都能拿到 `eventId`、`taskId`、最终报告、证据 ID、工具调用、Token 与耗时。
4. 输出 `manifest.json`、`samples.jsonl`、`summary.json`、`summary.csv`，字段完整、中文不乱码。
5. 判分逻辑有离线单元测试，不依赖网络与模型。
6. 运行结束后故障已关闭，`GET /api/experiments/faults` 返回 `active=false`。
7. 不修改任何生产代码、测试代码或配置。
8. 论文口径明确区分：根因服务准确率（受事件提示影响）、故障类别准确率、证据覆盖率、严格准确率。

---

## 5. 后续手动执行建议（本轮不执行）

以下命令仅作为实施后的建议，本轮未运行：

```bash
# 1. 离线单元测试（不联网、不调用模型）
python -m unittest discover -s eval/tests -v

# 2. 只读预检与计划，不产生副作用
python eval/run_eval.py plan --suite eval/cases/day7-baseline.json

# 3. 冒烟：每个故障场景 1 次
python eval/run_eval.py run --suite eval/cases/day7-baseline.json --repeats 1 --yes

# 4. 基线：每个故障场景 3 次
python eval/run_eval.py run --suite eval/cases/day7-baseline.json --repeats 3 --yes --max-diagnosis-runs 6

# 5. 从原始结果重新判分，不调用模型
python eval/run_eval.py rescore eval/results/run-20261024-xxxxxx
```

前置条件（需人工确认）：

- inventory-service 已设置 `EXPERIMENT_FAULT_INJECTION_ENABLED=true`。
- backend 已配置 `DEEPSEEK_API_KEY`，且健康检查正常。
- order-service 使用默认读超时 5000ms（基线 `delay-slow` 依赖此配置）。
- inventory-service 使用默认最大延迟 4500ms（基线不注入超过 4500ms 的延迟）。
- `SKU-1001` 库存足够覆盖 DELAY + normal 样本数（每样本扣 1）。
- 没有其他诊断任务同时运行，避免线程池与事件状态干扰。

---

## 6. 主要风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 故障未关闭 | 后续样本或人工操作被注入故障 | `finally` 关闭 + GET 复核；失败即中止 |
| 库存耗尽 | 正常/DELAY 样本变成 409，样本无效 | 预检余量；按最大样本数校验；不足时中止 |
| 事件/任务数据增长 | 数据库记录增多 | 保留 eventId/taskId；不自动删除；论文后统一归档 |
| 配置漂移 | 预期观测与实际不一致 | 预检 + `OBSERVATION_MISMATCH`；README 固化配置前提 |
| 关键词判分误判 | 准确率偏差 | 保留原文与 riskFlags；人工抽检 20%；P1 否定处理 |
| 服务名提示 | 服务准确率虚高 | 论文中单独说明；重点报告类别准确率与证据覆盖 |
| 模型非确定性 | 重复运行结果波动 | 固定 temperature；保留全部样本；报告均值/方差 |
| Token 成本 | 费用超预期 | plan 预估、`--yes`、硬上限、正常场景不调用 |
| Harness 与生产耦合 | 误改业务数据 | 只调用现有 HTTP API；不直连数据库；不改生产代码 |
| 评测结果不可追溯 | 论文数据无法复核 | 保存原始响应、标签版本、环境指纹、rescore 能力 |

---

## 7. 预估 DeepSeek 调用与 Token

以下估算基于 Day 6 真实诊断实测：4 次模型调用、3 次工具调用、10824 Token、约 16 秒/次。实际值随证据量与模型决策波动。

| 方案 | 诊断样本数 | 预计模型调用 | 预计 Token | 说明 |
| --- | --- | --- | --- | --- |
| P0 冒烟（1 次重复） | 2 | 约 8 | 约 2.2 万 | HTTP_500 + DELAY；正常 0 次 |
| P0 基线（3 次重复） | 6 | 约 24 | 约 6.5 万 | 论文最小可用数据 |
| P1 扩展（5 次重复，3 个故障场景） | 15 | 约 60 | 约 16 万 | 含 `delay-timeout`，需改环境变量 |
| 论文完整（10 次重复，3 个故障场景） | 30 | 约 120 | 约 32 万 | 建议在 P0 稳定后再执行 |

控制建议：

- 先跑 P0 冒烟（2 次诊断），确认 Harness、判分、报告都正确，再跑基线。
- 不要在调试 Harness 时反复跑真实样本；用 `rescore` 和离线 fixture 调试判分。
- 每轮记录实际 Token，超过预算立即停止。

---

## 8. 待确认事项

1. 基线是否按 3 次重复执行，论文最终是否提升到 5–10 次。
2. 是否将 `delay-timeout` 纳入主矩阵；若纳入，需要确认是否允许调整 order-service 读超时/inventory 最大延迟并重启服务。
3. 评测产生的故障事件与诊断任务是否需要清理；建议保留，论文实验结束后统一归档。
4. `eval/results/` 是否提交到 Git；建议只提交精选 `summary.json`/`summary.csv`，原始结果本地保留。
5. 是否需要在 P2 给报告增加结构化 `faultCategory` 字段；这会改动生产代码、提示词、validator 与测试，建议单独评审后再做。

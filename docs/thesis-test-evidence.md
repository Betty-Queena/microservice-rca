# 第五章 真实测试与实验证据整理

> 整理日期：2026-10-26
> 证据范围：`eval/results/`、PostgreSQL 中 `fault_event` / `diagnosis_task` / `remediation_action` 表、`backend/target/surefire-reports/`、相关源码与测试代码。
> 时间口径：评测文件与数据库时间均为 UTC（`Z` / `+00`）；测试报告时间为本机时间。
> 真实性原则：本文只整理已有记录，未重新启动服务、未重新注入故障、未调用 DeepSeek API、未执行处置。没有记录的数据标注“未核实”，不作推测。

## 1. 证据口径与真实性分级

| 证据类型 | 是否真实 DeepSeek 调用 | 是否真实 HTTP 请求 | 能证明什么 | 不能证明什么 |
|---|---|---|---|---|
| E1/E2 评测运行 | 是。运行清单记录 `networkCalls=true`、`directModelCalls=false`，模型调用只经过后端 Agent | 是。真实订单结算请求与故障注入 | 真实端到端诊断、真实日志/指标证据、真实 token 与耗时 | 不能外推总体准确率；仅 2 个诊断样本 |
| E3/E4 处置闭环 | 是。任务 `model_name=deepseek-flash`，每次模型调用有 usage 与延迟记录 | 是。真实故障注入、真实处置接口、真实结算恢复验证 | “诊断 → 人工确认 → 关闭实验故障 → 真实结算验证 → 事件 RESOLVED”闭环 | 处置类型是关闭实验故障，不是修复业务代码或重启生产服务 |
| E5/E6 DELAY 对比 | 是。任务有 `deepseek-flash`、逐轮 token 与延迟记录 | 是。真实 DELAY 注入与真实结算请求 | P0 前失败轨迹、P0 后复测行为对比 | E6 的后端构建版本未记录，不能单独证明 P0 因果效果 |
| 9 个测试类 / 99 个用例 | 否。Mock / ScriptedModelClient / Mockito | 否。单元与轻量集成测试 | 编排、预算、最终轮、兜底、校验、持久化逻辑 | 不是真实 DeepSeek 端到端测试，不能替代 E1–E6 |
| 离线重评分 | 否。`modelCalls=false`、`networkCalls=false` | 否。只读取已有原始结果 | 对已有真实结果做规则判分 | 不是新的真实实验，不代表模型再次运行 |

补充说明：

- E1–E6 的 `model_name` 均为 `deepseek-flash`；成功任务的报告均写明“所有模型调用均返回 usage”。数据库没有保存 DeepSeek 请求 ID 或原始响应体，因此“真实调用”的依据是运行清单、任务记录、usage 与延迟，而不是 API 审计日志。
- 99 个自动化测试全部是 Mock / 脚本化模型测试，不是真实 DeepSeek 调用。
- 离线重评分不调用模型，不能写成“又做了一次真实实验”。

## 2. 实验汇总表

### 2.1 真实实验总表

| 编号 | 场景 / 注入 | 故障事件 ID | 诊断任务 ID | 诊断结果 | 置信度 | 模型调用 / 工具调用 | Token | 诊断耗时 | 事件 / 处置 |
|---|---|---|---|---|---:|---:|---:|---:|---|
| E1 | HTTP 500，inventory-service 注入 `HTTP_500` | `f26b6fd9-0c88-4fd2-8db8-df7f8814504d` | `ac8e13ac-cd7d-45c2-8753-69e191020675` | `ROOT_CAUSE_IDENTIFIED` | MEDIUM | 6 / 5 | 17,426 | 26,280 ms | 事件 OPEN；未执行处置 |
| E2 | DELAY 3500 ms，inventory-service 注入 `DELAY` | `657479d0-b70b-4197-b8d3-3632938744f7` | `d5fa71ab-0ba7-4de9-8be8-4f6982cc349d` | `ROOT_CAUSE_IDENTIFIED` | MEDIUM | 6 / 5 | 19,792 | 25,552 ms | 事件 OPEN；未执行处置 |
| E3 | DELAY 3500 ms，Day 8 受控恢复验证 | `a661e773-437f-4ef8-85dc-afcf7bf0cd96` | `8361c50d-12d8-46fe-8d36-65895e40c96d` | `ROOT_CAUSE_IDENTIFIED` | MEDIUM | 6 / 5 | 17,849 | 25,568 ms | 已处置 `b713cb14-aa77-4e82-99ee-d01d15516959`；验证 PASS；事件 RESOLVED |
| E4 | HTTP 500，毕业设计受控处置闭环 | `6dfce9a7-c447-4743-b37a-41b2a5cfdf22` | `6f534d94-b519-4dab-8975-9ab2dd79267d` | `ROOT_CAUSE_IDENTIFIED` | MEDIUM | 6 / 5 | 19,036 | 17,297 ms | 已处置 `c85cdd66-5b6c-4ed0-8bc7-69fc951fae32`；验证 PASS；事件 RESOLVED |
| E5 | DELAY 3500 ms，P0 修复前失败样本 | `81ae61cd-9fc8-41d0-9223-8ac6751077d4` | `c770df55-13da-4d40-96fb-3cb209ddc9b5` | `FAILED`，无 `result_status` | 无 | 6 / 5 | 18,074 | 17,616 ms | 事件 OPEN；未执行处置 |
| E6 | DELAY 3500 ms，失败后复测 | `e216dd80-aa5a-4f12-ae4d-972d74506e84` | `dc676295-78b9-4459-8952-1d16d1911b79` | `ROOT_CAUSE_IDENTIFIED` | MEDIUM | 3 / 2 | 6,787 | 6,616 ms | 事件 OPEN；未执行处置 |

表注：

1. “Token”为该任务所有模型调用 report / trace 中的 `totalTokens` 合计，不是独立账单核对结果。
2. “诊断耗时”为 `diagnosis_task.completed_at - started_at`，单位毫秒。
3. E1、E2、E5、E6 的事件仍为 `OPEN`，表示诊断完成后未执行人工处置，不是“处置失败”。
4. E5 的 6 次模型调用 = 5 次 `MODEL_DECISION` + 1 次 `TOOL_REJECTED`；第 6 次模型输出仍为工具调用，被重复调用防护拒绝，未执行工具，但该次调用有 token usage。
5. E1–E6 是“统一评测 2 个样本 + 4 个补充真实案例”的案例集合，不是统一设计的准确率评测集，不能把 5/6 直接写成总体准确率。

### 2.2 处置与恢复明细（E3、E4）

| 项目 | E3（DELAY） | E4（HTTP 500） |
|---|---|---|
| 处置 ID | `b713cb14-aa77-4e82-99ee-d01d15516959` | `c85cdd66-5b6c-4ed0-8bc7-69fc951fae32` |
| 处置类型 | `DISABLE_EXPERIMENT_FAULT` | `DISABLE_EXPERIMENT_FAULT` |
| 目标服务 | inventory-service | inventory-service |
| 处置状态 | `SUCCEEDED` | `SUCCEEDED` |
| 请求方 | `rca-console` | `rca-console` |
| 人工确认 | 审计 `REQUEST_ACCEPTED`：“已显式确认；目标 experimentId=…” | 操作备注：“毕业设计受控处置闭环测试，已人工核对目标实验与诊断结果，同意关闭本次实验故障并执行恢复验证。” |
| 处置前故障 | `activeBefore=true`，`observedFaultTypeBefore=DELAY` | `activeBefore=true`，`observedFaultTypeBefore=HTTP_500` |
| 处置后故障 | `activeAfter=false`，`observedExperimentIdAfter=null` | `activeAfter=false`，`observedExperimentIdAfter=null` |
| 恢复验证 | `PASS`，`verified=true`，HTTP 200，11 ms，`outcome=SUCCESS`，`faultObserved=false` | `PASS`，`verified=true`，HTTP 200，48 ms，`outcome=SUCCESS`，`faultObserved=false` |
| 验证 requestId | `exp-e8b124ae83324444800125c6b9e24360` | `exp-8b60f12266c54984963a3caaf3d193fd` |
| 验证标准 | `criteriaVersion=day8-v1`，`slowThresholdMs=3000` | `criteriaVersion=day8-v1`，`slowThresholdMs=3000` |
| 审计步骤 | `REQUEST_ACCEPTED → PRECONDITIONS_CHECKED → FAULT_DISABLED → VERIFICATION_PASSED → EVENT_STATUS_UPDATE_REQUESTED` | 同左 |
| 事件结果 | `RESOLVED`，`resolved_at=2026-10-24T14:43:08.297956Z` | `RESOLVED`，`resolved_at=2026-10-25T18:28:04.425647Z` |

说明：这里的“恢复验证”是关闭实验故障后发起的一次真实结算请求，验证结果为 HTTP 200、`faultObserved=false`；它证明本次实验故障已关闭且结算恢复成功，不等于证明业务代码层面的缺陷已被修复。

## 3. 关键结果

### 3.1 HTTP 500：真实 DeepSeek 诊断（E1）

**事件与观测**

- 事件：`f26b6fd9-0c88-4fd2-8db8-df7f8814504d`，2026-10-24T11:34:58Z，inventory-service。
- 注入：`HTTP_500`，实验 `d6be4165-8fba-490f-804e-34645c66271b`，requestId `exp-7e17616a3c2646d8b87a646ff8b66854`。
- 真实观测：order-service checkout 返回 HTTP 502，耗时 122 ms，分类 `DOWNSTREAM_HTTP_5XX`，`downstreamErrorType=HTTP_5XX`。
- 事件状态：`OPEN`；未执行处置。

**诊断结果**

- 任务：`ac8e13ac-cd7d-45c2-8753-69e191020675`，`SUCCEEDED`，`ROOT_CAUSE_IDENTIFIED`，MEDIUM，`deepseek-flash`。
- 调用量：6 次模型调用，5 次工具调用，0 次工具失败。
- Token：17,426（prompt 13,826；completion 3,600）。
- 耗时：任务 26,280 ms；模型调用延迟合计 26,068 ms。
- 根因结论：inventory-service 在处理 `POST /api/inventory/SKU-1001/reserve` 时直接返回 HTTP 500（约 2 ms），order-service 的 `/api/orders/checkout` 因下游 5xx 返回 502；故障点在 inventory-service 的库存预留接口。
- 关键证据 ID：
  - `log:order-service:e0e816c1-c9af-4a09-a443-6fea4ffe6807`
  - `log:inventory-service:c0d990bf-d513-4ef3-ae2b-bbf00046d2d1`
  - `metric:order-service:2026-10-24T11:34:00Z`
  - `metric:inventory-service:2026-10-24T11:34:00Z`
- 报告限制：工具只提供请求级日志与分钟级指标，没有异常堆栈、错误码详情或依赖调用链；窗口内只有 1 条 inventory-service 请求样本；无法确认 500 的内部原因。

**可写入论文的结论**

在真实 HTTP 500 场景中，Agent 能基于真实日志与指标把故障定位到 inventory-service 的库存预留接口，并说明 order-service 的 502 是下游 5xx 的结果；置信度为 MEDIUM，未声称已定位到代码级根因。

### 3.2 HTTP 500：人工确认处置 + 真实 checkout 恢复（E4）

**事件与诊断**

- 事件：`6dfce9a7-c447-4743-b37a-41b2a5cfdf22`，2026-10-25T18:23:42Z，inventory-service。
- 注入：`HTTP_500`，实验 `5f22f562-5d18-49bc-b410-a5cf1db74e87`，requestId `exp-185cb07eb2b7451b9b4aa15cd6bbf995`。
- 真实观测：order-service checkout 返回 HTTP 502，耗时 19 ms，分类 `DOWNSTREAM_HTTP_5XX`。
- 任务：`6f534d94-b519-4dab-8975-9ab2dd79267d`，`SUCCEEDED`，`ROOT_CAUSE_IDENTIFIED`，MEDIUM，`deepseek-flash`。
- 调用量：6 次模型调用，5 次工具调用，0 次工具失败。
- Token：19,036（prompt 16,440；completion 2,596）。
- 耗时：任务 17,297 ms；模型调用延迟合计 17,160 ms。
- 根因结论：inventory-service 内部发生服务端错误（HTTP 500）；18:23 这一分钟内 inventory-service 的 2 次请求全部为 5xx，`POST /api/inventory/SKU-1001/reserve` 与 `GET /api/inventory` 都在 0 ms 内失败，说明故障在库存服务自身请求处理链路，不是下游超时；order-service 因下游失败返回 502。
- 关键证据 ID：
  - `log:order-service:31b2a267-ade2-4b08-98f6-c23cd8c0c6f5`
  - `log:inventory-service:70b0c5e1-bdc7-4fd1-9bed-de8bd2d55b61`
  - `log:inventory-service:82807c32-8707-40ed-8575-14f8d3302ca6`
  - `metric:inventory-service:2026-10-25T18:23:00Z`
  - `metric:order-service:2026-10-25T18:23:00Z`
- 报告限制：仅有请求级日志与分钟聚合指标，缺少异常栈、依赖健康和 JVM 指标；窗口内库存服务 3 条、订单服务 1 条样本；无法定位到具体内部组件。

**处置与恢复**

- 处置：`c85cdd66-5b6c-4ed0-8bc7-69fc951fae32`，`DISABLE_EXPERIMENT_FAULT`，目标 inventory-service，状态 `SUCCEEDED`。
- 人工确认：`requested_by=rca-console`；操作备注明确记录“已人工核对目标实验与诊断结果，同意关闭本次实验故障并执行恢复验证”。
- 处置结果：`activeBefore=true`、`activeAfter=false`，`observedFaultTypeBefore=HTTP_500`，`targetExperimentId=5f22f562-5d18-49bc-b410-a5cf1db74e87`。
- 恢复验证：`PASS`，`verified=true`，`attempted=true`，HTTP 200，48 ms，`outcome=SUCCESS`，`faultObserved=false`，requestId `exp-8b60f12266c54984963a3caaf3d193fd`，`criteriaVersion=day8-v1`，`slowThresholdMs=3000`。
- 审计链：`REQUEST_ACCEPTED → PRECONDITIONS_CHECKED → FAULT_DISABLED → VERIFICATION_PASSED → EVENT_STATUS_UPDATE_REQUESTED`；事件仅在仍为 OPEN 时条件更新为 `RESOLVED`。
- 事件：`RESOLVED`，`resolved_at=2026-10-25T18:28:04.425647Z`。

**可写入论文的结论**

该案例完成了“真实 DeepSeek 诊断 → 人工确认 → 关闭实验故障 → 真实结算请求恢复验证 → 事件 RESOLVED”的完整闭环。处置不是自动执行：请求方为 `rca-console`，有显式人工确认记录和操作备注；处置类型是关闭实验故障，不是修改业务代码或重启服务。

### 3.3 DELAY：P0 修复前失败（E5）

**事件与观测**

- 事件：`81ae61cd-9fc8-41d0-9223-8ac6751077d4`，2026-10-25T18:30:45Z，inventory-service。
- 注入：`DELAY`，3500 ms，实验 `5fbf9aa2-b9d7-4c13-b009-3d166cead40a`，requestId `exp-e23555dc40394290b0d2fe692b874a33`。
- 真实观测：order-service checkout 返回 HTTP 200，耗时 3,529 ms，订单 `CONFIRMED`，分类 `SLOW_RESPONSE`。
- 事件状态：`OPEN`；未执行处置。

**任务结果**

- 任务：`c770df55-13da-4d40-96fb-3cb209ddc9b5`，`FAILED`。
- 失败原因（原样）：`达到最大模型调用轮数（6）仍未形成可验证结论`。
- `result_status`、`confidence_level`、`root_cause`、`report` 均为空；没有形成可引用的诊断报告。
- 调用量：6 次模型调用，5 次工具调用；5 次工具调用全部 `SUCCEEDED`，0 次工具失败。
- Token：18,074（prompt 15,357；completion 2,717），来自 `decision_trace` 的 `AGENT_RUN_SUMMARY`。
- 耗时：任务 17,616 ms；5 次已记录延迟的模型调用合计 12,262 ms；第 6 次被拒调用没有记录延迟。

**逐轮决策轨迹**

| 轮次 | 模型决策 | 工具 | 结果 | 关键理由（原文摘要） | Token |
|---:|---|---|---|---|---:|
| 1 | `CALL_TOOL` | `query_logs` | ACCEPTED / SUCCEEDED | “先按 requestId 检索订单结算链路的真实日志，确认各服务耗时与调用顺序” | 1,122 |
| 2 | `CALL_TOOL` | `query_metrics` | ACCEPTED / SUCCEEDED | “日志显示同一请求中 inventory reserve 耗时 3515ms，占 order checkout 3525ms 的绝大部分；需查询指标判断 inventory-service 的延迟是否来自自身资源/依赖瓶颈” | 1,764 |
| 3 | `CALL_TOOL` | `query_logs` | ACCEPTED / SUCCEEDED | “已确认慢点集中在 inventory-service 的 reserve 调用（3515ms，占结算 3525ms 的绝大部分，状态 200 无错误），需查看更长窗口的库存服务日志，判断该慢响应是孤立偶发还是持续/反复出现” | 2,913 |
| 4 | `CALL_TOOL` | `query_metrics` | ACCEPTED / SUCCEEDED | “需要确认 inventory-service 慢响应是持续劣化还是仅 18:30 分钟桶的瞬时抖动，扩大回看窗口以获取基线对比” | 3,414 |
| 5 | `CALL_TOOL` | `query_logs` | ACCEPTED / SUCCEEDED | “核实 inventory-service 慢响应是否为持续性、何时开始，区分瞬时抖动与全局性延迟” | 3,896 |
| 6 | `CALL_TOOL` | `query_metrics` | `TOOL_REJECTED`，未执行 | “确认 inventory-service 慢响应是持续性劣化还是窗口内瞬时抖动，作为最后一次取证” | 4,965 |

**工具证据（已获取，但未被报告引用）**

- `log:order-service:a3ae7cd5-1762-462a-b4a8-0009a899ef05`
- `log:inventory-service:3c1c1d26-82e3-4a86-92bd-823db925a33f`
- `log:inventory-service:43d7a52c-9438-40ef-9538-a6a34b176044`
- `metric:order-service:2026-10-25T18:30:00Z`
- `metric:inventory-service:2026-10-25T18:29:00Z`
- `metric:inventory-service:2026-10-25T18:30:00Z`

**失败机制分析**

第 2 轮模型已经根据真实日志把慢点定位到 inventory-service 的 reserve 调用（3,515 ms / 3,525 ms，HTTP 200），满足“定位到服务或调用环节”的条件；但后续第 3–5 轮继续查询“是否持续、是否有基线、是否反复出现”，第 6 轮仍尝试同类指标查询，被重复调用防护拒绝。由于没有输出 `FINAL_REPORT` 或 `INSUFFICIENT_EVIDENCE`，循环耗尽预算后返回 `FAILED`，已有的工具证据没有被写入报告。该失败不是工具失败，也不是没有证据，而是“已定位后未收敛”的决策轨迹问题。

### 3.4 DELAY：P0 后复测成功（E6，代码版本未核实）

**事件与观测**

- 事件：`e216dd80-aa5a-4f12-ae4d-972d74506e84`，2026-10-25T18:51:56Z，inventory-service。
- 注入：`DELAY`，3500 ms，实验 `59d1daf5-a4a4-446c-b075-4051e034c4b3`，requestId `exp-2e7dbc8c08494685a143004ed3fe90a6`。
- 真实观测：order-service checkout 返回 HTTP 200，耗时 3,530 ms，订单 `CONFIRMED`，分类 `SLOW_RESPONSE`。
- 事件状态：`OPEN`；未执行处置。

**任务结果**

- 任务：`dc676295-78b9-4459-8952-1d16d1911b79`，`SUCCEEDED`，`ROOT_CAUSE_IDENTIFIED`，MEDIUM，`deepseek-flash`。
- 调用量：3 次模型调用，2 次工具调用，0 次工具失败。
- Token：6,787（prompt 5,833；completion 954）。
- 耗时：任务 6,616 ms；模型调用延迟合计 6,493 ms。
- 根因结论：checkout 总耗时 3,523 ms，其中 inventory-service 的 `POST /api/inventory/SKU-1001/reserve` 耗时 3,516 ms，HTTP 200；慢响应定位到 inventory-service 的库存预占环节。
- 关键证据 ID：
  - `log:order-service:33c9a1bd-d6f1-4267-9de9-bc0ea8e4cc01`
  - `log:inventory-service:437d7ee4-d628-4822-80e3-cdae5a0e2e4f`
  - `metric:inventory-service:2026-10-25T18:51:00Z`
- 报告限制（原文）：
  - “未定位到 inventory-service 内部的具体代码级根因”
  - “inventory-service 指标仅覆盖单个时间桶且请求量少（3 次），无法判断是否持续或建立基线”
  - “日志中无错误或 5xx 证据，属于纯慢响应场景”
- 报告明确以 MEDIUM 置信度结束调查，并写明“内部原因尚未确认”。

**版本可追溯性说明**

- E6 发生在 E5 失败后约 21 分钟，诊断行为与 P0 收敛规则一致：仅 3 次模型调用、2 次工具调用即结束，报告明确写“内部原因尚未确认”，没有继续追求基线或持续性证据。
- 但现有记录没有保存该后端实例的构建版本、启动命令或 commit。P0 修改当前位于工作区，尚未提交；HEAD `5d38b9e` 不包含收敛规则。当前仍在运行的 18080 后端显式关闭了 LLM（`RCA_LLM_ENABLED=false`，`DEEPSEEK_BASE_URL=http://127.0.0.1:1`），不可能产生 E6 的成功 DeepSeek 调用；E6 由另一个已停止的后端实例处理，其构建版本未记录。
- 因此 E6 只能标记为“修复后复测成功（代码版本未核实）”，不能单独作为 P0 修复因果验证。若要严格证明 P0 效果，需要一次记录 commit/构建号、启动命令和后端日志的真实 E2E 复测；本次未执行。

### 3.5 DELAY 修复前后对比

| 维度 | E5：P0 前失败 | E6：P0 后复测（版本未核实） |
|---|---|---|
| 事件 / 任务 | `81ae61cd…` / `c770df55…` | `e216dd80…` / `dc676295…` |
| 真实观测 | HTTP 200，3,529 ms，`SLOW_RESPONSE` | HTTP 200，3,530 ms，`SLOW_RESPONSE` |
| 结果 | `FAILED`，无报告 | `ROOT_CAUSE_IDENTIFIED`，MEDIUM |
| 模型 / 工具 | 6 / 5 | 3 / 2 |
| Token | 18,074 | 6,787 |
| 诊断耗时 | 17,616 ms | 6,616 ms |
| 关键行为 | 已定位 reserve 慢点后，连续追问持续性/基线；第 6 轮重复工具调用被拒；未输出最终报告 | 2 次工具调用后输出报告；明确“内部原因尚未确认”；未继续追求 HIGH |
| 证据引用 | 工具已获取 6 个 evidenceId，但报告为空，未引用 | 报告引用 3 个 evidenceId |
| 事件状态 | OPEN；未处置 | OPEN；未处置 |

**重要限定**

- P0 前 DELAY 并不是全部失败：E2（2026-10-24）和 E3（2026-10-24）都在 P0 前成功，且都是 6 次模型调用、5 次工具调用。E5 是特定决策轨迹下的预算耗尽失败，不能写成“P0 前 DELAY 场景普遍失败”。
- E5 与 E6 的对比能说明“收敛行为”的差异，但由于 E6 的后端版本未记录，不能把差异单独归因于 P0 代码。
- P0 后的真实 E2E 证据目前只有 E6 这一条，且版本不可追溯；99 个自动化测试只证明了代码级行为，不构成真实 DeepSeek E2E 证明。

### 3.6 其他 DELAY 真实实验（E2、E3）

**E2：评测中的 DELAY 成功样本**

- 事件 `657479d0-b70b-4197-b8d3-3632938744f7`，2026-10-24T11:35:36Z，HTTP 200，3,564 ms，`CONFIRMED`。
- 任务 `d5fa71ab-0ba7-4de9-8be8-4f6982cc349d`：`ROOT_CAUSE_IDENTIFIED`，MEDIUM，6 次模型调用，5 次工具调用，19,792 token，25,552 ms。
- 根因：checkout 3,561 ms，inventory reserve 3,547 ms（HTTP 200），约占 99.6%；order-service 主要等待下游。
- 证据：`log:inventory-service:750f4239-8f9c-4d34-abed-6b55986682a9`、`log:order-service:f8f8a1ee-2f30-4811-b02a-fb6614b68682`、`metric:inventory-service:2026-10-24T11:35:00Z`、`metric:order-service:2026-10-24T11:35:00Z`。
- 限制：窗口约 1 分钟、每服务 2 条请求；缺少内部耗时分解、线程/锁与数据库指标；同窗口 500 请求与本慢响应的关联未验证。
- 事件 `OPEN`；未执行处置。

**E3：Day 8 受控恢复中的 DELAY 成功样本**

- 事件 `a661e773-437f-4ef8-85dc-afcf7bf0cd96`，2026-10-24T14:40:27Z，HTTP 200，3,840 ms，`CONFIRMED`。
- 任务 `8361c50d-12d8-46fe-8d36-65895e40c96d`：`ROOT_CAUSE_IDENTIFIED`，MEDIUM，6 次模型调用，5 次工具调用，17,849 token，25,568 ms。
- 根因：checkout 3,773 ms，inventory reserve 3,549 ms；同窗口另一条无关的 `GET /api/inventory` 也耗时 3,503 ms，说明变慢是 inventory-service 服务级别的，不是仅 reserve 路径或 order-service 编排逻辑；所有请求 200，无错误计数。
- 证据：`log:inventory-service:80ead7a9-2530-44cd-b914-b3744f2d974c`、`log:inventory-service:d98cebd4-d506-4927-8b5d-a2e6a00aff01`、`log:order-service:00dc2afc-bb42-4f01-9eff-c4156f63bf33`、`metric:inventory-service:2026-10-24T14:40:00Z`、`metric:order-service:2026-10-24T14:40:00Z`。
- 限制：只有 2 条 inventory-service 请求和 1 条 order-service 请求；缺少 JVM/GC、线程池、数据库与下游依赖细粒度指标；无链路 span。
- 处置 `b713cb14-aa77-4e82-99ee-d01d15516959`：`DISABLE_EXPERIMENT_FAULT`，`SUCCEEDED`；恢复验证 `PASS`，HTTP 200，11 ms，`faultObserved=false`；事件 `RESOLVED`。

### 3.7 自动化测试：9 个测试类、99 个用例

**运行结果**

- 运行时间：2026-10-26 02:48（本机时间，Surefire 报告时间）。
- 命令（在 `backend/` 下，离线执行）：
  ```bash
  mvn.cmd -o -q -Dtest=AgentDiagnosisServiceTest,AgentInputFactoryTest,AgentPromptBuilderTest,AgentReportValidatorTest,AgentTaskRunnerTest,AgentToolCallValidatorTest,DeepSeekAgentModelClientTest,DefaultAgentOrchestratorTest,DiagnosisTaskRecoveryTest test
  ```
- 结果：9 个测试类，99 个用例，0 failures，0 errors，0 skipped，退出码 0。

| 测试类 | 用例数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| `AgentDiagnosisServiceTest` | 8 | 0 | 0 | 0 |
| `AgentInputFactoryTest` | 4 | 0 | 0 | 0 |
| `AgentPromptBuilderTest` | 3 | 0 | 0 | 0 |
| `AgentReportValidatorTest` | 7 | 0 | 0 | 0 |
| `AgentTaskRunnerTest` | 15 | 0 | 0 | 0 |
| `AgentToolCallValidatorTest` | 13 | 0 | 0 | 0 |
| `DeepSeekAgentModelClientTest` | 11 | 0 | 0 | 0 |
| `DefaultAgentOrchestratorTest` | 33 | 0 | 0 | 0 |
| `DiagnosisTaskRecoveryTest` | 5 | 0 | 0 | 0 |
| **合计** | **99** | **0** | **0** | **0** |

**覆盖点**

- DELAY 慢响应定位与 MEDIUM 结论：`reportsSlowResponseWhenLatencyIsLocalized`
- HTTP 500 下游证据：`reportsHttp500WithDownstreamEvidence`
- 最后一轮硬约束：`promptsForTerminalActionOnFinalRound`
- 预算耗尽兜底：`fallsBackToInsufficientEvidenceWhenModelCallLimitIsReached`、`staysFailedWhenBudgetExhaustsWithoutAnyToolObservation`
- 最后一轮重复/被拒报告：`lastRoundDuplicateFallsBackToInsufficientEvidence`、`lastRoundRejectedReportFallsBackToInsufficientEvidence`
- 无新证据软反馈：`warnsWhenToolReturnsNoNewEvidence`
- 正常请求不编造根因：`normalRequestWithoutEvidenceDoesNotFabricateRootCause`
- 证据 ID 校验、工具白名单、重复调用防护：`AgentReportValidatorTest`、`AgentToolCallValidatorTest`
- 任务持久化、取消保护、事件恢复：`AgentTaskRunnerTest`、`DiagnosisTaskRecoveryTest`

**真实性限定**

- 上述 99 个用例全部使用 Mock / ScriptedModelClient / Mockito，不调用真实 DeepSeek API，不启动真实服务，不执行真实故障注入。
- 它们证明的是代码级编排与校验逻辑，不是真实端到端诊断效果。
- `backend/target/surefire-reports/` 目录中还有 34 个 XML 报告，来自不同批次运行；本节只统计上述 9 个类的定向运行，不把其他批次或 288 个历史用例混入本结果。

### 3.8 离线评测：day7-baseline

**运行信息**

- 运行目录：`eval/results/run-20261024T113458Z-1cb64a/`。
- 运行清单：`run-manifest.json`，开始 `2026-10-24T11:34:58Z`，结束 `2026-10-24T11:36:16Z`。
- 模式：`black-box-http`；`networkCalls=true`，`directModelCalls=false`；模型调用只经过后端 Agent，评测器没有直接读取 `DEEPSEEK_API_KEY`。
- 样本：3 个（2 个诊断 + 1 个正常基线）。
- 离线重评分：`eval/results/run-20261024T113458Z-1cb64a-rescore/summary.json`，`mode=offline-rescore`，`networkCalls=false`，`modelCalls=false`；标签来自故障注入配置 + `ObservationClassifier` 的确定性预期结果，未使用模型输出作为标签。

**逐样本结果**

| 样本 | 场景 | 真实观测 | 诊断任务 | 诊断结果 | 模型 / 工具 | Token | 耗时 |
|---|---|---|---|---|---:|---:|---:|
| `http500-baseline-r1` | HTTP_500 | HTTP 502，122 ms，`DOWNSTREAM_HTTP_5XX` | `ac8e13ac…` | `ROOT_CAUSE_IDENTIFIED`，MEDIUM | 6 / 5 | 17,426 | 26,280 ms |
| `delay-slow-3500-r1` | DELAY | HTTP 200，3,564 ms，`SLOW_RESPONSE` | `d5fa71ab…` | `ROOT_CAUSE_IDENTIFIED`，MEDIUM | 6 / 5 | 19,792 | 25,552 ms |
| `normal-baseline-r1` | NORMAL | HTTP 200，7 ms，`SUCCESS` | 无（不调用诊断） | 正常基线通过 | 无 | 无 | 无 |

**汇总指标**

- 诊断样本：2；正常样本：1。
- `rootCauseIdentified=2`，`insufficientEvidence=0`，`failed=0`。
- `strictAccuracy=1.0`，`serviceAccuracy=1.0`，`categoryAccuracy=1.0`，`evidenceCoverageAvg=1.0`。
- `normalPass=1`，`falseAlarms=0`，`normalPassRate=1.0`，`falseAlarmRate=0.0`。
- `confidenceDistribution={MEDIUM: 2}`。
- `modelCalls=12`，`toolExecutions=10`，`toolFailures=0`。
- `promptTokens=29,959`，`completionTokens=7,259`，`totalTokens=37,218`。
- 墙钟耗时均值 25,951 ms，中位数 25,951 ms，P95 26,338 ms；执行耗时均值 25,916 ms。
- `failures=[]`，`reviewCandidates=[]`，`invalidSamples=0`。

**真实性限定**

- `strictAccuracy=1.0` 是 2 个诊断样本上的样本级结果，不是总体准确率；不能写成“系统准确率 100%”。
- 离线重评分只对已有原始结果做规则匹配，不是新的真实实验，也不调用模型。
- 正常基线只参与误报检测，不调用诊断接口；它不能证明 Agent 在正常请求下不会误报，只能说明本次观测分类未误报。

## 4. 证据来源

### 4.1 真实评测与原始结果

| 文件 | 用途 |
|---|---|
| `eval/results/run-20261024T113458Z-1cb64a/run-manifest.json` | 运行时间、模式、样本数、网络调用与模型调用边界 |
| `eval/results/run-20261024T113458Z-1cb64a/raw/run-20261024T113458Z-1cb64a-http500-baseline-r1.json` | E1 原始样本：事件、任务、报告、决策轨迹 |
| `eval/results/run-20261024T113458Z-1cb64a/raw/run-20261024T113458Z-1cb64a-delay-slow-3500-r1.json` | E2 原始样本：事件、任务、报告、决策轨迹 |
| `eval/results/run-20261024T113458Z-1cb64a/raw/run-20261024T113458Z-1cb64a-normal-baseline-r1.json` | 正常基线原始样本 |
| `eval/results/run-20261024T113458Z-1cb64a-rescore/summary.json` | 离线重评分汇总指标 |
| `eval/results/run-20261024T113458Z-1cb64a-rescore/summary.csv` | 逐样本判分结果 |
| `eval/results/run-20261024T113458Z-1cb64a-rescore/failures.md` | 失败样本说明（本次为空） |

### 4.2 数据库记录

只按事件/任务/处置 ID 定向查询，未做全表扫描，未修改数据。

| 表 | 关键字段 | 用途 |
|---|---|---|
| `fault_event` | `id`, `event_type`, `observed_result`, `status`, `experiment_id`, `experiment_fault_type`, `experiment_parameters`, `request_id`, `occurred_at`, `resolved_at` | 故障事件、真实观测、实验注入参数、事件状态 |
| `diagnosis_task` | `id`, `status`, `result_status`, `confidence_level`, `failure_reason`, `report`, `decision_trace`, `model_name`, `created_at`, `started_at`, `completed_at` | 诊断结果、置信度、token、逐轮决策、耗时 |
| `remediation_action` | `id`, `fault_event_id`, `diagnosis_task_id`, `action_type`, `target_service`, `status`, `requested_by`, `operator_note`, `action_result`, `verification_result`, `audit_trace`, `requested_at`, `completed_at` | 人工确认、处置结果、恢复验证、审计链 |

关键记录：

- E1：`fault_event.f26b6fd9-…`、`diagnosis_task.ac8e13ac-…`
- E2：`fault_event.657479d0-…`、`diagnosis_task.d5fa71ab-…`
- E3：`fault_event.a661e773-…`、`diagnosis_task.8361c50d-…`、`remediation_action.b713cb14-…`
- E4：`fault_event.6dfce9a7-…`、`diagnosis_task.6f534d94-…`、`remediation_action.c85cdd66-…`
- E5：`fault_event.81ae61cd-…`、`diagnosis_task.c770df55-…`
- E6：`fault_event.e216dd80-…`、`diagnosis_task.dc676295-…`

### 4.3 测试报告与源码

| 证据 | 路径 |
|---|---|
| 9 个测试类的 Surefire 报告 | `backend/target/surefire-reports/TEST-com.microservice.rca.agent.*.xml` |
| 提示词收敛与最终轮约束 | `backend/src/main/java/com/microservice/rca/agent/AgentPromptBuilder.java` |
| 预算耗尽兜底、无新证据软反馈、终态原因 | `backend/src/main/java/com/microservice/rca/agent/DefaultAgentOrchestrator.java` |
| 兜底结论持久化前缀 | `backend/src/main/java/com/microservice/rca/agent/AgentTaskRunner.java` |
| 提示词测试 | `backend/src/test/java/com/microservice/rca/agent/AgentPromptBuilderTest.java` |
| 编排与预算测试 | `backend/src/test/java/com/microservice/rca/agent/DefaultAgentOrchestratorTest.java` |
| 任务持久化测试 | `backend/src/test/java/com/microservice/rca/agent/AgentTaskRunnerTest.java` |

说明：`docs/design/day8-remediation-design.md` 是设计文档，不是执行证据；本文的处置结论来自 `remediation_action` 表与真实验证结果，不来自设计文档。

## 5. 论文第五章可使用的结论

### 5.1 可以写入的结论

1. **真实 DeepSeek 诊断能够定位 HTTP 500 和 DELAY 的服务/调用环节。**
   E1、E2、E3、E4、E6 五次成功实验都把故障定位到 inventory-service（库存预留接口或服务级慢响应），置信度均为 MEDIUM；报告引用了真实工具返回的 `log:` / `metric:` evidenceId。

2. **HTTP 500 闭环具备完整证据链。**
   E4 完成了“真实诊断 → 人工确认 → 关闭实验故障 → 真实结算恢复验证 → 事件 RESOLVED”。处置记录包含 `activeBefore=true`、`activeAfter=false`、验证 HTTP 200/48 ms、`faultObserved=false` 和五步审计链。

3. **DELAY 的 P0 前失败是“已定位后未收敛”，不是工具失败或没有证据。**
   E5 第 2 轮已根据真实日志定位 reserve 慢点（3,515 ms / 3,525 ms），但后续继续追问持续性/基线，第 6 轮重复工具调用被拒，最终以 `FAILED` 结束；5 次工具调用全部成功，已获取 6 个 evidenceId，但没有形成报告。

4. **P0 通过收敛 Prompt、最终轮硬约束、无新证据软反馈和预算兜底，修复了该失败路径。**
   99 个自动化测试覆盖了 `finalRound`、预算耗尽 fallback、最后一轮重复/被拒、无新证据反馈、正常请求不编造、证据 ID 校验等行为。

5. **P0 前 DELAY 并非普遍失败，失败具有轨迹依赖性。**
   E2、E3 在 P0 前成功，E5 失败；因此论文应描述“特定决策轨迹下的预算耗尽失败”，不能写成“DELAY 场景整体不可用”。

6. **真实实验与 Mock 测试的边界清楚。**
   E1–E6 是真实 DeepSeek/真实 HTTP 案例；99 个自动化测试是 Mock/Scripted 测试；离线重评分不调用模型。三者不能混写。

### 5.2 不建议写入的表述

- 不写“系统准确率 100%”：统一评测只有 2 个诊断样本，`strictAccuracy=1.0` 是样本级结果。
- 不写“P0 已通过真实端到端验证”：E6 的后端版本未记录，P0 后的真实 E2E 证据不足；99 个测试是 Mock 测试。
- 不写“DELAY 场景在 P0 前全部失败”：E2、E3 是 P0 前的成功样本。
- 不写“系统自动处置/自动修复”：处置需要人工确认，类型是关闭实验故障，不是修改业务代码或重启服务。
- 不写“已定位到代码级/数据库级根因”：成功报告只定位到服务或调用环节，内部原因均标注“尚未确认”。
- 不写“恢复验证证明业务缺陷已修复”：验证只证明实验故障已关闭、结算请求恢复 200，不等于修复业务代码。
- 不写“6 个案例的 5/6 成功率是总体准确率”：案例集合不是统一评测设计，样本量小且非随机。

## 6. 未核实与不可推断清单

| 项目 | 状态 | 说明 |
|---|---|---|
| P0 后带 commit/构建号的真实 DeepSeek E2E | **未执行 / 未核实** | 本次只读整理，没有重新启动服务或调用 API；99 个测试是 Mock 测试 |
| E6 使用的后端代码版本 | **未核实** | 数据库未保存构建版本/启动命令/commit；当前 18080 实例关闭 LLM，不是 E6 的调用方 |
| E5 第 6 次模型调用的延迟 | **未记录** | 该次调用有 token usage，但 `TOOL_REJECTED` 条目没有 `latencyMs` |
| DeepSeek 上游模型精确版本/快照 | **未核实** | 只记录模型名 `deepseek-flash`，没有上游版本号 |
| DeepSeek API 请求 ID / 原始响应体 | **未保存** | 数据库有 model、usage、latency，但没有请求级 API 审计日志 |
| 总体准确率 / 泛化能力 | **不可推断** | 统一评测仅 2 个诊断样本；补充案例非随机 |
| E1、E2、E5、E6 的处置结果 | **未执行** | 事件仍为 OPEN；不是处置失败，而是没有执行处置 |
| E3、E4 的内部代码级根因 | **未定位** | 只定位到 inventory-service / reserve 调用环节；报告明确写“内部原因尚未确认” |
| E3、E4 恢复验证的长期效果 | **未核实** | 只有单次真实结算验证（11 ms / 48 ms，HTTP 200），没有持续监控 |
| 正常基线的 Agent 误报率 | **不可推断** | 正常样本不调用诊断接口，只验证观测分类未误报 |
| Token 与账单一致性 | **未核实** | Token 来自模型返回的 usage，没有独立账单核对 |
| P0 修改的提交状态 | **未提交** | 修改仍在工作区；HEAD `5d38b9e` 不包含收敛规则 |

## 7. 一句话总结

现有真实证据可以支持“Agent 能在真实 HTTP 500 与 DELAY 场景中基于日志/指标定位到 inventory-service 的服务或调用环节，并在人工确认后完成实验故障关闭与真实结算恢复验证”；也可以支持“P0 前存在已定位后未收敛、耗尽 6 次预算而失败的 DELAY 轨迹，P0 用收敛 Prompt、最终轮约束和预算兜底修复了该路径”。但现有证据**不足以**支持“P0 已通过真实 E2E 验证”或“系统总体准确率 100%”这类更强结论。

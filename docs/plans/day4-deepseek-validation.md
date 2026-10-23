# Day 4 真实 DeepSeek 接入与端到端验收报告

| 项目 | 内容 |
| --- | --- |
| 项目 | 基于 LLM 的微服务故障分析与处置系统 |
| 日期 | 2026-10-22 |
| 范围 | DeepSeek Key 加载、账户模型核实、低成本真实调用、HTTP_500 / DELAY 真实 Agent 诊断、工具审计、数据库持久化、前端展示、故障清理 |
| 结论 | **通过**。HTTP_500 与 DELAY 均完成真实 DeepSeek Agent 诊断；DELAY 首次调用遇到一次空 `content` 瞬时失败，已做最小修复并通过重试成功 |
| 实际模型 | `deepseek-flash` |
| 真实 `chat/completions` 调用 | **12 次**（含 1 次冒烟调用） |
| 真实根因报告 | **2 份**（HTTP_500、DELAY 重试） |
| 故障注入最终状态 | `experimentEnabled=false`、`active=false`；inventory-service 已以禁用状态重启 |
| 代码修改 | 1 个生产文件 + 1 个测试文件；无接口变更、无新增依赖 |
| 回归测试 | 定向 18 个用例通过（6 + 12），0 失败；完整测试套件未自动运行（项目规范要求手动执行） |
| Git | 未执行 `commit` / `push`，未输出或保存 API Key |

> 本文件只把**真实 DeepSeek 返回 + 真实服务请求 + 真实数据库/接口**记作真实结果。Mock、单元测试和未鉴权探测单独标注，未用于冒充端到端结果。

## 0. 结论摘要

| 检查项 | 真实结果 |
| --- | --- |
| 项目侧 `DEEPSEEK_API_KEY` | **已配置且非空**（只确认状态，未输出、未复制、未记录值） |
| Spring Boot 加载根目录 `.env` | 已确认；后端启动后完成真实模型调用 |
| `GET /models` | HTTP 200，耗时 0.294 s；账户实际返回 `deepseek-flash`、`deepseek-v4-pro` |
| `DEEPSEEK_MODEL` | `deepseek-flash`，在账户模型列表中，配置有效 |
| 低成本冒烟调用 | HTTP 200，0.777 s，返回合法 JSON，`finish_reason=stop` |
| Flyway / 数据库 | V1/V2/V3 全部成功，schema version 3，数据库健康 |
| HTTP_500 真实诊断 | 任务 `SUCCEEDED` / `ROOT_CAUSE_IDENTIFIED`，根因正确指向 inventory-service |
| DELAY 3500ms 真实诊断 | 首次因空 `content` 失败；最小修复后重试 `SUCCEEDED` / `ROOT_CAUSE_IDENTIFIED` |
| Agent 自主工具选择 | 两个场景均由模型自主调用 `query_logs` / `query_metrics`，非固定流水线 |
| 证据链 | 报告引用的证据 ID 全部来自真实工具输出；DELAY 报告还正确识别了无关的历史 500 证据 |
| 数据库持久化 | 3 个任务、8 条工具调用、报告/输入/决策轨迹均已落库 |
| 前端展示 | 无头 Chrome 渲染两个事件详情页，确认展示诊断状态、报告、证据、时间线、工具审计 |
| 故障清理 | 已 disable + reset；最终 `active=false`；对照组结算 `SUCCESS` |
| 真实根因报告 | 2 份；无 Mock 结果冒充 |

## 1. Key 与配置加载

### 1.1 检查结果（只报告状态）

| 检查位置 | 结果 |
| --- | --- |
| 仓库根目录 `.env` 中的 `DEEPSEEK_API_KEY` | **SET**（非空；未输出值） |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` |
| `DEEPSEEK_MODEL` | `deepseek-flash` |
| WSL shell / Windows 环境变量 | 未依赖；以仓库 `.env` 为准 |
| Claude Code 的 `ANTHROPIC_*` 配置 | 与本项目独立；未读取、未复制、未输出其凭据 |

检查与运行过程中没有打印、记录或写入任何 Key 内容。`/proc/<pid>/environ` 也不用于判断 `.env` 是否加载，因为 Spring 的 `spring.config.import` 读取的变量不会出现在进程环境块中。

### 1.2 加载链（已由真实调用验证）

```text
仓库根目录 .env
  -> application.yml: optional:file:../.env[.properties] / ./.env[.properties]
  -> agent.deepseek.api-key = ${DEEPSEEK_API_KEY:}
  -> AgentProperties.DeepSeek.configured() = apiKey 非空
  -> DeepSeekAgentModelClient 使用 Bearer Token 调用 /chat/completions
```

后端启动后真实 `/models` 与 `/chat/completions` 均返回 200，说明 `.env` 已被正确读取并注入。

### 1.3 `.env` 的 Git 状态与权限

- `git check-ignore -v .env` → `.gitignore:2:.env`，已忽略。
- `git ls-files -- .env` → 空，未被跟踪。
- `/mnt/c` DrvFs 下 `chmod 600 .env` 不生效，权限仍为 `777`。这是本地文件系统限制，不是应用缺陷；建议后续改用启动 shell 注入或 Windows ACL。

## 2. 账户模型核实与低成本真实调用

### 2.1 `GET /models`

| 项目 | 结果 |
| --- | --- |
| HTTP 状态 | 200 |
| 耗时 | 0.294 s |
| 账户可用模型 | `deepseek-flash`、`deepseek-v4-pro` |
| 是否包含 `deepseek-chat` | 否 |

因此保留 `DEEPSEEK_MODEL=deepseek-flash`，没有凭 Claude Code 的 `ANTHROPIC_MODEL=deepseek-chat` 推断本项目模型可用。

### 2.2 冒烟调用（1 次）

| 项目 | 结果 |
| --- | --- |
| HTTP 状态 | 200 |
| 耗时 | 0.777 s |
| 实际返回模型 | `deepseek-flash` |
| `content` | `{"ok":true,"action":"PING"}`，可被 JSON 解析 |
| `finish_reason` | `stop` |
| usage | prompt 80 / completion 34 / total 114；reasoning 23；cached 0 |

该调用只验证鉴权、JSON 模式、响应解析和 usage 字段，不作为根因诊断结果。

## 3. HTTP_500 真实故障 E2E

### 3.1 实验与真实请求

| 项目 | 结果 |
| --- | --- |
| 实验 ID | `027a21a2-c218-4e3b-a8f0-342a3afe75e1` |
| 故障 | HTTP_500，持续 120 s |
| 激活时间 | 2026-10-22T12:22:20.799Z |
| requestId | `exp-a4bd29209a9a4da5a06f6e875f482036` |
| 真实结算结果 | `outcome=DOWNSTREAM_HTTP_5XX`，上游 HTTP 502，耗时 921 ms |
| 下游观测 | inventory-service `/api/inventory/SKU-1001/reserve` 返回 500，耗时 12 ms |
| 故障事件 ID | `b57bd168-9659-4ecc-8d35-310257a42969` |
| 事件类型 | `DOWNSTREAM_HTTP_5XX` |

### 3.2 Agent 诊断结果

| 项目 | 结果 |
| --- | --- |
| 任务 ID | `ab7f79d3-9f78-4755-8141-da8bc63baa0d` |
| 状态 | `SUCCEEDED` |
| 结论状态 | `ROOT_CAUSE_IDENTIFIED` |
| 模型 | `deepseek-flash` |
| 置信等级 | `MEDIUM` |
| 开始 / 完成 | 12:22:30.025Z / 12:22:39.691Z（9.666 s） |
| 模型调用 / 工具调用 | 4 / 3 |
| 失败原因 | 无 |

根因摘要：

> inventory-service 的库存预占接口 `POST /api/inventory/SKU-1001/reserve` 自身返回 HTTP 500，导致 order-service 的结算请求在下游调用失败后返回 HTTP 502。

### 3.3 自主工具选择与审计

| # | 工具 | 服务 | 参数要点 | 耗时 | 证据数 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `query_logs` | all | `requestId=exp-a4bd...`，lookback 30 | 119 ms | 2 | SUCCEEDED |
| 2 | `query_logs` | inventory-service | lookback 30 | 3 ms | 1 | SUCCEEDED |
| 3 | `query_metrics` | inventory-service | lookback 30 | 28 ms | 1 | SUCCEEDED |

工具输出来自真实请求日志与分钟级指标，不包含健康检查与实验控制请求。模型在第 4 轮输出最终报告，该轮耗时 4606 ms、prompt 2391 tokens、completion 732 tokens。

### 3.4 证据链

支持证据（3 条，全部来自工具输出）：

- `log:order-service:ea7fed03-bc64-484f-9534-90ff506fbc07`：checkout 返回 502，873 ms
- `log:inventory-service:856f6585-c998-4c01-a23c-76710140de10`：reserve 返回 500，12 ms
- `metric:inventory-service:2026-10-22T12:22:00Z`：该分钟 1 次请求、1 次 5xx、平均 12 ms

反向证据：无。

模型在报告中明确指出：现有日志/指标缺少 inventory-service 内部异常堆栈，且窗口内仅 1 次请求，因此置信度为 `MEDIUM`，未夸大为 `HIGH`。该限制与真实证据一致。

## 4. DELAY 3500ms 真实故障 E2E

### 4.1 实验与真实请求

| 项目 | 结果 |
| --- | --- |
| 实验 ID | `1b8d7fa8-af4e-4d95-8c0b-19e985130ca3` |
| 故障 | DELAY 3500 ms，持续 120 s |
| 激活时间 | 2026-10-22T12:23:07.745Z |
| requestId | `exp-843b1bf13cc0409e82a72cc863460cf6` |
| 真实结算结果 | `outcome=SLOW_RESPONSE`，HTTP 200，耗时 3529 ms |
| 订单 | `ORD-39F85EAFBC1C`，`CONFIRMED`，剩余库存 48 |
| 下游观测 | inventory-service reserve 耗时 3502 ms，HTTP 200 |
| 故障事件 ID | `ae26ddd0-26e0-458c-90d3-0029b7224cd0` |
| 事件类型 | `SLOW_RESPONSE` |

### 4.2 首次诊断：真实瞬时失败

| 项目 | 结果 |
| --- | --- |
| 任务 ID | `acfa70a3-3df9-4c35-9617-99a268527191` |
| 状态 | `FAILED` |
| 失败原因 | `DeepSeek 响应缺少 message.content` |
| 模型 | `deepseek-flash` |
| 开始 / 完成 | 12:23:15.720Z / 12:23:24.909Z（9.189 s） |
| 已执行工具 | 2 次成功：`query_logs`（6 ms，2 条证据）、`query_metrics`（22 ms，4 条证据） |
| 模型调用 | 3 次（前两轮决定调用工具，第 3 轮返回空 `content`） |

这是一次真实的 Provider 瞬时响应问题，不是 Mock，也没有被当作成功结果。失败路径把事件从 `DIAGNOSING` 恢复为 `OPEN`。

### 4.3 最小修复

见第 5 节。修复后通过重试接口创建新任务，未重新开启故障。

### 4.4 重试诊断结果

| 项目 | 结果 |
| --- | --- |
| 任务 ID | `e09e6864-90e2-4dcc-872f-fbeadee38f30` |
| 触发来源 | `AGENT_RETRY` |
| 状态 | `SUCCEEDED` |
| 结论状态 | `ROOT_CAUSE_IDENTIFIED` |
| 模型 | `deepseek-flash` |
| 置信等级 | `MEDIUM` |
| 开始 / 完成 | 12:26:15.782Z / 12:26:27.385Z（11.602 s） |
| 模型调用 / 工具调用 | 4 / 3 |
| 失败原因 | 无 |

根因摘要：

> order-service 的 `/api/orders/checkout` 慢响应（3509 ms）几乎全部来自对 inventory-service `/api/inventory/SKU-1001/reserve` 的下游调用；同一 requestId 下 inventory-service 自身耗时 3502 ms，占端到端约 99.8%，上游自身开销约 7 ms。因此归因于 inventory-service 的库存预留接口本次调用异常耗时。

### 4.5 自主工具选择与审计

| # | 工具 | 服务 | 参数要点 | 耗时 | 证据数 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `query_logs` | all | `requestId=exp-843b...`，lookback 30 | 10 ms | 2 | SUCCEEDED |
| 2 | `query_metrics` | all | lookback 30 | 11 ms | 4 | SUCCEEDED |
| 3 | `query_logs` | inventory-service | lookback 30 | 2 ms | 3 | SUCCEEDED |

最终报告轮次耗时 5044 ms、prompt 3264 tokens、completion 949 tokens、响应 1649 字符。

### 4.6 证据链

支持证据（5 条）：

- `log:order-service:2fec1606-fb4e-4c28-a1c7-bd1d04d58a26`：checkout 200，3509 ms
- `log:inventory-service:ddc19df0-e1cd-4c2c-9b63-3effd0ab9e25`：reserve 200，3502 ms
- `metric:order-service:2026-10-22T12:23:00Z`
- `metric:inventory-service:2026-10-22T12:23:00Z`
- `log:inventory-service:d43cd423-4e33-4862-9c36-31a32482aaac`：同窗口正常请求，17 ms

反向证据（1 条）：

- `log:inventory-service:856f6585-c998-4c01-a23c-76710140de10`：前一次 HTTP_500 实验的 500 日志（12 ms）

模型正确地把这条历史 500 证据标为**反向/无关证据**，没有把两个实验混为一谈；同时在 limitations 中说明该 500 与本次慢响应没有直接因果证据。这是本次验收中证据链行为最值得肯定的部分。

## 5. 代码修改与回归测试

### 5.1 修改内容

| 文件 | 修改 |
| --- | --- |
| `backend/src/main/java/com/microservice/rca/agent/DeepSeekAgentModelClient.java` | 空 `message.content` 的 `INVALID_RESPONSE` 从不可重试改为**可重试**，并补充注释说明 JSON 模式偶发空响应；仍受 Agent 总模型调用预算限制 |
| `backend/src/test/java/com/microservice/rca/agent/DeepSeekAgentModelClientTest.java` | 原“拒绝缺失内容”测试改为 `treatsEmptyContentAsRetryable`，断言 failureType 为 `INVALID_RESPONSE` 且 `isRetryable=true` |

设计边界：

- 只放宽“空 content”这一种瞬时响应，不改变 401/402/请求错误等不可重试语义；
- 没有新增依赖、没有修改接口、没有改变 Agent 状态机；
- 重试次数仍由 `AGENT_MAX_MODEL_CALLS` 控制（本轮为 4）。

### 5.2 回归结果

| 测试类 | 用例数 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| `DeepSeekAgentModelClientTest` | 6 | 0 | 0 | 0 |
| `DefaultAgentOrchestratorTest` | 12 | 0 | 0 | 0 |

修复后后端已重启并加载新代码，`/api/health` 为 `UP`、数据库为 `UP`。完整后端测试套件未自动运行，按项目规范由开发者手动执行。

## 6. 故障清理与业务恢复

| 步骤 | 结果 |
| --- | --- |
| HTTP_500 disable | 2026-10-22T12:23:05.184Z，`active=false` |
| 无故障对照组 | `SUCCESS`，HTTP 200，33 ms，订单 `ORD-1F9391477FF5`，剩余库存 49 |
| DELAY 实验 | 120 s 后自动过期；最终执行 `faults/reset` |
| 最终故障状态 | `experimentEnabled=false`、`active=false`、`faultType=null` |
| inventory-service | 已以 `EXPERIMENT_FAULT_INJECTION_ENABLED=false` 重启，健康检查 `UP` |
| 最终对照组结算 | `SUCCESS`，HTTP 200，629 ms（服务重启后首次请求），订单 `ORD-4F8EDAFF27F7`，`faultObserved=false` |

所有实验期间使用的故障均已关闭；故障注入能力本身也已通过重启关闭。

## 7. 数据库持久化与前端展示

### 7.1 数据库

| 表 | 结果 |
| --- | --- |
| `diagnosis_task` | 3 行：2 个 `SUCCEEDED`、1 个 `FAILED`；`result_status`、`model_name`、`confidence_level`、`failure_reason`、`started_at`、`completed_at` 与接口一致 |
| `tool_invocation` | 8 行，全部 `SUCCEEDED`；`input_payload`、`output_payload`、`latency_ms`、`sequence_no` 可查 |
| `fault_event` | 两个事件均已落库；验收时 HTTP_500 事件为 `DIAGNOSING`、DELAY 事件为 `OPEN`。Day 5 已修复任务终态未恢复 `OPEN` 的问题，但本条历史记录保持原样、不回溯修改 |
| 报告快照 | 两个成功任务的 `report`、`agent_input`、`decision_trace` 均非空 |

### 7.2 前端

使用 Windows Chrome 无头模式渲染事件详情页（`--dump-dom`），并检查页面文本：

| 页面 | 已确认内容 |
| --- | --- |
| HTTP_500 事件 `b57bd168...` | “已识别根因”、`deepseek-flash`、模型调用 4 次、工具调用 3 次、根因报告、真实证据 ID、Agent 决策与工具执行时间线 |
| DELAY 事件 `ae26ddd0...` | 重试任务状态、失败历史及失败原因、根因报告、5 条支持证据、1 条反向证据、决策时间线、`tool_invocation` 审计表 |

验证方式是 DOM 文本，不是像素截图或人工点击；页面已真实调用后端接口，不是静态 Mock 数据。未实现能力仍显示“待开发”。

## 8. 真实结果与 Mock 结果区分

| 项目 | 真实结果 | Mock / 单元测试 |
| --- | --- | --- |
| DeepSeek 鉴权 | `GET /models` 200；1 次冒烟调用 200 | `DeepSeekAgentModelClientTest` 使用本地 `HttpServer` 验证错误映射 |
| `/chat/completions` 200 | 真实调用 12 次 | 本地 `HttpServer` 验证请求体与响应解析 |
| JSON 输出 / 解析 | 冒烟调用与 2 份最终报告均为真实 JSON | 解析器与校验器单元测试 |
| 模型自主选择工具 | 真实调用 `query_logs` / `query_metrics`，参数由模型决定 | `DefaultAgentOrchestratorTest` 的脚本化模型客户端 |
| 真实 evidenceId | 报告中的 ID 全部来自真实工具输出 | 校验器测试使用构造证据 |
| 真实故障请求 | HTTP_500 / DELAY 均由 order-service → inventory-service 真实 HTTP 调用产生 | 不适用 |
| 真实根因报告 | 2 份，见第 3、4 节 | Mock 报告仅用于结构校验，未作为验收结果 |
| token / 费用 | 仅记录可核实的真实 usage，见第 9 节 | 不适用 |

本文件没有把任何 Mock 返回、静态样例或未鉴权探测写成真实模型结果。

## 9. 调用次数、耗时与 token 记录

### 9.1 真实模型调用

| 用途 | 调用次数 | 说明 |
| --- | --- | --- |
| `/models` 模型核实 | 1 次 HTTP 请求 | 不产生模型 token，不计入模型调用 |
| 冒烟调用 | 1 | `deepseek-flash`，0.777 s，`finish_reason=stop` |
| HTTP_500 诊断 | 4 | 3 次工具决策 + 1 次最终报告 |
| DELAY 首次诊断 | 3 | 2 次工具决策 + 1 次空 `content` 失败 |
| DELAY 重试诊断 | 4 | 3 次工具决策 + 1 次最终报告 |
| **合计** | **12** | 不含 `/models` |

### 9.2 可核实的 token

| 调用 | prompt tokens | completion tokens | 合计 |
| --- | --- | --- | --- |
| 冒烟调用 | 80 | 34 | 114 |
| HTTP_500 最终报告轮 | 2391 | 732 | 3123 |
| DELAY 重试最终报告轮 | 3264 | 949 | 4213 |
| **可核实合计** | **5735** | **1715** | **7450** |

**限制**：中间工具决策轮次的 usage 没有写入 `decision_trace`，因此无法据此计算全部 12 次调用的总 token 或费用；本报告不估算、不编造。DELAY 首次失败的第 3 轮返回空内容，也未获得 usage 数据，但 Provider 是否计费无法从响应确认。

> Day 5 已补充逐轮 usage 与 `AGENT_RUN_SUMMARY` 核算，后续任务可完整记录可获取的 input/output token 和耗时；本报告中的历史数据仍保持原样，不回溯补齐。详见 [Day 5 收尾报告](day5-stabilization-report.md)。

### 9.3 任务耗时

| 任务 | 总耗时 | 最终模型轮耗时 |
| --- | --- | --- |
| HTTP_500 诊断 | 9.666 s | 4606 ms |
| DELAY 首次诊断（失败） | 9.189 s | 6617 ms（空内容） |
| DELAY 重试诊断 | 11.602 s | 5044 ms |

两次成功报告的 completion tokens 分别为 732 / 949，均低于 `max_tokens=1200`；JSON 均成功解析，未观察到截断。`finish_reason` 当前未持久化，因此只能以“报告完整解析”作为未截断证据。

## 10. 遗留观察项

| 观察项 | 当前行为 | 影响 / 建议 |
| --- | --- | --- |
| 诊断成功后事件状态 | HTTP_500 事件成功后仍为 `DIAGNOSING`；DELAY 重试成功后仍为 `OPEN` | 状态语义未定义清楚。此前已确认“无需求确认不改状态机”；建议产品侧明确成功后应进入 `RESOLVED`、回到 `OPEN` 还是保持待人工处置 |
| 中间轮次 token 未持久化 | 只有最终报告轮的 usage 写入轨迹 | 无法精确核算总费用；后续可在每次 `MODEL_DECISION`/工具决策轮记录 usage，属于审计增强，不在本轮范围 |
| 空 `content` 重试 | 已改为可重试，但重试会消耗一次模型预算 | 若在最后一次允许调用时再次出现空内容，任务仍会失败；这是预算保护的合理边界 |
| 指标分钟桶混合 | 同一分钟/窗口内可能包含多个实验的流量 | 模型本次已通过 `requestId` 日志区分，并把历史 500 标为反向证据；后续实验应尽量拉开时间窗口 |
| `.env` 权限 | `/mnt/c` 下为 `777`，`chmod` 无效 | 本地文件权限不可控；建议 shell 注入或 Windows ACL |
| 前端验证方式 | 使用无头 Chrome DOM 文本验证 | 未做像素截图和人工点击；交互行为仍建议开发者本地复核 |
| 完整测试套件 | 本轮只运行定向 18 个用例 | 按项目规范，完整测试由开发者手动执行 |

## 11. 结论

Day 4 真实 DeepSeek 端到端验收完成：

- 账户模型已核实，`deepseek-flash` 可用；
- 冒烟调用与两条真实故障链路均使用真实 DeepSeek API；
- Agent 能自主选择并调用 `query_logs` / `query_metrics`；
- 报告引用的证据 ID 全部来自真实工具输出，DELAY 场景还正确识别了反向证据；
- 工具审计、任务状态、报告、输入快照、决策轨迹均已持久化；
- 前端能展示诊断过程、证据链、根因报告和工具审计；
- 所有故障实验已关闭，业务已恢复；
- 发现并最小修复了 JSON 模式空 `content` 的不可重试问题，定向回归通过。

未开发新功能，未进入 Day 5，未执行 `git commit` / `push`，未输出或保存 API Key。

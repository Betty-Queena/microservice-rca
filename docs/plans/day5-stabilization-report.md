# Day 5 工程收尾与答辩演示准备报告

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-22 |
| 范围 | 状态机语义、诊断历史、usage 核算、全量测试、敏感信息检查、前端完整性、演示脚本与截图规划 |
| 结论 | **通过**。修复了诊断终态与事件状态不一致问题；逐轮 usage/耗时已可审计；后端 63、库存 17、订单 7 共 87 个测试通过；前端构建成功 |
| 约束 | 未新增 Agent 或故障类型；未执行自动修复；未输出/保存 API Key；未执行 git commit/push |

---

## A. 状态机检查及修复

### A.1 修复前的问题

| 场景 | 修复前现象 | 原因 |
| --- | --- | --- |
| HTTP_500 诊断成功 | 事件停留在 `DIAGNOSING` | 任务成功路径只更新任务，没有把事件恢复到 `OPEN` |
| DELAY 重试成功 | 事件保持 `OPEN` | `retry` 只创建新任务，没有把 `OPEN` 推进为 `DIAGNOSING` |
| 诊断完成后的语义 | 容易被误解为“故障已解决” | 事件状态与诊断任务状态没有在界面和文档中明确区分 |

### A.2 确定的语义

- `fault_event.status` 表示**处置生命周期**，不是诊断结论：
  - `OPEN`：待人工处置；
  - `DIAGNOSING`：仅在有诊断任务执行期间；
  - `RESOLVED` / `CLOSED`：只能由人工确认。
- `diagnosis_task.status` 表示**任务执行状态**：`PENDING → RUNNING → SUCCEEDED / FAILED`。
- `diagnosis_task.result_status` 表示**诊断结论**：`ROOT_CAUSE_IDENTIFIED` / `INSUFFICIENT_EVIDENCE`。
- AI 诊断完成（无论成功、证据不足还是失败）只代表调查结束，不改变故障是否已解决：
  - 任务启动：`OPEN → DIAGNOSING`；
  - 任务进入任意终态：`DIAGNOSING → OPEN`（内部恢复，等待人工处置）；
  - 人工确认后才允许 `OPEN/DIAGNOSING → RESOLVED/CLOSED`。

### A.3 代码修改

| 文件 | 修改 |
| --- | --- |
| `backend/src/main/java/com/microservice/rca/agent/AgentTaskRunner.java` | 新增 `restoreEventAfterDiagnosis`：成功、证据不足、失败、异常路径统一把 `DIAGNOSING` 恢复为 `OPEN`；只修改仍为 `DIAGNOSING` 的事件，不触碰 `RESOLVED/CLOSED` |
| `backend/src/main/java/com/microservice/rca/agent/AgentDiagnosisService.java` | 抽取 `markEventDiagnosing`；首次诊断与重试都在事件为 `OPEN` 时推进为 `DIAGNOSING`；重试创建新任务，原任务不变 |
| `frontend/src/views/FaultEventDetailView.vue` | 增加“AI 诊断已完成，事件仍需人工处置”提示；明确只有人工确认才进入已解决/已关闭 |
| `docs/architecture/system-design.md` | 补充任务/事件状态语义、内部恢复流转和诊断历史说明 |

### A.4 诊断历史

- `startForEvent` 与 `retry` 都通过 `createTask` 新建记录，不更新原任务的状态、失败原因或报告。
- 前端“任务历史”按创建时间展示全部任务；失败任务和重试后的成功任务同时保留。
- 没有删除、覆盖或回填任何历史任务。

### A.5 回归测试

| 测试 | 验证点 |
| --- | --- |
| `AgentTaskRunnerTest.restoresEventToOpenAfterSuccessfulDiagnosisAndSummarizesUsage` | 成功后事件 `DIAGNOSING → OPEN`，报告含调用次数、Token、耗时汇总 |
| `AgentTaskRunnerTest.reopensEventWhenDiagnosisFails` | 失败后事件恢复 `OPEN`（既有行为回归） |
| `AgentDiagnosisServiceTest.retryCreatesNewTaskAndMarksOpenEventDiagnosing` | 重试创建新任务、事件进入 `DIAGNOSING`、原任务保留 |
| `AgentDiagnosisServiceTest.retryDoesNotReopenResolvedOrClosedEvent` | 重试已解决事件不会把事件改回诊断中 |

---

## B. Token 统计完善情况

### B.1 数据采集链路

| 环节 | 修改 |
| --- | --- |
| `AgentModelClient.ModelResponse` | 增加 `usageAvailable`，明确区分“真实 0 token”和“Provider 未返回 usage” |
| `DeepSeekAgentModelClient.parseResponse` | 仅当 `usage` 对象同时包含 `prompt_tokens`、`completion_tokens` 时标记可用；缺失时不伪造 token |
| `DefaultAgentOrchestrator` | 每一轮模型调用都写入 usage 与耗时；新增被接受的 `CALL_TOOL` 轮次记录；`TOOL_REJECTED` 轮次同样记录 usage |
| `AgentTaskRunner` | 从决策轨迹汇总 `AGENT_RUN_SUMMARY`：模型调用次数、有/无 usage 轮数、prompt/completion/total tokens、模型耗时合计、`usageNote` |
| 根因报告/证据不足报告 | 嵌入同一组统计字段，前端无需展开完整轨迹即可看到 |
| 前端时间线 | 每轮展示 `input xxx / output xxx tokens`；缺失时显示“Provider 响应未包含 usage”；末条显示“本次运行统计” |
| 前端报告头部 | 展示 Token 合计、input/output、缺失 usage 轮数提示 |

### B.2 缺失处理

- Provider 未返回 usage：`promptTokens/completionTokens/totalTokens` 在轨迹和报告中为 `null`，`usageAvailable=false`，并写明原因；**不补零、不估算**。
- 模型调用失败：记录 `MODEL_ERROR`，usage 字段为 `null`，说明“模型调用失败，未获得 usage”。
- 汇总只累加 `usageAvailable=true` 的轮次，并单独统计缺失轮数，避免把缺失值当作 0 参与“总消耗”。

### B.3 已知限制

- Day 4 已落库的历史任务产生于本次修改之前，其 `decision_trace` 没有逐轮 usage 和 `AGENT_RUN_SUMMARY`；这些任务无法回溯补齐，报告不会反推或编造。
- 本系统只记录 Provider 返回的 token 数，不计算费用；模型单价可能变化。
- Provider 即使未返回 usage 也可能计费，系统无法从响应确认，已在字段语义中明确标注缺失。

### B.4 回归测试

- `DeepSeekAgentModelClientTest.parsesSuccessfulResponseAndSendsExpectedRequest`：usage 解析与 `totalTokens`。
- `DeepSeekAgentModelClientTest.marksUsageMissingWithoutFabricatingTokens`：无 usage 时 `usageAvailable=false`；模型对象内部 Token 为 0 占位，但轨迹和报告写入 `null`，不把占位值当作真实消耗。
- `DefaultAgentOrchestratorTest.supportsMultiRoundInvestigation`：3 轮模型调用全部带 usage，包括 `CALL_TOOL` 轮次。
- `AgentTaskRunnerTest.restoresEventToOpenAfterSuccessfulDiagnosisAndSummarizesUsage`：汇总 42/28/70 tokens、250 ms、2 次调用。
- `AgentTaskRunnerTest.marksMissingUsageExplicitlyWithoutFabricatingTokens`：缺失轮次被单独计数。

---

## C. 完整测试结果

执行命令：

```bash
cd backend && mvn test
cd services/inventory-service && mvn test
cd services/order-service && mvn test
cd frontend && npm run build
```

| 模块 | 结果 | 测试数 | 失败/错误/跳过 |
| --- | --- | ---: | --- |
| 管理后端 `backend` | BUILD SUCCESS | 63 | 0 / 0 / 0 |
| 库存服务 `inventory-service` | BUILD SUCCESS | 17 | 0 / 0 / 0 |
| 订单服务 `order-service` | BUILD SUCCESS | 7 | 0 / 0 / 0 |
| **合计** | — | **87** | **0 / 0 / 0** |
| 前端 `npm run build` | 成功 | — | 1669 个模块，5.69 s |

前端构建提示单个 JS chunk 约 1.13 MB（gzip 363 KB），超过 Vite 500 KB 警告阈值；属于现有依赖体积提示，不影响构建和功能，本轮未做代码分割重构。

### C.1 敏感信息检查

| 检查项 | 结果 |
| --- | --- |
| `.env` Git 状态 | 存在于仓库根目录；`.gitignore` 第 2 行命中，`git check-ignore` 通过；未跟踪 |
| 仓库中的 Key/密码文件 | 未发现被跟踪的 `.env`、密钥、凭据或密码文件 |
| 源码硬编码 | 未发现 `sk-...`、真实 `DEEPSEEK_API_KEY`、`POSTGRES_PASSWORD`、`DB_PASSWORD` 赋值 |
| Compose 配置 | 使用 `${POSTGRES_PASSWORD:?...}`，未写死密码 |
| 后端配置 | `DB_PASSWORD`、`DEEPSEEK_API_KEY` 均为环境变量占位，无默认真实值 |
| 日志/截图文件 | 仓库内未发现 `.log`、`.png`、`.jpg` 等演示产物 |
| `.env.example` | Key 为空；数据库密码为占位符，不含真实凭据 |
| 文件权限 | `.env` 位于 Windows 挂载盘，显示 `777` 且 `chmod 600` 不生效；需依赖 Windows ACL、不投屏文件管理器和不提交来防护 |

### C.2 前端完整性检查

- `npm run build` 成功，1669 个模块完成转换。
- 使用 headless Chrome 加载并检查四个真实页面：
  - `/overview`：系统概览与三服务/数据库状态；
  - `/fault-injection`：DELAY/HTTP_500 表单、实验状态、真实结果、对照请求；
  - `/fault-events`：筛选、状态标签、事件列表与详情入口；
  - `/fault-events/{id}`：实验预设与实际观测、根因报告、Agent 状态、Token 统计、时间线、工具审计、任务历史。
- 页面 DOM 均成功渲染，未出现空白页或加载错误；构建与页面检查不替代答辩前的完整交互走查。

### C.3 文档同步

- `README.md`：更新 Day 5 版本说明、状态语义、usage 核算、测试结果、敏感信息与截图注意事项、文档链接。
- `docs/architecture/system-design.md`：更新版本与状态、模块完成情况、逐轮 usage 核算、任务/事件状态语义和 `DIAGNOSING → OPEN` 内部恢复。
- `docs/plans/day4-deepseek-validation.md`：保留历史结果，补充 Day 5 修复说明，不修改历史 token 与状态记录。
- 新增 `docs/plans/day5-demo-script.md` 与本报告。

---

## D. 演示脚本

已生成独立文档：[5 分钟答辩演示脚本](day5-demo-script.md)。

主流程：

| 时间 | 内容 |
| --- | --- |
| 0:00–0:30 | 系统概览：三服务与 PostgreSQL 实时健康状态 |
| 0:30–1:10 | 注入 HTTP_500（120 秒），展示实验 ID 与倒计时 |
| 1:10–1:45 | 执行真实结算请求，展示 502、耗时、requestId 与关联事件 |
| 1:45–3:10 | 启动 AI 诊断，展示自主工具调用、根因、证据、Token/耗时统计 |
| 3:10–3:50 | 关闭故障，执行无故障对照请求，确认业务恢复；事件恢复“待处理”，人工标记已解决 |
| 3:50–4:30 | 展示任务历史、诊断时间线和 `AGENT_RUN_SUMMARY` |
| 4:30–5:00 | 真实性边界与安全边界收尾 |

脚本同时包含启动顺序、预热、HTTP_500 主方案、DELAY 备选、失败/限流/超时/端口/数据库异常预案和演示后清理命令。

---

## E. 5 张截图规划

> 只拍摄真实运行页面；不生成、不拼接、不使用 Mock 页面。截图不得包含 `.env`、终端环境变量、Authorization 请求头或数据库密码。

| # | 页面/时机 | 应展示的内容 | 目的 |
| --- | --- | --- | --- |
| 1 | `/overview`，服务全部启动后 | 管理后端、订单、库存健康状态；PostgreSQL `UP` 与真实延迟；最近刷新时间 | 证明系统是真实运行的多服务环境 |
| 2 | `/fault-injection`，HTTP_500 已开启并执行一次实验请求后 | 实验开关、故障类型、实验 ID、剩余时间；真实结果 HTTP 502、耗时、requestId；“查看关联故障事件” | 证明故障由真实请求产生，不是前端模拟 |
| 3 | `/fault-events`，事件生成后 | 事件列表中的服务、观测类型、严重级别、摘要、状态（待处理/诊断中）、来源“故障注入实验” | 证明观测结果真实落库并可追踪 |
| 4 | 事件详情“Agent 诊断”，任务成功后 | 事件状态、实验预设与实际观测；`deepseek-flash`；模型/工具调用次数；Token input/output 与耗时；根因、置信等级、真实证据 ID；“诊断完成仍需人工处置”提示 | 证明真实模型自主诊断与证据约束 |
| 5 | 事件详情“决策时间线/工具审计/任务历史” | 逐轮 `MODEL_DECISION`、`query_logs`/`query_metrics` 调用与参数、证据条数、每轮 input/output token 与耗时、`AGENT_RUN_SUMMARY`、失败/重试历史 | 证明过程可审计、历史不被覆盖、usage 不造假 |

拍摄建议：

- 使用浏览器窗口截图，保留地址栏可证明页面来源；不要拍到书签栏中的敏感信息。
- 截图 2、4、5 应在同一场演示内完成，保证实验 ID、requestId、任务 ID 可交叉核对。
- 如果使用历史真实任务补拍，必须在答辩中明确说明是历史真实记录，不能声称是当场调用。

---

## F. 剩余风险

| 风险 | 影响 | 现状/建议 |
| --- | --- | --- |
| 当前运行中的后端仍是修复前实例 | 新状态语义尚未在 8080 进程生效 | 演示前重启后端；本轮为避免影响现有环境未自动重启 |
| Day 4 遗留的 HTTP_500 事件仍可能显示 `DIAGNOSING` | 历史数据与修复后语义不一致 | 不直接改数据库；演示使用新注入事件，或对该事件重新启动诊断，任务终态会恢复 `OPEN` |
| 进程崩溃/强制终止 | 任务可能停在 `RUNNING`，事件停在 `DIAGNOSING` | 当前没有启动时对账任务；演示时避免 kill 后端；后续可增加启动恢复扫描 |
| 历史任务缺少逐轮 usage | 无法给出完整 token 总量 | 已在报告和界面标注缺失；不回溯、不估算 |
| Provider 未返回 usage | 单轮 token 无法统计 | 已显式标记 `usageAvailable=false`，汇总单独计数 |
| 模型/网络波动、余额或限流 | 现场诊断失败或变慢 | 演示前核实 `/models` 与余额；失败时展示真实失败原因并最多重试一次，不用 Mock 替代 |
| DELAY 3500 ms 接近订单服务 5 s 超时 | 抖动可能从慢响应变成 502 | 主演示使用 HTTP_500；DELAY 作为备选，并预留更多时间 |
| 前端 bundle 超过 500 KB | 首屏加载偏慢 | 不影响功能；若后续需要可做路由级懒加载，本轮不做无关重构 |
| 敏感信息进入截图/终端 | Key 或数据库密码泄漏 | `.env` 已被忽略且未跟踪；截图只拍浏览器业务页面；演示后恢复故障开关 |
| 仓库尚无任何提交 | 后续首次提交可能误纳入文件 | `.gitignore` 已覆盖 `.env`、target、node_modules、dist、日志；首次提交前执行 `git status` 核对。另：当前 `git user.name/user.email` 未配置，提交前需按规范设置 |
| 前端检查以构建和 DOM 加载为主 | 未覆盖所有交互分支 | 已用 headless Chrome 加载概览、故障注入、事件列表、事件详情四个页面；正式答辩前按脚本完整走一遍 |

---

## 附：本轮未执行的事项

- 未执行 `git commit` / `git push`。
- 未输出、复制、保存或提交 `DEEPSEEK_API_KEY`。
- 未新增 Agent、故障类型或自动修复能力。
- 未使用 Mock 模型结果冒充真实 DeepSeek 结果。
- 未自动重启后端或修改运行中的数据库记录。

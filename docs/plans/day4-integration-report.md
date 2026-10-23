# Day 4 第一阶段联调与端到端验收报告

| 项目 | 内容 |
| --- | --- |
| 项目 | 基于 LLM 的微服务故障分析与处置系统 |
| 日期 | 2026-10-21 |
| 范围 | 真实 DeepSeek 联调、全服务启动、Flyway V3、HTTP_500/DELAY 真实故障、证据与持久化验证 |
| 结论 | 基础设施、迁移、真实故障注入与证据采集已完成；**当前环境没有 `DEEPSEEK_API_KEY`，真实 DeepSeek 调用和真实 Agent 根因诊断未执行，端到端验收被阻塞** |

> 本报告不把 Mock、手工推断或静态答案当作真实模型结果。没有真实 Key，就没有真实根因结论。

## 0. 当前状态与安全配置步骤

### 0.1 Key 检查结果（只报告存在性）

| 检查位置 | 结果 |
| --- | --- |
| WSL 环境变量 `DEEPSEEK_API_KEY` | 不存在（absent） |
| 仓库根目录 `.env` | 不存在该配置（absent） |
| Windows 用户环境变量 | 不存在或不可读（absent/unreadable） |
| Windows 系统环境变量 | 不存在或不可读（absent/unreadable） |
| `cmd.exe` 当前进程 | 不存在（absent） |

未读取、未输出、未记录任何 Key 内容。

### 0.2 安全配置步骤（待执行）

在 WSL 中运行后端时，Windows 用户环境变量不会自动传入 WSL。推荐在仓库根目录的本地 `.env` 中配置，或只在启动后端的 shell 中注入：

```bash
cd /mnt/c/Users/y/Documents/AI/program/microservice-rca
chmod 600 .env

# 方式一：用编辑器在 .env 中增加一行（不要提交、不要截图）
# DEEPSEEK_API_KEY=<your-key>
# DEEPSEEK_MODEL=deepseek-flash

# 方式二：仅当前 shell 注入，避免写入文件
read -rsp 'DEEPSEEK_API_KEY: ' DEEPSEEK_API_KEY
export DEEPSEEK_API_KEY

# 低成本冒烟预算
export AGENT_MAX_MODEL_CALLS=3
export AGENT_MAX_TOOL_CALLS=2
export AGENT_TIMEOUT_SECONDS=60

# 重新启动 backend 才会读取新环境变量
cd backend
export JAVA_HOME=/home/yi/.jdks/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"
mvn -o spring-boot:run
```

配置后先做一个最小验证，不要直接批量跑实验：

```bash
curl --noproxy '*' -sS http://localhost:8080/api/health
# 然后在事件详情页点击“启动AI诊断”，或调用 POST /api/fault-events/{id}/diagnosis
# 查询 GET /api/diagnosis/tasks/{taskId}，确认 status、modelName、decisionTrace 和 report
```

安全要求：

- 不把 Key 写入 README、报告、代码、测试、日志或截图。
- 不把 Key 作为 `curl` 命令参数，避免进入 shell history。
- `.env` 已在 `.gitignore` 中；仍应在提交前检查 `git status`。
- 如果真实调用失败，保留错误类型与失败原因，不用 Mock 结果替代。

### 0.3 模型兼容性发现

DeepSeek 官方兼容 API 文档当前列出的模型为 `deepseek-flash` 和 `deepseek-v4-pro`；项目原先默认的 `deepseek-chat` 已不在当前官方模型列表。为避免真实联调时因模型名无效返回 400，已将默认模型更新为 `deepseek-flash`：

- `backend/src/main/java/com/microservice/rca/agent/AgentProperties.java`
- `backend/src/main/resources/application.yml`
- `.env.example`
- 对应测试断言

该变更仅依据官方文档核对，**尚未用真实 Key 验证**；如果账户可用模型不同，可通过 `DEEPSEEK_MODEL` 覆盖。

## A. 实际启动和数据库迁移结果

### A.1 服务启动

| 组件 | 地址 | 结果 |
| --- | --- | --- |
| PostgreSQL 16 | `localhost:5432` | Docker 容器 `rca-postgres` 已启动，healthy |
| rca-backend | `http://localhost:8080` | 启动成功，`Started RcaBackendApplication` |
| order-service | `http://localhost:8081` | 启动成功，`Started OrderServiceApplication` |
| inventory-service | `http://localhost:8082` | 启动成功；为实验临时以 `EXPERIMENT_FAULT_INJECTION_ENABLED=true` 进程环境启动 |
| frontend (Vite) | `http://localhost:5173` | 启动成功，页面与 Vite 代理可访问 |

健康检查实测：

```text
GET http://localhost:8080/api/health  -> 200
status=UP, database.status=UP, database.latencyMs=9
GET http://localhost:8081/api/health  -> 200
GET http://localhost:8082/api/health  -> 200
GET http://localhost:5173/            -> 200
GET http://localhost:5173/api/health  -> 200（Vite 代理到 backend）
```

启动后端时使用了 WSL 中的 Linux JDK：

```text
JAVA_HOME=/home/yi/.jdks/jdk-17.0.20.1+1
java 17.0.20.1
Maven 3.9.16（mvn -o）
```

### A.2 Flyway V3 正式应用

后端启动日志：

```text
Database: jdbc:postgresql://localhost:5432/rca (PostgreSQL 16.15)
Migrating schema "public" to version "3 - agent diagnosis"
Successfully applied 1 migration to schema "public", now at version v3 (execution time 00:00.037s)
```

`flyway_schema_history` 实测：

| installed_rank | version | description | success |
| --- | --- | --- | --- |
| 1 | 1 | init schema | true |
| 2 | 2 | fault event experiment evidence | true |
| 3 | 3 | agent diagnosis | true |

V3 列已存在：

```text
result_status     varchar
confidence_level  varchar
failure_reason    varchar
report            jsonb
agent_input       jsonb
decision_trace    jsonb
model_name        varchar
started_at        timestamptz
```

JPA 配置为 `ddl-auto: validate`，后端完成 `Initialized JPA EntityManagerFactory for persistence unit 'default'` 并正常启动，说明实体映射与 V1/V2/V3 实际表结构校验通过。

## B. 真实 DeepSeek API 调用结果

### B.1 结论

**真实 DeepSeek 模型调用：未执行。**

原因：缺少 `DEEPSEEK_API_KEY`。按照任务要求，缺少 Key 时先暂停真实模型测试，不使用 Mock 结果冒充。

### B.2 已完成的接口兼容性检查

| 检查项 | 配置/结果 |
| --- | --- |
| API Base URL | `https://api.deepseek.com` |
| 请求路径 | `POST /chat/completions` |
| 默认模型 | `deepseek-flash`（原 `deepseek-chat` 已更新） |
| 连接超时 | 5000 ms |
| 读取超时 | 60000 ms |
| 整体 Agent 超时 | 180 s |
| JSON 输出 | 请求体 `response_format={"type":"json_object"}`，系统提示词包含 JSON 输出要求 |
| 决策解析 | 只接受单个 JSON 对象，支持去除 Markdown 代码围栏，校验 `action` |
| 网络（curl，经环境代理） | `HTTP 401`，`Authentication Fails (governor)` |
| 网络（curl，直连） | `HTTP 401`，`Authentication Fails (governor)` |
| 网络（JVM `HttpURLConnection` 直连） | `HTTP 401` |
| Java 17 TLS/DNS/API 可达性 | 通过（能收到 401，而不是连接失败） |

401 仅说明请求到达 DeepSeek 且缺少鉴权，**不代表模型名、JSON 模式或真实推理已验证**。

### B.3 代理说明

当前 shell 设置了 `HTTP_PROXY`、`HTTPS_PROXY`、`http_proxy`、`https_proxy`。实测：

- `curl` 经代理与直连都能到达 DeepSeek；
- JVM 直连也能到达 DeepSeek（401）；
- Spring `SimpleClientHttpRequestFactory` 不会自动读取 `HTTPS_PROXY` 环境变量。如果后续运行环境必须经代理才能出网，需要给后端 JVM 增加 `-Dhttps.proxyHost=... -Dhttps.proxyPort=...`（或改用支持代理的请求工厂），否则会出现 `UNREACHABLE`。当前环境直连可用，未做代码改动。

### B.4 缺少 Key 时的真实失败路径（已实测）

在 DELAY 事件 `17d1734e-c9f4-4cda-ba49-9c88ace56b13` 上启动诊断，真实返回：

```text
taskId=880366f7-2b25-472f-bca3-fa573056e72b
status=FAILED
resultStatus=null
model=deepseek-flash
failureReason=未配置 DEEPSEEK_API_KEY，Agent 未调用模型，也未生成任何诊断结论
report=null
toolInvocations=0
decisionTrace=[{"type":"MODEL_DECISION","round":0,"status":"FAILED",
  "decision":"MODEL_ERROR","errorType":"CONFIG_MISSING","retryable":false,
  "model":"deepseek-flash", ...}]
```

事件状态在失败后恢复为 `OPEN`。该路径证明：没有 Key 时不会生成假根因、不会伪造工具调用，且失败原因与决策轨迹可查询。

## C. HTTP_500 与 DELAY 的真实结果

> 本节是**真实故障注入、真实跨服务请求和真实证据采集**的结果，不是 LLM 诊断结论。由于没有 Key，未产生真实 DeepSeek 根因报告。

### C.1 HTTP_500

开启故障：

```json
{"faultType":"HTTP_500","durationSeconds":120,"note":"Day4 HTTP_500 E2E"}
```

执行一次真实订单结算：

| 项目 | 实测值 |
| --- | --- |
| experimentId | `ddcc6122-65f7-4fb5-9f86-30e31d6486de` |
| requestId | `exp-ce49127e88474edaa4109e826ff93bf3` |
| outcome | `DOWNSTREAM_HTTP_5XX` |
| HTTP 状态（order-service 返回） | 502 |
| 耗时 | 466 ms |
| downstreamErrorType | `HTTP_5XX` |
| faultEventId | `ae4a2ed3-27e0-40d1-b4b1-93477adf373f` |

真实日志证据：

```text
log:order-service:6d1b2800-1c48-41c9-9131-5f716e675600
  POST /api/orders/checkout, status=502, durationMs=432, errorType=DOWNSTREAM_HTTP_5XX
log:inventory-service:b1889787-126e-4f2c-98bf-639616ca7d71
  POST /api/inventory/SKU-1001/reserve, status=500, durationMs=5, errorType=HTTP_5XX
```

关闭故障后对照组：

```text
outcome=SUCCESS, httpStatus=200, durationMs=28,
orderStatus=CONFIRMED, orderId=ORD-0D6CBD241517, remainingStock=49
```

### C.2 DELAY

开启故障：

```json
{"faultType":"DELAY","delayMs":3500,"durationSeconds":120,"note":"Day4 DELAY E2E"}
```

执行一次真实订单结算：

| 项目 | 实测值 |
| --- | --- |
| experimentId | `163e5c55-741f-4726-ad15-e3ef9b47cc6d` |
| requestId | `exp-3eca75b5b4094ed786f5d2e38cce2afd` |
| outcome | `SLOW_RESPONSE` |
| HTTP 状态 | 200 |
| 耗时 | 3512 ms |
| orderStatus | `CONFIRMED` |
| faultEventId | `17d1734e-c9f4-4cda-ba49-9c88ace56b13` |

真实日志证据：

```text
log:order-service:1c27f071-ce05-4c12-b5be-c51fd9171c64
  POST /api/orders/checkout, status=200, durationMs=3508, errorType=NONE
log:inventory-service:9962419b-ce64-418d-8644-f51ae8d5f435
  POST /api/inventory/SKU-1001/reserve, status=200, durationMs=3502, errorType=NONE
```

关闭故障后对照组：

```text
outcome=SUCCESS, httpStatus=200, durationMs=9,
orderStatus=CONFIRMED, orderId=ORD-2736A8020C36, remainingStock=47
```

### C.3 故障关闭与恢复

两次实验都在执行后调用 `POST /api/experiments/faults/disable`，返回：

```text
active=false, experimentId=null, faultType=null
```

随后各执行一次无故障 `checkout`，均返回 `SUCCESS`，证明业务恢复。当前故障注入控制接口仍可用（进程以 `EXPERIMENT_FAULT_INJECTION_ENABLED=true` 启动），但**当前没有激活故障**；仓库 `.env` 中该值仍为 `false`。

### C.4 真实诊断结论

**未产生。** 没有真实 DeepSeek 调用，因此没有 `rootCause`、没有 `confidenceLevel`、没有真实 Agent 报告。HTTP_500 与 DELAY 的事实仅作为后续真实联调的输入数据保留在数据库中。

## D. Agent 工具调用时间线与证据引用

### D.1 真实模型驱动的工具调用

**无。** 缺少 Key，编排器在模型调用前即失败，`toolInvocations=[]`。因此本轮**不能证明** Agent 真正自主选择了工具，也不能证明真实结论与证据内容一致。

### D.2 手工证据采集的真实工具调用

为验证工具链和证据 ID，使用与 Agent 相同的只读工具，通过事件证据接口各采集一次日志与指标：

| 事件 | 工具 | 状态 | 调用 ID | 证据 ID |
| --- | --- | --- | --- | --- |
| HTTP_500 | `query_logs` | SUCCEEDED | `6fb31f8d-73cb-4969-b925-dcc639991de3` | `log:order-service:6d1b2800-...`、`log:inventory-service:b1889787-...` |
| HTTP_500 | `query_metrics` | SUCCEEDED | `c11a9908-e9c4-4069-aed7-271040e151a7` | `metric:order-service:2026-10-20T17:57:00Z`、`metric:inventory-service:2026-10-20T17:57:00Z` |
| DELAY | `query_logs` | SUCCEEDED | `15ab6aa2-18f2-4839-a269-1225c148e880` | `log:order-service:1c27f071-...`、`log:inventory-service:9962419b-...` |
| DELAY | `query_metrics` | SUCCEEDED | `a421cfed-97ad-4452-b2c8-1f306bfc6bb1` | `metric:order-service:2026-10-20T17:57:00Z`、`metric:inventory-service:2026-10-20T17:57:00Z` |

这些工具调用已写入 `tool_invocation`，`output_payload.evidenceIds` 与上述 ID 一致；证据 ID 可在对应工具输出和 `fault_event.evidence` 快照中追溯。

### D.3 证据 ID 真实性检查

- `query_logs` 返回的每个 ID 都对应一条真实请求日志，包含 `requestId`、服务名、路径、状态码和耗时。
- HTTP_500 的两条日志分别对应 order-service 502 与 inventory-service 500，`requestId` 与事件一致。
- DELAY 的两条日志分别对应 order-service 3508 ms 与 inventory-service 3502 ms，`requestId` 与事件一致。
- `query_metrics` 的 ID 对应真实的分钟桶；HTTP_500 与 DELAY 发生在同一分钟，因此指标桶同时包含两类流量，不能仅凭指标区分两个实验。这是当前指标粒度的限制，不是数据伪造。

### D.4 实验预设字段泄漏检查

对缺少 Key 时持久化的 `agent_input` 快照做字符串检查：

```text
experimentFaultType      = False
experimentParameters     = False
DELAY                    = False
3500                     = False
experimentNote           = False
```

说明白名单输入没有把实验预设答案传给模型。注意：事件详情 API 仍会向**人工界面**展示 `experimentFaultType` / `experimentParameters` 作为实验编排记录，这是预期行为，不属于 LLM 输入泄漏。

## E. 发现并修复的问题

### E.1 诊断失败后事件一直停留在 DIAGNOSING

**现象**：缺少 Key 时任务已 `FAILED`，但事件详情仍显示 `DIAGNOSING`。原因是 `AgentDiagnosisService` 在提交任务前把事件置为 `DIAGNOSING`，失败路径没有恢复。

**修复**：`AgentTaskRunner` 在任务失败时，如果事件状态仍为 `DIAGNOSING`，将其恢复为 `OPEN`；不修改 `RESOLVED` / `CLOSED`。新增回归测试 `AgentTaskRunnerTest.reopensEventWhenDiagnosisFails`。

**验证**：DELAY 事件重新执行缺少 Key 的诊断后，任务 `FAILED`，事件状态为 `OPEN`。修复前产生的 HTTP_500 测试事件已被旧逻辑留在 `DIAGNOSING`，作为测试数据手工修正为 `OPEN`。

### E.2 缺少 Key 时 decisionTrace 为空

**现象**：模型未配置时编排器直接返回失败，`decision_trace` 为 `[]`，只能看到 `failure_reason`。

**修复**：`DefaultAgentOrchestrator` 在 `isConfigured()` 为 false 时写入一条 `MODEL_DECISION` / `MODEL_ERROR` 轨迹，`errorType=CONFIG_MISSING`、`retryable=false`。新增回归测试 `DefaultAgentOrchestratorTest.recordsConfigurationFailureWhenKeyIsMissing`。

**验证**：任务详情和数据库 `decision_trace` 均包含该轨迹，且 `toolInvocations=[]`，没有伪造工具调用。

### E.3 默认模型名与当前官方 API 不兼容

**现象**：项目默认 `deepseek-chat`，但当前 DeepSeek 官方兼容 API 文档列出的模型为 `deepseek-flash` / `deepseek-v4-pro`。

**修复**：默认模型改为 `deepseek-flash`，同步 `AgentProperties`、`application.yml`、`.env.example` 和相关测试。

**限制**：没有真实 Key，未能用真实请求确认该模型在账户下的可用性；如账户策略不同，需通过 `DEEPSEEK_MODEL` 覆盖。

### E.4 未修复的观察项

- `query_metrics` 是分钟级聚合，同一分钟内的多个实验会混在同一桶中；当前依赖 `query_logs` 的 `requestId` 做精确区分。本轮不扩展指标模型，记录为后续限制。
- Spring `RestClient` 默认不读取 shell 代理环境变量；当前直连可用，未改动。若部署环境必须走代理，需要在 JVM 层配置代理。
- `AgentDiagnosisService` 的“单事件单活动任务”约束在正常情况下成立；失败回退逻辑假设没有其他活动任务，后续如引入并发诊断需重新审视。

## F. 测试结果与剩余风险

### F.1 回归测试

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `mvn -o test` | 57 tests, 0 failures, 0 errors |
| 库存服务 | `mvn -o test` | 17 tests, 0 failures, 0 errors |
| 订单服务 | `mvn -o test` | 7 tests, 0 failures, 0 errors |
| 前端 | `npm run build` | 成功（仅有 chunk > 500 kB 的提示） |

合计 **81 个测试通过**（Day 3 的 79 个 + Day 4 新增 2 个回归测试）。后端 Day 4 新增测试：

- `DefaultAgentOrchestratorTest.recordsConfigurationFailureWhenKeyIsMissing`
- `AgentTaskRunnerTest.reopensEventWhenDiagnosisFails`

说明：Maven surefire 目录中仍有旧包名 `com.btxc.rca.*` 的历史报告，统计时只计入当前包 `com.microservice.rca.*`，避免把旧报告误算为新增测试。

### F.2 持久化验证

已通过 API 和 PostgreSQL 实测：

- `diagnosis_task` 的 `status`、`result_status`、`model_name`、`started_at`、`completed_at`、`failure_reason`、`agent_input`、`decision_trace` 均按预期写入。
- 缺少 Key 的任务为 `FAILED`，`result_status` 为空，`report` 为空，`root_cause` 为空。
- `tool_invocation` 的 `tool_name`、`status`、`sequence_no`、`latency_ms`、`input_payload`、`output_payload` 均写入，证据 ID 可追溯。
- 任务详情 API 返回 `agentInput`、`decisionTrace`、`toolInvocations`，可查询。
- V3 迁移和 JPA `validate` 通过。

### F.3 剩余风险与阻塞

| 风险/阻塞 | 影响 | 下一步 |
| --- | --- | --- |
| **没有 `DEEPSEEK_API_KEY`** | 无法完成真实模型调用、真实 Agent 工具选择、真实根因报告和完整端到端验收 | 按第 0.2 节配置 Key，重启 backend，先做 1 次 3 轮/2 工具的最小冒烟测试 |
| 模型名变更未经真实请求验证 | 可能仍遇到模型不可用 | 用最小请求确认 `deepseek-flash` 或改用账户支持的 `DEEPSEEK_MODEL` |
| 无浏览器自动化工具 | 未做真实浏览器点击流；仅完成页面 200、Vite 组件编译和 API 级流程 | 人工在 `http://localhost:5173` 走一遍演示流程，或补充 Playwright |
| 指标分钟桶混合实验流量 | LLM 可能误读同分钟内的其他错误 | 优先用 `requestId` 日志证据；后续增加更细粒度指标或按请求过滤 |
| 代理依赖 | 若网络环境变化，JVM 可能出现 `UNREACHABLE` | 需要时配置 JVM 代理参数，不要改代码硬编码代理 |
| 真实模型输出未验证 | JSON 模式、token 限制、超时、费用均未实测 | 配置 Key 后先小预算冒烟，记录 `modelName`、token、耗时和错误类型 |
| 自动修复/多智能体 | 当前未实现，且本轮明确不扩展 | 保持人工确认，不进入新功能开发 |

### F.4 结论

本轮完成了可验证的基础设施与真实故障链路：

- PostgreSQL、backend、order-service、inventory-service、frontend 全部启动；
- Flyway V3 正式应用，JPA `validate` 通过；
- HTTP_500 和 DELAY 均通过真实跨服务请求触发，事件、日志、指标证据和业务恢复均已验证；
- 缺少 Key 时的失败路径、事件状态恢复、决策轨迹持久化已修复并回归测试。

但**不能宣称真实 DeepSeek 端到端验收成功**：没有真实模型调用，就没有真实 Agent 工具选择、真实根因报告和“结论与证据一致”的验证。需要在配置 `DEEPSEEK_API_KEY` 后继续 Day 4 第二阶段。

未执行 `git commit`、`git push`，未进入新功能开发。

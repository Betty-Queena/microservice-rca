# Day 2 验证报告 — 真实故障注入、故障事件与最小可观测性

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-21 |
| 版本 | v0.2.0 |
| 环境 | WSL2 + Linux JDK 17.0.20.1 + Maven 3.9.16 + PostgreSQL 16（Docker Desktop，容器 `rca-postgres`） |
| 范围 | 故障注入、故障事件管理、结构化请求日志、分钟级指标、真实诊断工具、前端业务页面 |
| 明确未做 | 未调用 DeepSeek API，未实现 LLM Agent，未执行 git commit / push |

> 本报告中的请求 ID、实验 ID、事件 ID、耗时与数据库记录均来自本次实际运行，未使用模拟数据。

## A. 新增与修改文件

### A1. 管理后端 `backend`

新增：

- `src/main/resources/db/migration/V2__fault_event_experiment_evidence.sql`
- `diagnosis/api/CreateFaultEventRequest.java`、`UpdateFaultEventStatusRequest.java`、`FaultEventView.java`、`FaultEventDetailView.java`、`FaultEventPage.java`、`DiagnosisTaskView.java`
- `diagnosis/api/EvidenceQueryRequest.java`、`EvidenceCollectionView.java`
- `diagnosis/web/FaultEventController.java`、`DiagnosisToolController.java`
- `diagnosis/service/EvidenceService.java`
- `observability/ObservabilityClient.java`
- `experiment/ExperimentController.java`、`ExperimentService.java`、`ObservationClassifier.java`
- `experiment/api/ExperimentFaultStatusView.java`、`EnableFaultRequest.java`、`CheckoutRequest.java`、`ExperimentExecutionView.java`
- `tools/QueryLogsTool.java`、`QueryMetricsTool.java`、`DiagnosticToolRegistry.java`、`ToolInvocationService.java`
- 测试：`ObservationClassifierTest`、`QueryLogsToolTest`、`QueryMetricsToolTest`

修改：

- `diagnosis/domain/FaultEvent.java`（增加实验关联、观测结果、证据字段）
- `diagnosis/repository/FaultEventRepository.java`（支持 Specification 过滤）
- `diagnosis/domain/ToolInvocation.java`（显式记录调用时间）
- `diagnosis/service/FaultEventService.java`（创建、列表、详情、状态流转、证据保存、实验事件创建）
- `config/WebConfig.java`（CORS 增加 PATCH）
- `src/main/resources/application.yml`（实验服务地址、慢响应阈值、版本号）
- 包名统一为 `com.microservice.rca`，三个 Maven 工程 `groupId` 统一为 `com.microservice`（Day 1 遗留修正）

### A2. 库存服务 `services/inventory-service`

新增：

- `fault/FaultInjectionProperties.java`、`FaultInjectionService.java`、`FaultInjectionFilter.java`、`FaultInjectionController.java`、`FaultInjectionState.java`、`FaultInjectionStatus.java`、`FaultType.java`、`EnableFaultRequest.java`、`ExperimentAccessGuard.java`
- `observability/RequestIdContext.java`、`RequestLogEntry.java`、`RequestLogStore.java`、`RequestLogWriter.java`、`RequestObservationFilter.java`、`MetricBucket.java`、`RequestMetricsStore.java`、`ObservabilityController.java`
- `src/main/resources/logback-spring.xml`
- 测试：`FaultInjectionServiceTest`、`RequestLogStoreTest`、`RequestMetricsStoreTest`

修改：

- `InventoryServiceApplication.java`（启用 `@ConfigurationPropertiesScan`）
- `src/main/resources/application.yml`（故障注入开关、观测上限、业务路径前缀）
- `InventoryControllerTest`（排除过滤器切片依赖）

### A3. 订单服务 `services/order-service`

新增：

- `observability/RequestIdContext.java`、`RequestLogEntry.java`、`RequestLogStore.java`、`RequestLogWriter.java`、`RequestObservationFilter.java`、`MetricBucket.java`、`RequestMetricsStore.java`、`ObservabilityController.java`
- `src/main/resources/logback-spring.xml`
- 测试：`InventoryClientTest`（真实本地 HTTP Server：正常响应、读超时、下游 500、409）

修改：

- `inventory/InventoryClient.java`：透传 `X-Request-ID`；区分 409、5xx、超时、连接失败、协议错误
- `common/GlobalExceptionHandler.java`：下游不可用返回 502 并写 `X-Downstream-Error-Type`
- `src/main/resources/application.yml`（读超时 5s、业务路径前缀、版本号）
- `OrderControllerTest`（排除过滤器切片依赖）

### A4. 前端 `frontend`

新增：

- `src/api/experiments.js`、`src/api/faultEvents.js`
- `src/views/FaultInjectionView.vue`、`src/views/FaultEventsView.vue`、`src/views/FaultEventDetailView.vue`

修改：

- `src/router/index.js`（三个业务页面路由）
- `src/layouts/MainLayout.vue`（菜单与版本信息）
- `src/views/OverviewView.vue`（Day 2 真实能力说明）
- `src/api/http.js`（超时 15s，覆盖真实下游超时场景）

### A5. 配置与文档

- `.env.example`：新增故障注入开关、地址白名单、最大延迟/时长、观测上限、实验服务地址
- `.env`：验收期间临时开启故障注入，验收完成后已恢复 `EXPERIMENT_FAULT_INJECTION_ENABLED=false`
- `README.md`、`docs/architecture/system-design.md`、`docs/plans/day2-report.md`

## B. 实际实现的功能

### B1. 故障注入（库存服务）

- 支持两种真实故障：`DELAY`（响应延迟，1–30000 ms）与 `HTTP_500`。
- 控制接口：`GET /api/experiments/faults`、`POST /enable`、`POST /disable`、`POST /reset`。
- 默认禁用：`EXPERIMENT_FAULT_INJECTION_ENABLED=false`；关闭时启用返回 403。
- 访问限制：`ExperimentAccessGuard` 校验请求来源地址白名单，默认仅回环地址。
- 状态保存在内存，带自动过期时间（1–600 秒，默认 120 秒），可随时关闭/重置，不会永久阻塞。
- `FaultInjectionFilter` 只匹配 `/api/inventory/**`；健康检查、`/api/observability/**`、`/api/experiments/**` 不受影响。
- 延迟使用带上限的 `Thread.sleep`，线程中断返回 503；HTTP 500 直接返回统一错误结构，不产生无限循环。
- 响应头 `X-Experiment-Fault`、`X-Experiment-Id` 用于实验识别。

### B2. 故障事件管理（管理后端）

- `POST /api/fault-events` 创建事件；`GET /api/fault-events` 按状态、服务、时间范围分页查询；`GET /{id}` 查看详情。
- `PATCH /{id}/status` 支持 OPEN → DIAGNOSING/RESOLVED/CLOSED、DIAGNOSING → RESOLVED/CLOSED、RESOLVED → CLOSED，并记录时间戳。
- 实验执行产生的事件通过 `experiment_id` 关联实验，保存预设参数与实际观测结果。
- **预设与观测分离**：`experiment_fault_type` / `experiment_parameters` 只表示实验预设；`observed_result` 只表示真实请求结果；`root_cause` 未写入，不会被预设类型替代。
- 数据持久化到 PostgreSQL `fault_event` 表。

### B3. 最小可观测性

- 订单与库存服务对业务请求记录结构化日志：`timestamp`、`serviceName`、`requestId`、`method`、`path`、`status`、`durationMs`、`errorType`。
- `X-Request-ID` 由订单服务生成或透传，库存服务接收并写入 MDC；跨服务调用可关联。
- 有界内存日志（默认 5000 条）+ Logback 滚动 JSONL 文件（单文件 10 MB、保留 3 份、总量 50 MB）。
- 分钟级真实指标：请求数、5xx 数、4xx/业务错误数、总耗时、最大耗时；默认保留 180 分钟。
- 错误类型区分：`NONE`、`HTTP_4XX`、`HTTP_5XX`、`BUSINESS_CONFLICT`、`DOWNSTREAM_TIMEOUT`、`DOWNSTREAM_HTTP_5XX`、`UNHANDLED_EXCEPTION` 等。
- 管理后端通过 `/api/observability/logs|metrics` 查询真实数据，不使用随机数或静态 JSON。

### B4. 诊断工具

- 在既有 `DiagnosticTool` 契约上实现：
  - `query_logs`：按服务、时间窗口、`requestId` 查询，最多 200 条。
  - `query_metrics`：按服务、时间窗口查询真实分钟桶与汇总。
- 返回结构化结果：`dataSources`、`query`、`timeRange`、`collectedAt`、`evidenceIds`、`items/buckets`、`warnings`。
- 任一数据源失败时显式返回错误或告警，不伪造成功。
- 每次调用写入 `tool_invocation`；采集前创建真实 `diagnosis_task` 记录，外键均为真实关联。
- `GET /api/diagnosis/tools` 返回工具注册表，尚未接入 LLM。

### B5. 前端业务页面

- **故障注入实验**：选择 `DELAY`/`HTTP_500`、延迟、持续时间、备注；查看实验状态与剩余时间；执行真实实验请求；无故障对照请求；展示真实 HTTP 状态、耗时、订单号、剩余库存、requestId 与关联事件。
- **故障事件**：真实分页列表、状态/服务过滤、严重级别与来源标签、跳转详情。
- **故障事件详情**：事件基本信息、实验预设与实际观测对照、状态操作、证据采集、关联日志、指标汇总、工具调用审计；Agent 诊断明确标记为“待开发”。

## C. 真实故障注入执行过程

以一次 `HTTP_500` 实验为例：

1. 管理后端调用库存服务 `POST /api/experiments/faults/enable`，库存服务校验实验开关与来源地址后，在内存中写入带过期时间的故障状态并返回 `experimentId`。
2. 管理后端调用 `POST /api/experiments/{experimentId}/execute`，后端生成 `requestId`（`exp-...`），向订单服务 `POST /api/orders/checkout` 发起真实 HTTP 请求。
3. 订单服务调用库存服务 `POST /api/inventory/{sku}/reserve`，透传 `X-Request-ID`。
4. 库存服务 `FaultInjectionFilter` 命中 `/api/inventory/**`，按实验状态返回 HTTP 500；`RequestObservationFilter` 记录真实状态与耗时。
5. 订单服务 `InventoryClient` 将下游 5xx 映射为 `InventoryUnavailableException("HTTP_5XX")`，`GlobalExceptionHandler` 返回 502 并附带 `X-Downstream-Error-Type: HTTP_5XX`。
6. 管理后端读取真实 HTTP 响应、`X-Downstream-Error-Type` 与实测耗时，`ObservationClassifier` 判定为 `DOWNSTREAM_HTTP_5XX`。
7. 仅当观测到故障时写入 `fault_event`，同时保存实验预设参数、实际观测结果、`requestId` 与来源 `EXPERIMENT`。
8. 实验到期、手动关闭或重置后，故障立即失效；后续正常请求恢复。

本次实际执行结果：

| 场景 | 实验 ID | 请求 ID | 实测结果 | 耗时 | 事件 |
| --- | --- | --- | --- | --- | --- |
| 无故障正常结算 | — | `exp-bf3aacb80d344f6ab9b527cf723d6d87` | SUCCESS / 200 | 9 ms | 无 |
| 无故障正常结算 | — | `exp-edf0e2364bf94f6bbbf7710f556f05b1` | SUCCESS / 200 | 9 ms | 无 |
| 无故障正常结算 | — | `exp-37315979e6324cdc877195dbcc335a07` | SUCCESS / 200 | 10 ms | 无 |
| DELAY 3500 ms | `c02b4a2a-cc1f-4f97-af38-be247fadf4ea` | `exp-025017f209484d7181155f21f7cdd180` | SLOW_RESPONSE / 200 | 3516 ms | `6a5968e4-7ad9-435d-924e-5fdace261154` |
| DELAY 6000 ms（超时） | `18ada6e2-e267-4de3-a38b-1f2aa376e0d8` | `exp-567d6923e6dd4621898b1a187d848459` | DOWNSTREAM_TIMEOUT / 502 | 5328 ms | `183a57c9-b525-4d2f-90fc-35a0dd25278b` |
| HTTP_500 | `8afdd49f-1301-45fd-8444-449062bf2b5e` | `exp-66c55eefbbea49bba5c778a9790fa8ca` | DOWNSTREAM_HTTP_5XX / 502 | 131 ms | `a046a115-e410-4a6a-b882-00f2ca61c056` |
| 库存不足（SKU-1003，可用 0） | — | `exp-1b762c97ee5843689923b803cb3633ad` | BUSINESS_CONFLICT / 409 | 31 ms | 无（业务冲突，非系统故障） |
| 关闭故障后正常结算 | — | `exp-6f063878c6c3499581ff49a485056bfd` | SUCCESS / 200 | 13 ms | 无 |
| 关闭故障后正常结算 | — | `exp-ff746805a18c46b9acec42445f33770f` | SUCCESS / 200 | 8 ms | 无 |
| 最终超时复验 | `1385f9fa-a8b9-481f-b41b-60e46438b45d` | `exp-bc97eeabf92c42ddb7bb3c8e34bea101` | DOWNSTREAM_TIMEOUT / 502 | 5410 ms | `c85c1d1c-3ac0-4f1c-9720-e80ecc1da01d` |

### C1. 验收中发现并修复的真实缺陷

首次 6000 ms 延迟实验时，订单服务返回的是 **500 + `SERVER_ERROR` + 无下游错误类型**，而不是预期的 `502 + TIMEOUT`。日志显示：

```text
org.springframework.web.client.RestClientException: Error while extracting response ...
Caused by: java.net.SocketTimeoutException: Read timed out
```

原因：`SimpleClientHttpRequestFactory` 的读超时发生在响应读取阶段，Spring 将其包装为通用 `RestClientException`，而不是 `ResourceAccessException`，原代码未覆盖该分支。修复方式：在 `InventoryClient` 中增加 `RestClientException` 兜底分支，并通过异常链识别 `SocketTimeoutException`，映射为 `TIMEOUT`；非超时响应解析错误映射为 `PROTOCOL_ERROR`。修复后复验得到 `502 / TIMEOUT / DOWNSTREAM_TIMEOUT`。

## D. 日志、指标与工具的数据来源

| 数据 | 产生位置 | 存储 | 查询入口 | 说明 |
| --- | --- | --- | --- | --- |
| 请求日志 | 订单/库存 `RequestObservationFilter` | 有界内存 `RequestLogStore`（默认 5000 条）+ `logs/*-requests.jsonl` | 各服务 `GET /api/observability/logs` | 只记录业务路径，不含健康检查与实验控制 |
| 请求指标 | 同上 | 内存分钟桶 `RequestMetricsStore`（默认 180 分钟） | 各服务 `GET /api/observability/metrics` | 真实请求数、5xx、4xx、耗时 |
| `query_logs` | 管理后端 `QueryLogsTool` | 不落原始数据，结果写入证据快照 | 调用两个服务的日志接口并按 `requestId` 合并 | 返回 `dataSources` 与 `evidenceIds` |
| `query_metrics` | 管理后端 `QueryMetricsTool` | 同上 | 调用两个服务的指标接口并汇总 | 返回服务级与全局汇总 |
| 工具审计 | `ToolInvocationService` | PostgreSQL `tool_invocation` | `GET /api/fault-events/{id}` | 记录输入、输出、状态、耗时、顺序 |
| 证据快照 | `EvidenceService` | `fault_event.evidence` JSONB | `POST /api/fault-events/{id}/evidence` | 采集时创建 `diagnosis_task`，再写工具调用 |

本次证据采集实测：

- 事件：`a046a115-e410-4a6a-b882-00f2ca61c056`
- 诊断任务：`ef73d952-7fe0-4fac-8317-d5249204c693`（`SUCCEEDED`，`triggerSource=MANUAL_EVIDENCE`）
- `query_logs`：调用 `3397cc69-2f93-481c-a4c0-d8bf5f3d9a4a`，55 ms，命中订单服务与库存服务各 1 条日志（同一 `requestId`）。
- `query_metrics`：调用 `4322becc-0cf9-4142-bc90-46bdbf5b8bf1`，30 ms，返回真实分钟桶；窗口内订单服务 3 次请求、库存服务 6 次请求。
- 证据中的日志条目包含 `sourceService`，指标包含 `source`、`collectedAt`、`timeRange` 与分钟级 `evidenceIds`。

## E. 测试命令与实际结果

```bash
export JAVA_HOME="$HOME/.jdks/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:$PATH"

cd backend && mvn -o test
# Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

cd services/inventory-service && mvn -o test
# Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

cd services/order-service && mvn -o test
# Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

cd frontend && npm run build
# vite v8.3.4 build 成功；仅有 chunk 体积提示，无构建错误
```

合计 **41 个后端测试全部通过**（Day 1 为 9 个，新增 32 个）。新增测试覆盖：

- `ObservationClassifier` 对成功、慢响应、超时、下游 5xx、业务冲突、4xx、订单服务不可达、服务端错误的分类。
- `FaultInjectionService` 的开关、参数边界、延迟/500 类型、关闭与自动过期。
- 有界日志存储的过滤、条数限制、容量上限。
- 分钟桶指标的聚合、时间窗口与保留期清理。
- `query_logs` / `query_metrics` 的合并、证据 ID、部分失败告警与全失败错误。
- `InventoryClient` 对正常响应、读超时（回归本次修复）、下游 500、409 的映射。

前端运行验证：

- 通过 Vite dev server 启动，`/api` 代理可返回真实故障事件列表。
- 使用本机 Chrome 无头模式实际渲染并截图核对：
  - 故障事件列表显示 PostgreSQL 中 6 条真实事件；
  - 事件详情显示预设 `HTTP_500` 与实际观测 `DOWNSTREAM_HTTP_5XX`、证据采集时间、日志与指标数据；
  - 故障注入页显示实验开关已启用/已禁用状态、真实库存与参数上限。

## F. 数据库迁移

- `V1__init_schema.sql` 未修改，Flyway 历史保持 `1 / init schema / success`。
- 新增 `V2__fault_event_experiment_evidence.sql`，为 `fault_event` 增加：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `experiment_id` | UUID | 关联故障注入实验 |
| `experiment_fault_type` | VARCHAR(64) | 实验预设故障类型（不是根因） |
| `experiment_parameters` | JSONB | 实验参数快照 |
| `observed_result` | JSONB | 真实请求观测结果 |
| `request_id` | VARCHAR(64) | 跨服务请求关联 |
| `source` | VARCHAR(32) | `MANUAL` / `EXPERIMENT` |
| `evidence` | JSONB | 工具采集证据快照 |
| `evidence_collected_at` | TIMESTAMPTZ | 证据采集时间 |
| `updated_at` | TIMESTAMPTZ | 更新时间 |
| `resolved_at` | TIMESTAMPTZ | 解决时间 |

同时增加 `source` CHECK 约束、`request_id` / `experiment_id` / `occurred_at` 索引，并为预设、观测、证据字段添加注释说明语义。

实际 Flyway 历史：

```text
 version |           description           | success |        installed_on
---------+-------------------------------+---------+----------------------------
 1       | init schema                   | t       | 2026-10-21 00:44:23.013455
 2       | fault event experiment evidence| t       | 2026-10-21 01:14:03.732709
```

验收结束时数据库记录：`fault_event = 6`、`diagnosis_task = 1`、`tool_invocation = 2`。

## G. 已知问题与风险

1. **观测数据为进程内存储**：日志与指标保存在内存中，服务重启后清空；JSONL 文件仍保留，但当前工具查询只读取内存存储，不会回放历史文件。跨重启证据需要后续增加日志解析或外部存储。
2. **证据窗口有限**：默认查询事件发生前 60 秒至当前时间，日志最多 5000 条、指标保留 180 分钟；若采集过晚或请求量过大，早期数据可能被淘汰。
3. **实验状态为单实例内存状态**：多实例部署时只有命中的实例生效；当前仅支持本地单实例实验环境。
4. **访问限制不等于认证**：实验控制仅校验开关与回环地址白名单，没有登录鉴权，不能直接暴露到非实验网络。
5. **预设类型仍存储在事件中**：虽然 UI 与字段语义已区分，未来 Agent 如果直接读取完整事件 JSON，仍可能把 `experiment_fault_type` 当作线索。Day 3 必须在 Agent 输入层显式剔除或标记该字段。
6. **冷启动慢响应事件**：`d91079ed-f5c4-455d-a469-655d493166bd`（1961 ms，`MANUAL`）是调整慢响应阈值前真实观测到的冷启动事件，保留在数据库中作为真实历史记录，未删除。
7. **修复前的超时事件**：`418a7107-2530-49c2-86b1-321f493ecb8d`（HTTP 500 / `SERVER_ERROR`）记录了修复前的真实缺陷表现，保留用于复盘。
8. **工具查询范围**：仅实现订单与库存两个服务、日志与指标两类工具；调用链、配置、服务状态工具仍待开发。
9. **前端为开发模式验证**：页面通过 Vite dev server 与无头 Chrome 验证，尚未配置生产静态资源托管与反向代理。
10. **慢响应阈值**：默认 3000 ms，订单读超时 5000 ms；不同机器冷启动可能短暂超过阈值，演示前应先执行一次预热请求。

## H. Day 3 DeepSeek Agent 建议

1. **输入脱敏**：Agent 只接收 `observed_result`、事件摘要、日志/指标工具结果与 `requestId`；不传入 `experiment_fault_type`、`experiment_parameters`，避免预设类型泄漏为“根因答案”。
2. **工具编排**：复用 `DiagnosticToolRegistry`，让 Agent 通过 `query_logs` / `query_metrics` 自主取证；新增 `query_service_status`、`query_config_diff`、`query_traces` 等只读工具。
3. **结论结构**：要求模型输出 `hypothesis`、`supportingEvidenceIds`、`contradictingEvidenceIds`、`confidence`、`recommendedActions`；结论必须引用真实工具证据 ID，禁止无证据根因。
4. **调用审计**：新增 LLM 调用记录（prompt、模型、token、耗时、错误），与 `diagnosis_task` / `tool_invocation` 关联；保留人工可复核轨迹。
5. **安全边界**：DeepSeek API Key 仅通过环境变量注入；设置超时、重试上限、调用次数与成本上限；处置动作只生成建议，必须人工确认。
6. **评测设计**：为 DELAY、HTTP_500、库存不足三类场景准备固定实验脚本，比较 Agent 结论与真实观测；库存不足应被识别为业务冲突而不是系统故障。
7. **前端**：新增诊断任务详情页，展示 Agent 推理步骤、工具调用时间线、证据链与置信度，未实现部分继续标记“待开发”。

## 当前运行状态

- PostgreSQL 容器 `rca-postgres` 运行中。
- 验收完成后本地 `.env` 已恢复 `EXPERIMENT_FAULT_INJECTION_ENABLED=false`，库存服务重启后验证：状态接口返回 `experimentEnabled=false`，启用故障返回 403，正常结算仍成功。
- 本轮未执行 git commit / push，未调用 DeepSeek API。

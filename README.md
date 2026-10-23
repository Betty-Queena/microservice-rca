# microservice-rca

基于 LLM 的微服务故障分析与处置系统（本科毕业设计项目）。

当前版本：**Day 5 — 工程收尾与答辩演示准备**。Day 4 已完成真实 DeepSeek 模型接入，HTTP_500 与 DELAY 场景均通过真实 Agent 自主诊断验收；Day 5 修正了故障事件与诊断任务的状态语义，补齐逐轮 token/耗时核算，并完成全量测试与演示材料。系统只展示真实接口与真实模型返回的数据；未配置 Key 或模型失败时任务会明确失败，不生成伪造结论。

## 当前已实现

- 管理后端 `rca-backend`：统一 JSON 响应、真实数据库健康检查、Flyway 数据库迁移、故障事件管理与证据采集
- 实验服务 `order-service` / `inventory-service`：真实 HTTP 跨服务调用（下单扣减库存）
- 库存服务真实故障注入：`DELAY` 响应延迟、`HTTP_500`，默认禁用、仅本机实验地址、自动过期
- 真实可观测性：结构化请求日志、`X-Request-ID` 跨服务透传、分钟级请求量与耗时指标
- 诊断工具：`query_logs`、`query_metrics`，查询真实观测数据并写入 `tool_invocation` 审计
- DeepSeek Agent 自主诊断：模型逐轮选择 `query_logs` / `query_metrics` 或结束调查，不是固定调用流水线
- 证据约束根因报告：`supportingEvidenceIds` 必须来自真实工具结果；证据不足输出 `INSUFFICIENT_EVIDENCE`
- Agent 安全边界：白名单输入、未注册工具/非法参数拒绝、重复调用跳过、模型轮数/工具次数/超时/输出长度限制
- 诊断任务异步执行与轮询：任务执行状态与诊断结论状态分离，支持失败重试并保留历史任务
- 明确状态语义：AI 诊断完成只把事件从“诊断中”恢复为“待处理”，不自动标记“已解决”；`RESOLVED/CLOSED` 仅由人工确认
- 模型调用核算：逐轮记录 input/output tokens、调用耗时与 usage 是否缺失；任务终态汇总调用次数与可获取的 Token，缺失时不补零
- Vue 3 管理界面：系统概览、故障注入实验、故障事件列表与详情（含日志/指标证据、逐轮 token、Agent 时间线与根因报告）
- PostgreSQL 16：Flyway V1 基础表 + V2 实验/观测/证据字段 + V3 Agent 诊断字段

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 管理后端 | Java 17、Spring Boot 3.5.x、Spring Data JPA、Flyway |
| 实验服务 | Java 17、Spring Boot 3.5.x、RestClient |
| 前端 | Vue 3、Vite、Element Plus、Vue Router、Axios |
| 数据库 | PostgreSQL 16（Docker Compose） |
| 构建 | Maven 3.9+、npm |

## 目录结构

```text
microservice-rca/
├── backend/                     # 管理后端（8080）
├── frontend/                    # Vue 管理界面（5173）
├── services/
│   ├── order-service/           # 订单实验服务（8081）
│   └── inventory-service/       # 库存实验服务（8082）
├── infra/
│   └── docker-compose.yml       # PostgreSQL
├── docs/
│   ├── architecture/            # 架构设计
│   └── plans/                   # 开发计划与验证报告
├── .env.example
└── README.md
```

## 环境要求

- JDK 17（**WSL 中必须是 Linux 版 JDK**，不能使用 Windows `java.exe` 的包装脚本）
- Maven 3.9+
- Node.js 20.19+ 或 22.12+、npm 10+
- Docker Desktop + Docker Compose
- PostgreSQL 由 Docker Compose 提供，无需本机安装

## 快速开始

### 1. 准备环境变量

```bash
cp .env.example .env
# 修改 .env 中的 POSTGRES_PASSWORD / DB_PASSWORD（保持一致）
```

仓库根目录的 `.env` 不会提交到 Git。三个 Spring Boot 工程都通过 `spring.config.import` 自动读取它：

- 管理后端使用 `SERVER_PORT`、`DB_*`、`CORS_ALLOWED_ORIGINS`、`ORDER_SERVICE_BASE_URL`、`INVENTORY_SERVICE_BASE_URL`
- 管理后端 Agent 使用 `DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL` 与 `AGENT_*` 预算限制
- 库存服务使用 `INVENTORY_SERVER_PORT`、`EXPERIMENT_FAULT_INJECTION_*`、`OBSERVABILITY_*`
- 订单服务使用 `ORDER_SERVER_PORT`、`INVENTORY_BASE_URL`、`INVENTORY_READ_TIMEOUT_MS`

Agent 诊断需要 DeepSeek API Key。项目默认模型为 `deepseek-flash`，但模型 ID 必须以账户实际可用为准：先调用 `GET /models` 核实，不要假设 `deepseek-flash` 或 `deepseek-chat` 一定可用（详见 [Day 4 真实 DeepSeek 接入验证](docs/plans/day4-deepseek-validation.md)）。不要把真实 Key 写入仓库、日志或截图：

```bash
export DEEPSEEK_API_KEY='<your-key>'
# 或在本地 .env 中配置 DEEPSEEK_API_KEY
```

低预算冒烟测试可临时设置：

```bash
export AGENT_MAX_MODEL_CALLS=3
export AGENT_MAX_TOOL_CALLS=2
export AGENT_TIMEOUT_SECONDS=60
```

未配置 Key 时，启动诊断会创建任务并以明确的失败原因结束，不调用模型、不生成根因。

故障注入默认关闭。只有在需要做实验时，才临时把 `EXPERIMENT_FAULT_INJECTION_ENABLED` 改为 `true` 并重启库存服务；验收/演示结束后应恢复为 `false`。

### 2. 启动 PostgreSQL

```bash
docker compose --env-file .env -f infra/docker-compose.yml up -d
docker compose --env-file .env -f infra/docker-compose.yml ps
```

> WSL 未启用 Docker Desktop 集成时，`docker` 命令不可用，可改用 Windows CLI：
> `"/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe" compose --env-file .env -f infra/docker-compose.yml up -d`

### 3. 启动管理后端

```bash
cd backend
mvn spring-boot:run
```

启动时 Flyway 会依次执行 `db/migration/V1__init_schema.sql`、`V2__fault_event_experiment_evidence.sql` 与 `V3__agent_diagnosis.sql`。健康检查：

```bash
curl http://localhost:8080/api/health
```

### 4. 启动实验微服务

```bash
# 终端 1
cd services/inventory-service
mvn spring-boot:run

# 终端 2
cd services/order-service
mvn spring-boot:run
```

验证真实跨服务调用：

```bash
curl -X POST http://localhost:8081/api/orders/checkout \
  -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1001","quantity":2}'
```

订单服务调用库存服务的默认读超时为 5 秒。正常结算成功但耗时超过 3 秒时，管理后端会记录为慢响应事件。

DELAY 故障的延迟上限为 4500 毫秒，低于订单服务的 5 秒读超时，避免实验本身把请求变成超时。库存预留在库存服务进程内按订单号幂等：同一 `orderId` 重复预留只扣减一次，不同 `orderId` 不会被合并；幂等记录保存在内存中，库存服务重启后会清空。

### 4.1 可选：执行真实故障注入实验

```bash
# 1. 在 .env 中设置 EXPERIMENT_FAULT_INJECTION_ENABLED=true 并重启库存服务
# 2. 通过管理后端开启故障（也可在“故障注入实验”页面操作）
curl -X POST http://localhost:8080/api/experiments/faults/enable \
  -H 'Content-Type: application/json' \
  -d '{"faultType":"DELAY","delayMs":3500,"durationSeconds":120,"note":"本地实验"}'

# 3. 使用返回的 experimentId 执行真实实验请求
curl -X POST http://localhost:8080/api/experiments/{experimentId}/execute \
  -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1001","quantity":1}'

# 4. 关闭/重置故障，确认正常业务恢复
curl -X POST http://localhost:8080/api/experiments/faults/disable
```

故障只作用于 `/api/inventory/**` 业务接口，健康检查、观测查询与实验控制接口不受影响；故障到期会自动失效。

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 <http://localhost:5173>。开发环境下 `/api` 由 Vite 代理到 `http://localhost:8080`。

## 端口约定

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| frontend | 5173 | Vue 开发服务器 |
| rca-backend | 8080 | 管理后端 |
| order-service | 8081 | 订单实验服务 |
| inventory-service | 8082 | 库存实验服务 |
| postgres | 5432 | PostgreSQL |

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 管理后端健康检查（含数据库真实连通性） |
| GET | `/api/health` | 两个实验服务各自健康检查 |
| GET | `/api/inventory` | 查询实验库存列表 |
| GET | `/api/inventory/{sku}` | 查询单个商品库存 |
| POST | `/api/inventory/{sku}/reserve` | 预占库存（409 表示库存不足） |
| POST | `/api/orders/checkout` | 创建订单并调用库存服务（下游失败返回 502） |
| GET | `/api/experiments/faults` | 查询库存服务故障注入状态 |
| POST | `/api/experiments/faults/enable` | 开启 DELAY / HTTP_500 故障 |
| POST | `/api/experiments/faults/disable` | 关闭当前故障 |
| POST | `/api/experiments/faults/reset` | 重置实验状态 |
| GET | `/api/experiments/inventory` | 代理库存列表，供实验页面选择 SKU |
| POST | `/api/experiments/{experimentId}/execute` | 执行真实故障实验请求 |
| POST | `/api/experiments/checkout` | 无故障对照结算 |
| POST | `/api/fault-events` | 创建故障事件 |
| GET | `/api/fault-events` | 分页查询故障事件 |
| GET | `/api/fault-events/{id}` | 故障事件详情（含实验预设、观测结果、证据） |
| PATCH | `/api/fault-events/{id}/status` | 更新事件状态与时间戳 |
| POST | `/api/fault-events/{id}/evidence` | 调用 query_logs / query_metrics 采集证据 |
| POST | `/api/fault-events/{id}/diagnosis` | 启动 DeepSeek Agent 诊断（异步任务） |
| GET | `/api/diagnosis/tasks/{taskId}` | 查询任务状态、决策时间线、工具审计与报告 |
| GET | `/api/diagnosis/tasks?faultEventId={id}` | 查询事件的 Agent 任务历史 |
| POST | `/api/diagnosis/tasks/{taskId}/retry` | 重试失败任务（创建新任务，保留原记录） |
| GET | `/api/diagnosis/tools` | 查询已注册诊断工具 |
| GET | `/api/observability/logs` | 实验服务真实请求日志（按服务与时间窗口） |
| GET | `/api/observability/metrics` | 实验服务真实分钟级指标 |

## 数据真实性说明

- 系统概览页只展示 `/api/health` 的实时返回值和前端实测响应时间。
- 故障事件来自真实订单请求观测：实验请求经过 `order-service → inventory-service` 的真实 HTTP 调用，事件中的耗时、状态码、requestId 与数据库记录均可复核。
- 日志与指标来自实验服务真实业务请求，不使用随机数或静态 JSON；观测数据保存在有界内存与滚动 JSONL 文件中。
- 实验预设故障类型与实际观测结果分开存储，预设类型不作为根因结论，也不写入 `root_cause`。
- 库存不足返回 409，归类为业务冲突，不记为系统故障。
- Agent 根因报告中的证据 ID 必须存在于实际工具结果；不存在的 ID 会被拒绝，证据不足时输出 `INSUFFICIENT_EVIDENCE`。
- Agent 输入采用白名单 DTO，不包含 `experiment_fault_type`、`experiment_parameters` 等实验预设；预设类型不会被当作答案。
- Day 4 已使用真实 `deepseek-flash` 完成 HTTP_500 与 DELAY 端到端诊断；单元测试中的脚本化模型、本地 HTTP Mock 与真实调用严格区分，报告中不把 Mock 结果写成真实模型结果。
- AI 诊断完成不等于故障已解决：事件进入 `RESOLVED/CLOSED` 必须经过人工操作，诊断成功只恢复为 `OPEN`（待处理）。
- 库存服务使用内存样例商品数据，仅用于形成真实跨服务调用链路，不作为生产数据。

## 敏感信息与演示截图

- 根目录 `.env` 已被 `.gitignore` 忽略；仓库只保留 `.env.example` 占位符，不包含真实 Key 或密码。
- 数据库密码与 `DEEPSEEK_API_KEY` 通过环境变量注入，不写入源码、数据库、日志或诊断报告；模型错误响应会在写入前截断，且不包含 Authorization 头。
- 答辩截图只应包含浏览器中的业务页面：不要截取 `.env`、终端环境变量、Docker 环境配置或包含 Authorization 的请求头。
- 演示与测试结束后关闭故障注入，并把 `EXPERIMENT_FAULT_INJECTION_ENABLED` 恢复为 `false`。

## WSL 环境注意事项

1. 若 `mvn -version` 报 `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`，说明 WSL 中的 `java` 指向了 Windows `java.exe`。请安装 Linux 版 JDK 17，并设置：
   ```bash
   export JAVA_HOME=/path/to/linux-jdk-17
   export PATH="$JAVA_HOME/bin:$PATH"
   ```
2. Docker Desktop 的 WSL 集成未启用时，使用 `docker.exe`（见第 2 步）。
3. 当 PostgreSQL 运行在 Windows 宿主机 Docker 中时，WSL 内访问 `localhost:5432` 可能不通。可执行 `ip route | grep default` 获取宿主机地址，将其填入 `.env` 的 `DB_HOST`。
4. 如果环境设置了 `HTTP_PROXY` / `HTTPS_PROXY`，`curl http://localhost:...` 可能被代理吞掉而返回空内容。使用：
   ```bash
   curl --noproxy '*' http://localhost:8080/api/health
   # 或
   export NO_PROXY=localhost,127.0.0.1
   ```

## 测试

```bash
cd backend && mvn test
cd services/inventory-service && mvn test
cd services/order-service && mvn test
cd frontend && npm run build
```

Day 2 实际结果：后端 17、库存服务 17、订单服务 7，共 41 个测试通过；前端构建成功。详见 [Day 2 验证报告](docs/plans/day2-report.md)。

Day 3 实际结果：后端 55 个测试（原 17 + 新增 38）通过，库存服务 17、订单服务 7 通过，合计 79 个测试；前端 `npm run build` 成功。真实 DeepSeek 调用因当前环境无 `DEEPSEEK_API_KEY` 未执行。详见 [Day 3 验证报告](docs/plans/day3-report.md)。

Day 4 真实模型结果：`deepseek-flash` 完成 12 次真实调用；HTTP_500 诊断 9.666 s，DELAY 3500 ms 重试诊断 11.602 s，均输出 `ROOT_CAUSE_IDENTIFIED`，证据 ID 全部来自真实工具结果。详见 [Day 4 真实 DeepSeek 验证报告](docs/plans/day4-deepseek-validation.md)。

Day 5 结果：后端 63、库存服务 17、订单服务 7，共 87 个测试通过；前端 `npm run build` 成功；修复诊断终态后事件未恢复 `OPEN`、重试未进入 `DIAGNOSING` 的状态不一致，并补齐逐轮 usage 与 `AGENT_RUN_SUMMARY` 统计。详见 [Day 5 收尾报告](docs/plans/day5-stabilization-report.md)。

## 文档

- [系统架构设计](docs/architecture/system-design.md)
- [Day 1 验证报告](docs/plans/day1-report.md)
- [Day 2 验证报告](docs/plans/day2-report.md)
- [Day 3 验证报告](docs/plans/day3-report.md)
- [Day 4 联调与端到端验收报告](docs/plans/day4-integration-report.md)
- [Day 4 真实 DeepSeek 接入验证](docs/plans/day4-deepseek-validation.md)
- [Day 5 工程收尾与演示准备报告](docs/plans/day5-stabilization-report.md)
- [5 分钟答辩演示脚本](docs/plans/day5-demo-script.md)

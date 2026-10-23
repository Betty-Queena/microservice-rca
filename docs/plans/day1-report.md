# Day 1 验证报告

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-21 |
| 范围 | 架构设计、工程初始化、基础联通 |
| 结论 | Day 1 必做项全部完成；9 个后端测试通过；三条基础链路真实验证通过 |
| 未涉及 | LLM 调用、故障注入、日志/指标采集、诊断 Agent 实现 |

## 1. 环境检查

| 工具 | 版本 | 状态 | 说明 |
| --- | --- | --- | --- |
| Java | Temurin 17.0.18（Windows）/ Temurin 17.0.20.1（Linux） | 已修复 | WSL 默认 `java` 是包装 Windows `java.exe` 的脚本，无法配合 WSL 内 Maven 使用 |
| Maven | 3.9.16 | 可用 | 位于 `/mnt/c/program-develop/maven/apache-maven-3.9.16`，配合 Linux JDK 后可正常构建 |
| Node.js | 22.22.2 | 可用 | 满足 Vite 8 要求（^20.19.0 或 >=22.12.0） |
| npm | 10.9.7 | 可用（有警告） | 用户级 `/mnt/c/Users/y/.npmrc` 含 `prefix`，npm 将其视为 project config 并打印警告，不影响安装 |
| Docker CLI | Docker Desktop 29.4.2 | 可用 | WSL 未启用 Docker 集成，使用 Windows 侧 `docker.exe` |
| Docker Compose | v5.1.3 | 可用 | 通过 `docker.exe compose` 调用 |
| Git | 2.25.1（WSL） | 可用 | 版本较旧，但不影响当前流程 |
| PostgreSQL | 16.15（容器） | 可用 | `postgres:16-alpine`，健康检查通过 |
| Spring Boot | 3.5.16 | 已采用 | Java 17 兼容 |
| Flyway | 11.7.2 | 已采用 | 由 Spring Boot 依赖管理 |
| Vue / Vite / Element Plus | 3.5.44 / 8.3.4 / 2.14.7 | 已采用 | 前端构建通过 |

### 环境问题与处理

1. **WSL Java 不可用**：`/home/yi/bin/java` 内容为
   `exec "/mnt/c/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot/bin/java.exe" "$@"`。
   Windows JVM 无法解析 `/mnt/c/...` 形式的 classpath，导致 Maven 报
   `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`。
   处理：在 `~/.jdks/jdk-17.0.20.1+1` 放置 Linux 版 Temurin JDK 17（仅用户目录，未修改系统），构建时设置 `JAVA_HOME`。
2. **Docker WSL 集成未启用**：WSL 内无 `docker` 命令，改用
   `/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe`。
3. **HTTP 代理影响本地请求**：环境设置了 `HTTP_PROXY=http://172.31.144.1:7897`，未设置 `NO_PROXY`。
   普通 `curl http://localhost:8080/...` 会被代理拦截并返回空内容。验证时使用 `--noproxy '*'`。
4. **npm 配置警告**：`npm` 将 `/mnt/c/Users/y/.npmrc` 识别为项目配置并提示
   `config prefix cannot be changed from project config`。安装命令退出码为 0，依赖安装成功；未修改该用户文件。

## 2. 实际执行命令与结果

### 2.1 启动 PostgreSQL

```bash
docker.exe compose --env-file .env -f infra/docker-compose.yml up -d
```

实际结果：镜像 `postgres:16-alpine` 拉取成功，网络、卷、容器创建并启动。

```text
NAME           IMAGE                COMMAND                   SERVICE    CREATED         STATUS                    PORTS
rca-postgres   postgres:16-alpine   "docker-entrypoint.s…"   postgres   2 seconds ago   Up 2 seconds (health: starting)   0.0.0.0:5432->5432/tcp
```

WSL 内端口探测：`127.0.0.1:5432 OPEN`、`172.31.144.1:5432 OPEN`。

### 2.2 后端构建与测试

```bash
export JAVA_HOME=$HOME/.jdks/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"

cd backend && mvn -B test
cd services/inventory-service && mvn -B test
cd services/order-service && mvn -B test
```

实际结果：

| 模块 | 测试数 | 失败 | 错误 | 结果 |
| --- | --- | --- | --- | --- |
| `backend` | 3 | 0 | 0 | BUILD SUCCESS |
| `services/inventory-service` | 3 | 0 | 0 | BUILD SUCCESS |
| `services/order-service` | 3 | 0 | 0 | BUILD SUCCESS |

覆盖内容：

- 管理后端：数据库可达 / 不可达时的健康状态、统一响应结构。
- 库存服务：列表、预占成功、库存不足 409。
- 订单服务：下单成功、下游不可用 502、参数校验 400。

### 2.3 启动服务并验证健康接口

```bash
# 三个 Spring Boot 工程分别启动
cd backend && mvn spring-boot:run
cd services/inventory-service && mvn spring-boot:run
cd services/order-service && mvn spring-boot:run

curl --noproxy '*' http://localhost:8080/api/health
curl --noproxy '*' http://localhost:8082/api/health
curl --noproxy '*' http://localhost:8081/api/health
```

实际结果：

管理后端（真实数据库探测）：

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "service": "rca-backend",
    "version": "0.1.0",
    "status": "UP",
    "timestamp": "2026-10-20T16:49:13.473604405Z",
    "uptimeSeconds": 299,
    "database": {
      "status": "UP",
      "latencyMs": 10,
      "message": "连接正常"
    }
  },
  "timestamp": "2026-10-20T16:49:13.493411466Z"
}
```

库存服务：

```json
{"code":200,"message":"OK","data":{"service":"inventory-service","version":"0.1.0","status":"UP","timestamp":"2026-10-20T16:49:16.769236284Z"},"timestamp":"2026-10-20T16:49:16.769256240Z"}
```

订单服务（包含对库存服务的真实探测）：

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "service": "order-service",
    "version": "0.1.0",
    "status": "UP",
    "timestamp": "2026-10-20T16:49:17.803065970Z",
    "dependency": {
      "service": "inventory-service",
      "status": "UP",
      "url": "http://localhost:8082"
    }
  },
  "timestamp": "2026-10-20T16:49:17.803089361Z"
}
```

启动日志确认：

- Flyway：`Successfully applied 1 migration to schema "public", now at version v1`
- Hibernate：`Initialized JPA EntityManagerFactory for persistence unit 'default'`（`ddl-auto=validate` 通过）
- Tomcat：`Tomcat started on port 8080` / `8081` / `8082`

### 2.4 跨服务调用

```bash
curl --noproxy '*' -X POST http://localhost:8081/api/orders/checkout \
  -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1001","quantity":2}'
```

实际结果（真实扣减库存，`downstreamLatencyMs` 为实测值）：

```json
{
  "code": 200,
  "message": "OK",
  "data": {
    "orderId": "ORD-91099F789C8F",
    "status": "CONFIRMED",
    "sku": "SKU-1001",
    "quantity": 2,
    "remainingStock": 48,
    "downstreamLatencyMs": 530,
    "createdAt": "2026-10-20T16:49:19.376940501Z"
  },
  "timestamp": "2026-10-20T16:49:19.376946966Z"
}
```

库存不足路径：

```bash
curl --noproxy '*' -X POST http://localhost:8081/api/orders/checkout \
  -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1003","quantity":1}'
```

实际结果：

```json
{"code":409,"message":"库存不足: SKU-1003 可用 0，请求 1","data":null,"timestamp":"2026-10-20T16:49:19.429752651Z"}
```

HTTP 状态码：`409`。说明订单服务正确识别并透传了下游业务冲突。

### 2.5 数据库迁移与表结构

```bash
docker.exe exec rca-postgres psql -U rca -d rca -c "\dt"
docker.exe exec rca-postgres psql -U rca -d rca -c \
  "SELECT installed_rank, version, description, success FROM flyway_schema_history;"
```

实际结果：

```text
 public | diagnosis_task        | table | rca
 public | fault_event           | table | rca
 public | flyway_schema_history | table | rca
 public | tool_invocation       | table | rca

 installed_rank | version | description | success
----------------+---------+-------------+---------
              1 | 1       | init schema | t
```

字段核对：`fault_event.details`、`tool_invocation.input_payload/output_payload` 均为 `jsonb`；
主键为 `uuid`；时间字段为 `timestamp with time zone`。

### 2.6 前端安装、构建与代理

```bash
cd frontend
npm install --no-audit --no-fund
npm run build
npm run dev -- --host 127.0.0.1
```

实际结果：

- `npm install`：`added 85 packages in 2m`
- `npm run build`：`✓ 1660 modules transformed`，`✓ built in 4.58s`
- 构建警告：主 chunk 为 1,078.83 kB（gzip 350.38 kB），超过 500 kB 提示阈值；当前不影响运行。
- 开发服务器：`VITE v8.3.4 ready`，监听 `http://127.0.0.1:5173/`
- 页面入口：`GET /` 返回 HTTP 200，HTML 标题为“微服务故障分析与处置系统”
- Vite 代理真实调用后端：

```bash
curl --noproxy '*' http://localhost:5173/api/health
```

返回与 `http://localhost:8080/api/health` 一致的 `code=200`、`status=UP`、`database.status=UP` 数据。

## 3. 验收链路对照

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| 前端 → 管理后端 | 通过 | 5173 首页 200，Vite 代理 `/api/health` 返回真实健康数据 |
| 管理后端 → PostgreSQL | 通过 | 健康接口 `database.status=UP`，Flyway V1 成功，JPA validate 通过 |
| 订单服务 → 库存服务 | 通过 | 下单返回 `CONFIRMED`，库存从 50 降至 48，实测下游耗时 530 ms |
| 库存不足错误路径 | 通过 | 返回 HTTP 409，消息来自库存服务 |
| 统一 JSON 响应 | 通过 | 三个服务均返回 `code/message/data/timestamp` |
| 无硬编码密钥 | 通过 | 数据库口令仅在本地 `.env`（已忽略）与 `.env.example` 示例中 |
| 无虚构监控/AI 数据 | 通过 | 概览页仅展示真实健康接口数据；其余模块标记“待开发” |
| 可复现启动说明 | 通过 | README、`.env.example`、Flyway 迁移、本报告 |

## 4. 未完成或存在的问题

1. **WSL Java 环境需手动设置**：当前依赖 `~/.jdks/jdk-17.0.20.1+1` 与 `JAVA_HOME`。建议安装 Linux 版 JDK 后写入 shell 配置，避免每次手工导出。
2. **Docker WSL 集成未启用**：当前必须使用 `docker.exe`，绝对路径参数需使用 Windows 路径。建议在 Docker Desktop 中启用 WSL Integration。
3. **本地代理影响 curl**：必须使用 `--noproxy '*'` 或设置 `NO_PROXY`，否则本地接口返回空内容。答辩演示前务必确认。
4. **前端主包体积偏大**：Element Plus 全量引入导致约 1.08 MB。后续可按需引入组件或代码分割，不影响 Day 1 功能。
5. **缺少浏览器端自动化验证**：环境无 Chromium/Firefox，未做截图或 E2E；已通过 Vite 构建、页面 200、代理接口 200 验证。页面视觉需开发者打开浏览器确认。
6. **集成测试为手工验证**：单元/切片测试不连接真实 PostgreSQL，也未启动真实库存服务。后续可引入 Testcontainers 或 Compose 测试 profile。
7. **Docker Desktop 需保持运行**：容器设置了 `restart: unless-stopped`，但 Docker Desktop 本身需手动启动。
8. **未实现能力**：故障事件接入、日志/指标采集、Agent 编排、工具实现、证据链、人工确认处置均未实现，界面明确标记“待开发”。

## 5. Day 2 最小开发建议（本轮不实施）

1. **故障事件闭环**：基于已建表实现 `POST /api/fault-events` 与列表/详情 API，前端“故障事件”页接入真实数据；仅创建事件，不生成诊断结论。
2. **诊断任务状态机**：支持从事件创建 `PENDING` 任务，并实现 `PENDING → RUNNING → SUCCEEDED/FAILED` 的状态流转与管理页展示，暂不接入 LLM。
3. **最小真实观测**：为两个实验服务增加结构化日志落盘与按时间窗查询接口；先实现 `query_logs` 工具和 `tool_invocation` 审计写入，避免引入 ELK。
4. **故障注入**：在订单/库存服务增加显式的实验接口（延迟、异常），用于后续诊断实验；接口必须标注“实验用途”，不得伪装为真实生产故障。
5. **集成测试**：为管理后端引入 Testcontainers PostgreSQL，覆盖 Flyway 迁移、事件持久化与健康检查。
6. **演示脚本**：准备 5 分钟演示步骤：启动环境 → 健康检查 → 下单成功/库存不足 → 事件创建 → 任务状态展示，并明确当前未接入 AI。

## 6. 重新验证的最短步骤

```bash
# 1. 启动数据库
docker.exe compose --env-file .env -f infra/docker-compose.yml up -d

# 2. 启动三个后端（分别在不同终端）
export JAVA_HOME=$HOME/.jdks/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd backend && mvn spring-boot:run
cd services/inventory-service && mvn spring-boot:run
cd services/order-service && mvn spring-boot:run

# 3. 启动前端
cd frontend && npm run dev

# 4. 验证
curl --noproxy '*' http://localhost:8080/api/health
curl --noproxy '*' -X POST http://localhost:8081/api/orders/checkout \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-1001","quantity":2}'
curl --noproxy '*' http://localhost:5173/api/health
```

> 若使用 Docker Desktop 的 WSL 集成，可直接使用 `docker compose`；否则使用上述 `docker.exe`。

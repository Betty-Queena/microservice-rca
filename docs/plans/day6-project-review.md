# Day 6 项目全量验收审查报告

| 项目 | 内容 |
| --- | --- |
| 审查日期 | 2026-10-23 |
| 审查对象 | 《基于 LLM 的微服务故障分析与处置系统的设计与实现》全部代码、配置、迁移脚本、测试与文档 |
| 审查方式 | 静态代码审阅、既有测试报告与源码计数核对、只读运行时检查；**本轮未修改业务代码、配置或数据库，未停止服务，未执行故障注入，未调用 DeepSeek，未读取/输出 `.env` 密钥明文** |
| 审查结论 | **有条件通过**：核心业务链路完整，具备答辩演示基础；但存在 1 个 P0 工程交付阻断、14 个 P1 问题，其中 4 个直接影响演示可靠性或实验数据正确性，建议提交前修复 |

---

## 0. 结论摘要

### 0.1 总体判断

系统已经形成一条可运行、可解释、可审计的完整链路：

```text
Vue 管理界面
  -> rca-backend（8080，事件/任务/证据/Agent 编排）
  -> order-service（8081）
  -> inventory-service（8082，故障注入 + 观测）
  -> PostgreSQL 16（Flyway V1/V2/V3）
  -> DeepSeek OpenAI 兼容接口（JSON 模式、多轮工具调用）
```

从“本科毕业设计”的完成度看：

- **核心功能完成度高**：真实跨服务调用、真实故障注入、真实日志/指标、真实模型多轮工具调用、证据约束根因报告、任务历史与 token 核算均已实现，不是 Mock 演示。
- **工程化完成度中等**：状态机、异常处理、并发控制、启动恢复、集成测试、权限控制仍有明显缺口。
- **论文评测证据偏薄**：目前只有 HTTP_500 与 DELAY 的少量真实调用记录，缺少成体系的场景矩阵、准确率/耗时/Token 对比和失败案例分析。
- **“处置”闭环偏弱**：系统能给出根因与建议动作，但实际处置仍靠人工点击“已解决/已关闭”，没有自动或半自动执行、回滚、验证闭环。

### 0.2 分级统计

| 级别 | 数量 | 含义 |
| --- | ---: | --- |
| P0 | 1 | 提交/交付阻断，必须立即处理 |
| P1 | 14 | 影响核心流程正确性、演示可靠性、论文可信度或安全边界，建议答辩前修复 |
| P2 | 60 | 健壮性、可维护性、文档与体验问题，可在提交后迭代 |

> 说明：本轮没有发现“正常演示路径必定崩溃”的功能性 P0。P0-1 属于工程交付阻断，不是业务代码 Bug。

### 0.3 提交前必须处理（建议顺序）

1. **P0-1**：初始化 Git 仓库并完成首次提交，配置 `user.name=zhongjianyi`、`user.email=zhongjianyi@btxc.com`。
2. **P1-11**：修正系统概览与侧边栏中“Agent 待开发 / Day 2 / v0.2.0”等与实现严重矛盾的文字。
3. **P1-08、P1-09、P1-10**：修复前端错误提示、证据采集假成功、历史任务选择导致轮询停止。
4. **P1-01、P1-02**：修复 DELAY 超时后仍扣库存、非 409 的 4xx 被误判为系统故障。
5. **P1-03、P1-04、P1-05、P1-06、P1-07**：补齐任务启动/失败/超时/并发的一致性保护。
6. **P1-12**：至少补一条基于 Testcontainers 的核心链路集成测试和一条前端冒烟测试。
7. **P1-13、P1-14**：答辩环境网络暴露与双 Vite 实例问题。

---

## 1. 审查范围与方法

### 1.1 已审阅范围

- `backend/`：`diagnosis`、`agent`、`experiment`、`observability`、`tools`、`health`、`common`、`config`、Flyway V1–V3、全部测试。
- `services/order-service/`：订单接口、库存客户端、异常映射、观测过滤器、测试与配置。
- `services/inventory-service/`：库存接口、故障注入过滤器/服务/访问守卫、观测存储、测试与配置。
- `frontend/`：全部 `src` 文件、路由、API 封装、四个业务页面、布局与健康状态 composable。
- 根目录：`README.md`、`.env.example`、`.gitignore`、`infra/docker-compose.yml`、`.idea/workspace.xml`、`docs/` 下的 Day 1–Day 5 报告与架构文档。

### 1.2 实际执行的只读检查

- `git status --porcelain`、`git status --porcelain -uall`、`git log`、`git config user.name/user.email`。
- 源码/测试/迁移脚本的静态检索与逐文件阅读。
- 既有 Surefire XML 与 Day 5 报告中的测试计数核对。
- 当前 Windows/WSL 进程与端口监听检查（8080/8081/8082/5173/5432），以及 `curl` 健康检查路径对比。
- 未执行：`mvn test`、`mvn package`、`npm run build`、真实故障注入、DeepSeek 调用、数据库写入、服务重启/停止。

### 1.3 证据标注约定

- **“已实际执行”**：本轮直接运行命令或此前会话中已完成的只读检查。
- **“静态复现路径”**：代码路径明确，但本轮未执行写操作/注入操作；复现步骤供开发者手工验证。
- **“静态推断”**：依赖框架行为推断，未做运行时验证；报告中会明确标注。

---

## 2. 已验证正常的部分

以下内容经代码与既有记录核对，未发现问题，答辩时可以放心展示：

1. **真实跨服务链路**：`order-service` 通过真实 HTTP 调用 `inventory-service` 的 `/api/inventory/{sku}/reserve`，超时、5xx、409、协议错误分别映射，非前端模拟。
2. **故障注入范围正确**：`FaultInjectionFilter.shouldNotFilter` 只匹配 `/api/inventory` 及其子路径，健康检查、观测接口、实验控制接口不被直接注入；HTTP_500 在 `filterChain` 之前短路，不会产生库存副作用。
3. **观测数据真实**：请求日志来自真实业务请求，`X-Request-ID` 跨服务透传；指标按分钟聚合，存储有界；日志滚动策略有上限。
4. **Agent 工具白名单与参数边界**：只允许 `query_logs` / `query_metrics`，服务名、requestId、lookback、limit 均有校验；未注册工具和未知参数会被拒绝。
5. **证据约束**：最终报告引用的 evidence ID 必须来自成功工具结果，否则拒绝；无证据时只能输出 `INSUFFICIENT_EVIDENCE`。
6. **实验预设与观测结果分离**：`experiment_fault_type` / `experiment_parameters` 不进入 Agent 输入白名单，预设类型不会直接变成根因。
7. **状态语义基本正确**：AI 诊断完成只把事件从 `DIAGNOSING` 恢复为 `OPEN`，不会自动标记 `RESOLVED`；`RESOLVED/CLOSED` 只能由人工触发。
8. **前端接口契约**：页面调用的路径、请求字段、任务/报告/Token 字段与后端 DTO 基本一致；未发现大面积字段错位。
9. **敏感信息**：源码与 `.env.example` 未发现真实 Key；`.env` 已被 `.gitignore` 忽略；模型错误响应在写入前截断，不包含 Authorization 头。
10. **既有测试结果**：Day 5 报告记录后端 63、库存 17、订单 7，共 87 个测试通过；当前源码 `@Test` 计数与之一致。Surefire 目录中存在旧包名 `com.btxc.*` 的 stale XML，导致原始统计看起来是 66/20/10；当前 `com.microservice.rca.*` 报告为 0 失败/0 错误。**提交前应执行一次 `mvn clean test`，避免 stale 报告误导。**

### 2.1 需要澄清的非问题

- **8081/8082 的 `/health` 返回 500**：不是服务故障。三个服务都只映射 `/api/health`；`/health` 是未映射路径，被 `@ExceptionHandler(Exception.class)` 转成了 500。正确地址是 `/api/health`，本轮实测返回 200。
- **WSL 内 `localhost:5173/api/health` 返回 502**：是当前 Windows/WSL 双 Vite 实例与 NAT 网络导致，不是后端 8080 故障；Windows 侧 Vite 与 `172.31.144.1:8080` 均正常。
- **HTTP_500 注入不会扣库存**：过滤器在业务链之前直接返回 500，已确认无副作用；DELAY 的副作用是另一个问题（P1-01）。
- **409 库存不足不记为系统故障**：分类器明确把 409 归为 `BUSINESS_CONFLICT`，符合设计。

---

## 3. P0 问题

### P0-1 代码库从未提交，Git 身份未配置，全部 179 个文件处于未跟踪状态

**位置**

- 仓库根目录；`git rev-list --count HEAD` 为 `0`，`git log` 报 `your current branch 'master' does not have any commits yet`。
- `git config user.name` 与 `git config user.email` 均为空。
- `git status --porcelain` 显示 8 个顶层未跟踪项；`git status --porcelain -uall` 显示 179 个未跟踪文件；`git ls-files` 为 0。

**原因**

项目从初始化至今没有执行过首次提交，也没有配置提交身份。`.gitignore` 已正确覆盖 `.env`、`target/`、`node_modules/`、`dist/`、`logs/`，但没有任何被跟踪的基线。

**影响**

- 代码没有任何版本历史，无法回滚、无法对比 Day 1–Day 6 的修改，也无法证明各阶段工作真实发生。
- 一次误删除、磁盘故障或错误覆盖即可丢失全部毕设代码。
- 若学校要求提交 Git 仓库或代码托管链接，当前状态无法交付。
- 后续首次提交时，若未先核对 `git status`，存在把运行产物、日志或本地配置误纳入的风险。

**复现/证据**

```bash
cd /mnt/c/Users/y/Documents/AI/program/microservice-rca
git rev-list --count HEAD        # 0
git ls-files | wc -l             # 0
git status --porcelain -uall | wc -l  # 179
git config user.name             # 空
git config user.email            # 空
```

**建议修复**

1. 按全局规范设置身份：
   ```bash
   git config user.name "zhongjianyi"
   git config user.email "zhongjianyi@btxc.com"
   ```
2. 首次提交前执行 `git status --porcelain -uall`，确认没有 `.env`、`target/`、`node_modules/`、`dist/`、`logs/`、截图或数据库文件。
3. 建议分两次提交，保留可读历史：
   - 第一次：`chore: 初始化项目仓库与忽略规则`（README、.gitignore、.env.example、infra、docs 基础结构）；
   - 第二次：`feat: 完成微服务故障分析与 LLM Agent 诊断主链路`（backend、services、frontend、docs 报告）。
4. 提交后打一个 `day6-review` 标签或创建 `release/day6` 分支，作为答辩基线。

**提交前是否必须修复：是（P0，最高优先级）。**

---

## 4. P1 问题

### P1-01 DELAY 超过订单读超时后，调用方收到 502，但库存仍被扣减；重试会重复扣减

**位置**

- `services/inventory-service/src/main/java/com/microservice/rca/inventory/fault/FaultInjectionFilter.java:54-63`：DELAY 分支先 `Thread.sleep(delayMs)`，睡醒后才 `filterChain.doFilter`。
- `services/order-service/src/main/resources/application.yml:15`：`read-timeout-ms` 默认 5000。
- `services/order-service/src/main/java/com/microservice/rca/order/inventory/InventoryClient.java:34-37,70-74`：读超时映射为 `InventoryUnavailableException(TIMEOUT)`，最终 502。
- `services/inventory-service/src/main/java/com/microservice/rca/inventory/inventory/InventoryStore.java:44-50`：`reserve` 在同步块内扣减库存。
- `services/inventory-service/src/main/resources/application.yml:28`：`max-delay-ms` 默认 30000。

**原因**

延迟施加在业务执行之前，且允许的最大延迟（30 s）远大于订单服务读超时（5 s）。客户端 5 s 超时后断开，但 Tomcat 工作线程不会因此中断；线程睡满后仍会执行 `reserve` 并扣减库存。系统没有 reservation TTL、取消、补偿或按 `orderId` 去重。

**影响**

- 调用方看到 502 / `X-Downstream-Error-Type: TIMEOUT`，但库存实际已减少，形成“失败但已扣库存”的矛盾证据。
- 客户端或人工重试会再次扣减，造成库存丢失。
- RCA 输入出现“订单失败、库存却变化”的不一致观测，可能误导根因分析。
- 这是当前故障注入实验平台自身的业务正确性缺陷，不只是展示问题。

**复现/证据（静态复现路径，本轮未执行注入）**

1. 开启 `DELAY`，`delayMs=6000`（合法范围 1–30000），持续时间 ≥60 s。
2. `POST /api/orders/checkout {"sku":"SKU-1001","quantity":1}`。
3. 约 5 s 后订单服务返回 502，错误类型 `TIMEOUT`。
4. 约 6 s 后查询 `GET /api/inventory/SKU-1001`，`available` 从 50 变为 49。
5. 再重试一次，变为 48。

代码路径已确认：`Thread.sleep` 在 `filterChain.doFilter` 之前，且库存服务没有任何释放/补偿接口。

**建议修复**

- 演示层面：将 `EXPERIMENT_FAULT_INJECTION_MAX_DELAY_MS` 限制为小于订单读超时（例如 4500 ms），或把演示 DELAY 固定为 3500 ms，避免超时。
- 工程层面（推荐至少做一项）：
  1. 以 `orderId` 做幂等键：`InventoryStore.reserve(sku, quantity, orderId)` 记录已处理订单，重复请求直接返回首次结果；
  2. 增加 reservation TTL / 释放接口，订单最终失败时由订单服务补偿；
  3. 若要把“超时但服务端成功”作为真实分布式现象保留，必须在文档和事件观测中显式标注“客户端超时、服务端可能已执行”，避免把它当作确定性失败。
- 增加 `FaultInjectionFilter` 集成测试，覆盖 DELAY 超过读超时、重试、disable 中途取消。

**提交前是否必须修复：是（至少限制 DELAY 上限并补充说明；否则答辩时若演示 6 s 以上延迟会出现自相矛盾的数据）。**

---

### P1-02 非 409 的下游 4xx 被转换成 502，并被分类为系统故障，凭空生成故障事件

**位置**

- `services/order-service/src/main/java/com/microservice/rca/order/inventory/InventoryClient.java:61-69`：除 409 外的所有 4xx 都抛 `InventoryUnavailableException(..., "HTTP_4XX")`。
- `services/order-service/src/main/java/com/microservice/rca/order/common/GlobalExceptionHandler.java:34-40`：该异常统一返回 502，并带 `X-Downstream-Error-Type: HTTP_4XX`。
- `backend/src/main/java/com/microservice/rca/experiment/ObservationClassifier.java:38-52`：502/503/504 分支只识别 `TIMEOUT/HTTP_5XX/CONNECTION_ERROR/PROTOCOL_ERROR`，`HTTP_4XX` 落入 `default`，返回 `DOWNSTREAM_ERROR`、`faultObserved=true`、`severity=ERROR`。
- `backend/src/main/java/com/microservice/rca/experiment/ExperimentService.java:146-158,286-292`：只要 `faultObserved()` 就创建事件，并把 `DOWNSTREAM_ERROR` 归因到 `inventory-service`。
- `services/inventory-service/src/main/java/com/microservice/rca/inventory/inventory/InventoryStore.java:39-43`：未知 SKU 返回 404。

**原因**

订单服务把“客户端请求错误”和“下游不可用”混为一类；管理后端的分类器又缺少 `HTTP_4XX` 分支，导致 404 被当作下游故障。

**影响**

- 输入一个不存在的 SKU（例如 `UNKNOWN`）即可生成一条“inventory-service 故障”事件，严重污染 RCA 数据集。
- 在 DELAY 实验期间，同一路径还会把预设 `DELAY` 叠加到这条假事件上，使实验统计失真。
- 与分类器自身“4xx 请求问题不记为系统故障”的规则相矛盾（`ObservationClassifier.java:58-61`）。
- 现有 `ObservationClassifierTest` 没有 `HTTP_4XX` 用例。

**复现/证据（静态复现路径，本轮未执行写操作）**

```bash
# 1. 不开启任何故障
curl -X POST http://localhost:8081/api/orders/checkout \
  -H 'Content-Type: application/json' \
  -d '{"sku":"UNKNOWN","quantity":1}'
# 预期：库存服务 404 -> 订单服务 502，header X-Downstream-Error-Type: HTTP_4XX

# 2. 通过管理后端执行对照请求
curl -X POST http://localhost:8080/api/experiments/checkout \
  -H 'Content-Type: application/json' \
  -d '{"sku":"UNKNOWN","quantity":1}'
# 预期：创建 fault_event，outcome=DOWNSTREAM_ERROR，serviceName=inventory-service
```

**建议修复**

1. 订单服务保留 4xx 语义：404 返回 404（或 400），不要统一转 502；只有 5xx/超时/连接失败才转 502。
2. 分类器增加 `HTTP_4XX` 分支，返回 `REQUEST_REJECTED`、`faultObserved=false`、`severity=INFO`。
3. 补充 `ObservationClassifierTest` 的 404/400 用例，以及订单服务 404 映射测试。
4. 实验页面只允许选择真实 SKU 只是前端缓解，不能替代后端修复。

**提交前是否必须修复：是（改动小、收益高，直接关系 RCA 数据可信度）。**

---

### P1-03 手工证据采集任务会阻塞 AI 诊断；收集中断可永久卡死事件

**位置**

- `backend/src/main/java/com/microservice/rca/diagnosis/service/EvidenceService.java:50-131`：`collect()` 先创建 `RUNNING` 的 `MANUAL_EVIDENCE` 任务，随后执行两次工具调用，最后才更新任务状态和写证据；方法没有 `@Transactional`，也没有 `try/finally`。
- `backend/src/main/java/com/microservice/rca/agent/AgentDiagnosisService.java:29-30,52-55`：AI 启动只按 `PENDING/RUNNING` 判断“已有任务”，不区分 `triggerSource`。
- `backend/src/main/java/com/microservice/rca/diagnosis/repository/DiagnosisTaskRepository.java:14`：`existsByFaultEventIdAndStatusIn` 不区分任务来源。
- `backend/src/main/java/com/microservice/rca/observability/ObservabilityClient.java:33-47,110-115`：每次工具调用串行查询两个服务，每次连接 2 s、读 5 s；两次工具调用最坏可达约 28 s。

**原因**

- 手工证据采集和 AI 诊断共用同一张任务表与同一个“活跃任务”判断，导致采集期间启动 AI 会收到 409。
- 如果进程在任务保存后、最终状态更新前退出，或 `saveEvidence` 失败，任务会永久停在 `RUNNING`；系统没有启动对账、没有 `CANCELLED`、没有清理接口。
- 任务先标记 `SUCCEEDED` 并提交，再写证据；第二步失败会留下“成功任务、无证据”。

**影响**

- 用户在“证据采集”慢或失败时点击“启动 AI 诊断”，会看到 409“该事件已有诊断任务正在执行”，但界面上并没有可见的 Agent 任务。
- 后端崩溃/重启后，该事件的所有 AI 诊断都会被永久拒绝，只能改数据库。
- 证据采集失败时仍可能显示“成功”，误导后续操作（前端问题见 P1-09）。

**复现/证据（静态复现路径）**

- 瞬时复现：让 8081/8082 接受连接但响应很慢，点击“采集真实证据”，在 28 s 窗口内点击“启动 AI 诊断” → 409。
- 永久复现：在 `taskRepository.save` 之后、最终 `save` 之前 kill 后端，重启后再次启动 AI → 仍为 409。
- 证据：`AgentDiagnosisService.java:52` 的查询条件只有事件 ID 与状态，没有 `triggerSource`。

**建议修复**

1. AI 启动守卫只统计 `triggerSource in ('AGENT','AGENT_RETRY')` 的活跃任务；手工采集不应阻塞 AI。
2. `EvidenceService.collect` 使用 `try/catch/finally`：无论工具调用是否异常，都把任务置为 `SUCCEEDED/FAILED` 并写 `completedAt`；证据写入放在任务终态之后，失败时记录明确错误。
3. 增加启动对账：应用启动时把超过阈值仍为 `PENDING/RUNNING` 的任务标记为 `FAILED`（原因：进程重启中断），并把对应 `DIAGNOSING` 且无活跃任务的事件恢复为 `OPEN`。
4. 增加取消接口或使用 `DiagnosisStatus.CANCELLED`（当前枚举与数据库 CHECK 已存在但从未使用）。
5. 补充 EvidenceService 的部分失败/并发测试。

**提交前是否必须修复：是（演示可靠性关键路径）。**

---

### P1-04 Agent 异步任务可能永久停在 PENDING/RUNNING，事件永久停在 DIAGNOSING；无线程池拒绝处理与启动恢复

**位置**

- `backend/src/main/java/com/microservice/rca/agent/AgentAsyncConfig.java:16-27`：核心 1、最大 2、队列 20，使用默认拒绝策略。
- `backend/src/main/java/com/microservice/rca/agent/AgentTaskRunner.java:52-64`：`findById` 与首次 `save(RUNNING)` 在 `try` 之外；此阶段异常不会被 `fail()` 捕获。
- `backend/src/main/java/com/microservice/rca/agent/AgentDiagnosisService.java:56-58,70-72`：先创建任务、标记事件 `DIAGNOSING`，再调用 `taskRunner.run()`；若异步提交被拒绝，调用方收到异常，但任务/事件已落库。
- `backend/src/main/java/com/microservice/rca/diagnosis/service/FaultEventService.java:240-249`：公开状态机没有 `DIAGNOSING -> OPEN`，只有 `RESOLVED/CLOSED`。

**原因**

- 线程池满时 `TaskRejectedException` 没有被捕获，任务停留在 `PENDING`，事件停留在 `DIAGNOSING`。
- `run()` 的前两行不在异常保护范围内，保存 `RUNNING` 失败也会留下 `PENDING`。
- 没有任何启动扫描、超时清理或人工恢复接口。

**影响**

- 事件进入“诊断中”后既不能回到“待处理”，也不能再次启动诊断（被 409 拒绝），只能人工关闭。
- 演示时如果连续点几次或线程池被慢模型占满，会出现“永远转圈”的界面。
- 与 Day 5 报告已识别的“进程崩溃/强制终止”风险一致，但当前仍无对账机制。

**复现/证据（静态复现路径）**

- 启动多个诊断任务占满 2 个线程与 20 个队列，再启动一个 → `TaskRejectedException`，任务为 `PENDING`。
- 在 `run()` 首次 `save` 前 kill 进程 → 任务 `PENDING`；重启后无任何扫描。
- 对 `DIAGNOSING` 事件调用 `PATCH {"status":"OPEN"}` → 409。

**建议修复**

1. `startForEvent` / `retry` 捕获 `TaskRejectedException`（或 `RejectedExecutionException`），立即把任务置为 `FAILED` 并恢复事件，返回 503/409 和明确原因。
2. 把 `run()` 的前两步移入 `try`，或先 `findById` 再在 `try` 内保存。
3. 增加启动对账（同 P1-03）：清理 `PENDING/RUNNING` 超时任务，恢复无活跃任务的 `DIAGNOSING` 事件。
4. 考虑给 `DIAGNOSING -> OPEN` 增加内部/管理员恢复路径；或提供“取消诊断”接口。
5. 为线程池拒绝、启动恢复、取消流程补测试。

**提交前是否必须修复：是（演示稳定性）。**

---

### P1-05 启动/重试存在检查后执行竞态，可创建多个并行 Agent 任务，重复消耗 DeepSeek 额度

**位置**

- `backend/src/main/java/com/microservice/rca/agent/AgentDiagnosisService.java:50-60`：先 `existsBy...` 再 `createTask`，非原子。
- `backend/src/main/java/com/microservice/rca/agent/AgentDiagnosisService.java:62-74`：`retry` 只检查“当前任务”是否活跃，不检查同一事件是否已有其他活跃任务。
- `backend/src/main/java/com/microservice/rca/diagnosis/repository/DiagnosisTaskRepository.java:14`：只有普通查询，没有锁。
- Flyway V1/V3 没有 `(fault_event_id) WHERE status IN ('PENDING','RUNNING')` 之类的部分唯一索引。

**影响**

- 两个并发请求（双击、两个标签页、前端“重试失败任务”和“重新诊断”同时点）可以创建两个 `AGENT`/`AGENT_RETRY` 任务。
- 两个任务同时调用模型和工具，费用翻倍，任务历史冲突，事件状态被多次恢复。
- 前端 `agentStarting`/`agentRetrying` 只防单个按钮的重复点击，不能防两个按钮并发（见 P2）。

**复现/证据（静态复现路径）**

- 对同一事件并发发送两个 `POST /api/fault-events/{id}/diagnosis`；两个请求都可能通过 `existsBy`，随后各自创建任务。
- 对一个已有 `FAILED` 任务的事件，同时点“重新诊断”和“重试失败任务”。

**建议修复**

- 数据库层加部分唯一索引（PostgreSQL）：
  ```sql
  CREATE UNIQUE INDEX uk_diagnosis_task_active
    ON diagnosis_task (fault_event_id)
    WHERE status IN ('PENDING', 'RUNNING');
  ```
  或使用 `SELECT ... FOR UPDATE` / 悲观锁 + 事务。
- `retry` 增加“该事件是否已有活跃任务”的检查。
- 前端在事件维度维护 `hasActiveTask`，两个按钮共用同一禁用条件（见 P1-10）。

**提交前是否必须修复：是（并发正确性 + 费用控制）。**

---

### P1-06 终态持久化非原子：成功结果可能被覆盖为 FAILED，任务可能停在 RUNNING；事件恢复存在丢失更新

**位置**

- `backend/src/main/java/com/microservice/rca/agent/AgentTaskRunner.java:87-127`：`applyResult` 先写任务字段，再 `restoreEventAfterDiagnosis(task)`，最后 `taskRepository.save(task)`；方法没有事务。
- `backend/src/main/java/com/microservice/rca/agent/AgentTaskRunner.java:129-152`：`fail()` 先恢复事件，再保存任务；如果保存任务失败，任务仍为 `RUNNING`。
- `backend/src/main/java/com/microservice/rca/agent/AgentTaskRunner.java:159-166`：恢复事件是“读-判断-写”，没有锁/版本号。
- `backend/src/main/java/com/microservice/rca/diagnosis/domain/FaultEvent.java:18-24`、`DiagnosisTask.java:19-24`：没有 `@Version`；V1/V2/V3 没有 version 列。

**原因**

- 任务状态、报告、事件状态分多次写，且不在同一事务；任何一步失败都会留下部分状态。
- `run()` 的 `catch` 会调用 `fail()`，可能把已经计算出的成功报告清空并改成 FAILED。
- 人工同时把事件标记为 `RESOLVED` 时，恢复逻辑可能把 `DIAGNOSING` 覆盖回 `OPEN`（如果读到旧状态），或者相反。

**影响**

- 模型已经成功，用户却看到“执行失败、无报告”。
- 任务永久 `RUNNING`，前端一直轮询。
- 人工处置结果被后台恢复逻辑覆盖，审计记录与真实操作不一致。

**建议修复**

1. 把 `applyResult` / `fail` 提取到带 `@Transactional` 的独立服务方法（注意避免同类自调用导致事务失效），让任务终态与事件恢复在同一事务内提交。
2. 为 `FaultEvent`、`DiagnosisTask` 增加 `@Version` 和数据库 `version` 列（Flyway V4），恢复事件时使用乐观锁/条件更新：
   ```sql
   UPDATE fault_event SET status='OPEN', version=version+1
    WHERE id=? AND status='DIAGNOSING' AND version=?;
   ```
3. 恢复事件只允许 `DIAGNOSING -> OPEN`，不要覆盖 `RESOLVED/CLOSED`；当前代码有状态判断，但缺少并发保护。
4. 对 `fail()` 的保存失败做兜底日志与最小重试。

**提交前是否必须修复：是（核心状态一致性）。**

---

### P1-07 Agent 整体超时只在外层循环检查，模型/工具调用内部不感知剩余时间，180 秒上限可能被大幅突破

**位置**

- `backend/src/main/java/com/microservice/rca/agent/DefaultAgentOrchestrator.java:67`：`deadline = now + overallTimeout`。
- `:78-82`：只在每轮模型调用前检查一次。
- `:92-98`：模型调用使用固定 60 s 读超时（`application.yml:56`）。
- `:165-170`：工具调用没有传入剩余 deadline；`ObservabilityClient` 每次工具调用串行访问两个服务，每次读 5 s、连接 2 s。
- 最坏情况：6 轮模型 ×60 s + 6 次工具 ×14 s ≈ 444 s，远超 180 s。

**影响**

- 用户等待时间不可控，演示时可能长时间“调查中”。
- 线程池被长时间占用，进一步放大 P1-04 的拒绝风险。
- `AGENT_TIMEOUT_SECONDS=180` 只是名义配置，不能作为可靠预算。

**建议修复**

- 在每次模型/工具调用前计算 `remaining = deadline - now`，把 `remaining` 作为读超时上限传给 `RestClient`（或使用 `CompletableFuture.orTimeout`）。
- 工具调用增加剩余时间参数；`ObservabilityClient` 支持动态超时。
- 模型调用返回后、工具调用返回后再次检查 deadline，超时立即走 `FAILED` 并记录当前 trace。
- 增加超时测试（例如模型 sleep 超过剩余时间）。

**提交前是否必须修复：是（演示可控性）。**

---

### P1-08 前端对所有非 2xx 响应丢弃后端 `ApiResponse.message`，用户只看到英文 axios 通用错误

**位置**

- `frontend/src/api/http.js:3-11`：只创建 axios 实例，没有响应拦截器。
- `frontend/src/api/faultEvents.js:3-9`、`experiments.js:3-9`、`agentDiagnosis.js:3-9`：`unwrap(response)` 只能处理 2xx。
- `frontend/src/views/FaultEventsView.vue:69-70`、`FaultInjectionView.vue:92,106,122,135,148,170,187`、`FaultEventDetailView.vue:221,247,261,287,311,333,350,368,385`：统一 `error.value = ex.message`。

**原因**

axios 在非 2xx 时直接 reject，`unwrap` 不会执行；`ex.message` 是 `"Request failed with status code 403"` 这类通用文本，不是后端返回的中文原因。

**影响**

- 故障注入开关未开启时，后端返回 403“故障注入默认禁用，请设置 EXPERIMENT_FAULT_INJECTION_ENABLED=true 后重启库存服务”，前端只显示 `Request failed with status code 403`，用户不知道如何修复。
- 409（已有诊断任务、实验过期、非法状态流转）都无法展示具体原因。
- 答辩现场出现错误时，无法向评委解释真实原因。

**复现/证据（静态复现路径）**

- 设置 `EXPERIMENT_FAULT_INJECTION_ENABLED=false`，在“故障注入实验”页点击“开启故障注入”，观察顶部错误提示。

**建议修复**

```js
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error.response?.data?.message
    if (message) error.message = message
    return Promise.reject(error)
  }
)
```
同时保留网络错误/超时的中文兜底。

**提交前是否必须修复：是（改动小，直接影响演示解释）。**

---

### P1-09 证据采集前端无条件提示“成功”，即使两个工具都失败

**位置**

- `frontend/src/views/FaultEventDetailView.vue:235-251`：`handleCollect` 丢弃 `collectFaultEventEvidence` 的返回值，直接 `ElMessage.success`。
- `:567-579`：只要 `evidence` 存在就显示绿色“已采集证据”，不检查 `success`。
- 后端 `EvidenceCollectionView.success = logs.success || metrics.success`（`backend/src/main/java/com/microservice/rca/diagnosis/api/EvidenceCollectionView.java`，`EvidenceService.java:98-99,121-131`）。

**影响**

- 两个观测服务都不可用时，采集会创建一个 `MANUAL_EVIDENCE` 任务并写入一份全失败快照，但页面显示成功。
- 用户可能据此认为已有证据，继续启动诊断，得到 `INSUFFICIENT_EVIDENCE`，难以判断是模型问题还是采集问题。
- 与系统“不把失败伪装成成功”的设计原则冲突。

**复现/证据（静态复现路径）**

- 停止 8081/8082，点击“采集真实证据” → 绿色 toast + 绿色“已采集证据”横幅；展开工具页签才看到失败。

**建议修复**

- 使用返回值：`const result = await collect...`；`result.success` 为 false 时显示 `ElMessage.warning('证据采集失败：...')`，并用红色/黄色 alert 展示失败工具。
- 横幅文案区分“采集成功”“部分成功”“全部失败”。
- 增加前端测试覆盖 `success=false` 分支。

**提交前是否必须修复：是（真实性原则 + 演示可信度）。**

---

### P1-10 选择历史任务会停止正在运行任务的轮询，并重新启用“启动/重新诊断”

**位置**

- `frontend/src/views/FaultEventDetailView.vue:61`：`agentActive` 只看当前选中的 `agentTask`。
- `:274-289`：`loadAgentTasks` 默认选最新任务，只有最新任务活跃时才启动轮询。
- `:296-313`：轮询只刷新当前任务，不刷新任务列表。
- `:339-354`：`selectAgentTask` 先 `stopAgentPolling()`，只有选中任务自身活跃才重启。
- `:760-768`：Agent 页签的“重新诊断”只按 `agentActive` 禁用。
- `:428`：详情头部的“启动AI诊断”同样只按 `agentActive` 判断。

**影响**

- 运行中点击历史任务的“查看”，轮询停止；“Agent 正在进行”提示消失；启动按钮重新可用。
- 用户以为任务已结束，点击启动 → 后端 409；事件状态恢复也不会自动刷新，需要整页刷新。
- 多标签页同时操作时更容易出现。

**复现/证据（静态复现路径）**

- 启动 AI 诊断（2.5 s 轮询），在任务完成前点击历史成功任务的“查看” → 观察轮询停止、启动按钮恢复。

**建议修复**

- 增加独立于选中任务的 `hasActiveTask`：从 `agentTasks` 列表中判断是否存在 `PENDING/RUNNING` 的 `AGENT/AGENT_RETRY`。
- 轮询期间同时刷新任务列表；选择历史任务不应停止“事件级”轮询，或至少保留一个事件级轮询。
- 启动/重试按钮统一使用 `hasActiveTask` 禁用。

**提交前是否必须修复：是（演示状态一致性）。**

---

### P1-11 系统概览与侧边栏仍把已实现的 Agent/根因能力标为“待开发”，版本号与后端不一致

**位置**

- `frontend/src/views/OverviewView.vue:126-146`：“LLM Agent 工具调用”“根因结论与辅助处置”“人工确认与处置闭环”均为 `status: '待开发'`；备注仍写“Day 3 接入 DeepSeek”“Day 2 不生成根因结论”。
- `frontend/src/views/OverviewView.vue:264`：“Day 2 已落地的组件”。
- `frontend/src/layouts/MainLayout.vue:86-89`：侧边栏底部“Day 2 真实故障实验”“v0.2.0”。
- `backend/src/main/resources/application.yml:33-34`：`app.version: 0.3.0`。

**影响**

- 答辩第一页就告诉评委“Agent 未开发、根因未实现”，与后面展示的真实 Agent 诊断直接矛盾。
- 版本号 v0.2.0 与后端健康检查返回的 0.3.0 不一致，显得项目未收尾。
- 这是“内容和展示”问题，不是功能问题，但在论文答辩中杀伤力很大。

**建议修复**

- 将三条能力改为“已完成”，备注改为真实实现说明（多轮工具调用、证据约束、人工处置闭环）。
- 将“Day 2 已落地”改为“当前已落地”或“Day 5/6 状态”。
- 侧边栏改为当前版本（建议与 `app.version` 统一为 `v0.3.0` 或 `v0.4.0`）。
- 同时更新 README、`.env.example:60`、`docs/architecture/system-design.md` 的版本/日期。

**提交前是否必须修复：是（纯文字修改，优先级极高）。**

---

### P1-12 自动化测试只有单元测试与 3 个 MVC 切片，缺少数据库/集成/端到端测试；前端完全没有测试

**位置**

- 后端 12 个测试类：`AgentDiagnosisServiceTest`、`AgentInputFactoryTest`、`AgentReportValidatorTest`、`AgentTaskRunnerTest`、`AgentToolCallValidatorTest`、`DeepSeekAgentModelClientTest`、`DefaultAgentOrchestratorTest`、`ObservationClassifierTest`、`HealthControllerTest`、`HealthServiceTest`、`QueryLogsToolTest`、`QueryMetricsToolTest`。
- 库存 4 个：`FaultInjectionServiceTest`、`InventoryControllerTest`、`RequestLogStoreTest`、`RequestMetricsStoreTest`。
- 订单 2 个：`InventoryClientTest`、`OrderControllerTest`。
- 只有 3 个 `@WebMvcTest`（健康、库存、订单控制器）；没有 `@SpringBootTest`、`@DataJpaTest`、Testcontainers、Flyway 迁移验证或真实 PostgreSQL 测试。
- `diagnosis.service`、`diagnosis.web`、`experiment.ExperimentService`、`observability.ObservabilityClient`、`common.GlobalExceptionHandler` 没有测试。
- `frontend/package.json` 没有 `test` 脚本，没有 Vitest/Playwright 依赖，源码下没有 `*.spec.*`。

**影响**

- 以下高风险路径完全未验证：状态机持久化、Flyway `ddl-auto: validate`、JSONB 映射、并发启动/重试、证据采集部分失败、异步线程池拒绝、DELAY 副作用、4xx 分类。
- 论文中的“测试充分性”章节很难写实；评委追问“如何证明 Agent 可靠”时，只有少量真实调用记录。
- 前端轮询、路由参数、错误提示、证据成功标志等均已出现实际缺陷，但没有测试拦截。

**建议修复（按投入产出排序）**

1. 用 Testcontainers PostgreSQL 增加 1–2 条 `@SpringBootTest` 集成测试：Flyway V1–V3 → 创建事件 → 采集证据（Mock 观测 HTTP）→ 启动诊断（脚本化模型）→ 任务/事件终态。覆盖成功、FAILED、超时三条路径。
2. 增加 `FaultInjectionFilter` 集成测试：HTTP_500 无库存副作用、DELAY 超时副作用、disable 中途取消。
3. 增加控制器契约测试：`FaultEventController`、`ExperimentController`、`AgentDiagnosisController` 的 400/404/409/500 状态码与 `ApiResponse` 结构。
4. 前端引入 Vitest + Vue Test Utils，至少覆盖：错误拦截器、证据 `success=false`、历史任务选择不停止轮询、路由参数变化、指标平均耗时。
5. 可选：Playwright 一条“注入 HTTP_500 → 执行实验 → 启动诊断 → 查看报告”的演示冒烟。

**提交前是否必须修复：是（至少完成第 1、3、4 项；否则论文的测试章节支撑不足）。**

---

### P1-13 管理后端无认证、三服务绑定全部网卡、管理后端绕过库存服务的本机访问守卫，PostgreSQL 端口对外发布

**位置**

- `backend/pom.xml:24-56`：没有 Spring Security 或任何认证 starter。
- `backend/src/main/resources/application.yml:1-2`、库存/订单 `application.yml`：只配置 `server.port`，没有 `server.address`，Tomcat 默认绑定 `0.0.0.0`。
- `backend/src/main/java/com/microservice/rca/config/WebConfig.java:21-28`：仅配置 CORS，不阻止非浏览器客户端。
- `services/inventory-service/src/main/java/com/microservice/rca/inventory/fault/ExperimentAccessGuard.java:20-29`：只校验 `request.getRemoteAddr()` 是否为本机。
- `backend/src/main/java/com/microservice/rca/experiment/ExperimentService.java:331-349`：管理后端从本机代理故障控制请求，因此任何能访问 8080 的主机都可以间接开启/关闭故障。
- `infra/docker-compose.yml:11-12`：`${POSTGRES_PORT:-5432}:5432` 发布到所有网卡。
- `frontend/src/api/http.js` 无 Authorization；后端也没有任何鉴权入口。

**影响**

- 同一局域网内的任意主机可以：开启/关闭故障注入、触发真实 DeepSeek 诊断（消耗费用）、读取故障事件与观测数据、尝试连接 PostgreSQL。
- 库存服务对本机来源的限制在“经管理后端代理”的路径上被绕过。
- 答辩若在校园网/公共网络进行，存在被他人操作或扫描的风险。

**复现/证据（静态复现路径）**

- 在另一台主机访问 `http://<答辩机IP>:8080/api/fault-events`，无需任何凭据即可读取。
- 通过 `POST /api/experiments/faults/enable` 可间接控制库存故障（前提是库存服务实验开关为 true）。
- `docker compose ps` 可看到 5432 绑定 `0.0.0.0`。

**建议修复**

- 本地答辩最低成本方案：三个 Spring Boot 服务设置 `server.address: 127.0.0.1`；PostgreSQL 改为 `127.0.0.1:${POSTGRES_PORT:-5432}:5432`；Vite 只监听 `127.0.0.1`。
- 管理 API 增加最简单的 Token/HTTP Basic 认证（至少对写操作），前端在 `.env.local` 注入，不提交。
- 若必须跨机演示，使用防火墙只放行答辩机，不要直接暴露 8080/5432。
- 论文中明确“当前为单机实验环境，未实现多用户认证”，不要声称具备生产级安全。

**提交前是否必须修复：是（至少绑定 127.0.0.1 + PostgreSQL 不对外；认证可作为答辩说明或后续工作）。**

---

### P1-14 当前运行环境存在两个 Vite 实例与 WSL/Windows 网络错位，WSL 侧 5173 的 `/api` 返回 502

**位置/现象（本轮只读检查时）**

- Windows Vite PID 66596 监听 `::1:5173`；WSL Vite PID 25318 经 `wslrelay` PID 45624 监听 `127.0.0.1:5173`。
- WSL 内 `curl localhost:5173/api/health` 与 `curl 127.0.0.1:5173/api/health` 均返回 502；WSL 内 `curl localhost:8080/8081/8082/api/health` 返回 000，而 `curl 172.31.144.1:8080/8081/8082/api/health` 返回 200。
- `frontend/vite.config.js:11`：代理目标只读 `process.env.VITE_API_PROXY_TARGET`，默认 `http://localhost:8080`，在 WSL NAT 模式下无法到达 Windows 后端。

**影响**

- 如果答辩时打开的是 WSL 侧 Vite 的地址，所有 `/api` 请求都会 502，页面看起来“后端没启动”。
- 当前两个实例同时存在，浏览器访问 `localhost:5173` 可能命中不同实例，行为不稳定。
- 这是环境/操作问题，不是业务代码缺陷，但会直接阻断演示。

**建议修复**

- 答辩统一使用 **Windows 侧 Vite**（在 Windows 终端 `cd frontend && npm run dev`），并只保留一个实例；停止 WSL 侧 Vite。
- 若必须从 WSL 启动 Vite，设置 `VITE_API_PROXY_TARGET=http://172.31.144.1:8080`，或把后端也放到 WSL（但当前 PostgreSQL/IDEA 在 Windows，不推荐临时切换）。
- 在演示脚本中加入“启动前检查 5173 只有一个监听、`/api/health` 返回 200”的步骤。

**提交前是否必须修复：是（答辩环境操作项，非代码修改）。**

---

## 5. P2 问题

> P2 不影响主流程演示，但会影响健壮性、可维护性、论文严谨性或现场体验。按主题分组，便于排期。

### 5.1 状态与数据一致性

| ID | 位置 | 问题与原因 | 影响/复现 | 建议 |
| --- | --- | --- | --- | --- |
| P2-01 | `FaultEventService.java:157-160` | `RESOLVED -> CLOSED` 时再次写入 `resolvedAt=now` | 先标记已解决、再关闭，原始解决时间被覆盖；`OPEN -> CLOSED` 也会写入一个“解决时间”。复现：PATCH RESOLVED → 等待 → PATCH CLOSED，比较 `resolvedAt` | 只在 `target==RESOLVED` 且 `resolvedAt==null` 时写入；关闭时保留原值 |
| P2-02 | `FaultEvent.java:18-24`、`DiagnosisTask.java:19-24`、V1–V3 | 没有 `@Version`/version 列；所有状态更新是读-改-写 | 并发 PATCH 可丢失流转，甚至把 CLOSED 改回 DIAGNOSING；证据快照并发覆盖 | 增加乐观锁 + Flyway V4；关键更新用条件 UPDATE |
| P2-03 | `UpdateFaultEventStatusRequest.java:6`、`FaultEventDetailView.vue:436-443` | 公共 API 接受任意 `FaultEventStatus`，前端提供“标记诊断中” | 可创建“DIAGNOSING 但没有任务”的事件，启动 AI 时又被 409；状态机没有 `DIAGNOSING -> OPEN` | 公共 API 只允许 OPEN→RESOLVED/CLOSED 等人工流转；DIAGNOSING 仅由任务启动/恢复维护 |
| P2-04 | `V2__fault_event_experiment_evidence.sql:21`、`ExperimentService.java:136`、`FaultEventService.java:72-98` | `request_id` 只有普通索引，无唯一约束；实验每次生成新 requestId；手工创建接受调用方 requestId | 客户端超时重试/双击可生成重复事件；同一 requestId 可插入多行 | 增加部分唯一索引 + 幂等键；实验请求复用同一 requestId 或做 upsert |
| P2-05 | `EvidenceService.java:50-51`、`FaultEventService.java:164-170`、`FaultEventDetailView.vue:561` | 已关闭事件仍可采集证据；证据是单快照，直接覆盖 | 关闭事件被修改；历史证据无法审计 | 采集前校验状态；增加 evidence 历史表或只追加不覆盖 |
| P2-06 | `FaultEventService.java:50-67,146-162`、`FaultEvent.java:50-56` | `@CreationTimestamp/@UpdateTimestamp` 在 insert/flush 时生成；`toView` 在 `save()` 后立即读取，未 flush | **静态推断（未运行时验证）**：`POST /api/fault-events` 响应中 `createdAt/updatedAt` 可能为 null；PATCH 响应可能返回旧 `updatedAt`。建议用 POST 后立即 GET 对比验证 | 使用 `saveAndFlush`，或在保存前显式设置时间戳；补契约测试 |
| P2-07 | `EvidenceService.java:51-65`、`AgentInputFactory.java:55`、`AgentToolCallValidator.java:44,57-62` | 证据默认窗口 `occurredAt-60s ~ now`，超过 24 h 直接报错；Agent 查询窗口固定 `occurredAt-60s`，但 lookback 最大 60 min，且被 clamp 到 `windowStart` | 对 2 天前的事件采集证据 → 400；对旧事件诊断只查最近 1 小时，查不到事件时日志；未来 `occurredAt` 会让所有工具调用报“窗口无效” | 按事件年龄计算滚动窗口（如 `min(occurredAt, now-24h)`），或给事件设置最大回溯；未来时间做校验/截断 |
| P2-08 | `ExperimentService.java:116-127` | `execute` 先查状态再执行，实验可能在两步之间过期/被关闭 | 调用方以为执行了故障实验，实际无故障且不生成事件 | 在库存侧校验 experimentId + 过期时间，或接受并明确记录“执行时已过期” |
| P2-09 | `InventoryStore.java:31-37,68-70` vs `:44-50` | `available` 是普通 int，读路径不加锁；写路径在 `synchronized(product)` 内 | JMM 下 `GET /api/inventory` 可能读到旧值，证据与实际不一致 | 读也使用 `synchronized(product)` 或 `volatile`/`AtomicInteger` |
| P2-10 | `FaultInjectionService.java:75-82` | 过期状态 CAS 失败时直接返回 null，不重读 | 另一线程刚启用新故障时，本请求可能漏掉故障，返回 SUCCESS | CAS 失败后重新读取 `active.get()` |
| P2-11 | `FaultInjectionFilter.java:46-63`、`FaultInjectionService.java:62-70` | disable/reset 只置空引用，无法中断正在 sleep 的请求；过期只在入口检查 | disable 返回后，最多 30 s 内仍有请求继续延迟并扣库存；请求可在过期后执行 | 使用可中断的等待/状态轮询；sleep 后再次检查状态和过期时间 |
| P2-12 | `FaultInjectionFilter.java:57`、Tomcat 默认线程池 | 每个 DELAY 请求占用一个 worker 最多 30 s | 约 200 并发延迟请求可占满线程，健康检查/观测/控制接口排队，间接“故障泄漏”到控制面 | 限制并发延迟数、使用异步延迟、缩短最大延迟；配置独立线程池 |
| P2-13 | `RequestObservationFilter.java:75-88`、`RequestLogEntry.java` | 观测日志不记录 `X-Experiment-Id`/`X-Experiment-Fault` | 注入的 HTTP_500 与真实 500 在日志/指标中无法区分；RCA 无法知道是否实验 | 在日志条目中增加 experimentId/faultType/wasInjected 字段 |
| P2-14 | `logback-spring.xml`（两服务） | 日志 pattern 没有 `%X{requestId}` | 异常堆栈无法与 requestId 关联，排障困难 | 在 pattern 中加入 `%X{requestId}`，并确保 MDC remove 在 finally 最外层 |

### 5.2 API、异常与契约

| ID | 位置 | 问题与原因 | 影响/复现 | 建议 |
| --- | --- | --- | --- | --- |
| P2-15 | `GlobalExceptionHandler.java:32-37`（backend/order/inventory 三处） | 只处理校验异常、`ResponseStatusException` 和 `Exception`，未处理 `HttpMessageNotReadableException`、`MethodArgumentTypeMismatchException`、`HttpRequestMethodNotSupportedException`、`NoResourceFoundException` | 非法枚举、坏 JSON、错误方法、未知路径都变成 500；日志被自造 5xx 污染 | 继承 `ResponseEntityExceptionHandler` 或增加对应 handler，返回 400/404/405/415 |
| P2-16 | `HealthController.java:18-21`、`HealthService.java:37-55`；订单 `HealthController.java:27-41` | 数据库/依赖 DOWN 时仍返回 HTTP 200，只在 body 里写 DEGRADED | K8s/负载均衡/脚本按状态码判断会认为健康；健康探测最长阻塞 Hikari 5 s | 依赖 DOWN 时返回 503，或拆分 liveness/readiness；设置查询超时 |
| P2-17 | 三个 `HealthController` 的 `@RequestMapping("/api")` + `@GetMapping("/health")` | `/health` 未映射，被 catch-all 转成 500 | 运维/评委按常见习惯访问 `/health` 看到 500，误判服务故障 | 增加 `/health` 别名，或对未映射路径返回 404；README 明确 `/api/health` |
| P2-18 | `FaultEventController.java:43-46` | 创建事件返回 200 而非 201，且没有 Location | REST 契约不规范 | 返回 201 + Location，或明确文档写 200 |
| P2-19 | `CreateFaultEventRequest.java:20,23,24`、`FaultEventService.java:110-111,130-131` | `details/experimentParameters/observedResult` 无大小限制；列表返回完整 details；分页参数静默 clamp；排序只按 occurredAt | 单条大 JSON 拖慢列表/撑大数据库；并发相同时间戳分页可能重复/漏行 | 限制字段大小；列表返回摘要 DTO；分页非法参数返回 400；增加 id 作为第二排序键 |
| P2-20 | `FaultEventService.java:139-142,201-206` | 详情为每个任务单独查 tool_invocation（N+1），且返回全部历史 | 事件任务多时变慢 | 批量查询/分页；限制返回任务数 |
| P2-21 | `ExperimentService.java:147-158`、`FaultEventService.java:87` | 分类摘要包含下游 header 值，未截断；`summary` 列 512 | 超长 header 可导致插入失败，整个 checkout 变 500 且无事件 | 对 summary 截断/清洗；header 白名单化 |
| P2-22 | `DiagnosisToolController.java:10-13` | 注释仍称 Agent 未实现 | 代码注释与实现矛盾，误导维护者 | 更新注释 |
| P2-23 | `CreateFaultEventRequest`、`FaultEventService.java:60-65,96` | 允许 `source=EXPERIMENT` 但 `experimentId=null`，或 `source=MANUAL` 但 `experimentId` 非空；无 FK/注册表校验 | 事件来源语义不一致，统计失真 | 增加跨字段校验；实验来源必须有 experimentId |

### 5.3 Agent 与模型调用

| ID | 位置 | 问题与原因 | 影响/复现 | 建议 |
| --- | --- | --- | --- | --- |
| P2-24 | `DefaultAgentOrchestrator.java:149-156` | 重复签名在“执行前”就加入集合；失败工具不能再用相同参数重试 | 工具因瞬时网络错误失败后，模型无法重试同一查询，只能换参数或结束 | 只对成功调用去重，或签名中包含 attempt/失败状态 |
| P2-25 | `AgentTaskRunner.java:115-123,129-152`、`RunStats.from` | FAILED 路径直接 `setReport(null)`；失败前的 usage 只存在 trace 中，报告没有汇总 | 失败任务的 token 消耗无法在报告层查看；论文统计总消耗困难 | FAILED 也写入 RunStats 到 trace/报告；保留已完成轮次 usage |
| P2-26 | `DeepSeekAgentModelClient.java:113-118`、`AgentTaskRunner.java:74,92` | 解析了响应 `model`，但任务最终写的是配置模型名 | Provider 实际返回的模型与审计记录不一致；模型切换/别名时无法追踪 | 使用 `response.model()` 写入任务/报告；配置名作为 fallback |
| P2-27 | `AgentPromptBuilder.java:143-153,179-190` | 降级路径返回前没有再检查 `maxPromptChars` | 极端情况下仍超限，编排器直接 FAILED；本可继续调查 | 最终做硬截断（按字符）并标记 `promptHardTruncated` |
| P2-28 | `AgentDecisionParser.java:86-98` | 取第一个 `{` 到最后一个 `}` | 模型在 JSON 后追加含 `}` 的说明文字时解析失败 | 使用括号配对/流式解析，或严格 `readTree` + 失败再尝试首对象 |
| P2-29 | `DeepSeekAgentModelClient.java:121-138`、`DefaultAgentOrchestrator.java:99-109` | 429/5xx 重试使用固定 500 ms；没有 `Retry-After`；重试消耗模型调用预算 | 限流时容易连续失败；预算被重试吃掉 | 读取 `Retry-After`，指数退避+抖动；独立重试预算 |
| P2-30 | `ToolInvocationService.java:35-66` | `@Transactional` 包住外部 HTTP 工具调用 | 数据库事务在 14 s 网络等待期间一直占用连接，连接池（5）可能耗尽 | 外部调用移出事务，审计写入用独立短事务 |
| P2-31 | `ExperimentService.java:207-213`、`ObservationClassifier.java:20-24` | 任何非 `RestClientResponseException/ResourceAccessException` 都记为 status 0 → `ORDER_SERVICE_UNREACHABLE` | 订单返回 200 但非 JSON/内容类型错误时，被误报为“无法连接订单服务” | 区分 transport/protocol/parse 错误，新增 `ORDER_PROTOCOL_ERROR` |
| P2-32 | `ObservationClassifier.java:25-30`、`ExperimentService.java:286-292` | 慢响应只看端到端耗时，且一律归因 inventory-service | 订单服务自身慢也会被归因到库存，误导 RCA | 增加分 hop 耗时或至少标注“端到端慢，归属待定” |
| P2-33 | `ExperimentService.java:53`、`backend/application.yml:45`、`ObservationClassifier.java:11` | 慢阈值默认值分别为 1000/3000/3000，常量未使用 | 无 yml 的测试/切片环境分类结果不同 | 统一为一个配置源，删除死常量 |
| P2-34 | `DiagnosisStatus` 与 V1 CHECK | `CANCELLED` 定义了但从未使用，没有取消 API | 运行中的任务无法取消，只能等超时/结束 | 实现取消或删除未使用状态 |

### 5.4 前端体验与状态

| ID | 位置 | 问题与原因 | 影响/复现 | 建议 |
| --- | --- | --- | --- | --- |
| P2-35 | `FaultEventDetailView.vue:685-687`、`MetricBucket.java:8-22` | 前端读 `row.averageDurationMs`，但 record 只序列化组件，派生方法不入 JSON | 分钟桶“平均耗时”恒为 0（服务级 summary 正常） | 前端用 `totalDurationMs/requestCount` 计算，或后端增加组件 |
| P2-36 | `FaultEventDetailView.vue:215-225,391-394` | 只在 `onMounted` 加载，不监听 `route.params.eventId` | 地址栏从事件 A 改到 B 时，页面仍显示 A，但操作作用于 B | `watch(() => route.params.eventId, load, {immediate:true})` |
| P2-37 | `FaultInjectionView.vue:193-200`、`FaultEventDetailView.vue:274-289,391-396` | 定时器在 `await` 之后才赋值；组件卸载时可能还没赋值 | 卸载后仍轮询，后台请求泄漏 | `let mounted=true` + 卸载置 false；启动前检查；`onBeforeUnmount` 清理 |
| P2-38 | `FaultInjectionView.vue:86-96,193-196,233,279` | 每 5 s 轮询设置 `loading=true`，整张卡片被遮罩；`error` 每轮清空 | 按钮周期性不可点；动作错误被轮询清掉；慢响应可能把状态回退 | 轮询使用独立 `refreshing`，不遮罩操作区；动作错误单独展示 |
| P2-39 | `FaultInjectionView.vue:176-191,383-390`、`ExperimentService.java:129-131` | “无故障对照请求”在故障激活时仍可点；后端也不检查 | 对照请求仍经过故障，结果不是 baseline，但 UI 提示“未注入故障” | 故障激活时禁用对照按钮；后端在 checkout 前检查 active 状态或明确允许并标注 |
| P2-40 | `FaultEventDetailView.vue:760-768`、`AgentDiagnosisService.java:50` | Agent 页签的“重新诊断”不检查事件是否 CLOSED/RESOLVED | 已关闭事件仍可启动新 LLM 任务，污染终态记录 | 前端按事件状态禁用；后端也校验事件状态 |
| P2-41 | `FaultEventDetailView.vue:453-459` | “关闭事件”单击即执行，无确认；CLOSED 无出边 | 误点永久关闭，无法重开 | 使用 `ElMessageBox.confirm`；提供管理员重开或明确不可逆 |
| P2-42 | `FaultEventDetailView.vue:113-127,731,735`；`FaultEventsView.vue:151-159` | 状态/来源/严重级别映射不全或直接显示英文枚举；`PENDING` 工具调用被当失败颜色 | 界面中英混杂，语义不一致 | 补全映射；`PENDING` 用 info；来源 `MANUAL_EVIDENCE` 翻译为“手工证据采集” |
| P2-43 | `FaultEventsView.vue:13-21,93-95`、`FaultEventDetailView.vue:403` | 列表筛选/分页只在组件内，返回详情后丢失；`load()` 无请求序号 | 用户体验差；快速筛选时旧响应可能覆盖新结果 | 把筛选写入 route query；增加请求序号/取消 |
| P2-44 | `FaultEventDetailView.vue:415-417,426` | 初次加载失败时只有错误 alert，刷新按钮在 `v-if="event"` 内 | 404/500 时无法在页内重试，只能刷新浏览器 | 错误态提供“重试”按钮和“返回列表” |
| P2-45 | `router/index.js:39-60` | `diagnosis-tasks`、`tool-invocations` 仍是 Placeholder | 答辩时侧边栏点进去是“规划中”，显得未完成 | 隐藏入口，或实现为真实全局任务/调用列表 |
| P2-46 | `MainLayout.vue:54-63` | 菜单 `default-active` 用 `route.path`，详情路由 `/fault-events/:id` 不匹配 `/fault-events` | 详情页侧边栏无高亮 | 用 computed 把详情路由映射回列表项 |
| P2-47 | `OverviewView.vue:1-15`、`MainLayout.vue:32-36`、`useHealth.js:4-8` | 健康状态只在布局挂载时请求一次；概览页没有 `onMounted` 刷新 | 页面标注“实时”，但返回概览时状态可能过期 | 概览页挂载时刷新；或加定时刷新；增加请求序号防竞态 |
| P2-48 | `useHealth.js:29` vs 其他视图 | 只有健康 composable 正确读取 `err.response.data.message`，其他视图没有 | 错误展示不一致 | 统一使用 P1-08 的拦截器 |

### 5.5 配置、构建、文档与安全

| ID | 位置 | 问题与原因 | 影响/复现 | 建议 |
| --- | --- | --- | --- | --- |
| P2-49 | `frontend/vite.config.js:11`、`.env.example:55-58` | Vite 只用 `process.env`，不加载根 `.env`；示例却把 `VITE_*` 放在根 `.env` | 用户在根 `.env` 改代理目标无效；WSL/Windows 切换时容易踩坑 | 使用 `loadEnv(mode, '../', '')` 或把前端变量放到 `frontend/.env`；README 说明 |
| P2-50 | 三个 `application.yml` 的 `spring.config.import` | `optional:file:...` 依赖工作目录；从 `target/` 启动时找不到 `.env`，且失败是延迟且晦涩的 | 打包后 `java -jar` 运行会 DB 密码为空/Key 为空 | 提供启动脚本固定工作目录；或改为环境变量注入并在启动时显式校验必填项 |
| P2-51 | `.gitignore:1-5` | 只忽略 `.env`、`.env.local`、`.env.*.local` | 将来新增 `.env.production`/`.env.staging` 可能被误提交 | 增加 `.env.*`（保留 `!.env.example`） |
| P2-52 | `.env` 文件权限 | 位于 `/mnt/c`，显示 `777`，`chmod` 不生效 | 同机其他用户/进程可读，Key 暴露风险 | 依赖 Windows ACL 限制目录；不要把 `.env` 放到共享目录；答辩前确认未被截图 |
| P2-53 | `services/order-service/application.yml:17-18`、库存 `application.yml:13`、两个 `pom.xml:17` | `app.version` 为 0.2.0，pom 为 0.1.0，后端为 0.3.0 | 健康检查/文档版本不一致 | 统一版本号，最好由 Maven 资源过滤注入 |
| P2-54 | `InventoryClient.java:33` vs 订单 `application.yml:15` | 构造器 fallback 读超时 3000，yml 为 5000 | 缺少 yml 时行为静默变化 | 统一默认值，或删除 fallback 强制配置 |
| P2-55 | 根目录 | 没有 Maven Wrapper、`.nvmrc`、一键启动脚本 | 换机器/答辩机环境搭建依赖手工步骤，易出错 | 增加 `mvnw`、`.nvmrc`、`scripts/start-all`（Windows/WSL 两版）和启动检查脚本 |
| P2-56 | `README.md:5,254-260`、`.env.example:60`、`docs/architecture/system-design.md:5,8` | 文档仍写 Day 5；`.env.example` 写“Day 3”；无 Windows + IDEA + Docker Desktop 的完整启动说明 | 答辩前照文档操作可能走 WSL 旧路径，遇到 502/端口问题 | 增加 Day 6 章节：Windows IDEA 三服务、Docker Desktop PostgreSQL、Windows Vite、端口检查、故障开关 |
| P2-57 | `backend/pom.xml:51-55` | 只有 `spring-boot-starter-test`，无 Testcontainers/H2 | 无法做真实数据库集成测试 | 增加 Testcontainers PostgreSQL 测试依赖（test scope） |
| P2-58 | Surefire 报告目录 | 存在旧包名 `com.btxc.*` 的 stale XML | `mvn test` 统计虚高，可能误判通过数 | 提交前 `mvn clean test`；不要提交 `target/` |
| P2-59 | `docs/architecture/system-design.md:409` | 文档写“解决时写入 resolved_at”，但 `OPEN -> CLOSED` 也写，且 `RESOLVED -> CLOSED` 覆盖 | 文档与实现/数据语义不一致 | 与 P2-01 一起修正文档和代码 |
| P2-60 | `backend/src/main/java/com/microservice/rca/config/WebConfig.java:21-28` | CORS 允许来源来自配置；若 `CORS_ALLOWED_ORIGINS` 为空则为空集合 | 前端跨域全部失败，且错误不直观 | 启动时校验至少一个来源；文档说明 |

---

## 6. 论文题目匹配度与关键功能缺口

### 6.1 题目拆解

题目：《基于 LLM 的微服务故障分析与处置系统的设计与实现》

| 题目要素 | 实现情况 | 匹配度 |
| --- | --- | --- |
| 基于 LLM | DeepSeek OpenAI 兼容接口、JSON 模式、多轮决策、工具调用、证据约束报告 | 高 |
| 微服务 | order-service + inventory-service 真实 HTTP 调用；管理后端独立部署；PostgreSQL | 中高（规模较小，但链路真实） |
| 故障分析 | 真实日志/指标、分类器、Agent 多轮调查、根因/证据/置信度 | 高 |
| 处置 | 人工标记 RESOLVED/CLOSED；Agent 输出 `recommendedActions`，但不执行 | 中（有“建议”，缺“执行/验证/回滚”） |
| 系统设计与实现 | 前后端、数据库、迁移、观测、审计、状态机 | 高 |

### 6.2 结论

**题目匹配度总体较高，足以支撑本科毕设。** 核心亮点是“真实模型 + 真实工具 + 真实跨服务故障”而不是 Mock；证据约束和审计轨迹也比普通 CRUD 项目更有技术含量。

但“处置”部分是弱项：当前系统更像“故障分析与辅助处置建议系统”，而不是完整的“分析—处置—验证”闭环。建议在论文中：

1. 明确区分“诊断完成”和“故障解决”：AI 诊断只恢复 `OPEN`，人工确认才 `RESOLVED/CLOSED`（当前实现和文档已经这样定义，是加分项）。
2. 把 `recommendedActions` 定义为“辅助处置建议”，不要声称系统会自动修复。
3. 若时间允许，增加一个最小“处置执行”能力：例如根据建议一键执行“关闭故障注入/重试对照请求/记录处置结果”，并验证业务恢复。即使只是半自动，也能明显增强题目匹配度。
4. 在“未来工作”中说明：自动扩缩容、重启、回滚、告警接入、调用链追踪是后续方向。

### 6.3 关键功能缺口（按论文影响排序）

1. **定量评测不足**：目前只有 HTTP_500 与 DELAY 的少量真实调用；缺少多场景 × 多次运行的准确率、首因定位率、平均耗时、Token 成本、失败率对比。
2. **无调用链/Trace 证据**：只有日志和分钟级指标；跨服务根因定位主要靠 requestId 关联，缺少 span/依赖拓扑。
3. **故障类型少**：只有 DELAY、HTTP_500；没有连接拒绝、超时、错误率、部分失败、数据库慢查询等。
4. **无告警接入**：事件来自实验请求或手工创建，没有 Prometheus/Alertmanager/日志告警自动生成事件。
5. **处置未执行**：只有状态标记和建议文本，没有实际动作、验证与回滚。
6. **无认证/多用户**：单机实验工具，不是多租户管理平台。
7. **前端无自动化测试**：交互回归依赖手工走查。

这些缺口不要求在答辩前全部补齐，但论文的“不足与展望”应主动承认，避免被评委追问时被动。

---

## 7. 5 分钟答辩演示建议

> 前提：先完成 P0-1、P1-08、P1-09、P1-10、P1-11，并确保只保留一个 Vite 实例、三个 Spring Boot 服务从当前源码重启。

### 7.1 演示前检查清单

| 检查项 | 命令/操作 | 通过标准 |
| --- | --- | --- |
| PostgreSQL | `docker compose --env-file .env -f infra/docker-compose.yml ps` | `rca-postgres` 为 healthy |
| 后端 8080 | `curl --noproxy '*' http://localhost:8080/api/health` | HTTP 200，`data.status=UP`，数据库 UP |
| 订单 8081 | `curl --noproxy '*' http://localhost:8081/api/health` | HTTP 200 |
| 库存 8082 | `curl --noproxy '*' http://localhost:8082/api/health` | HTTP 200 |
| 故障开关 | 库存 IDEA 启动环境变量 `EXPERIMENT_FAULT_INJECTION_ENABLED=true` | 实验页可开启故障 |
| 前端 | 只保留 Windows 侧 `npm run dev`，访问 `http://localhost:5173` | 概览页正常，`/api/health` 不 502 |
| DeepSeek | 答辩前用最小请求核实模型名与余额 | 模型可用，余额充足 |
| 数据 | 故障事件列表至少有一条历史成功任务 | 可用于兜底展示真实报告 |
| 关闭无关程序 | 停止 WSL 侧 Vite、OnlineMusic 等 5173/8080/8081/8082 占用 | 端口唯一 |

### 7.2 时间线

| 时间 | 内容 | 讲解要点 |
| --- | --- | --- |
| 0:00–0:30 | 系统概览 | 三服务 + PostgreSQL 实时健康；强调“真实接口，不是 Mock” |
| 0:30–1:10 | 注入 HTTP_500（120 s） | 展示实验 ID、故障类型、倒计时；说明故障只作用于 `/api/inventory/**` |
| 1:10–1:45 | 执行真实结算请求 | 展示 502、耗时、`X-Downstream-Error-Type: HTTP_5XX`、requestId；打开关联事件 |
| 1:45–3:10 | 启动 AI 诊断 | 展示模型逐轮选择 `query_logs`/`query_metrics`、工具参数、证据 ID、根因、置信度、Token/耗时 |
| 3:10–3:50 | 关闭故障 + 对照请求 | 展示业务恢复（200、库存扣减正常）；事件仍为“待处理”，人工标记“已解决” |
| 3:50–4:30 | 任务历史与时间线 | 展示失败/重试历史不被覆盖、`AGENT_RUN_SUMMARY`、逐轮 usage |
| 4:30–5:00 | 边界与展望 | 真实模型、证据约束、人工确认；承认处置执行、认证、评测是后续工作 |

### 7.3 兜底方案

- 模型超时/限流：展示真实失败原因，说明“系统不会伪造结论”；切到历史真实成功任务继续讲解。
- 网络/端口异常：提前准备一条已完成的真实任务截图/页面，但必须说明是历史真实记录，不能声称当场调用。
- DELAY 备选：使用 3500 ms（低于订单 5 s 超时），并预留更多等待时间；不要用 6000 ms，避免 P1-01 的库存不一致。
- 故障开关未生效：检查库存服务是否以 `EXPERIMENT_FAULT_INJECTION_ENABLED=true` 重启；不要现场改代码。

---

## 8. 下一阶段计划（按优先级）

### 8.1 答辩前（建议 1–2 天）

| 优先级 | 任务 | 预计 | 验收标准 |
| --- | --- | --- | --- |
| P0 | Git 初始化、身份配置、首次提交、打标签 | 0.5 h | `git log` 有基线；`git status` 干净；`.env` 未跟踪 |
| P1 | 修正概览/侧边栏/Day 2/v0.2.0 文案 | 0.5 h | 页面与后端版本一致，Agent 标为已完成 |
| P1 | axios 响应拦截器 + 证据 success 判断 | 1 h | 403/409 显示后端中文原因；全失败显示警告 |
| P1 | 历史任务选择/轮询/启动按钮状态 | 2 h | 选择历史任务不停止运行中轮询；按钮不出现 409 |
| P1 | 4xx 分类修复 | 2 h | 未知 SKU 不再生成故障事件；补测试 |
| P1 | DELAY 上限/幂等/说明 | 2–4 h | 6 s 延迟不再出现“502 但扣库存”，或文档明确标注 |
| P1 | 任务启动/失败/超时/并发保护 | 4–6 h | 线程池拒绝、进程重启、并发启动都有明确终态 |
| P1 | 安全绑定 127.0.0.1 + DB 不对外 | 0.5 h | 外部主机无法访问 8080/5432 |
| P1 | 演示环境清理（单 Vite） | 0.5 h | 5173 只有一个监听；`/api/health` 200 |
| P1 | 核心集成测试 + 前端冒烟 | 1 天 | 至少 1 条 Testcontainers 全链路 + 3 条前端测试 |

### 8.2 答辩后 / 论文定稿前（建议 3–5 天）

1. 建立评测矩阵：HTTP_500、DELAY 3500、DELAY 6000、正常对照，各运行 5–10 次；记录根因正确率、证据命中率、耗时、Token、失败原因。
2. 增加至少一种新故障（连接拒绝或下游超时），展示分类器与 Agent 的泛化。
3. 增加“处置建议 → 人工执行 → 对照验证”的最小闭环，强化题目中的“处置”。
4. 补 Flyway/JPA 集成测试与并发测试；补前端 Vitest。
5. 统一版本号、文档、启动脚本；增加 Windows + IDEA + Docker Desktop 的独立启动章节。
6. 论文中增加“系统边界与真实性声明”：单机实验环境、无认证、内存库存、故障注入范围、模型可能失败。

### 8.3 可选增强（不影响答辩）

- 调用链/Trace 证据（OpenTelemetry）。
- Prometheus/Alertmanager 告警接入，自动创建事件。
- 处置动作执行器（重启/回滚/扩缩容）与验证。
- 多模型对比（DeepSeek vs 其他 OpenAI 兼容模型）。
- 前端路由级懒加载，降低 1.13 MB 主 chunk。

---

## 9. 最终验收结论

| 维度 | 评价 | 说明 |
| --- | --- | --- |
| 核心功能完整性 | **通过** | 真实跨服务、故障注入、观测、Agent、证据、报告、历史、前端展示齐全 |
| 演示可用性 | **有条件通过** | 修复 P1-08/09/10/11/14 后稳定；当前双 Vite 与文案问题会直接影响现场 |
| 数据与状态正确性 | **部分通过** | 正常路径正确；DELAY 超时、4xx 分类、并发/崩溃恢复存在 P1 |
| 工程完整性 | **部分通过** | 缺集成测试、启动恢复、认证、一键启动、版本统一 |
| 论文支撑度 | **基本满足** | 技术点足够，但评测数据与“处置”闭环偏薄 |
| 安全性 | **不足** | 无认证、全网卡绑定、DB 对外；单机演示可接受，公共网络不可 |
| 可提交性 | **修复 P0 + 关键 P1 后可提交** | 当前不建议直接提交/答辩 |

**一句话结论：项目已经“能做出来、能讲清楚”，但还没有“工程上收干净”。优先修 P0-1、P1-08、P1-09、P1-10、P1-11、P1-14，再处理状态/并发/超时的 P1，最后补集成测试与评测数据，即可达到本科毕设答辩的稳妥水平。**

---

## 附录 A：本轮未执行的操作

- 未修改任何业务代码、配置、数据库记录或迁移脚本。
- 未停止/重启任何服务，未执行 `wsl --shutdown`。
- 未执行真实故障注入，未调用 DeepSeek API。
- 未读取或输出 `.env` 中的密钥明文。
- 未执行 `mvn test`、`mvn package`、`npm run build`。
- 未执行 `git commit` / `git push`。

## 附录 B：关键文件索引

| 主题 | 文件 |
| --- | --- |
| 事件状态机 | `backend/src/main/java/com/microservice/rca/diagnosis/service/FaultEventService.java` |
| 证据采集 | `backend/src/main/java/com/microservice/rca/diagnosis/service/EvidenceService.java` |
| Agent 生命周期 | `backend/src/main/java/com/microservice/rca/agent/AgentDiagnosisService.java`、`AgentTaskRunner.java`、`AgentAsyncConfig.java` |
| Agent 编排 | `backend/src/main/java/com/microservice/rca/agent/DefaultAgentOrchestrator.java` |
| 工具校验 | `backend/src/main/java/com/microservice/rca/agent/AgentToolCallValidator.java` |
| 故障分类 | `backend/src/main/java/com/microservice/rca/experiment/ObservationClassifier.java` |
| 实验编排 | `backend/src/main/java/com/microservice/rca/experiment/ExperimentService.java` |
| 故障注入 | `services/inventory-service/src/main/java/com/microservice/rca/inventory/fault/FaultInjectionFilter.java`、`FaultInjectionService.java` |
| 库存 | `services/inventory-service/src/main/java/com/microservice/rca/inventory/inventory/InventoryStore.java` |
| 订单下游调用 | `services/order-service/src/main/java/com/microservice/rca/order/inventory/InventoryClient.java` |
| 前端错误处理 | `frontend/src/api/http.js` |
| 事件详情 | `frontend/src/views/FaultEventDetailView.vue` |
| 故障注入页面 | `frontend/src/views/FaultInjectionView.vue` |
| 概览/布局 | `frontend/src/views/OverviewView.vue`、`frontend/src/layouts/MainLayout.vue` |
| 数据库迁移 | `backend/src/main/resources/db/migration/V1__init_schema.sql`、`V2__fault_event_experiment_evidence.sql`、`V3__agent_diagnosis.sql` |
| 部署 | `infra/docker-compose.yml`、`frontend/vite.config.js`、`.env.example` |

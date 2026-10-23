# Day 6 第一批修复报告（A1、A2、C1、C2、C4）

- 执行日期：2026-10-24
- 修复范围：A1、A2、C1、C2、C4
- 未实施：Agent 任务生命周期（Phase B）、Flyway V4、数据库业务数据修改
- 验证方式：后端及两个微服务 `mvn clean test`，前端 `npm run build`

---

## 1. 问题根因与实际修复

### A1：下游业务 4xx 被当作系统故障

**根因**

`InventoryClient` 用同一个 `onStatus` 分支处理下游所有非 2xx 响应，统一包装为 `InventoryUnavailableException`；`GlobalExceptionHandler` 再将这类异常映射为 HTTP 502。后端实验观测因此把“未知 SKU、数量非法”等业务拒绝记录为 `DOWNSTREAM_HTTP_5XX`，产生虚假的微服务故障事件。

**实际修复**

1. `InventoryClient` 按下游状态码分开处理：
   - `409` → `InventoryRejectedException`（业务冲突，保持原有语义）；
   - `400 / 404 / 422` → 新增 `InventoryRequestRejectedException`，保留原始状态码和下游 `message`；
   - `>= 500` → `InventoryUnavailableException("HTTP_5XX")`；
   - 其他 4xx → `InventoryUnavailableException("HTTP_4XX")`。
2. 新增 `InventoryRequestRejectedException`，携带下游 HTTP 状态码。
3. `GlobalExceptionHandler` 新增处理器：按原始状态码返回（不再统一 502），并写入 `X-Downstream-Error-Type: HTTP_4XX`。
4. `ObservationClassifier` 增加防御性分支：即使收到 502 但 header 为 `HTTP_4XX`，也归类为 `REQUEST_REJECTED`，`faultObserved=false`，不创建故障事件。
5. 真实 HTTP 500（`HTTP_5XX`）和超时（`DOWNSTREAM_TIMEOUT`）的映射与故障记录逻辑未改动。

**结果**

- 未知 SKU（404）、非法数量（400）保持业务错误状态，不生成故障事件。
- 下游 500、读超时仍生成故障事件，原有探测能力保留。

### A2：DELAY 上限与库存预留幂等

**根因**

1. DELAY 允许的延迟上限高于订单服务 5 秒读超时，实验注入的延迟会让订单服务先超时，实验结论与预设故障类型自相矛盾。
2. 库存预留没有幂等控制，同一请求重试会重复扣减库存。

**实际修复**

1. DELAY 上限统一为 **4500 ms**：
   - `FaultInjectionProperties.maxDelayMs = 4500`；
   - `application.yml` 中 `EXPERIMENT_FAULT_INJECTION_MAX_DELAY_MS` 默认值 4500；
   - `.env.example` 同步并注明必须低于订单服务读超时；
   - 前端输入框上限与提示文案同步；
   - `README.md`、`docs/architecture/system-design.md` 同步描述。
2. 库存预留幂等（按 `orderId`）：
   - 确认 `orderId` 确实由 `OrderService` 生成、`InventoryClient` 放在预留请求中、`InventoryController` 接收并传入 `InventoryStore`，不是仅凭字段名假定；
   - `InventoryStore` 新增预留记录 Map，`reserve()` 在 `synchronized` 内先查幂等键：
     - 同一 `orderId` + 相同 SKU/数量 → 直接返回首次结果，不再扣减；
     - 同一 `orderId` + 不同 SKU/数量 → HTTP 409，不扣减；
     - 不同 `orderId` → 各自正常扣减；
   - `ReserveRequest.orderId` 限制最大 64 字符；
   - Javadoc 明确说明：**幂等记录保存在内存中，服务重启后丢失**。

**结果**

- 重复预留只扣一次库存；并发同一 `orderId` 只扣一次；并发不同订单不超卖、不出现负库存。
- 4500 ms 低于订单服务 5000 ms 读超时，默认实验不会再由延迟本身触发超时。

### C1：前端错误提示丢失后端 message

**根因**

Axios 响应拦截器直接透传 Axios 原始错误，页面大多展示 `error.message`，用户只能看到 `Request failed with status code 400`，后端统一响应中的中文 `message` 没有展示。

**实际修复**

`frontend/src/api/http.js` 新增 `normalizeError(error)`：

1. 优先提取后端统一响应中的 `message`（兼容字符串和对象形式，兼容 `error` 字段）。
2. 保留超时（`ECONNABORTED` / `ETIMEDOUT` / 超时文案）、网络不可达、请求取消、404、5xx、其他 HTTP 状态的兜底提示。
3. 成功响应路径不变，仍直接返回 `response`。

### C2：证据采集在工具全部失败时仍提示成功

**根因**

`FaultEventDetailView.vue` 的 `handleCollect` 只要请求没有抛异常就提示成功，没有根据返回结果中的工具执行状态判断。

**实际修复**

1. 根据 `result.tools` 统计成功/总数，区分四种状态：全部成功、部分成功、全部失败、无工具结果。
2. 全部成功 → success；部分成功 → warning；全部失败或无结果 → error，**不再提示成功**。
3. 证据区域标题和提示类型随实际状态变化，并显示“工具结果：X / Y 成功”。
4. 采集期间按钮 `disabled`，异常分支展示后端 `message`。

### C4：概览、侧边栏、页脚信息过时

**根因**

页面文案和版本号硬编码，未随实际实现更新，仍出现 “Day 2”“v0.2.0”“待开发”等描述。

**实际修复**

1. `OverviewView.vue`：Agent 工具调用、根因分析标记为已完成；人工确认/关闭标记为“部分完成”，并注明自动处置尚未实现；“Day 2 已落地”改为“当前已落地”；Flyway V2 更正为 V3；Agent 层描述改为真实的 DeepSeek 模型、工具白名单与证据约束。
2. `MainLayout.vue`：侧边栏副标题与页脚版本更新为 v0.3.0，描述改为“真实故障实验与 AI 诊断”。
3. `FaultInjectionView.vue`：去掉 “Day 2” 表述。
4. `PlaceholderView.vue`、`router/index.js`：诊断任务/工具调用入口由“待开发”改为引导到故障事件详情页，能力列表与已实现功能一致。
5. `frontend/package.json`、`package-lock.json`：版本 0.1.0 → 0.3.0。

---

## 2. 修改文件清单

### A1（commit `848df0d`，7 个文件）

- `services/order-service/src/main/java/com/microservice/rca/order/exception/InventoryRequestRejectedException.java`（新增）
- `services/order-service/src/main/java/com/microservice/rca/order/inventory/InventoryClient.java`
- `services/order-service/src/main/java/com/microservice/rca/order/common/GlobalExceptionHandler.java`
- `backend/src/main/java/com/microservice/rca/experiment/ObservationClassifier.java`
- `services/order-service/src/test/java/com/microservice/rca/order/inventory/InventoryClientTest.java`
- `services/order-service/src/test/java/com/microservice/rca/order/order/OrderControllerTest.java`
- `backend/src/test/java/com/microservice/rca/experiment/ObservationClassifierTest.java`

### A2（commit `6003627`，13 个文件）

- `services/inventory-service/src/main/java/com/microservice/rca/inventory/inventory/InventoryStore.java`
- `services/inventory-service/src/main/java/com/microservice/rca/inventory/inventory/InventoryController.java`
- `services/inventory-service/src/main/java/com/microservice/rca/inventory/inventory/ReserveRequest.java`
- `services/inventory-service/src/main/java/com/microservice/rca/inventory/fault/FaultInjectionProperties.java`
- `services/inventory-service/src/main/resources/application.yml`
- `services/inventory-service/src/test/java/com/microservice/rca/inventory/inventory/InventoryStoreTest.java`（新增）
- `services/inventory-service/src/test/java/com/microservice/rca/inventory/inventory/InventoryControllerTest.java`
- `services/inventory-service/src/test/java/com/microservice/rca/inventory/fault/FaultInjectionServiceTest.java`
- `services/order-service/src/test/java/com/microservice/rca/order/inventory/InventoryClientTest.java`
- `frontend/src/views/FaultInjectionView.vue`
- `.env.example`
- `README.md`
- `docs/architecture/system-design.md`

### C1 / C2 / C4（commit `7962741`，9 个文件）

- `frontend/src/api/http.js`
- `frontend/src/views/FaultEventDetailView.vue`
- `frontend/src/views/OverviewView.vue`
- `frontend/src/layouts/MainLayout.vue`
- `frontend/src/views/FaultInjectionView.vue`
- `frontend/src/views/PlaceholderView.vue`
- `frontend/src/router/index.js`
- `frontend/package.json`
- `frontend/package-lock.json`

### 本报告

- `docs/plans/day6-first-batch-fix-report.md`（新增）

---

## 3. 测试与构建结果（真实输出）

全部使用 `mvn clean test`，即清理后重新编译执行，避免历史 surefire 报告干扰计数。

| 模块 | 结果 | 失败 | 错误 | 跳过 | 结论 |
| --- | --- | --- | --- | --- | --- |
| `backend` | 65 | 0 | 0 | 0 | BUILD SUCCESS |
| `services/inventory-service` | 26 | 0 | 0 | 0 | BUILD SUCCESS |
| `services/order-service` | 12 | 0 | 0 | 0 | BUILD SUCCESS |

前端构建：`npm run build`（Windows Node），1669 个模块转换完成，exit 0；存在既有的 chunk 体积警告（主包约 1.13 MB），不影响构建成功。

### A1 新增/补充测试

- `ObservationClassifierTest.missingSkuIsRequestRejectedInsteadOfSystemFault`
- `ObservationClassifierTest.downstream4xxWrappedAs502IsStillNotAFault`
- `InventoryClientTest.preservesDownstream404AsRequestRejected`
- `InventoryClientTest.preservesDownstream400AsRequestRejected`
- `OrderControllerTest.preservesNotFoundWhenSkuDoesNotExist`
- `OrderControllerTest.preservesBadRequestWhenDownstreamRejectsRequest`
- `InventoryClientTest` 补充接近读超时但成功的用例，确认超时边界不误判。

### A2 新增/补充测试

`InventoryStoreTest`（6 个）：

- `normalReservationDecrementsStockOnce`
- `duplicateOrderIdReturnsOriginalResultWithoutSecondDecrement`
- `sameOrderIdWithDifferentPayloadIsRejectedWithoutDecrement`
- `differentOrderIdsAreNotMerged`
- `concurrentReservationsWithSameOrderIdOnlyDecrementOnce`
- `concurrentReservationsWithDifferentOrderIdsDoNotOversell`

补充：

- `InventoryControllerTest.duplicateReservationWithSameOrderIdDoesNotDecrementTwice`
- `InventoryControllerTest.rejectsOrderIdLongerThanSixtyFourCharacters`
- `FaultInjectionServiceTest.defaultDelayUpperBoundIsBelowOrderServiceReadTimeout`
- `InventoryClientTest.succeedsWhenResponseArrivesBeforeReadTimeout`

### C1 / C2 / C4

- 前端未配置单元测试框架，本轮未新增自动化前端测试，仅执行生产构建验证。
- 未运行浏览器端人工走查（未启动/重启前端服务）。

---

## 4. Git 提交

| 阶段 | Commit | 说明 |
| --- | --- | --- |
| A1 | `848df0d` | 修复下游业务 4xx 被误映射为 502 的问题 |
| A2 | `6003627` | 限制 DELAY 上限并实现库存预留按订单号幂等 |
| C1/C2/C4 | `7962741` | 修复前端错误提示、证据采集状态与过时页面信息 |

- 仅为本地提交，未 push，未创建远程仓库。
- 提交身份使用本项目约定的 `Betty-Queena`。

---

## 5. 剩余风险与限制

1. **幂等记录仅存在内存**：库存服务重启后丢失，重启前的重复请求在重启后可能再次扣减。已写入代码注释和 README。
2. **重试结算不会复用 orderId**：当前 `orderId` 由每次 checkout 新建，因此“客户端重试创建订单”会生成新 `orderId`，库存层幂等无法覆盖该场景。彻底解决需要在 checkout 接口引入客户端幂等键（对应计划中的 A3），本轮未实施。
3. **运行中的服务尚未重启**：已启动的库存/订单服务仍运行旧代码，4500 ms 上限与 4xx 映射需重启后生效。本轮按要求未停止或重启任何服务。
4. **4xx 分类依赖状态码与 header 约定**：若未来新增下游服务或改变错误响应格式，需要同步维护 `X-Downstream-Error-Type` 约定。
5. **前端无自动化测试**：C1/C2/C4 仅通过构建和代码检查验证，交互行为需在演示前手工走查。
6. **未覆盖 Phase B / V4**：并发启动诊断任务、重启恢复、异步拒绝、乐观锁、动态超时等 Agent 生命周期问题仍存在。
7. **前端包体积警告**：主包超过 500 kB，为既有问题，不影响功能。

---

## 6. 下一批（Phase B）建议

建议下一批只做 Agent 任务可靠性，仍坚持“一个阶段一个提交”：

1. **B1 活跃任务定义与并发启动**：按 `triggerSource` 过滤活跃任务；`retry` 前检查；并发启动只创建一个任务。先不加 V4 也可用查询 + 409 兜底。
2. **B2 异步拒绝与首次保存保护**：捕获线程池 `TaskRejectedException` 并落终态；`AgentTaskRunner.run()` 首次保存异常不能留下无主任务。
3. **B3 启动恢复与证据 finally**：新增启动恢复组件，将残留 PENDING/RUNNING 置为失败；`EvidenceService` 用 `finally` 保证工具调用记录落库。
4. **B4 状态覆盖与乐观锁**：终态先保存、事件条件恢复、`@Version` 与 409；同时引入 Flyway V4（唯一索引 + version 列）。
5. **B5 动态超时**：模型/工具/整体超时可配置，避免慢调用拖垮任务。
6. **B6 `resolvedAt` 与手工 DIAGNOSING**：修正状态机与前端按钮。

建议先执行 B1–B3（不依赖 V4），验证通过后再执行 B4 的数据库迁移，降低回滚成本。

---

## 7. 约束遵守情况

- 未修改 Agent 任务生命周期。
- 未实施 Flyway V4，未修改数据库业务数据。
- 未新增依赖，未引入新架构。
- 未调用 DeepSeek API。
- 未读取或输出 `.env` 密钥。
- 未执行 `git push`。
- 未停止或重启任何服务。
- 未伪造测试结果；以上计数均来自本轮 `mvn clean test` 的 surefire 报告。

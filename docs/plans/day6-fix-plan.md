# Day 6 第一阶段：P1 核心修复方案

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-24 |
| 范围 | Git 基线；P1-01～P1-07 核心修复方案；后续 Phase A–D 计划 |
| 约束 | 本轮只做 Git 基线与方案设计；**不修改业务代码、配置、数据库或迁移脚本**；不执行故障注入；不调用 DeepSeek；不读取/输出 `.env` 密钥 |
| 依据 | `docs/plans/day6-project-review.md` 与当前真实代码 |

---

## 0. 结论与总体策略

### 0.1 修复原则

1. **最小侵入**：优先在现有 `FaultEventService`、`AgentDiagnosisService`、`AgentTaskRunner`、`InventoryStore` 内完成，不引入消息队列、Redis、工作流引擎或新的微服务。
2. **先保证“不会卡死、不会写错”，再考虑“更优雅”**：任务终态、事件状态、库存扣减都是用户可见数据，优先保证一致性。
3. **数据库变更只做一次**：把 `@Version` 与活跃任务唯一索引合并到 Flyway V4，避免多次迁移。
4. **测试跟随每个阶段**：不把测试全部堆到最后；Phase D 负责集成测试与最终验收，不替代单元测试。
5. **不伪造结果**：所有“验证通过”必须来自真实命令输出；无法执行的路径在文档中标注“静态验证/待手工验证”。

### 0.2 问题分组合并

| 组 | 包含问题 | 合并原因 |
| --- | --- | --- |
| A. 业务语义 | P1-01 DELAY/幂等、P1-02 4xx 映射 | 都影响“实验请求的真实结果”，改动集中在 order/inventory 与分类器，互不依赖，可同一阶段完成 |
| B. 任务生命周期 | P1-03 证据任务卡死、P1-04 异步任务卡死/重启恢复、P1-05 重复启动 | 都围绕 `diagnosis_task` 的活跃状态、唯一性、恢复与拒绝处理，适合共用一套守卫/恢复组件 |
| C. 终态一致性 | P1-06 终态持久化、人工状态覆盖；P2-01 `resolvedAt`、P2-02 乐观锁、P2-03 手工 DIAGNOSING | 都涉及 `fault_event` 状态机与并发写，合并到同一次 V4 迁移与同一组测试 |
| D. 超时 | P1-07 模型/工具整体超时 | 独立改动，但依赖 Agent 编排接口，放在任务生命周期之后 |
| E. 前端/演示 | P1-08～P1-11、P1-14、P2 前端项 | 不改后端协议，集中一次前端修复与文案更新 |

### 0.3 不建议做的事

- 不引入 Redis/消息队列来做任务状态；当前单机 JVM + PostgreSQL 足够。
- 不把库存改成数据库持久化；当前内存库存是明确的实验设计，改为数据库会扩大范围。
- 不为“超时但服务端成功”强行制造确定性；应通过限制实验参数 + 幂等键 + 文档说明来处理。
- 不引入 Spring Security 全量框架只为本地演示；Phase C/D 先用绑定 `127.0.0.1` + 简单 Token 的方案。
- 不为通过测试而放宽断言、Mock 真实模型结果或跳过失败用例。

---

## 1. P1-01 DELAY 超时、库存扣减与幂等性

### 1.1 当前代码事实

- `services/inventory-service/src/main/java/com/microservice/rca/inventory/fault/FaultInjectionFilter.java:54-63`
  - DELAY 分支先 `Thread.sleep(state.delayMs())`，再 `filterChain.doFilter(...)`。
- `services/order-service/src/main/resources/application.yml:15`
  - `inventory.read-timeout-ms` 默认 5000。
- `services/inventory-service/src/main/resources/application.yml:28`
  - `max-delay-ms` 默认 30000。
- `services/order-service/src/main/java/com/microservice/rca/order/inventory/InventoryClient.java:70-74`
  - 读超时抛 `InventoryUnavailableException(..., "TIMEOUT")`，最终 502。
- `services/inventory-service/src/main/java/com/microservice/rca/inventory/inventory/InventoryStore.java:39-51`
  - `reserve(sku, quantity)` 在 `synchronized(product)` 内扣减，但不接收 `orderId`。
- `services/inventory-service/src/main/java/com/microservice/rca/inventory/inventory/InventoryController.java:37-46`
  - 只把 `quantity` 传给 `reserve`，`orderId` 仅回显。
- `services/order-service/src/main/java/com/microservice/rca/order/order/OrderService.java`
  - 每次 checkout 生成新的随机 `orderId`，客户端重试会得到新 `orderId`。

### 1.2 根因判断

这是两个独立但叠加的问题：

1. **超时语义**：延迟发生在业务之前；客户端超时断开不会中断服务端线程，服务端仍会扣库存。这是分布式系统的真实歧义，但当前实验平台没有幂等/补偿，导致“502 但扣库存 + 重试再扣”。
2. **缺少幂等**：`orderId` 已经跨服务传递，但没有被库存服务用作幂等键；同一订单的重复 reserve 会重复扣减。

### 1.3 最小修复方案

#### A. 约束实验参数，避免“支持的配置”内出现超时（必做）

- 将 `EXPERIMENT_FAULT_INJECTION_MAX_DELAY_MS` 默认值从 `30000` 调整为 `4500`（小于订单读超时 5000），或在 `FaultInjectionService.validateDelay` 中增加与订单读超时的关系校验。
- 更稳妥：在 `.env.example`、README、演示脚本中明确“DELAY 必须小于订单服务 `INVENTORY_READ_TIMEOUT_MS`”；`max-delay-ms` 默认 4500。
- 不改变 DELAY 的业务语义：仍然先延迟再执行，只是保证在受支持配置下客户端不会先超时。

> 取舍：这会限制“模拟超时”的能力。若论文需要演示“下游超时”，应使用独立的 `TIMEOUT` 故障类型或把订单读超时调小，而不是让 DELAY 与读超时互相踩踏。当前阶段不新增故障类型。

#### B. 库存服务按 `orderId` 做幂等（必做）

- `InventoryStore` 增加：
  ```java
  private final Map<String, ReservationRecord> reservations = new HashMap<>();
  ```
  `ReservationRecord` 保存 `sku`、`quantity`、`remaining`、`reservedAt`。
- 新增方法：
  ```java
  Reservation reserve(String sku, int quantity, String orderId)
  ```
  逻辑：
  1. `orderId` 非空校验（`@NotBlank @Size(max=64)`）。
  2. 以 `orderId` 为键查已处理记录：
     - 存在且 `sku/quantity` 一致：直接返回原结果，不扣减；
     - 存在但 `sku/quantity` 不一致：抛 409 `ORDER_ID_CONFLICT`；
  3. 不存在：在 `synchronized(product)` 内检查库存、扣减、写入 `reservations`。
- 并发保护：
  - `reservations` 的读写必须与 `available` 扣减在同一临界区，或使用 `ConcurrentHashMap` + 每个商品锁。
  - 最小实现：在 `InventoryStore` 上使用一个 `synchronized (reservations)` 包裹“查重 + 扣减 + 写入”，再进入 `synchronized(product)` 扣库存；注意锁顺序固定，避免死锁。
  - 更简单：整个 `reserve` 方法 `synchronized`（当前是实验服务，吞吐不是关键），避免复杂锁顺序。**推荐这个最小方案**。
- `InventoryController.reserve` 传入 `request.orderId()`；`ReserveRequest` 增加校验注解。
- `ReservationResponse` 可增加 `duplicate` 布尔字段（可选），前端/日志可区分重复请求。

#### C. 订单侧幂等键（建议同批做，改动很小）

- `services/order-service/.../order/CheckoutRequest` 增加可选 `orderId` 或 `idempotencyKey` 字段：
  - 有值：使用调用方提供的键；
  - 无值：保持现有随机生成。
- 管理后端 `CheckoutRequest` 同步增加可选字段；`ExperimentService.runCheckout` 可以把本次实验的 `requestId` 作为幂等键传入，这样实验重试/补偿可以复用。
- 前端“重试”按钮若要真正幂等，需要在同一实验执行上下文中复用同一个键；当前演示不强制，先把后端能力留好。

#### D. 失败补偿（本轮不做，写入未来工作）

- reservation TTL + `POST /api/inventory/{sku}/release` + 订单失败补偿。
- 这是生产级方案；本科实验平台用“限制延迟 + 幂等键 + 文档说明”即可。

### 1.4 涉及文件

| 文件 | 改动 |
| --- | --- |
| `services/inventory-service/.../inventory/InventoryStore.java` | 增加 `orderId` 幂等 Map、`reserve(sku, quantity, orderId)`、重复/冲突处理 |
| `services/inventory-service/.../inventory/InventoryController.java` | 传递 `orderId` |
| `services/inventory-service/.../inventory/ReserveRequest.java` | `orderId` 增加 `@NotBlank @Size(max=64)`（若允许空则改为条件校验） |
| `services/inventory-service/.../inventory/ReservationResponse.java` | 可选增加 `duplicate` |
| `services/inventory-service/.../fault/FaultInjectionService.java` / `application.yml` / `.env.example` | `max-delay-ms` 默认 4500 或增加与读超时的校验 |
| `services/order-service/.../order/CheckoutRequest.java`、`OrderService.java`、`InventoryClient.java` | 可选 `idempotencyKey` 透传 |
| `backend/.../experiment/api/CheckoutRequest.java`、`ExperimentService.java` | 可选透传幂等键（若做 C） |

### 1.5 数据库变更

- **无**。库存是内存数据；幂等记录也在内存中，重启后丢失。
- 文档必须标注：**幂等仅在同一库存服务进程生命周期内有效**；重启后重复请求仍可能重复扣减。这是当前内存库存设计的限制，不伪装成持久幂等。

### 1.6 测试

| 测试 | 断言 |
| --- | --- |
| `InventoryStoreTest.reserveTwiceWithSameOrderIdOnlyDecrementsOnce` | 同一 `orderId` 两次 reserve，`available` 只减一次，第二次返回相同 `remaining` |
| `InventoryStoreTest.rejectsSameOrderIdWithDifferentPayload` | 同一 `orderId` 不同 sku/quantity → 409 |
| `InventoryStoreTest.concurrentReservationsWithDifferentOrderIds` | 多线程不同 `orderId`，库存不为负，扣减总数正确 |
| `InventoryControllerTest` | `orderId` 缺失/超长 → 400；重复请求 → 200 且不重复扣减 |
| `FaultInjectionFilterTest`（新增） | DELAY 分支设置 header、执行一次 chain；HTTP_500 不执行 chain；健康/观测/实验路径不进入过滤器 |
| 集成测试（Phase D） | `delayMs=6000` 在默认配置下不再被允许（400）；`delayMs=3500` 正常成功且只扣一次 |

### 1.7 验收标准

- 默认配置下，合法 DELAY 不会触发订单 5 s 读超时。
- 同一 `orderId` 重复 reserve 只扣一次；不同 payload 返回 409。
- 不同 `orderId` 并发 reserve 不超卖、不为负。
- 文档明确“内存幂等、重启失效”的边界。

---

## 2. P1-02 下游 4xx 被错误映射为 502

### 2.1 当前代码事实

- `services/order-service/.../inventory/InventoryClient.java:61-69`：除 409 外的所有 4xx 都抛 `InventoryUnavailableException(..., "HTTP_4XX")`。
- `services/order-service/.../common/GlobalExceptionHandler.java:34-40`：`InventoryUnavailableException` 统一 502。
- `backend/.../experiment/ObservationClassifier.java:38-52`：502/503/504 分支没有 `HTTP_4XX`，落入 `default` → `DOWNSTREAM_ERROR`、`faultObserved=true`。
- `backend/.../experiment/ExperimentService.java:146-158`：只要 `faultObserved()` 就创建事件。
- `backend/.../experiment/ObservationClassifier.java:58-61`：4xx 本应 `REQUEST_REJECTED`、不记系统故障。

### 2.2 最小修复方案

#### A. 订单服务保留 4xx 语义（主修复）

- 新增异常 `InventoryRequestRejectedException`（或扩展现有异常，携带 `HttpStatus`）：
  ```java
  public class InventoryRequestRejectedException extends RuntimeException {
      private final HttpStatus status; // 400/404/422 等
  }
  ```
- `InventoryClient` 的 `RestClientResponseException` 分支：
  - 409 → 现有 `InventoryRejectedException`；
  - 400/404/422 → `InventoryRequestRejectedException(status, message)`；
  - 401/403/405/415 → 仍可 502 `HTTP_4XX`（配置/协议问题），或统一保留 4xx。建议只保留 400/404/422，其余仍 502，避免把下游鉴权问题伪装成客户端错误。
- `GlobalExceptionHandler` 增加 handler：返回下游原状态码（400/404/422）和 `ApiResponse.error(status, message)`；不写 `X-Downstream-Error-Type` 或写 `HTTP_4XX` 仅供观测。
- 这样 `ObservationClassifier` 看到的是 4xx，走现有 `status >= 400` 分支 → `REQUEST_REJECTED`、`faultObserved=false`，不会创建事件。

#### B. 分类器增加防御性分支（建议同批）

- 在 `ObservationClassifier` 的 502/503/504 分支中增加：
  ```java
  case "HTTP_4XX" -> new Observation("REQUEST_REJECTED", false, FaultSeverity.INFO,
      "订单服务拒绝请求（下游 HTTP 4xx），不记为系统故障");
  ```
- 即使未来订单服务仍把某个 4xx 包装成 502，也不会生成假故障事件。

#### C. 测试

| 测试 | 断言 |
| --- | --- |
| `InventoryClientTest.inventory404BecomesRequestRejected` | 404 → `InventoryRequestRejectedException`，状态 404 |
| `InventoryClientTest.inventory400BecomesRequestRejected` | 400 → 400 |
| `OrderControllerTest` / `GlobalExceptionHandlerTest` | 未知 SKU 返回 404（不再是 502） |
| `ObservationClassifierTest.http4xxIsRequestRejected` | 404/400 → `REQUEST_REJECTED`、`faultObserved=false` |
| `ObservationClassifierTest.http4xxHeaderOn502IsNotFault` | 502 + `HTTP_4XX` → 非故障 |
| `ExperimentServiceTest`（可选，需可测试性改造） | 未知 SKU 不调用 `createFromExperiment` |

### 2.3 涉及文件

- `services/order-service/.../inventory/InventoryClient.java`
- `services/order-service/.../exception/InventoryRequestRejectedException.java`（新增）
- `services/order-service/.../common/GlobalExceptionHandler.java`
- `backend/.../experiment/ObservationClassifier.java`
- 对应测试类

### 2.4 数据库变更

- 无。

### 2.5 验收标准

- `POST /api/orders/checkout {"sku":"UNKNOWN"}` 返回 404（或 400），不再是 502。
- 管理后端执行未知 SKU 的对照请求后，`fault_event` 不新增记录。
- 409 库存不足仍为 409，不生成故障事件。

---

## 3. P1-03/P1-04/P1-05 Agent 任务卡死、重启恢复、重复启动

这三个问题共用一个事实：**`diagnosis_task` 的“活跃”定义太宽、没有唯一性约束、没有恢复机制**。建议合并为 Phase B 的“任务生命周期守卫”。

### 3.1 当前代码事实

- `AgentDiagnosisService.java:50-60`：先 `existsByFaultEventIdAndStatusIn` 再 `createTask`，非原子；查询不区分 `triggerSource`。
- `AgentDiagnosisService.java:62-74`：`retry` 只检查原任务，不检查同一事件是否已有其他活跃任务。
- `AgentTaskRunner.java:52-64`：`findById` 与首次 `save(RUNNING)` 在 `try` 外。
- `AgentAsyncConfig.java:16-27`：核心 1、最大 2、队列 20，默认拒绝策略。
- `EvidenceService.java:74-80,98-131`：创建 `MANUAL_EVIDENCE` 的 `RUNNING` 任务；无 `try/finally`；先 SUCCEEDED 再写证据。
- `FaultEventService.java:240-249`：`DIAGNOSING` 没有回到 `OPEN` 的公开路径。
- `DiagnosisTaskRepository.java:14`：只有普通 `exists` 查询。
- Flyway V1/V3：没有活跃任务唯一索引。

### 3.2 修复设计（建议一个 Phase B 提交）

#### B1. 明确“活跃 Agent 任务”的定义

- 只有 `triggerSource in ('AGENT','AGENT_RETRY')` 且 `status in ('PENDING','RUNNING')` 才算阻塞 AI 诊断的活跃任务。
- `MANUAL_EVIDENCE` 任务不应阻塞 AI 诊断；它可以与 AI 并发（都是只读工具），但同一事件的手工证据采集应互斥（可选）。

新增 repository 方法（二选一）：

```java
boolean existsByFaultEventIdAndTriggerSourceInAndStatusIn(
        UUID eventId, Collection<String> sources, Collection<DiagnosisStatus> statuses);
```

或使用 `@Query`：

```java
@Query("""
   select count(t) > 0 from DiagnosisTask t
    where t.faultEventId = :eventId
      and t.triggerSource in :sources
      and t.status in :statuses
""")
boolean existsActiveAgentTask(...);
```

- `startForEvent` / `retry` 改用该方法。
- `retry` 增加“同一事件是否已有活跃 Agent 任务”的检查。

#### B2. 数据库唯一性（Flyway V4）

```sql
CREATE UNIQUE INDEX uk_diagnosis_task_active_agent
    ON diagnosis_task (fault_event_id)
    WHERE status IN ('PENDING', 'RUNNING')
      AND trigger_source IN ('AGENT', 'AGENT_RETRY');
```

- PostgreSQL 部分唯一索引直接解决 TOCTOU：两个并发请求最多一个成功，另一个抛 `DataIntegrityViolationException`。
- 迁移前检查重复数据：
  ```sql
  SELECT fault_event_id, count(*)
    FROM diagnosis_task
   WHERE status IN ('PENDING','RUNNING')
     AND trigger_source IN ('AGENT','AGENT_RETRY')
   GROUP BY fault_event_id
  HAVING count(*) > 1;
  ```
  若有重复，先人工保留最新一条、其余标记 FAILED；不要用迁移脚本自动删数据。
- 注意：该索引不包含 `MANUAL_EVIDENCE`，因此不会阻止“手工采集 + AI 诊断”并行。

#### B3. 捕获唯一约束冲突并返回 409

- `startForEvent` / `retry` 在 `createTask` 外层捕获 `DataIntegrityViolationException`：
  ```java
  try {
      task = createTask(...);
  } catch (DataIntegrityViolationException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "该事件已有诊断任务正在执行，请等待完成或查询现有任务");
  }
  ```
- 注意：当前 `createTask` 没有 `@Transactional`，repository `save` 会立即提交并触发约束；如果未来加了外层事务，异常可能在提交时抛出，需要在外层捕获。
- `retry` 同样处理。

#### B4. 异步拒绝处理

- `AgentDiagnosisService` 注入 `TaskRejectedException` 处理：
  ```java
  try {
      taskRunner.run(task.getId());
  } catch (TaskRejectedException ex) {
      failTaskBeforeStart(task, "诊断线程池已满，任务未能启动");
      restoreEventIfDiagnosing(event.getId());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "诊断任务队列已满，请稍后重试");
  }
  ```
- `failTaskBeforeStart` 可直接使用 `DiagnosisTaskRepository` 设置 `FAILED`、`failureReason`、`completedAt`；不必依赖 `AgentTaskRunner`。
- 这一步保证：**即使异步提交失败，任务和事件都有明确终态**。

#### B5. `AgentTaskRunner.run` 的前置操作纳入异常保护

- 把 `findById`、`setStatus(RUNNING)`、`save` 移入 `try`，或至少用 `try` 包住首次 `save`：
  ```java
  DiagnosisTask task = taskRepository.findById(taskId).orElse(null);
  if (task == null) { log.warn(...); return; }
  try {
      task.setStatus(RUNNING);
      ...
      taskRepository.save(task);
      // 后续编排
  } catch (Exception ex) {
      fail(task, ...);
  }
  ```
- 如果首次 `save` 失败，`fail(task)` 也可能失败；记录日志并让启动恢复兜底。

#### B6. 启动恢复（`ApplicationReadyEvent`）

新增 `DiagnosisTaskRecovery`（`@Component`）：

```java
@EventListener(ApplicationReadyEvent.class)
public void recoverOrphanTasks() {
    Instant startup = Instant.now();
    List<DiagnosisTask> orphans = taskRepository.findByStatusIn(
        List.of(PENDING, RUNNING));
    for (DiagnosisTask task : orphans) {
        // 只恢复本进程启动前创建的任务，避免误伤启动期间新建的任务
        if (task.getCreatedAt() != null && task.getCreatedAt().isAfter(startup)) continue;
        task.setStatus(FAILED);
        task.setFailureReason("服务重启导致任务中断");
        task.setCompletedAt(Instant.now());
        taskRepository.save(task);
        restoreEventIfDiagnosing(task.getFaultEventId());
    }
}
```

- `findByStatusIn` 加到 `DiagnosisTaskRepository`。
- 恢复顺序：先标记任务 FAILED（各自 repository 事务），再恢复事件；不要用一个长事务包住所有任务。
- `restoreEventIfDiagnosing` 使用条件更新（见第 5 节），只把 `DIAGNOSING` 改回 `OPEN`，不覆盖 `RESOLVED/CLOSED`。
- 如果任务有 `MANUAL_EVIDENCE` 来源，也一并恢复；它不应该永久阻塞。
- 启动恢复不删除任何记录，只改状态和失败原因，保留审计。

#### B7. `EvidenceService` 的 `try/finally`

- `collect()` 结构改为：
  ```java
  DiagnosisTask task = createRunningTask(...);
  try {
      logs = invoke(...);
      metrics = invoke(...);
      boolean anySuccess = ...;
      saveEvidence(...);
      markTask(task, anySuccess ? SUCCEEDED : FAILED, null);
      return view;
  } catch (Exception ex) {
      markTask(task, FAILED, truncate(ex.getMessage()));
      throw ...; // 或返回 success=false 的 view
  }
  ```
- 关键：**任务终态与证据写入的顺序**。建议先写证据，再标记任务 SUCCEEDED；如果证据写入失败，任务标记 FAILED。这样不会出现“SUCCEEDED 但无证据”。
- 如果工具全部失败，任务仍可 `FAILED`，但证据快照可以保留（记录失败原因），供审计。
- `EvidenceService` 不需要 `@Transactional` 包住网络调用；每个 repository 调用独立事务。

#### B8. 可选：取消接口

- 当前 `DiagnosisStatus.CANCELLED` 未使用。Phase B 暂不实现取消，避免扩大范围；启动恢复 + 失败兜底已能解决卡死。
- 如果时间允许，增加 `POST /api/diagnosis/tasks/{id}/cancel`，只允许 PENDING/RUNNING，设置 `CANCELLED` 并恢复事件。该接口需要额外测试，不列入第一批。

### 3.3 涉及文件

| 文件 | 改动 |
| --- | --- |
| `backend/.../agent/AgentDiagnosisService.java` | 活跃任务查询按 triggerSource；捕获 `TaskRejectedException`/`DataIntegrityViolationException`；retry 增加活跃检查 |
| `backend/.../agent/AgentTaskRunner.java` | 前置保存纳入 try；终态保存顺序调整（见第 5 节） |
| `backend/.../agent/DiagnosisTaskRecovery.java`（新增） | 启动恢复 |
| `backend/.../diagnosis/repository/DiagnosisTaskRepository.java` | 新增 `findByStatusIn`、活跃 Agent 查询 |
| `backend/.../diagnosis/service/EvidenceService.java` | `try/finally`、先证据后任务终态 |
| `backend/.../diagnosis/domain/DiagnosisTask.java` | 可选 `@Version`（见第 5 节） |
| `backend/src/main/resources/db/migration/V4__task_lifecycle_and_version.sql` | 唯一索引 + version 列 |

### 3.4 数据库变更

- V4 迁移：
  - 活跃 Agent 任务部分唯一索引（见 B2）。
  - `fault_event.version`、`diagnosis_task.version`（与第 5 节合并）。
- 迁移前检查重复活跃任务；迁移脚本不自动删除数据。

### 3.5 测试

| 测试 | 断言 |
| --- | --- |
| `AgentDiagnosisServiceTest.startRejectsWhenActiveAgentTaskExists` | 已有 AGENT RUNNING → 409 |
| `AgentDiagnosisServiceTest.startAllowsWhenOnlyManualEvidenceRunning` | 只有 MANUAL_EVIDENCE RUNNING → 允许启动 AI |
| `AgentDiagnosisServiceTest.retryRejectsWhenAnotherAgentTaskActive` | 同一事件其他 AGENT 活跃 → 409 |
| `AgentDiagnosisServiceTest.marksFailedWhenExecutorRejects` | `taskRunner.run` 抛 `TaskRejectedException` → 任务 FAILED、事件 OPEN、503 |
| `AgentDiagnosisServiceTest.concurrentStartCreatesOnlyOneTask`（集成/并发） | 两线程启动，只有一个成功，另一个 409 |
| `DiagnosisTaskRecoveryTest`（新增） | PENDING/RUNNING 孤儿任务 → FAILED；对应 DIAGNOSING 事件 → OPEN；RESOLVED 不被改 |
| `EvidenceServiceTest.collectMarksTaskFailedWhenToolThrows` | 工具异常 → 任务 FAILED、有失败原因、不产生假成功 |
| `EvidenceServiceTest.collectWritesEvidenceBeforeSucceeded` | 证据写入失败 → 任务 FAILED |

### 3.6 验收标准

- 手工证据采集运行中，AI 诊断仍可启动（如果设计允许并行）。
- 同一事件最多一个活跃 Agent 任务；并发启动一个成功、一个 409。
- 模拟进程重启后，所有遗留 PENDING/RUNNING 任务变为 FAILED，相关事件回到 OPEN。
- 线程池拒绝时，任务不会停在 PENDING。
- 证据采集异常时，任务不会停在 RUNNING。

---

## 4. P1-06 诊断终态持久化非原子与人工状态覆盖

### 4.1 当前代码事实

- `AgentTaskRunner.java:87-127`：`applyResult` 先改任务字段，再 `restoreEventAfterDiagnosis(task)`，最后 `taskRepository.save(task)`；无事务。
- `AgentTaskRunner.java:129-152`：`fail()` 先恢复事件，再保存任务。
- `AgentTaskRunner.java:159-166`：恢复事件是读-判断-写，无锁/版本。
- `FaultEvent.java:18-24`、`DiagnosisTask.java:19-24`：没有 `@Version`。
- `FaultEventService.java:146-162`：人工状态更新也是读-改-写，无版本。

### 4.2 核心风险

1. **成功被覆盖**：`applyResult` 已经设置 SUCCEEDED/报告，但 `restoreEventAfterDiagnosis` 抛异常 → `run()` 的 catch 调用 `fail()` → 任务变 FAILED、报告清空。
2. **任务停在 RUNNING**：`fail()` 保存任务失败，或 `run()` 首次保存失败。
3. **人工状态被覆盖**：后台恢复读到旧的 `DIAGNOSING`，覆盖人工刚写入的 `RESOLVED/CLOSED`。

### 4.3 最小修复方案

#### C1. 先落任务终态，再恢复事件，恢复失败不影响任务

- `applyResult`：
  1. 计算所有任务字段（状态、结果、报告、trace、completedAt）。
  2. `taskRepository.save(task)` —— 这是单次 repository 事务，任务终态立即提交。
  3. `try { restoreEventAfterDiagnosis(task); } catch (Exception ex) { log.warn(...); }` —— 事件恢复失败只记录，不再调用 `fail()`，不覆盖已成功的任务。
- `fail`：
  1. 设置 FAILED 字段。
  2. `taskRepository.save(task)`。
  3. 同样 `try/catch` 恢复事件。
- 这样即使事件恢复失败，任务不会从 SUCCEEDED 变成 FAILED；事件可能短暂停留 DIAGNOSING，由启动恢复（B6）或下一次操作修复。
- 代价：任务终态与事件状态不再强一致。这是有意的取舍：**优先保证诊断结果不被覆盖**；事件状态是生命周期，允许最终一致。

#### C2. 事件恢复改为条件更新，禁止覆盖人工状态

在 `FaultEventRepository` 增加：

```java
@Modifying
@Transactional
@Query("""
   update FaultEvent e
      set e.status = com.microservice.rca.diagnosis.domain.FaultEventStatus.OPEN,
          e.updatedAt = :now,
          e.version = e.version + 1
    where e.id = :eventId
      and e.status = com.microservice.rca.diagnosis.domain.FaultEventStatus.DIAGNOSING
""")
int restoreOpenIfDiagnosing(@Param("eventId") UUID eventId, @Param("now") Instant now);
```

- 只有当前状态确实是 `DIAGNOSING` 才更新；`RESOLVED/CLOSED` 不会被覆盖。
- 返回 0 表示人工已改状态，直接忽略，不报错。
- 不加载实体，避免读-改-写竞态。
- `updatedAt` 手动设置，因为 bulk update 不触发 `@UpdateTimestamp`；`version` 手动 +1。

#### C3. 增加 `@Version` 与 Flyway V4

```sql
ALTER TABLE fault_event ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE diagnosis_task ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

- 实体增加：
  ```java
  @Version
  private long version;
  ```
- 保护人工 `updateStatus` 与其他并发写；并发冲突时 Hibernate 抛 `ObjectOptimisticLockingFailureException`。
- `GlobalExceptionHandler` 增加：
  ```java
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(...) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(ApiResponse.error(409, "数据已被其他操作修改，请刷新后重试"));
  }
  ```
- 注意：`FaultEventService.updateStatus` 的 `@Transactional` 内加载实体，提交时版本冲突会抛异常；handler 能捕获。
- `saveEvidence` 也可能因版本冲突 409；证据是单快照，建议前端提示“请刷新后重试”，或后续改为追加表。当前阶段接受 409，不静默覆盖。

#### C4. 同时修复 `resolvedAt` 与手工 DIAGNOSING（P2-01/P2-03，低成本）

- `FaultEventService.updateStatus`：
  ```java
  if (target == RESOLVED && event.getResolvedAt() == null) {
      event.setResolvedAt(Instant.now());
  }
  // target == CLOSED 不再写 resolvedAt；保留原值
  ```
- 公共 API `UpdateFaultEventStatusRequest` 只允许人工目标状态：
  - 方案 1：新增 `ManualFaultEventStatus` 枚举（OPEN/RESOLVED/CLOSED），禁止 `DIAGNOSING`；
  - 方案 2：在 `FaultEventService.updateStatus` 中拒绝 `target == DIAGNOSING`（返回 400/409）。
  - 推荐方案 2，改动最小；前端移除“标记诊断中”按钮。
- `DIAGNOSING -> OPEN` 不加入公开状态机；由后台恢复/条件更新完成。若需要管理员恢复，另加内部接口。

### 4.4 涉及文件

| 文件 | 改动 |
| --- | --- |
| `backend/.../agent/AgentTaskRunner.java` | 先保存任务终态，再 best-effort 恢复事件；不再让恢复异常触发 `fail()` |
| `backend/.../diagnosis/repository/FaultEventRepository.java` | 新增 `restoreOpenIfDiagnosing` 条件更新 |
| `backend/.../diagnosis/domain/FaultEvent.java`、`DiagnosisTask.java` | `@Version` |
| `backend/.../diagnosis/service/FaultEventService.java` | `resolvedAt` 修正；拒绝手工 DIAGNOSING |
| `backend/.../common/GlobalExceptionHandler.java` | 乐观锁 409 |
| `backend/.../diagnosis/api/UpdateFaultEventStatusRequest.java` | 可选：限制人工状态 |
| `backend/src/main/resources/db/migration/V4__task_lifecycle_and_version.sql` | version 列 + 唯一索引 |

### 4.5 数据库变更

- `fault_event.version`、`diagnosis_task.version`（默认 0）。
- 活跃 Agent 任务部分唯一索引（与第 3 节共用 V4）。
- 迁移前检查重复活跃任务。

### 4.6 测试

| 测试 | 断言 |
| --- | --- |
| `AgentTaskRunnerTest.successIsNotOverwrittenWhenEventRestoreFails` | 事件恢复抛异常 → 任务仍 SUCCEEDED、报告保留 |
| `AgentTaskRunnerTest.failedTaskRestoresOpenEvent` | 失败任务 → 事件 OPEN |
| `FaultEventServiceTest.resolvedAtNotOverwrittenOnClose` | RESOLVED → CLOSED 保留原 `resolvedAt` |
| `FaultEventServiceTest.rejectsManualDiagnosing` | PATCH DIAGNOSING → 400/409 |
| `FaultEventServiceTest.optimisticLockReturns409`（集成） | 两个并发更新 → 一个成功、一个 409 |
| `AgentTaskRunnerTest.humanResolvedEventIsNotReopened`（集成） | 任务结束时事件已 RESOLVED → 条件更新返回 0，状态保持 RESOLVED |

### 4.7 验收标准

- 模型成功后，即使事件恢复失败，任务仍为 SUCCEEDED 且报告存在。
- 人工已标记 RESOLVED/CLOSED 的事件不会被后台恢复改回 OPEN。
- `RESOLVED → CLOSED` 保留第一次解决时间。
- 公共 API 不能手工把事件设为 DIAGNOSING。
- 并发状态更新返回 409，而不是静默丢失。

---

## 5. P1-07 模型调用整体超时控制

### 5.1 当前代码事实

- `DefaultAgentOrchestrator.java:67`：`deadline = now + overallTimeout`。
- `:78-82`：只在每轮循环顶部检查。
- `:92-98`：模型调用固定 60 s 读超时。
- `:165-170`：工具调用没有剩余时间参数。
- `DeepSeekAgentModelClient.java:29-38`：`RestClient` 在构造时固定 connect/read timeout。
- `ObservabilityClient.java:110-115`：固定 2 s connect / 5 s read，每次工具调用串行访问两个服务。

### 5.2 最小修复方案

#### D1. 在编排层计算剩余时间

```java
private Duration remaining(Instant deadline) {
    Duration d = Duration.between(Instant.now(), deadline);
    return d.isNegative() ? Duration.ZERO : d;
}
```

- 每次模型/工具调用前计算 `remaining`；如果 `remaining <= 0`，立即返回 `FAILED("诊断整体超时")`。
- 每次调用返回后再次检查；如果已超时，停止后续调用。

#### D2. 模型客户端支持动态超时

- 在 `AgentModelClient.ModelRequest` 增加 `Duration timeout`（或新增重载 `complete(request, timeout)`）。
- `DeepSeekAgentModelClient` 每次调用构造一个临时 `RestClient`：
  ```java
  long timeoutMs = Math.max(1, Math.min(properties.readTimeoutMs(),
          remaining.toMillis()));
  SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
  factory.setConnectTimeout((int) Math.min(properties.connectTimeoutMs(), timeoutMs));
  factory.setReadTimeout((int) timeoutMs);
  RestClient client = builder.clone()
          .baseUrl(properties.baseUrl())
          .requestFactory(factory)
          .build();
  ```
- 保留现有固定 client 作为 `remaining` 很大时的默认实现也可以；但为避免两条路径，统一按剩余时间构造。
- 这样单次模型调用最多消耗 `remaining`，不会在 deadline 后继续等待 60 s。

#### D3. 工具调用支持动态超时

- `ToolInvocationService.invoke(...)` 增加 `Duration timeout` 参数；`ToolExecutionContext` 可增加 `timeout` 字段，或直接在 `invoke` 签名传递。
- `ObservabilityClient.queryLogs/queryMetrics` 增加 `Duration timeout`，每次调用构造临时 `RestClient`，read timeout 取 `min(5000, remaining)`。
- `DefaultAgentOrchestrator` 把 `remaining(deadline)` 传给工具调用。
- `EvidenceService` 调用工具时传一个固定上限（例如 15 s），避免手工采集无限等待。

#### D4. 失败与 trace

- 超时返回 `AgentRunResult.failed("诊断整体超时（N 秒）", trace, modelName)`。
- 在 trace 中记录 `MODEL_TIMEOUT` / `TOOL_TIMEOUT`，包含已用时间和剩余时间，便于审计。
- 不要为超时伪造 `INSUFFICIENT_EVIDENCE`；它是执行失败，不是证据不足。

### 5.3 涉及文件

| 文件 | 改动 |
| --- | --- |
| `backend/.../agent/AgentModelClient.java` | `ModelRequest` 增加 timeout 或新增重载 |
| `backend/.../agent/DeepSeekAgentModelClient.java` | 按剩余时间构造临时 RestClient |
| `backend/.../agent/DefaultAgentOrchestrator.java` | 计算/传递 remaining；检查前后 deadline |
| `backend/.../tools/ToolInvocationService.java` | 增加 timeout 参数 |
| `backend/.../observability/ObservabilityClient.java` | 动态 read timeout |
| `backend/.../diagnosis/service/EvidenceService.java` | 传固定工具超时 |
| 对应测试 | 慢模型/慢工具超时用例 |

### 5.4 数据库变更

- 无。

### 5.5 测试

| 测试 | 断言 |
| --- | --- |
| `DefaultAgentOrchestratorTest.stopsWhenOverallTimeoutExceeded` | 模型调用超过 deadline → `FAILED`，不继续下一轮 |
| `DeepSeekAgentModelClientTest.usesRemainingTimeout` | 传入 2 s timeout，实际 read timeout ≤2 s（可通过注入 fake request factory 验证） |
| `ObservabilityClientTest`（新增） | 传入 1 s timeout，慢服务在 ~1 s 内失败 |
| `EvidenceServiceTest.toolTimeoutMarksTaskFailed` | 工具超时 → 任务 FAILED，不永久 RUNNING |

### 5.6 验收标准

- `AGENT_TIMEOUT_SECONDS=180` 时，诊断总时长不超过约 180 s + 单次调用收尾开销。
- 模型/工具超时都有明确失败原因和 trace。
- 不会因为一次 60 s 模型调用而突破整体 deadline。

---

## 6. 数据库变更汇总（Flyway V4）

建议只新增一个迁移：`backend/src/main/resources/db/migration/V4__task_lifecycle_and_version.sql`

```sql
-- 1. 乐观锁版本列
ALTER TABLE fault_event ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE diagnosis_task ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 2. 同一事件最多一个活跃 Agent 任务
-- 注意：执行前先检查重复数据，迁移脚本不自动删除
CREATE UNIQUE INDEX uk_diagnosis_task_active_agent
    ON diagnosis_task (fault_event_id)
    WHERE status IN ('PENDING', 'RUNNING')
      AND trigger_source IN ('AGENT', 'AGENT_RETRY');
```

迁移前检查 SQL：

```sql
SELECT fault_event_id, count(*) AS active_count
  FROM diagnosis_task
 WHERE status IN ('PENDING','RUNNING')
   AND trigger_source IN ('AGENT','AGENT_RETRY')
 GROUP BY fault_event_id
HAVING count(*) > 1;
```

如果有重复：

1. 人工确认保留哪一条（通常是最近创建的一条）；
2. 其余任务改为 `FAILED`，填写 `failure_reason='重复活跃任务，人工清理'`、`completed_at=now()`；
3. 对应事件若仍为 `DIAGNOSING` 且没有其他活跃任务，改为 `OPEN`；
4. 再执行 V4。

> 不建议在迁移中自动修改业务数据；毕设演示库可以先备份，再手工清理。

---

## 7. 后续阶段计划

### Phase A：核心业务正确性（建议第一批）

| 任务 | 内容 | 文件 | DB | 测试 | 预计 |
| --- | --- | --- | --- | --- | --- |
| A1 | 4xx 映射修复（P1-02） | `InventoryClient`、`GlobalExceptionHandler`、`ObservationClassifier`、新增异常 | 无 | `InventoryClientTest`、`ObservationClassifierTest`、订单控制器测试 | 0.5 天 |
| A2 | DELAY 上限 + 库存幂等（P1-01） | `InventoryStore`、`InventoryController`、`ReserveRequest`、`FaultInjectionService`、配置/文档 | 无 | `InventoryStoreTest`、`InventoryControllerTest`、`FaultInjectionFilterTest` | 1 天 |
| A3 | （可选）订单/管理后端幂等键透传 | `CheckoutRequest`、`OrderService`、`InventoryClient`、`ExperimentService` | 无 | 透传测试 | 0.5 天 |

**退出标准**：未知 SKU 不生成事件；同一 `orderId` 只扣一次；默认 DELAY 不会触发订单超时；Phase A 测试通过。

---

### Phase B：Agent 任务可靠性

| 任务 | 内容 | 文件 | DB | 测试 | 预计 |
| --- | --- | --- | --- | --- | --- |
| B1 | 活跃任务按 triggerSource 过滤；`retry` 检查；唯一索引冲突 409（P1-05） | `AgentDiagnosisService`、`DiagnosisTaskRepository` | V4 唯一索引 | 并发启动、retry 测试 | 0.5 天 |
| B2 | 异步拒绝处理 + `run()` 前置保存异常保护（P1-04） | `AgentDiagnosisService`、`AgentTaskRunner` | 无 | `TaskRejectedException`、首次保存失败 | 0.5 天 |
| B3 | 启动恢复 + `EvidenceService` finally（P1-03/P1-04） | 新增 `DiagnosisTaskRecovery`、`EvidenceService` | 无 | 恢复测试、证据失败测试 | 1 天 |
| B4 | 终态先保存、事件条件恢复、`@Version`、乐观锁 409（P1-06） | `AgentTaskRunner`、`FaultEventRepository`、实体、`GlobalExceptionHandler` | V4 version 列 | 成功不被覆盖、人工状态不被覆盖、并发 409 | 1 天 |
| B5 | 动态超时（P1-07） | `AgentModelClient`、`DeepSeekAgentModelClient`、`DefaultAgentOrchestrator`、`ToolInvocationService`、`ObservabilityClient`、`EvidenceService` | 无 | 慢模型/慢工具 | 1 天 |
| B6 | `resolvedAt` 与手工 DIAGNOSING 修复（P2-01/P2-03） | `FaultEventService`、前端按钮 | 无 | 状态机测试 | 0.5 天 |

**退出标准**：并发启动只创建一个任务；重启后无 PENDING/RUNNING；线程池拒绝有明确终态；成功报告不被覆盖；人工 RESOLVED 不被改回；整体超时可控。

---

### Phase C：前端交互、版本信息与演示环境

| 任务 | 内容 | 文件 | 测试 | 预计 |
| --- | --- | --- | --- | --- |
| C1 | axios 响应拦截器，展示后端 `message`（P1-08） | `frontend/src/api/http.js`、各视图 | Vitest/手工 | 0.5 天 |
| C2 | 证据采集 success 判断与提示（P1-09） | `FaultEventDetailView.vue` | Vitest/手工 | 0.5 天 |
| C3 | 事件级 active task、轮询、历史选择（P1-10） | `FaultEventDetailView.vue` | Vitest/手工 | 1 天 |
| C4 | 概览/侧边栏/版本号/文档（P1-11、P2-53/56） | `OverviewView.vue`、`MainLayout.vue`、README、`.env.example`、docs | 页面走查 | 0.5 天 |
| C5 | 单 Vite + Windows/IDEA 启动说明 + 预检脚本（P1-14） | 演示脚本、README、可选 `scripts/preflight` | 手工执行 | 0.5 天 |
| C6 | 路由参数 watch、轮询清理、对照请求禁用、指标平均耗时等 P2 前端项 | 各视图 | Vitest/手工 | 1 天 |

**退出标准**：403/409 显示中文原因；全失败证据显示警告；历史任务选择不停止轮询；概览与实现一致；5173 只有一个实例且 `/api/health` 200。

---

### Phase D：集成测试与最终验收

| 任务 | 内容 | 预计 |
| --- | --- | --- |
| D1 | Testcontainers PostgreSQL：Flyway V1–V4、JPA validate、事件→证据→诊断全链路 | 1 天 |
| D2 | 并发/恢复测试：并发启动、重启恢复、人工状态覆盖、超时 | 1 天 |
| D3 | 前端 Vitest + 可选 Playwright 演示冒烟 | 1 天 |
| D4 | `mvn clean test` ×3、`npm run build`、5 分钟演示彩排 | 0.5 天 |
| D5 | 更新审查报告/修复报告/README/演示脚本，关闭 P0/P1 | 0.5 天 |

**退出标准**：所有测试真实通过；无 P0/P1 未关闭；答辩演示按脚本走通；文档与实现一致。

---

## 8. 推荐的第一批修复任务

### 8.1 推荐顺序（低风险、快速见效）

1. **A1 4xx 映射**（0.5 天）
   - 改动小，直接消除“未知 SKU 生成假故障事件”。
   - 不涉及数据库，容易回滚。
2. **A2 DELAY 上限 + 库存幂等**（1 天）
   - 直接修复实验数据自相矛盾；需要新增测试，但不改表结构。
   - 建议先做“上限 4500 ms”这个一行改动，再做幂等 Map。
3. **C1 axios 拦截器 + C2 证据 success**（1 天）
   - 纯前端，演示体验提升明显；不依赖后端。
4. **C4 概览/侧边栏文案与版本**（0.5 天）
   - 纯文字，答辩第一页必须正确。
5. **B1/B2 活跃任务与异步拒绝**（1 天）
   - 开始触碰任务生命周期；先不加 V4 唯一索引也可以，用查询 + 拒绝兜底；唯一索引可在 B4 一起上。

> 建议第一批先完成 1–4（约 2–3 天），再进行 Phase B 的数据库迁移。这样答辩前有一个可演示的稳定版本，且 V4 迁移的测试时间更充足。

### 8.2 不建议第一批做的事

- 不要在第一批同时做“库存幂等 + V4 迁移 + 动态超时”，改动面太大，难以定位回归。
- 不要先做前端大重构或路由拆分；当前页面结构可用。
- 不要为了“自动处置”新增执行器；先把诊断可靠性做稳。

---

## 9. 风险与取舍

| 风险 | 说明 | 应对 |
| --- | --- | --- |
| 幂等只在内存 | 库存服务重启后幂等记录丢失 | 文档明确；演示不重启库存；未来可持久化 |
| 事件状态最终一致 | 先保存任务终态，事件恢复失败时可能短暂 DIAGNOSING | 启动恢复 + 条件更新；前端轮询可显示状态；不覆盖人工状态 |
| 唯一索引迁移失败 | 现有重复活跃任务会导致 V4 失败 | 迁移前检查 SQL；人工清理；备份数据库 |
| 动态超时实现复杂 | 每次调用构造 RestClient 有开销 | 调用次数少（≤6+6），开销可接受；用固定 client 兜底 |
| 4xx 状态码变化影响前端 | 订单接口从 502 变 404/400 | 前端 `error.message` 修复后能正确展示；更新接口文档 |
| `@Version` 引入 409 | 并发操作从“静默覆盖”变“明确失败” | 前端提示刷新；这是期望行为 |
| 测试环境无 Docker | Testcontainers 需要 Docker | Phase D 在答辩机执行；CI/本地先跑单元测试 |
| 不修改业务代码的本轮 | 方案未经过编译验证 | 下一轮按 Phase A 小步实现，每步编译+测试 |

---

## 10. 本轮不做的事

- 不修改任何业务代码、配置、数据库或迁移脚本。
- 不执行 `mvn test`、`mvn package`、`npm run build`。
- 不执行故障注入、DeepSeek 调用或数据库写操作。
- 不实现 Phase A–D 的代码，只输出方案。
- 不创建远程仓库、不 `git push`。

---

## 附录：关键决策摘要

| 问题 | 决策 | 理由 |
| --- | --- | --- |
| DELAY 超时 | 限制最大延迟 < 订单读超时 + 库存按 orderId 幂等 | 最小改动消除矛盾；不引入补偿服务 |
| 4xx | 订单保留 4xx 状态码 + 分类器增加 HTTP_4XX 分支 | 语义正确；双保险 |
| 任务卡死 | 活跃任务按 triggerSource 过滤 + 唯一索引 + 启动恢复 + 拒绝兜底 | 覆盖并发、崩溃、线程池三种场景 |
| 终态一致性 | 先保存任务终态，再条件恢复事件；不因恢复失败改任务 | 保证诊断结果不被覆盖；事件可最终恢复 |
| 人工状态覆盖 | 条件 UPDATE `WHERE status='DIAGNOSING'` + `@Version` | 不覆盖 RESOLVED/CLOSED；并发冲突明确 409 |
| 超时 | 每轮计算 remaining，动态设置 HTTP read timeout | 真正约束总时长；不依赖线程中断 |
| 数据库 | 只加一个 V4（version + 唯一索引） | 减少迁移风险；一次评审 |
| 测试 | 单元测试随阶段；Testcontainers/前端测试放 Phase D | 平衡进度与可信度 |

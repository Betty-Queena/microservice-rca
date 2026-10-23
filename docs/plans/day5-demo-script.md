# 5 分钟答辩演示脚本

> 目标：在 5 分钟内完整展示“真实故障注入 → 真实跨服务请求 → 真实事件 → DeepSeek Agent 自主诊断 → 人工确认恢复”的闭环。
> 所有画面必须来自现场运行或已经落库的真实记录；不得使用 Mock、静态 JSON 或伪造截图。

## 0. 演示前 10 分钟检查

| 检查项 | 操作 | 通过标准 |
| --- | --- | --- |
| API Key | 确认根目录 `.env` 中存在非空 `DEEPSEEK_API_KEY`，**不要打开投屏或展示该文件** | 后端启动后任务不会因 `CONFIG_MISSING` 失败 |
| 模型可用 | `curl --noproxy '*' -H "Authorization: Bearer $DEEPSEEK_API_KEY" https://api.deepseek.com/models` | HTTP 200，且包含 `DEEPSEEK_MODEL` |
| 故障开关 | 启动库存服务前将 `.env` 的 `EXPERIMENT_FAULT_INJECTION_ENABLED` 设为 `true` | 故障注入页显示“实验开关：已启用” |
| 预算 | 建议 `AGENT_MAX_MODEL_CALLS=4`、`AGENT_MAX_TOOL_CALLS=3`、`AGENT_TIMEOUT_SECONDS=90` | 单次诊断约 10–20 秒，费用可控 |
| 端口 | 8080 / 8081 / 8082 / 5173 / 5432 | 无冲突 |
| 历史数据 | 演示前执行一次“关闭/重置故障”，确认没有残留故障 | `active=false` |
| 页面预热 | 提前打开概览、故障注入、故障事件三个页面 | 无 404、无空白页 |

> 演示结束后必须把 `EXPERIMENT_FAULT_INJECTION_ENABLED` 恢复为 `false` 并重启库存服务。

## 1. 启动顺序

```bash
# 终端 1：PostgreSQL
docker compose --env-file .env -f infra/docker-compose.yml up -d

# 终端 2：库存服务（必须先确认故障注入开关为 true）
cd services/inventory-service
mvn spring-boot:run

# 终端 3：订单服务
cd services/order-service
mvn spring-boot:run

# 终端 4：管理后端
cd backend
mvn spring-boot:run

# 终端 5：前端
cd frontend
npm run dev
```

健康检查（三个服务都应返回 `UP`）：

```bash
curl --noproxy '*' http://localhost:8080/api/health
curl --noproxy '*' http://localhost:8081/api/health
curl --noproxy '*' http://localhost:8082/api/health
curl --noproxy '*' http://localhost:8080/api/experiments/faults
```

## 2. 5 分钟时间轴

### 0:00–0:30 系统概览

- 打开 <http://localhost:5173/overview>。
- 说明三个 Spring Boot 服务与 PostgreSQL 均为实时健康检查，不是前端写死状态。
- 指出系统边界：真实故障注入、真实观测、真实模型调用；不自动修复、不执行 Shell/SQL。

### 0:30–1:10 注入真实故障

- 打开“故障注入实验”页面。
- 故障类型选 **HTTP_500**，持续时间 **120 秒**，备注“Day 5 答辩演示”。
- 点击“开启故障注入”，确认页面显示当前故障、实验 ID 与倒计时。
- 说明：故障只作用于 `/api/inventory/**` 业务接口，健康检查和观测接口不受影响，且会自动过期。

### 1:10–1:45 执行真实请求并生成事件

- 保持默认 SKU 与数量，点击“执行故障实验请求”。
- 展示真实结果：HTTP 502、下游错误类型、实测耗时、requestId。
- 强调：502 是订单服务对下游 500 的真实封装，不是前端模拟。
- 点击“查看关联故障事件 →”。

### 1:45–3:10 启动 AI 诊断并解读结果

- 在事件详情页点击“启动AI诊断”。
- 等待 10–20 秒，期间说明 Agent 有限状态机：
  `模型决策 → 工具调用 → 观察结果 → 再决策 → 最终报告/证据不足/失败`。
- 任务完成后展示：
  - 任务状态 `SUCCEEDED`、结论状态 `ROOT_CAUSE_IDENTIFIED`；
  - 模型名、模型调用次数、工具调用次数；
  - Token 合计（input/output）与逐轮耗时；若个别轮次 usage 缺失，页面会明确标注；
  - 根因报告、置信等级、支持证据 ID；
  - 决策时间线中的 `query_logs` / `query_metrics` 调用与工具审计；
  - 页面提示“AI 诊断完成不等于故障已解决，事件仍需人工处置”。

### 3:10–3:50 关闭故障并验证恢复

- 回到故障注入页，点击“关闭当前故障”（或“重置实验”）。
- 点击“无故障对照请求”，展示 HTTP 200 与正常耗时，证明业务恢复。
- 回到事件详情：事件状态应从“诊断中”恢复为“待处理”，而不是自动变成“已解决”。
- 点击“标记已解决”，说明 `RESOLVED/CLOSED` 只能由人工确认。

### 3:50–4:30 展示诊断历史与统计

- 在事件详情“任务历史”中展示本次成功任务；如有失败任务，展示失败原因与重试产生的新任务，原任务不被覆盖。
- 打开成功任务的时间线，指出每一轮模型的 input/output token、耗时，以及末尾 `本次运行统计`。
- 说明：usage 缺失时字段为 `null` 并附说明，不补零、不估算费用。

### 4:30–5:00 收尾

- 强调三条真实性边界：
  1. 证据 ID 全部来自真实工具结果，伪造 ID 会被拒绝；
  2. 模型失败、证据不足、超时都明确失败，不生成根因；
  3. 处置建议只展示，不自动执行。
- 如时间允许，补充一句：DELAY 3500ms 场景已在 Day 4 完成真实端到端验证，报告见 `docs/plans/day4-deepseek-validation.md`。

## 3. 异常预案

| 异常 | 现场判断 | 处理方式 |
| --- | --- | --- |
| 任务失败 `CONFIG_MISSING` / `AUTH_FAILED` | 未加载 Key 或 Key 无效 | 停止真实演示，明确说明配置失败；检查 `.env` 与后端启动目录，重启后端；**不能用 Mock 结果替代** |
| `INSUFFICIENT_BALANCE` / `RATE_LIMITED` | 账户余额或限流 | 展示真实失败原因；不要连续重试；必要时改用已落库的真实历史任务讲解，并说明是历史记录 |
| 模型超时 | 网络或 Provider 抖动 | 等待任务进入 `FAILED`，展示失败原因后点击“重试失败任务”；最多重试一次 |
| 故障注入页显示“未启用” | 库存服务启动时开关为 false | 修改 `.env` 后重启库存服务；不要在前端伪造“已启用” |
| 故障开启后请求仍 200 | 故障已过期或未命中业务接口 | 查看剩余时间，重新开启 120 秒故障；确认请求走 `/api/orders/checkout` |
| 页面数据不刷新 | 前端轮询或代理异常 | 点击“刷新”；确认 Vite 代理指向 8080；必要时重启 `npm run dev` |
| 数据库不可用 | 健康检查 `DEGRADED` | `docker compose ... up -d`，等待 PostgreSQL 就绪后重启后端 |
| 演示超时 | 诊断耗时偏长 | 直接展示已生成的时间线与工具审计；不要把“运行中”说成“已完成” |

## 4. 演示后清理

```bash
curl --noproxy '*' -X POST http://localhost:8080/api/experiments/faults/disable
curl --noproxy '*' -X POST http://localhost:8080/api/experiments/faults/reset
curl --noproxy '*' http://localhost:8080/api/experiments/faults
```

确认返回 `active=false`，再把 `.env` 中的 `EXPERIMENT_FAULT_INJECTION_ENABLED` 恢复为 `false` 并重启库存服务。不要提交 `.env`，不要截图包含 Key、数据库密码或终端中的敏感环境变量。

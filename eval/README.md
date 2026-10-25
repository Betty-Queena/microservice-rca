# Day 7 故障诊断评测（P0 第二阶段）

本目录实现 Day 7 的黑盒评测能力：

- 离线部分：case 校验、确定性判分、JSON/CSV 报告、原始结果重判。
- 真实黑盒部分：通过后端 HTTP API 串行执行故障注入、实验请求、Agent 诊断轮询，并保存原始样本。

真实运行必须显式传入 `--yes`。没有该参数时，`run` 只做标签校验并拒绝执行，不创建结果目录，也不会发起任何网络请求。

## 当前范围

已实现：

- `HTTP_500`、`DELAY 3500ms`、`NORMAL` 三类基线 case。
- 真实 HTTP 黑盒 runner，仅使用 Python 标准库。
- 运行前健康检查与故障注入开关检查。
- 故障注入启用、实验请求、故障事件查询、Agent 诊断创建与轮询。
- 诊断报告、决策轨迹、工具调用、Token 与耗时字段采集。
- NORMAL 场景不创建 Agent 诊断，只检查是否误报。
- 每个样本独立 `sampleId`，写入 `eval/results/<run-id>/raw/`。
- 运行结束后复用离线判分和报告生成。
- 标准库 `unittest`，全部使用内存 HTTP fixture，不访问真实服务。

**仍未实现（不在本轮范围）**：

- `delay-timeout` 场景。
- 自动修改 inventory-service 配置或自动开启故障注入。
- 自动删除故障事件、诊断任务或历史报告。
- 自动重试 DeepSeek 诊断。
- 并发故障注入或多 worker 评测。
- 自动修复、自动处置或生产报告结构变更。

## 目录

```text
eval/
├── README.md
├── run_eval.py                 # CLI：validate / plan / run / rescore
├── cases/
│   └── day7-baseline.json      # 标签冻结的 P0 基线
├── rca_eval/
│   ├── models.py               # 数据模型与宽容解析
│   ├── cases.py                # case 校验、原始结果读取
│   ├── scoring.py              # 确定性判分
│   ├── report.py               # 聚合统计与报告
│   ├── http_client.py          # 标准库 HTTP 客户端
│   └── runner.py               # 真实黑盒 runner
├── tests/                      # 标准库 unittest
└── results/                    # 本地结果，默认被 .gitignore 忽略
```

## 已核对的真实 API

所有调用都走 backend `http://localhost:8080` 代理；评测器不直接调用
inventory-service 的故障控制接口。backend 当前没有入站鉴权过滤器，因此安全边界
主要依赖本地运行环境和显式 `--yes`，不要把 backend 直接暴露到不可信网络。

统一响应结构为 `ApiResponse<T>`：

```json
{"code": 200, "message": "OK", "data": {}, "timestamp": "..."}
```

`code` 与 HTTP 状态码一致。错误响应同样使用该结构，HTTP 状态为对应错误码。

| 用途 | 方法与路径 | 关键字段 |
| --- | --- | --- |
| 健康检查 | `GET /api/health` | `status`、`service`、`version`、`database.status` |
| 故障状态 | `GET /api/experiments/faults` | `experimentEnabled`、`active`、`experimentId`、`faultType`、`delayMs`、`remainingMs` |
| 启用故障 | `POST /api/experiments/faults/enable` | 请求：`faultType`、`delayMs`、`durationSeconds`、`note` |
| 关闭故障 | `POST /api/experiments/faults/disable` | 返回同故障状态 |
| 故障实验 | `POST /api/experiments/{experimentId}/execute` | 请求：`sku`、`quantity`；响应：`outcome`、`faultObserved`、`httpStatus`、`durationMs`、`faultEventId` 等 |
| 正常实验 | `POST /api/experiments/checkout` | 请求：`sku`、`quantity` |
| 故障事件 | `GET /api/fault-events/{eventId}` | `event.id`、`event.serviceName`、`event.status` 等 |
| 启动诊断 | `POST /api/fault-events/{eventId}/diagnosis` | 响应：任务 `id`、`status`、`faultEventId` |
| 查询诊断 | `GET /api/diagnosis/tasks/{taskId}` | `status`、`resultStatus`、`report`、`decisionTrace`、`toolInvocations`、`modelName`、时间字段 |

诊断任务终态为 `SUCCEEDED`、`FAILED`、`CANCELLED`。评测器不调用
`POST /api/diagnosis/tasks/{taskId}/retry`，避免重复产生模型费用。

order-service 和 inventory-service 的健康检查分别使用：

- `http://localhost:8081/api/health`
- `http://localhost:8082/api/health`

## 黑盒执行流程

每个 case 的每个 repeat 串行执行：

1. 对故障场景，先确认 `experimentEnabled=true` 且当前没有 active 注入；否则标记环境错误并中止。
2. 通过 backend 启用对应 `HTTP_500` 或 `DELAY` 注入，记录返回的 `experimentId`。
3. 通过 backend 的 experiment execute 接口发起请求，采集 HTTP 状态、耗时、outcome、`faultObserved` 和 `faultEventId`。
4. NORMAL 场景在请求前确认故障注入已关闭；请求后只检查是否产生事件或诊断，不调用 Agent。
5. 故障场景查询故障事件，再创建 Agent 诊断任务。
6. 按 `--poll-interval-seconds` 轮询诊断任务，直到终态或达到诊断超时预算。
7. 采集报告、证据引用、决策轨迹、工具调用、模型名称和模型使用统计。
8. 在 `finally` 中按本次 `experimentId` 尝试关闭故障注入。
9. 立即原子写入 `raw/<sampleId>.json`，然后进入下一个样本。
10. 全部样本结束后复用第一阶段离线判分，生成 `report/` 和 `run-manifest.json`。

运行前 `preflight` 会检查：

- backend 健康且数据库状态为 `UP`；
- order-service 和 inventory-service 健康状态为 `UP`；
- 故障注入功能已启用；
- 当前没有未关闭的故障注入。

任一检查失败都会在发起实验前停止，不会自动修改配置。

## 手动命令

在仓库根目录执行：

```bash
# 校验 case 文件
python3 eval/run_eval.py validate

# 只打印计划，不执行实验
python3 eval/run_eval.py plan --repeats 1

# 不带 --yes：明确拒绝真实执行，返回码 2
python3 eval/run_eval.py run --repeats 1

# 真实冒烟评测（会注入故障并产生 Agent 诊断费用）
python3 eval/run_eval.py run --yes --repeats 1 --max-diagnosis-runs 2

# 对已有原始结果离线重判
python3 eval/run_eval.py rescore eval/results/<run-id> \
  --output eval/results/<run-id>/rescore

# 运行单元测试（不访问真实服务）
python3 -m unittest discover -s eval/tests -t eval -v
```

`run` 常用参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--yes` | 无 | 必须显式提供，否则拒绝执行 |
| `--repeats` | `1` | 每个 case 的重复次数；默认只做低成本冒烟，不自动采用 suite 的统计 repeat=3 |
| `--max-diagnosis-runs` | `2` | 本次运行最多启动的诊断次数；启动前和运行时都会检查 |
| `--backend-url` | `http://localhost:8080` | backend 根地址 |
| `--order-url` | `http://localhost:8081` | order-service 健康检查地址 |
| `--inventory-url` | `http://localhost:8082` | inventory-service 健康检查地址 |
| `--results-dir` | `eval/results` | 结果根目录 |
| `--run-id` | 自动生成 | 运行 ID；已存在的目录不会被覆盖 |
| `--poll-interval-seconds` | `2.0` | 诊断轮询间隔 |
| `--diagnosis-timeout-seconds` | suite `defaults.diagnosisTimeoutSeconds` | 单次诊断超时 |
| `--http-timeout-seconds` | `30.0` | 单次 HTTP 请求超时 |

## 费用与安全控制

- 真实实验必须显式传 `--yes`，不提供环境变量绕过。
- `run` 默认使用低成本冒烟配置：`repeats=1`，最多 2 次诊断；suite 的 `repeat=3`
  只用于统计运行，需要显式传入 `--repeats 3`。
- 预算在发起网络请求前检查；计划诊断次数超过 `--max-diagnosis-runs` 时直接拒绝运行。
- 运行时每次启动诊断前再次检查预算，防止配置或循环异常导致超预算。
- 若要执行 `--repeats 3` 的统计运行，需要同时把 `--max-diagnosis-runs` 提高到至少 6。
- 评测器不重试 DeepSeek 诊断，也不调用诊断 retry 接口。
- 故障注入严格串行：检测到 active 注入时拒绝开始；不与其他实验并发。
- 每个故障场景结束后都在 `finally` 中尝试清理，并且只关闭本次 `experimentId` 对应的注入。
- NORMAL 场景开始前无法确认故障注入关闭时，标记环境错误并停止场景。
- 不自动删除数据库中的事件、任务或报告。
- 不修改故障事件状态来迎合评测结果。
- 不把 DeepSeek API Key、环境变量秘密、请求头或完整响应原文写入原始结果和日志。
- 不把 Ground Truth 或预期答案传入 Agent 诊断请求；诊断请求只携带 `eventId`。
- Agent 输入白名单由后端 `AgentInputFactory` 控制，实验故障类型和参数不会进入模型输入。

错误分类在原始样本中分别记录：

```text
environmentError    健康检查、故障开关或 NORMAL 前置检查失败
apiError            HTTP/API 响应不符合预期
faultInjectionError 启用故障失败或返回状态不一致
executionError      实验响应缺少必要字段
diagnosisError      诊断任务创建/轮询/返回结构异常
cleanupError        故障清理失败
budgetError         诊断次数超过预算
harnessError        以上错误的汇总，供离线判分标记 INVALID_ENV
```

`harnessError` 存在时会中止后续样本；已完成的原始样本和报告仍会保留。

## 首次真实冒烟实验前置条件

1. backend、order-service、inventory-service 和 PostgreSQL 已启动，三个健康接口为 `UP`。
2. inventory-service 已设置 `EXPERIMENT_FAULT_INJECTION_ENABLED=true` 并重启；评测器不会自动改配置。
3. 当前没有其他实验或手动注入占用故障开关；`GET /api/experiments/faults` 返回 `active=false`。
4. backend 已按原有方式配置 DeepSeek 凭据；评测器不读取、不打印 Key。
5. 当前没有并发评测或其他故障注入操作。
6. 已确认 order-service `inventory.read-timeout-ms=5000`、inventory 最大延迟 `4500ms`、backend 慢响应阈值 `3000ms`，因此 `DELAY 3500ms` 是慢响应而不是超时。

默认 suite 有 3 个 case：2 个诊断场景、1 个正常场景。`--repeats 1` 时：

- 计划 Agent 诊断：2 次；
- Day 6 历史粗略估算：约 8 次模型调用、约 21,648 tokens（不是本轮真实数据，也不能直接作为论文结果）；
- 实际模型调用和 token 以运行后的 `report/summary.json`、`samples.jsonl` 和 `decisionTrace` 为准；
- 每个 Agent 任务内部可能还有模型层重试，因此实际调用量可能高于上述估算。

## 输出结构

```text
eval/results/<run-id>/
├── raw/
│   └── <run-id>-<caseId>-r<repeat>.json
├── report/
│   ├── summary.json
│   ├── summary.csv
│   ├── samples.jsonl
│   ├── manifest.json
│   └── failures.md
└── run-manifest.json
```

原始样本推荐结构：

```json
{
  "schemaVersion": "1.0",
  "sampleId": "run-20261024T120000Z-ab12cd-http500-baseline-r1",
  "caseId": "http500-baseline",
  "repeat": 1,
  "runId": "run-20261024T120000Z-ab12cd",
  "observation": {
    "faultObserved": true,
    "outcome": "DOWNSTREAM_HTTP_5XX",
    "httpStatus": 502,
    "durationMs": 120,
    "downstreamErrorType": "HTTP_5XX",
    "faultEventId": "event-uuid",
    "requestId": "request-uuid",
    "executedAt": "2026-10-24T12:00:05Z"
  },
  "diagnosis": {
    "taskId": "task-uuid",
    "status": "SUCCEEDED",
    "resultStatus": "ROOT_CAUSE_IDENTIFIED",
    "failureReason": null,
    "report": {},
    "decisionTrace": [],
    "toolInvocations": [],
    "createdAt": "2026-10-24T12:00:06Z",
    "startedAt": "2026-10-24T12:00:07Z",
    "completedAt": "2026-10-24T12:00:25Z"
  },
  "faultInjection": {
    "experimentId": "exp-uuid",
    "faultType": "HTTP_500",
    "delayMs": null,
    "remainingMs": 120000
  },
  "faultEvent": {
    "id": "event-uuid",
    "serviceName": "inventory-service",
    "status": "OPEN"
  },
  "harnessError": null
}
```

NORMAL 样本的 `diagnosis` 必须为 `null`，`observation.faultEventId` 必须为空。

诊断超时时，原始样本中的 `diagnosis.status` 写为 `HARNESS_TIMEOUT`，并记录最后观察到的任务状态和超时预算；不会把 RUNNING 任务伪造成成功或失败。

## 判分规则

### 观察是否通过

比较 `faultObserved`、`outcome`，以及 case 指定的 `httpStatus` 和 `minDurationMs`。缺失字段会进入 `missingFields`，不会被静默当作通过。

### NORMAL 场景

- 不调用诊断。
- 不进入 Agent 根因准确率分母。
- 不产生 Agent 的 Token、调用次数和诊断耗时指标；这些指标属于不适用，不计入 `samplesWithIncompleteMetrics`。
- 正常通过（`NORMAL_PASS`）的样本不会因为 `strictCorrect=false` 或 Agent 指标不适用而进入人工复核清单。
- 若出现故障事件或诊断任务，记为 `FALSE_ALARM`，仍保留在失败和人工复核清单中。- 若观察结果不符，记为 `OBSERVATION_MISMATCH`。
- 运行器标记的环境/API/清理错误优先记为 `INVALID_ENV`。

### Agent 诊断分母

只有**观察通过且诊断达到终态**的故障样本进入准确率分母：

- `SUCCEEDED + ROOT_CAUSE_IDENTIFIED`
- `SUCCEEDED + INSUFFICIENT_EVIDENCE`
- `FAILED`

以下样本单独统计，不进入分母：

- `INVALID_ENV`
- `INVALID_SAMPLE`
- `OBSERVATION_MISMATCH`
- `HARNESS_TIMEOUT`
- `TASK_NOT_TERMINAL`
- `CANCELLED`
- `UNKNOWN_CASE`

`INSUFFICIENT_EVIDENCE` 和 `FAILED` 进入分母，并在严格准确率中记为不正确。

### 服务、故障类型与严格准确率

- `serviceCorrect`：优先检查 `rootCause`、`hypothesis`；这两个字段都没有点名任何服务时，才用 `affectedServices` 兜底。命中预期服务别名且不是否定表述才算正确；根因描述明确指向其他服务时，`affectedServices` 不能覆盖该结论。
- `categoryCorrect`：`rootCause`、`hypothesis` 中出现该场景的故障关键词，且不是否定表述。
  - 纯数字关键词做简单边界检查，例如 `500` 不会命中 `3500`。
- `evidenceCoverage`：命中的证据 matcher 数 / `minEvidenceMatchers`，上限 1.0。
- `strictCorrect`：同时满足
  - 根因任务为 `ROOT_CAUSE_IDENTIFIED`；
  - 服务正确；
  - 故障类型关键词正确；
  - 证据覆盖率达到 1.0；
  - 报告必需字段完整。

`serviceAccuracy`、`categoryAccuracy`、`strictAccuracy` 是三个独立指标，报告中同时给出 `...AmongIdentified` 版本，便于人工复核。

### 否定词与误判

关键词匹配采用简单否定窗口（`不`、`未`、`没有`、`并非`、`不是`、`无`、`not`、`no`、`never`），只检查关键词前 10 个字符，并且否定词只在当前分句内生效；分句边界包括中英文标点和 `而是`、`但是`、`但`、`however`、`but`。它不是自然语言理解，可能误判；命中否定词时会写入 `riskFlags`，供人工复核。典型风险：

- `NEGATED_CATEGORY_KEYWORD:<keyword>`
- `NEGATED_SERVICE_ALIAS:<alias>`
- `SERVICE_CONFLICT:<other-service>`
- `RISK_KEYWORD:<keyword>`
- `NO_SUPPORTING_EVIDENCE`
- `EVIDENCE_COVERAGE_LOW`
- `EVIDENCE_ID_FORMAT:<id>`

### 缺失字段与失败

- 原始结果采用宽容解析，不因单个字段缺失中断整批重判。
- 无法计算的指标保持 `null`，不会填 0。
- `diagnosis` 缺失、报告字段缺失、证据缺失都会进入 `missingFields`。
- `FAILED` 的失败原因会归类为 `CONFIG_MISSING`、`BUDGET_TIMEOUT`、`EXECUTOR_REJECTED`、`MODEL_ERROR`、`AGENT_ERROR` 或 `UNKNOWN`。
- 任务仍为 `PENDING/RUNNING` 时记为 `TASK_NOT_TERMINAL`，不当作完成样本。

## 重判输入

`rescore` 支持：

- 目录下的 `raw/*.json`；
- 目录下的 `samples.jsonl`；
- 单个 `.json` 文件；
- JSON 数组；
- 带 `samples` 数组的运行清单。

`caseId` 必须存在于 case 文件；无法匹配时记为 `UNKNOWN_CASE`，不会猜测标签。

## 局限性与风险

1. **串行隔离**：当前故障注入是 inventory-service 进程内的单一 active 状态，实验 API 没有租户或并发隔离能力，因此评测器强制串行，并在每个场景前后检查 active 状态。不要与其他故障注入操作并行。
2. **清理失败**：`finally` 只能尝试关闭本次 `experimentId`；如果 backend 不可达或状态不匹配，原始样本会记录 `cleanupError`，运行中止，需要手动检查 `GET /api/experiments/faults`。
3. **模型内部重试**：评测器不重试诊断，但 Agent 模型层可能在 `maxModelCalls` 范围内重试可重试错误，实际费用可能高于按任务数的估算。
4. **无入站鉴权**：backend 当前没有入站鉴权，真实运行只应在本地可信环境进行。
5. **判分不是语义理解**：服务、故障类型和否定词匹配都是确定性字符串规则，可能误判，需要结合 `riskFlags` 人工复核。
6. **未覆盖超时场景**：默认配置下 `DELAY 3500ms` 低于 order-service 读超时 `5000ms`，不会产生 `DOWNSTREAM_TIMEOUT`；本轮不调整配置。
7. **不自动清理数据库**：故障事件、诊断任务和报告会保留在数据库中，便于复核；重复运行会创建新记录。
8. **原始结果包含实验观测、决策轨迹、工具调用与模型报告摘要**，不包含 API Key、请求头或完整响应原文；`toolInvocations` 可能含查询参数和证据摘要，提交前仍应人工检查 `eval/results/`。

## 数据与安全

- 本目录代码不读取或写入 `DEEPSEEK_API_KEY`。
- 真实运行会经由 backend 间接产生模型调用。
- 不要把 `.env`、密钥、完整模型请求头写入 `eval/results/` 或原始样本。
- 测试中的样本都是合成 fixture，只用于验证 runner 与判分逻辑，不是真实实验结果。
- `eval/results/` 下的内容默认不提交 Git。

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Refresh, RefreshRight, Search, VideoPlay } from '@element-plus/icons-vue'
import {
  collectFaultEventEvidence,
  fetchDiagnosticTools,
  fetchFaultEventDetail,
  updateFaultEventStatus
} from '../api/faultEvents'
import {
  fetchAgentTask,
  fetchAgentTasks,
  retryAgentTask,
  startAgentDiagnosis
} from '../api/agentDiagnosis'

const route = useRoute()
const router = useRouter()

const detail = ref(null)
const tools = ref([])
const loading = ref(false)
const collecting = ref(false)
const updating = ref(false)
const error = ref('')
const activeTab = ref('result')

const agentTask = ref(null)
const agentTasks = ref([])
const agentLoading = ref(false)
const agentStarting = ref(false)
const agentRetrying = ref(false)
let agentPollTimer = null

const event = computed(() => detail.value?.event || null)
const tasks = computed(() => detail.value?.diagnosisTasks || [])
const evidence = computed(() => event.value?.evidence || null)
const evidenceTools = computed(() => evidence.value?.tools || [])
const logsTool = computed(() => evidenceTools.value.find((item) => item.toolName === 'query_logs') || null)
const metricsTool = computed(
  () => evidenceTools.value.find((item) => item.toolName === 'query_metrics') || null
)
const logItems = computed(() => logsTool.value?.output?.items || [])
const metricServices = computed(() => metricsTool.value?.output?.services || [])
const metricRows = computed(() =>
  metricServices.value.flatMap((service) =>
    (service.buckets || []).map((bucket) => ({ ...bucket, serviceName: service.serviceName }))
  )
)
const observed = computed(() => event.value?.observedResult || null)
const presetParameters = computed(() => event.value?.experimentParameters || null)
const details = computed(() => event.value?.details || null)

const agentReport = computed(() => agentTask.value?.report || null)
const agentTrace = computed(() =>
  Array.isArray(agentTask.value?.decisionTrace) ? agentTask.value.decisionTrace : []
)
const agentInvocations = computed(() => agentTask.value?.toolInvocations || [])
const agentActive = computed(() => ['PENDING', 'RUNNING'].includes(agentTask.value?.status))
const agentRootCause = computed(
  () => agentTask.value?.resultStatus === 'ROOT_CAUSE_IDENTIFIED' && agentReport.value
)
const agentInsufficient = computed(
  () => agentTask.value?.resultStatus === 'INSUFFICIENT_EVIDENCE'
)
const supportingEvidence = computed(() => agentReport.value?.supportingEvidenceIds || [])
const contradictingEvidence = computed(() => agentReport.value?.contradictingEvidenceIds || [])
const recommendations = computed(() => agentReport.value?.recommendedActions || [])
const limitations = computed(() => agentReport.value?.limitations || [])

const statusOptions = [
  { value: 'OPEN', label: '待处理' },
  { value: 'DIAGNOSING', label: '诊断中' },
  { value: 'RESOLVED', label: '已解决' },
  { value: 'CLOSED', label: '已关闭' }
]

function formatTime(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function pretty(value) {
  if (value == null) return '—'
  try {
    return JSON.stringify(value, null, 2)
  } catch (ex) {
    return String(value)
  }
}

function statusType(status) {
  return { OPEN: 'danger', DIAGNOSING: 'warning', RESOLVED: 'success', CLOSED: 'info' }[status] || 'info'
}

function statusText(status) {
  return statusOptions.find((item) => item.value === status)?.label || status
}

function severityType(severity) {
  return { INFO: 'info', WARNING: 'warning', ERROR: 'danger', CRITICAL: 'danger' }[severity] || 'info'
}

function outcomeType(outcome) {
  if (!outcome) return 'info'
  if (outcome === 'SUCCESS') return 'success'
  if (outcome === 'SLOW_RESPONSE' || outcome === 'BUSINESS_CONFLICT') return 'warning'
  return 'danger'
}

function taskStatusType(status) {
  return { SUCCEEDED: 'success', FAILED: 'danger', RUNNING: 'warning' }[status] || 'info'
}

function toolSuccessType(success) {
  return success ? 'success' : 'danger'
}

function agentStatusType(status) {
  return { PENDING: 'info', RUNNING: 'warning', SUCCEEDED: 'success', FAILED: 'danger' }[status] || 'info'
}

function agentStatusText(status) {
  return { PENDING: '排队中', RUNNING: '调查中', SUCCEEDED: '已完成', FAILED: '执行失败' }[status] || status
}

function resultStatusText(status) {
  return {
    ROOT_CAUSE_IDENTIFIED: '已识别根因',
    INSUFFICIENT_EVIDENCE: '证据不足'
  }[status] || '未形成结论'
}

function resultStatusType(status) {
  return status === 'ROOT_CAUSE_IDENTIFIED' ? 'success' : 'warning'
}

function confidenceType(level) {
  return { HIGH: 'success', MEDIUM: 'warning', LOW: 'info' }[level] || 'info'
}

function triggerText(source) {
  return { AGENT: '手动启动', AGENT_RETRY: '失败重试' }[source] || source
}

function traceTitle(entry) {
  return {
    MODEL_DECISION: '模型决策',
    TOOL_EXECUTION: '工具执行',
    TOOL_REJECTED: '工具调用被拒绝',
    AGENT_ERROR: 'Agent 执行异常',
    AGENT_RUN_SUMMARY: '本次运行统计'
  }[entry?.type] || entry?.type || '决策记录'
}

function traceTagType(entry) {
  if (entry?.type === 'AGENT_ERROR' || entry?.status === 'FAILED') return 'danger'
  if (entry?.type === 'TOOL_REJECTED' || entry?.status === 'REJECTED') return 'warning'
  if (entry?.decision === 'FINAL_REPORT_ACCEPTED' || entry?.status === 'SUCCEEDED' || entry?.status === 'ACCEPTED') {
    return 'success'
  }
  return 'info'
}

function tracePrimary(entry) {
  if (entry?.type === 'AGENT_RUN_SUMMARY') {
    return `模型调用 ${entry.modelCallCount ?? '—'} 次 · Token ${entry.totalTokens ?? '—'}`
  }
  if (entry?.type === 'TOOL_EXECUTION') {
    return `${entry.tool} · ${entry.status === 'SUCCEEDED' ? '成功' : '失败'}`
  }
  if (entry?.type === 'TOOL_REJECTED') {
    return `${entry.tool || '未知工具'} 被拒绝`
  }
  if (entry?.type === 'AGENT_ERROR') {
    return 'Agent 执行异常'
  }
  return entry?.decision || '模型决策'
}

function traceSecondary(entry) {
  if (entry?.type === 'AGENT_RUN_SUMMARY') {
    const parts = []
    if (entry.usageNote) parts.push(entry.usageNote)
    if (entry.modelLatencyMsTotal != null) parts.push(`模型耗时 ${entry.modelLatencyMsTotal} ms`)
    return parts.join(' · ')
  }
  const parts = []
  if (entry?.round != null) parts.push(`第 ${entry.round} 轮`)
  if (entry?.reason) parts.push(entry.reason)
  if (entry?.evidenceCount != null) parts.push(`证据 ${entry.evidenceCount} 条`)
  if (entry?.latencyMs != null) parts.push(`${entry.latencyMs} ms`)
  if (entry?.modelLatencyMs != null) parts.push(`${entry.modelLatencyMs} ms`)
  if (entry?.usageAvailable === true) {
    parts.push(`input ${entry.promptTokens} / output ${entry.completionTokens} tokens`)
  } else if (entry?.usageAvailable === false) {
    parts.push(entry.usageNote || 'usage 缺失')
  }
  return parts.join(' · ')
}

function traceError(entry) {
  return entry?.error || entry?.validationError || ''
}

function evidenceSource(evidenceId) {
  const invocation = agentInvocations.value.find(
    (item) => Array.isArray(item.outputPayload?.evidenceIds) && item.outputPayload.evidenceIds.includes(evidenceId)
  )
  return invocation ? `${invocation.toolName} · 调用 #${invocation.sequenceNo}` : '真实工具结果'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await fetchFaultEventDetail(route.params.eventId)
  } catch (ex) {
    error.value = ex.message || '无法加载故障事件详情'
  } finally {
    loading.value = false
  }
}

async function loadTools() {
  try {
    tools.value = await fetchDiagnosticTools()
  } catch (ex) {
    tools.value = []
  }
}

async function handleCollect() {
  collecting.value = true
  error.value = ''
  try {
    await collectFaultEventEvidence(route.params.eventId, {
      requestId: event.value?.requestId || undefined,
      logLimit: 100
    })
    await load()
    activeTab.value = 'logs'
    ElMessage.success('证据采集完成，已写入事件与 tool_invocation')
  } catch (ex) {
    error.value = ex.message || '证据采集失败'
  } finally {
    collecting.value = false
  }
}

async function handleStatus(target) {
  updating.value = true
  error.value = ''
  try {
    await updateFaultEventStatus(route.params.eventId, target)
    await load()
    ElMessage.success('事件状态已更新')
  } catch (ex) {
    error.value = ex.message || '状态更新失败'
  } finally {
    updating.value = false
  }
}

function stopAgentPolling() {
  if (agentPollTimer) {
    clearInterval(agentPollTimer)
    agentPollTimer = null
  }
}

async function loadAgentTasks(selectLatest = true) {
  try {
    const list = await fetchAgentTasks(route.params.eventId)
    agentTasks.value = list.filter(
      (task) => task.triggerSource === 'AGENT' || task.triggerSource === 'AGENT_RETRY'
    )
    if (selectLatest && agentTasks.value.length) {
      agentTask.value = await fetchAgentTask(agentTasks.value[0].id)
      if (agentActive.value) {
        startAgentPolling()
      }
    }
  } catch (ex) {
    error.value = ex.message || '无法加载 AI 诊断任务'
  }
}

function startAgentPolling() {
  stopAgentPolling()
  agentPollTimer = window.setInterval(pollAgentTask, 2500)
}

async function pollAgentTask() {
  const taskId = agentTask.value?.id
  if (!taskId) {
    stopAgentPolling()
    return
  }
  try {
    agentTask.value = await fetchAgentTask(taskId)
    if (!agentActive.value) {
      stopAgentPolling()
      await load()
      await loadAgentTasks(false)
    }
  } catch (ex) {
    stopAgentPolling()
    error.value = ex.message || 'Agent 任务状态刷新失败'
  }
}

async function refreshAgentTask() {
  if (!agentTask.value?.id) {
    agentLoading.value = true
    try {
      await loadAgentTasks()
    } finally {
      agentLoading.value = false
    }
    return
  }
  agentLoading.value = true
  error.value = ''
  try {
    agentTask.value = await fetchAgentTask(agentTask.value.id)
    if (agentActive.value) {
      startAgentPolling()
    }
  } catch (ex) {
    error.value = ex.message || '无法刷新 Agent 任务'
  } finally {
    agentLoading.value = false
  }
}

async function selectAgentTask(taskId) {
  if (agentLoading.value) return
  agentLoading.value = true
  stopAgentPolling()
  try {
    agentTask.value = await fetchAgentTask(taskId)
    activeTab.value = 'agent'
    if (agentActive.value) {
      startAgentPolling()
    }
  } catch (ex) {
    error.value = ex.message || '无法加载 Agent 任务详情'
  } finally {
    agentLoading.value = false
  }
}

async function handleStartAgent() {
  if (agentActive.value) return
  agentStarting.value = true
  error.value = ''
  try {
    agentTask.value = await startAgentDiagnosis(route.params.eventId)
    activeTab.value = 'agent'
    await loadAgentTasks(false)
    startAgentPolling()
    await load()
    ElMessage.success('AI 诊断任务已创建，Agent 正在执行')
  } catch (ex) {
    error.value = ex.message || '启动 AI 诊断失败'
  } finally {
    agentStarting.value = false
  }
}

async function handleRetryAgent() {
  if (!agentTask.value?.id) return
  agentRetrying.value = true
  error.value = ''
  try {
    agentTask.value = await retryAgentTask(agentTask.value.id)
    await loadAgentTasks(false)
    startAgentPolling()
    await load()
    ElMessage.success('已创建重试任务，原任务记录保留')
  } catch (ex) {
    error.value = ex.message || '重试 Agent 诊断失败'
  } finally {
    agentRetrying.value = false
  }
}

onMounted(async () => {
  await Promise.all([load(), loadTools()])
  await loadAgentTasks()
})

onBeforeUnmount(stopAgentPolling)
</script>

<template>
  <div class="page-stack" v-loading="loading">
    <section class="hero">
      <div>
        <el-button text :icon="ArrowLeft" @click="router.push('/fault-events')">返回事件列表</el-button>
        <div class="hero-eyebrow detail-eyebrow">FAULT EVENT DETAIL</div>
        <h1>故障事件详情</h1>
        <p v-if="event">
          {{ event.summary }}
        </p>
      </div>
      <el-tag v-if="event" :type="statusType(event.status)" effect="dark" size="large">
        {{ statusText(event.status) }}
      </el-tag>
    </section>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <template v-if="event">
      <el-card shadow="never" class="panel-card">
        <template #header>
          <div class="card-header">
            <div>
              <div class="card-title">事件基本信息</div>
              <div class="card-subtitle">数据来源：PostgreSQL fault_event · 事件 ID {{ event.id }}</div>
            </div>
            <div class="header-actions">
              <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
              <el-button
                v-if="['OPEN', 'DIAGNOSING'].includes(event.status) && !agentActive"
                type="primary"
                :icon="VideoPlay"
                :loading="agentStarting"
                @click="handleStartAgent"
              >
                启动AI诊断
              </el-button>
              <el-button
                v-if="event.status === 'OPEN'"
                plain
                :loading="updating"
                @click="handleStatus('DIAGNOSING')"
              >
                标记诊断中
              </el-button>
              <el-button
                v-if="['OPEN', 'DIAGNOSING'].includes(event.status)"
                type="success"
                plain
                :loading="updating"
                @click="handleStatus('RESOLVED')"
              >
                标记已解决
              </el-button>
              <el-button
                v-if="['OPEN', 'DIAGNOSING', 'RESOLVED'].includes(event.status)"
                :loading="updating"
                @click="handleStatus('CLOSED')"
              >
                关闭事件
              </el-button>
            </div>
          </div>
        </template>

        <el-descriptions :column="3" border>
          <el-descriptions-item label="服务">{{ event.serviceName }}</el-descriptions-item>
          <el-descriptions-item label="观测类型">{{ event.eventType }}</el-descriptions-item>
          <el-descriptions-item label="严重级别">
            <el-tag :type="severityType(event.severity)" effect="light">{{ event.severity }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="来源">
            <el-tag :type="event.source === 'EXPERIMENT' ? 'warning' : 'info'" effect="plain">
              {{ event.source === 'EXPERIMENT' ? '故障注入实验' : '人工录入' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="发生时间">{{ formatTime(event.occurredAt) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatTime(event.updatedAt) }}</el-descriptions-item>
          <el-descriptions-item label="请求 ID">
            <code>{{ event.requestId || '—' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="实验 ID">
            <code>{{ event.experimentId || '—' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="证据采集时间">
            {{ formatTime(event.evidenceCollectedAt) }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" class="panel-card">
        <template #header>
          <div class="card-header">
            <div>
              <div class="card-title">实验预设与实际观测</div>
              <div class="card-subtitle">两者分开保存；预设故障类型不是根因结论</div>
            </div>
          </div>
        </template>

        <el-row :gutter="16">
          <el-col :xs="24" :lg="12">
            <div class="sub-card">
              <div class="sub-title">
                <el-tag type="warning" effect="plain">实验预设</el-tag>
              </div>
              <el-descriptions v-if="event.experimentFaultType" :column="1" border size="small">
                <el-descriptions-item label="预设故障类型">{{ event.experimentFaultType }}</el-descriptions-item>
                <el-descriptions-item label="预设参数">
                  <pre class="json-block">{{ pretty(presetParameters) }}</pre>
                </el-descriptions-item>
              </el-descriptions>
              <el-empty v-else description="非实验事件，无预设参数" :image-size="60" />
              <el-alert
                type="warning"
                :closable="false"
                show-icon
                title="预设类型仅用于实验编排，不会作为 Agent 的根因答案"
                class="sub-alert"
              />
            </div>
          </el-col>
          <el-col :xs="24" :lg="12">
            <div class="sub-card">
              <div class="sub-title">
                <el-tag :type="outcomeType(observed?.outcome)" effect="light">
                  {{ observed?.outcome || '无观测结果' }}
                </el-tag>
              </div>
              <el-descriptions v-if="observed" :column="2" border size="small">
                <el-descriptions-item label="HTTP 状态">{{ observed.httpStatus ?? '—' }}</el-descriptions-item>
                <el-descriptions-item label="耗时">{{ observed.durationMs ?? '—' }} ms</el-descriptions-item>
                <el-descriptions-item label="下游错误类型">
                  {{ observed.downstreamErrorType || '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="订单状态">{{ observed.orderStatus || '—' }}</el-descriptions-item>
                <el-descriptions-item label="订单号" :span="2">{{ observed.orderId || '—' }}</el-descriptions-item>
                <el-descriptions-item label="响应消息" :span="2">
                  {{ observed.responseMessage || '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="观测时间" :span="2">
                  {{ formatTime(observed.observedAt) }}
                </el-descriptions-item>
              </el-descriptions>
              <el-empty v-else description="无实际观测结果" :image-size="60" />
              <div class="data-note">
                实际结果由订单服务 HTTP 状态、下游错误类型标记和请求耗时共同判定，不包含根因推断。
              </div>
            </div>
          </el-col>
        </el-row>
      </el-card>

      <el-card shadow="never" class="panel-card">
        <template #header>
          <div class="card-header">
            <div>
              <div class="card-title">证据采集与诊断证据</div>
              <div class="card-subtitle">
                调用真实 query_logs / query_metrics，写入 diagnosis_task 与 tool_invocation
              </div>
            </div>
            <el-button type="primary" :icon="Search" :loading="collecting" @click="handleCollect">
              {{ evidence ? '重新采集证据' : '采集真实证据' }}
            </el-button>
          </div>
        </template>

        <el-alert
          v-if="evidence"
          type="success"
          :closable="false"
          show-icon
          :title="`已采集证据 · ${formatTime(event.evidenceCollectedAt)}`"
        >
          <div class="evidence-meta">
            <span>查询窗口：{{ formatTime(evidence.windowStart) }} ~ {{ formatTime(evidence.windowEnd) }}</span>
            <span>requestId：{{ evidence.requestId || '全部' }}</span>
            <span>服务范围：{{ evidence.serviceName || 'all' }}</span>
          </div>
        </el-alert>
        <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
          title="尚未采集证据"
          description="点击“采集真实证据”会调用诊断工具查询两个实验服务的日志与指标，并写入数据库。"
        />

        <el-tabs v-model="activeTab" class="detail-tabs">
          <el-tab-pane label="实际请求结果" name="result">
            <div class="json-grid">
              <div>
                <div class="json-title">observed_result（实际观测）</div>
                <pre class="json-block">{{ pretty(observed) }}</pre>
              </div>
              <div>
                <div class="json-title">details（请求上下文）</div>
                <pre class="json-block">{{ pretty(details) }}</pre>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="关联日志" name="logs">
            <template v-if="logsTool">
              <el-alert
                v-if="!logsTool.success"
                type="error"
                :closable="false"
                show-icon
                :title="logsTool.error || 'query_logs 采集失败'"
              />
              <template v-else>
                <div class="source-line">
                  <span>数据来源：{{ (logsTool.output?.dataSources || []).join('、') || '—' }}</span>
                  <span>证据条数：{{ logsTool.output?.itemCount ?? logItems.length }}</span>
                  <span>工具调用 ID：<code>{{ logsTool.invocationId }}</code></span>
                </div>
                <el-table :data="logItems" stripe size="small" max-height="420">
                  <el-table-column prop="timestamp" label="时间" width="190">
                    <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
                  </el-table-column>
                  <el-table-column prop="sourceService" label="服务" width="140" />
                  <el-table-column prop="requestId" label="requestId" width="220" show-overflow-tooltip />
                  <el-table-column prop="method" label="方法" width="80" />
                  <el-table-column prop="path" label="路径" min-width="210" show-overflow-tooltip />
                  <el-table-column prop="status" label="状态" width="80" />
                  <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
                  <el-table-column prop="errorType" label="错误类型" width="150" />
                  <template #empty>
                    <el-empty description="查询窗口内没有匹配的业务请求日志" :image-size="60" />
                  </template>
                </el-table>
              </template>
            </template>
            <el-empty v-else description="尚未采集日志证据" :image-size="70" />
          </el-tab-pane>

          <el-tab-pane label="关联指标" name="metrics">
            <template v-if="metricsTool">
              <el-alert
                v-if="!metricsTool.success"
                type="error"
                :closable="false"
                show-icon
                :title="metricsTool.error || 'query_metrics 采集失败'"
              />
              <template v-else>
                <div class="source-line">
                  <span>数据来源：{{ (metricsTool.output?.dataSources || []).join('、') || '—' }}</span>
                  <span>查询服务：{{ metricsTool.output?.query?.service || 'all' }}</span>
                  <span>工具调用 ID：<code>{{ metricsTool.invocationId }}</code></span>
                </div>
                <div class="json-title">分钟级指标汇总</div>
                <el-table :data="metricServices" stripe size="small">
                  <el-table-column prop="serviceName" label="服务" width="160" />
                  <el-table-column prop="source" label="数据来源" min-width="260" show-overflow-tooltip />
                  <el-table-column label="请求数" width="90">
                    <template #default="{ row }">{{ row.summary?.requestCount ?? 0 }}</template>
                  </el-table-column>
                  <el-table-column label="5xx" width="80">
                    <template #default="{ row }">{{ row.summary?.serverErrorCount ?? 0 }}</template>
                  </el-table-column>
                  <el-table-column label="4xx" width="80">
                    <template #default="{ row }">{{ row.summary?.clientErrorCount ?? 0 }}</template>
                  </el-table-column>
                  <el-table-column label="平均耗时(ms)" width="120">
                    <template #default="{ row }">
                      {{ Math.round(row.summary?.averageDurationMs ?? 0) }}
                    </template>
                  </el-table-column>
                  <el-table-column label="最大耗时(ms)" width="120">
                    <template #default="{ row }">{{ row.summary?.maxDurationMs ?? 0 }}</template>
                  </el-table-column>
                </el-table>

                <div class="json-title metric-title">分钟桶明细</div>
                <el-table :data="metricRows" stripe size="small" max-height="320">
                  <el-table-column prop="serviceName" label="服务" width="160" />
                  <el-table-column prop="minute" label="分钟" width="190">
                    <template #default="{ row }">{{ formatTime(row.minute) }}</template>
                  </el-table-column>
                  <el-table-column prop="requestCount" label="请求数" width="90" />
                  <el-table-column prop="serverErrorCount" label="5xx" width="80" />
                  <el-table-column prop="clientErrorCount" label="4xx" width="80" />
                  <el-table-column label="平均耗时(ms)" width="120">
                    <template #default="{ row }">{{ Math.round(row.averageDurationMs ?? 0) }}</template>
                  </el-table-column>
                  <el-table-column prop="maxDurationMs" label="最大耗时(ms)" width="120" />
                  <template #empty>
                    <el-empty description="查询窗口内没有分钟级指标" :image-size="60" />
                  </template>
                </el-table>
              </template>
            </template>
            <el-empty v-else description="尚未采集指标证据" :image-size="70" />
          </el-tab-pane>

          <el-tab-pane label="工具调用审计" name="audit">
            <el-table :data="tasks" stripe size="small">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="task-expand">
                    <div v-for="invocation in row.toolInvocations" :key="invocation.id" class="invocation-block">
                      <div class="invocation-head">
                        <el-tag :type="toolSuccessType(invocation.status === 'SUCCEEDED')" size="small">
                          {{ invocation.toolName }} · {{ invocation.status }}
                        </el-tag>
                        <span class="muted">{{ invocation.latencyMs }} ms · {{ formatTime(invocation.invokedAt) }}</span>
                      </div>
                      <el-alert
                        v-if="invocation.errorMessage"
                        type="error"
                        :closable="false"
                        show-icon
                        :title="invocation.errorMessage"
                      />
                      <div class="json-grid invocation-json">
                        <div>
                          <div class="json-title">输入</div>
                          <pre class="json-block">{{ pretty(invocation.inputPayload) }}</pre>
                        </div>
                        <div>
                          <div class="json-title">输出</div>
                          <pre class="json-block">{{ pretty(invocation.outputPayload) }}</pre>
                        </div>
                      </div>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="triggerSource" label="触发来源" width="160" />
              <el-table-column prop="goal" label="任务目标" min-width="260" show-overflow-tooltip />
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="taskStatusType(row.status)" size="small">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="工具数" width="90">
                <template #default="{ row }">{{ row.toolInvocations?.length || 0 }}</template>
              </el-table-column>
              <el-table-column label="创建时间" width="190">
                <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
              </el-table-column>
              <template #empty>
                <el-empty description="尚未创建诊断任务；采集证据时会创建真实任务记录" :image-size="60" />
              </template>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="Agent 诊断" name="agent">
            <div class="agent-toolbar">
              <div>
                <div class="agent-title">DeepSeek Agent 自主诊断</div>
                <div class="card-subtitle">
                  模型逐轮决定调用 query_logs / query_metrics 或结束调查；只允许只读工具，结论必须引用真实证据
                </div>
              </div>
              <div class="header-actions">
                <el-button :icon="Refresh" :loading="agentLoading" @click="refreshAgentTask">刷新</el-button>
                <el-button
                  type="primary"
                  :icon="VideoPlay"
                  :loading="agentStarting"
                  :disabled="agentActive"
                  @click="handleStartAgent"
                >
                  {{ agentTask ? '重新诊断' : '启动AI诊断' }}
                </el-button>
                <el-button
                  v-if="agentTask?.status === 'FAILED'"
                  type="warning"
                  plain
                  :icon="RefreshRight"
                  :loading="agentRetrying"
                  @click="handleRetryAgent"
                >
                  重试失败任务
                </el-button>
              </div>
            </div>

            <el-alert
              v-if="!agentTask"
              type="info"
              :closable="false"
              show-icon
              title="尚未启动 AI 诊断"
              description="点击“启动AI诊断”后，Agent 会基于事件观测结果自主选择只读工具并多轮调查。若后端未配置 DEEPSEEK_API_KEY，任务会明确失败，不会生成伪造结论。"
            />

            <template v-else>
              <div class="agent-status-line">
                <el-tag :type="agentStatusType(agentTask.status)" effect="dark">
                  {{ agentStatusText(agentTask.status) }}
                </el-tag>
                <el-tag v-if="agentTask.resultStatus" :type="resultStatusType(agentTask.resultStatus)" effect="light">
                  {{ resultStatusText(agentTask.resultStatus) }}
                </el-tag>
                <span class="muted">触发：{{ triggerText(agentTask.triggerSource) }}</span>
                <span class="muted">模型：{{ agentTask.modelName || '未调用' }}</span>
                <span class="muted">创建：{{ formatTime(agentTask.createdAt) }}</span>
                <span v-if="agentTask.completedAt" class="muted">完成：{{ formatTime(agentTask.completedAt) }}</span>
              </div>

              <el-alert
                v-if="agentTask.status === 'SUCCEEDED'"
                type="success"
                :closable="false"
                show-icon
                title="AI 诊断已完成，事件仍需人工处置"
                description="诊断完成不代表故障已解决：事件只会从“诊断中”恢复为“待处理”，只有人工确认后才会变为“已解决/已关闭”。诊断历史在下方任务列表中保留。"
              />

              <el-alert
                v-if="agentTask.status === 'FAILED'"
                type="error"
                :closable="false"
                show-icon
                title="Agent 执行失败，未生成诊断结论"
                :description="agentTask.failureReason || '未记录失败原因'"
              />
              <el-alert
                v-else-if="agentInsufficient"
                type="warning"
                :closable="false"
                show-icon
                :title="`证据不足：${agentReport?.reason || '模型未形成可验证根因'}`"
                :description="limitations.length ? `限制：${limitations.join('；')}` : '未生成根因、日志或置信度，禁止将证据不足当作确定结论。'"
              />
              <el-alert
                v-else-if="agentActive"
                type="info"
                :closable="false"
                show-icon
                title="Agent 正在进行多轮决策与工具调用"
                description="页面每 2.5 秒自动刷新任务状态、模型决策时间线和工具审计记录。"
              />
              <el-alert
                v-else-if="agentRootCause"
                type="success"
                :closable="false"
                show-icon
                title="根因报告已生成，需人工确认"
                description="报告中的证据 ID 均来自真实工具结果；系统不会自动执行任何处置动作。"
              />

              <el-card v-if="agentRootCause" shadow="never" class="agent-report-card">
                <template #header>
                  <div class="card-header">
                    <div>
                      <div class="card-title">根因分析报告</div>
                      <div class="card-subtitle">
                        模型 {{ agentReport.model || agentTask.modelName || '—' }} ·
                        模型调用 {{ agentReport.modelCallCount ?? '—' }} 次 ·
                        工具调用 {{ agentReport.toolCallCount ?? '—' }} 次 ·
                        Token {{ agentReport.totalTokens ?? '—' }}
                        （input {{ agentReport.promptTokens ?? '—' }} / output {{ agentReport.completionTokens ?? '—' }}）
                      </div>
                      <div class="card-subtitle">{{ agentReport.usageNote || 'usage 统计来自模型 Provider 响应' }}</div>
                    </div>
                    <div class="header-actions">
                      <el-tag
                        v-if="agentReport.modelCallsWithoutUsage"
                        type="warning"
                        effect="plain"
                        size="small"
                      >
                        {{ agentReport.modelCallsWithoutUsage }} 轮 usage 缺失
                      </el-tag>
                      <el-tag :type="confidenceType(agentReport.confidenceLevel)" effect="dark">
                        置信等级 {{ agentReport.confidenceLevel }}
                      </el-tag>
                    </div>
                  </div>
                </template>

                <div class="root-cause">{{ agentReport.rootCause }}</div>
                <el-descriptions :column="2" border size="small">
                  <el-descriptions-item label="影响服务">
                    <el-tag
                      v-for="service in agentReport.affectedServices || []"
                      :key="service"
                      size="small"
                      type="warning"
                      effect="plain"
                      class="service-tag"
                    >
                      {{ service }}
                    </el-tag>
                  </el-descriptions-item>
                  <el-descriptions-item label="故障假设">{{ agentReport.hypothesis }}</el-descriptions-item>
                </el-descriptions>

                <div class="report-section">
                  <div class="json-title">支持证据（{{ supportingEvidence.length }}）</div>
                  <div v-if="supportingEvidence.length" class="evidence-list">
                    <div v-for="evidenceId in supportingEvidence" :key="evidenceId" class="evidence-row">
                      <code>{{ evidenceId }}</code>
                      <span class="muted">{{ evidenceSource(evidenceId) }}</span>
                    </div>
                  </div>
                  <el-empty v-else description="报告未引用支持证据" :image-size="50" />
                </div>

                <div v-if="contradictingEvidence.length" class="report-section">
                  <div class="json-title">反驳证据（{{ contradictingEvidence.length }}）</div>
                  <div class="evidence-list">
                    <div v-for="evidenceId in contradictingEvidence" :key="evidenceId" class="evidence-row">
                      <code>{{ evidenceId }}</code>
                      <span class="muted">{{ evidenceSource(evidenceId) }}</span>
                    </div>
                  </div>
                </div>

                <div class="report-section">
                  <div class="json-title">建议处置（人工确认后执行）</div>
                  <ol v-if="recommendations.length" class="action-list">
                    <li v-for="action in recommendations" :key="action">{{ action }}</li>
                  </ol>
                  <el-empty v-else description="模型未给出处置建议" :image-size="50" />
                  <el-alert
                    type="warning"
                    :closable="false"
                    show-icon
                    title="系统不自动修复、不执行 Shell 或任意 SQL；处置建议必须由人工确认"
                    class="sub-alert"
                  />
                </div>

                <div v-if="limitations.length" class="report-section">
                  <div class="json-title">分析限制</div>
                  <ul class="limit-list">
                    <li v-for="item in limitations" :key="item">{{ item }}</li>
                  </ul>
                </div>
              </el-card>

              <el-card v-if="agentInsufficient && agentReport" shadow="never" class="agent-report-card">
                <template #header>
                  <div class="card-title">证据不足说明</div>
                </template>
                <el-descriptions :column="1" border size="small">
                  <el-descriptions-item label="原因">{{ agentReport.reason || '—' }}</el-descriptions-item>
                  <el-descriptions-item label="已观测证据">
                    <template v-if="(agentReport.observedEvidenceIds || []).length">
                      <code
                        v-for="evidenceId in agentReport.observedEvidenceIds"
                        :key="evidenceId"
                        class="evidence-code"
                      >
                        {{ evidenceId }}
                      </code>
                    </template>
                    <span v-else>无</span>
                  </el-descriptions-item>
                </el-descriptions>
              </el-card>

              <div class="json-title tool-title">Agent 决策与工具执行时间线</div>
              <el-timeline v-if="agentTrace.length" class="agent-timeline">
                <el-timeline-item
                  v-for="(entry, index) in agentTrace"
                  :key="`${entry.at || index}-${index}`"
                  :timestamp="formatTime(entry.at)"
                  placement="top"
                >
                  <div class="trace-item">
                    <div class="trace-head">
                      <el-tag size="small" :type="traceTagType(entry)">{{ traceTitle(entry) }}</el-tag>
                      <strong class="trace-primary">{{ tracePrimary(entry) }}</strong>
                    </div>
                    <div v-if="traceSecondary(entry)" class="trace-secondary">{{ traceSecondary(entry) }}</div>
                    <el-alert
                      v-if="traceError(entry)"
                      type="error"
                      :closable="false"
                      show-icon
                      :title="traceError(entry)"
                      class="trace-error"
                    />
                  </div>
                </el-timeline-item>
              </el-timeline>
              <el-empty v-else description="暂无模型决策记录" :image-size="60" />

              <div class="json-title tool-title">工具调用审计（tool_invocation）</div>
              <el-table :data="agentInvocations" stripe size="small" max-height="360">
                <el-table-column type="expand">
                  <template #default="{ row }">
                    <div class="json-grid invocation-json">
                      <div>
                        <div class="json-title">输入</div>
                        <pre class="json-block">{{ pretty(row.inputPayload) }}</pre>
                      </div>
                      <div>
                        <div class="json-title">输出（含 evidenceIds）</div>
                        <pre class="json-block">{{ pretty(row.outputPayload) }}</pre>
                      </div>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column prop="sequenceNo" label="#" width="55" />
                <el-table-column prop="toolName" label="工具" width="150" />
                <el-table-column label="状态" width="110">
                  <template #default="{ row }">
                    <el-tag :type="toolSuccessType(row.status === 'SUCCEEDED')" size="small">
                      {{ row.status }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="latencyMs" label="耗时(ms)" width="100" />
                <el-table-column label="调用时间" width="190">
                  <template #default="{ row }">{{ formatTime(row.invokedAt) }}</template>
                </el-table-column>
                <el-table-column prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip />
                <template #empty>
                  <el-empty description="Agent 尚未调用工具" :image-size="60" />
                </template>
              </el-table>

              <div class="json-title tool-title">Agent 任务历史</div>
              <el-table :data="agentTasks" stripe size="small" @row-click="(row) => selectAgentTask(row.id)">
                <el-table-column label="创建时间" width="190">
                  <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
                </el-table-column>
                <el-table-column label="触发" width="100">
                  <template #default="{ row }">{{ triggerText(row.triggerSource) }}</template>
                </el-table-column>
                <el-table-column label="执行状态" width="110">
                  <template #default="{ row }">
                    <el-tag :type="agentStatusType(row.status)" size="small">{{ agentStatusText(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="结论" width="120">
                  <template #default="{ row }">
                    {{ row.resultStatus ? resultStatusText(row.resultStatus) : '—' }}
                  </template>
                </el-table-column>
                <el-table-column label="置信" width="80">
                  <template #default="{ row }">{{ row.confidenceLevel || '—' }}</template>
                </el-table-column>
                <el-table-column prop="modelName" label="模型" width="140" />
                <el-table-column label="根因 / 失败原因" min-width="260" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.rootCause || row.failureReason || '—' }}</template>
                </el-table-column>
                <el-table-column label="操作" width="80">
                  <template #default="{ row }">
                    <el-button link type="primary" @click.stop="selectAgentTask(row.id)">查看</el-button>
                  </template>
                </el-table-column>
                <template #empty>
                  <el-empty description="暂无 Agent 诊断任务" :image-size="60" />
                </template>
              </el-table>
            </template>

            <div class="json-title tool-title">已注册诊断工具</div>
            <div class="tool-grid">
              <div v-for="tool in tools" :key="tool.name" class="tool-card">
                <div class="tool-name">{{ tool.name }}</div>
                <div class="tool-desc">{{ tool.description }}</div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.detail-eyebrow {
  margin-top: 10px;
}

.panel-card {
  border: 1px solid var(--rca-border);
  border-radius: var(--rca-radius);
}

.card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.card-title {
  color: var(--rca-text);
  font-size: 15px;
  font-weight: 650;
}

.card-subtitle {
  margin-top: 4px;
  color: var(--rca-muted);
  font-size: 12px;
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.sub-card {
  height: 100%;
  padding: 14px;
  border: 1px solid #e8eef8;
  border-radius: 10px;
  background: #fbfcff;
}

.sub-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.sub-alert {
  margin-top: 12px;
}

.data-note {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f6f9fe;
  color: #526179;
  font-size: 12px;
  line-height: 1.7;
}

.evidence-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 4px;
  font-size: 12px;
}

.detail-tabs {
  margin-top: 14px;
}

.json-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.json-title {
  margin-bottom: 7px;
  color: var(--rca-text);
  font-size: 12px;
  font-weight: 600;
}

.json-block {
  max-height: 360px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #0f172a;
  color: #dbeafe;
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 11px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-all;
}

.source-line {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 12px;
  color: var(--rca-muted);
  font-size: 12px;
}

.metric-title {
  margin-top: 18px;
}

.task-expand {
  padding: 8px 14px 14px;
}

.invocation-block {
  padding: 10px 0;
  border-bottom: 1px dashed #dce5f2;
}

.invocation-block:last-child {
  border-bottom: none;
}

.invocation-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.invocation-json {
  margin-top: 8px;
}

.muted {
  color: var(--rca-muted);
  font-size: 12px;
}

.agent-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px;
  border: 1px solid #dbe6f5;
  border-radius: 10px;
  background: #f8fbff;
}

.agent-title {
  color: var(--rca-text);
  font-size: 14px;
  font-weight: 650;
}

.agent-status-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 16px;
  margin: 14px 0;
}

.agent-report-card {
  margin-top: 14px;
  border: 1px solid #dbe6f5;
  border-radius: 10px;
}

.root-cause {
  margin-bottom: 14px;
  padding: 14px 16px;
  border-left: 4px solid #2563eb;
  border-radius: 8px;
  background: #eff6ff;
  color: #0f172a;
  font-size: 15px;
  font-weight: 650;
  line-height: 1.75;
}

.service-tag {
  margin-right: 6px;
}

.report-section {
  margin-top: 18px;
}

.evidence-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.evidence-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  border: 1px solid #e8eef8;
  border-radius: 7px;
  background: #fbfcff;
}

.evidence-code {
  display: inline-block;
  margin-right: 8px;
  margin-bottom: 4px;
}

.action-list,
.limit-list {
  margin: 0;
  padding-left: 22px;
  color: #334155;
  font-size: 13px;
  line-height: 1.9;
}

.agent-timeline {
  padding: 8px 4px 0;
}

.trace-item {
  padding: 10px 12px;
  border: 1px solid #e8eef8;
  border-radius: 9px;
  background: #ffffff;
}

.trace-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.trace-primary {
  color: var(--rca-text);
  font-size: 13px;
}

.trace-secondary {
  margin-top: 6px;
  color: var(--rca-muted);
  font-size: 12px;
  line-height: 1.7;
}

.trace-error {
  margin-top: 8px;
}

.tool-title {
  margin-top: 18px;
}

.tool-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 10px;
}

.tool-card {
  padding: 12px 14px;
  border: 1px solid #e8eef8;
  border-radius: 9px;
  background: #ffffff;
}

.tool-name {
  color: #1d4ed8;
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
}

.tool-desc {
  margin-top: 5px;
  color: var(--rca-muted);
  font-size: 12px;
  line-height: 1.6;
}

code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #eef2f8;
  color: #334155;
  font-size: 12px;
}

@media (max-width: 900px) {
  .json-grid {
    grid-template-columns: 1fr;
  }
}
</style>

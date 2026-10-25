<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search, VideoPlay } from '@element-plus/icons-vue'
import {
  createRemediation,
  fetchRemediationByIdempotencyKey,
  fetchRemediations
} from '../api/remediations'

const props = defineProps({
  event: { type: Object, required: true },
  agentTasks: { type: Array, default: () => [] }
})

const emit = defineEmits(['changed'])

const ACTION_TYPE = 'DISABLE_EXPERIMENT_FAULT'
const ACTIVE_REMEDIATION_STATUSES = ['PENDING', 'EXECUTING']
// 与服务端 RemediationService 的人工核查策略保持一致：验证类终态一定需要确认，
// 动作失败只有失败码表明外部结果可能未知时才需要确认。
const REVIEW_REQUIRED_STATUSES = ['VERIFICATION_FAILED', 'VERIFICATION_INCONCLUSIVE']
const AMBIGUOUS_ACTION_FAILURE_CODES = [
  'INVENTORY_UNAVAILABLE',
  'ACTION_EXECUTION_ERROR',
  'INTERNAL_ERROR'
]

const STATUS_META = {
  PENDING: {
    text: '待执行',
    type: 'info',
    hint: '处置已受理，尚未开始外部调用。'
  },
  EXECUTING: {
    text: '执行中',
    type: 'warning',
    hint: '正在关闭实验故障或执行真实恢复验证。'
  },
  SUCCEEDED: {
    text: '处置成功',
    type: 'success',
    hint: '动作执行成功，真实 checkout 验证通过，故障事件已更新为 RESOLVED。'
  },
  ACTION_FAILED: {
    text: '动作失败',
    type: 'danger',
    hint: '条件关闭动作未成功，未执行恢复验证，故障事件未被自动解决。'
  },
  VERIFICATION_FAILED: {
    text: '验证失败',
    type: 'danger',
    hint: '关闭动作已成功，但真实 checkout 验证未通过，故障事件未被自动解决。'
  },
  VERIFICATION_INCONCLUSIVE: {
    text: '验证结果不确定',
    type: 'warning',
    hint: '无法确认恢复是否成功。请不要自动重试，先人工核查服务与库存状态。'
  },
  RECOVERY_REQUIRED: {
    text: '需要人工核查',
    type: 'danger',
    hint: '后端启动恢复发现中断记录，已知结果不完整。必须人工核查后才能再次处置。'
  }
}

const RECOVERY_REASON_TEXT = {
  INTERRUPTED_BEFORE_ACTION: '进程中断时动作尚未开始，未产生外部副作用。',
  INTERRUPTED_ACTION_UNKNOWN: '进程中断，无法确认条件关闭接口是否已被调用。',
  INTERRUPTED_ACTION_RESULT_UNREADABLE: '进程中断，动作结果无法读取，不能判定成功或失败。',
  INTERRUPTED_ACTION_FAILED: '进程中断前已记录动作失败。',
  INTERRUPTED_VERIFICATION_UNKNOWN: '动作可能已成功，但恢复验证结果未知。',
  INTERRUPTED_STATE_INCONSISTENT: '进程中断，记录状态与结果字段不一致。'
}

const HISTORY_STATUS_TEXT = {
  REQUEST_ACCEPTED: '请求已受理',
  PRECONDITIONS_CHECKED: '前置条件校验通过',
  PRECONDITIONS_FAILED: '前置条件校验失败',
  PRIOR_RECOVERY_ACKNOWLEDGED: '已确认前次中断记录',
  PRIOR_MANUAL_REVIEW_ACKNOWLEDGED: '已确认核查前次处置',
  FAULT_DISABLED: '实验故障已关闭',
  ACTION_FAILED: '关闭动作失败',
  VERIFICATION_PASSED: '恢复验证通过',
  VERIFICATION_FAILED: '恢复验证失败',
  VERIFICATION_INCONCLUSIVE: '恢复验证不确定',
  EXECUTION_ERROR: '执行异常',
  EVENT_STATUS_UPDATE_REQUESTED: '请求更新故障事件状态',
  RECOVERY_MARKED: '启动恢复标记为需人工核查'
}

const history = ref([])
const historyLoading = ref(false)
const historyLoaded = ref(false)
const historyError = ref('')

const dialogVisible = ref(false)
const submitting = ref(false)
const submitError = ref('')
const pendingKey = ref('')
const unknownKey = ref('')
const queryingUnknown = ref(false)
const ackChecked = ref(false)
const operatorNote = ref('')

const latestRemediation = computed(() => history.value[0] || null)

const activeRemediation = computed(
  () => history.value.find((item) => ACTIVE_REMEDIATION_STATUSES.includes(item.status)) || null
)

// 成功处置是终态事实：即使事件因诊断竞态仍显示 OPEN，也不能再显示普通处置入口。
const succeededRemediation = computed(
  () => history.value.find((item) => item.status === 'SUCCEEDED') || null
)

const agentRunning = computed(() =>
  props.agentTasks.some((task) => ['PENDING', 'RUNNING'].includes(task.status))
)

const eligibleTask = computed(
  () =>
    props.agentTasks.find(
      (task) => task.status === 'SUCCEEDED' && task.resultStatus === 'ROOT_CAUSE_IDENTIFIED'
    ) || null
)

const ackRequired = computed(() => {
  const record = latestRemediation.value
  if (!record) return false
  if (record.status === 'RECOVERY_REQUIRED') {
    return record.failureCode !== 'INTERRUPTED_BEFORE_ACTION'
  }
  if (REVIEW_REQUIRED_STATUSES.includes(record.status)) return true
  if (record.status === 'ACTION_FAILED') {
    return !record.failureCode || AMBIGUOUS_ACTION_FAILURE_CODES.includes(record.failureCode)
  }
  return false
})

const ackAlertTitle = computed(() => {
  const status = latestRemediation.value?.status
  if (status === 'RECOVERY_REQUIRED') {
    return '前次处置处于 RECOVERY_REQUIRED，本次必须完成人工核查确认'
  }
  if (status === 'VERIFICATION_FAILED') {
    return '前次真实 checkout 验证未通过，再次处置会再执行一次真实 checkout'
  }
  if (status === 'VERIFICATION_INCONCLUSIVE') {
    return '前次验证结果不确定，本次必须完成人工核查确认'
  }
  return '前次关闭动作结果无法确认，本次必须完成人工核查确认'
})

const remediationReady = computed(() => {
  if (!props.event || !historyLoaded.value) return false
  if (props.event.source !== 'EXPERIMENT') return false
  if (!props.event.experimentId) return false
  if (succeededRemediation.value) return false
  if (props.event.status !== 'OPEN') return false
  if (agentRunning.value) return false
  if (!eligibleTask.value) return false
  if (activeRemediation.value) return false
  return true
})

const ineligibleReason = computed(() => {
  if (!props.event) return '事件尚未加载'
  if (!historyLoaded.value) return '正在加载处置历史'
  if (props.event.source !== 'EXPERIMENT') return '仅支持处理实验注入的故障事件'
  if (!props.event.experimentId) return '事件缺少 experimentId，无法执行受控恢复'
  if (succeededRemediation.value) {
    return '该事件已有成功处置记录，系统不会重复执行 checkout；请人工核查故障事件状态'
  }
  if (props.event.status !== 'OPEN') return `事件当前状态为 ${props.event.status}，只有待处理事件可以处置`
  if (agentRunning.value) return 'Agent 诊断正在执行，请等待任务结束'
  if (activeRemediation.value) return '当前事件已有处置正在执行，请等待结果'
  if (!eligibleTask.value) return '需要先完成一次成功且识别到根因的 Agent 诊断'
  return ''
})

const actionEntryVisible = computed(
  () => historyLoaded.value && remediationReady.value && !unknownKey.value && !submitting.value
)

const submitDisabled = computed(
  () =>
    submitting.value ||
    !remediationReady.value ||
    !eligibleTask.value ||
    (ackRequired.value && (!ackChecked.value || !operatorNote.value.trim()))
)

async function loadHistory({ silent = false } = {}) {
  if (!silent) historyLoading.value = true
  historyError.value = ''
  try {
    const list = await fetchRemediations(props.event.id)
    history.value = Array.isArray(list) ? list : []
    historyLoaded.value = true
    if (
      unknownKey.value &&
      history.value.some((item) => item.idempotencyKey === unknownKey.value)
    ) {
      // 超时请求已经落库，后续展示以服务端记录为准。
      unknownKey.value = ''
      pendingKey.value = ''
      submitError.value = ''
    }
  } catch (ex) {
    historyError.value = ex.message || '无法加载处置记录'
  } finally {
    if (!silent) historyLoading.value = false
  }
}

function statusMeta(status) {
  return STATUS_META[status] || { text: status || '未知状态', type: 'info', hint: '' }
}

function statusText(status) {
  return statusMeta(status).text
}

function statusType(status) {
  return statusMeta(status).type
}

function statusHint(record) {
  if (!record) return ''
  if (record.status === 'SUCCEEDED' && !record.eventStatusUpdated) {
    return '动作执行成功且真实 checkout 验证通过，但故障事件未被本次处置更新为 RESOLVED。'
  }
  if (record.status === 'SUCCEEDED' && props.event?.status === 'OPEN') {
    return '该记录已完成成功处置，但故障事件当前仍为 OPEN；请人工核查，系统不会重复执行 checkout。'
  }
  return statusMeta(record.status).hint
}

function failureText(code) {
  if (!code) return '—'
  return RECOVERY_REASON_TEXT[code] || code
}

function auditStepText(step) {
  return HISTORY_STATUS_TEXT[step] || step || '审计记录'
}

function auditTagType(step) {
  if (['ACTION_FAILED', 'EXECUTION_ERROR', 'VERIFICATION_FAILED', 'RECOVERY_MARKED'].includes(step)) {
    return 'danger'
  }
  if (
    [
      'PRECONDITIONS_FAILED',
      'VERIFICATION_INCONCLUSIVE',
      'PRIOR_RECOVERY_ACKNOWLEDGED',
      'PRIOR_MANUAL_REVIEW_ACKNOWLEDGED'
    ].includes(step)
  ) {
    return 'warning'
  }
  if (['FAULT_DISABLED', 'VERIFICATION_PASSED', 'EVENT_STATUS_UPDATE_REQUESTED'].includes(step)) {
    return 'success'
  }
  return 'info'
}

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

function auditEntries(record) {
  return Array.isArray(record?.auditTrace) ? record.auditTrace : []
}

function actionOutcome(record) {
  const result = record?.actionResult
  if (!result || typeof result !== 'object') {
    return record?.status === 'ACTION_FAILED'
      ? { text: '动作失败', type: 'danger' }
      : { text: '结果未记录', type: 'info' }
  }
  if (result.success === true) return { text: '动作成功', type: 'success' }
  if (result.success === false) return { text: '动作失败', type: 'danger' }
  return { text: '结果未知', type: 'warning' }
}

function verificationOutcome(record) {
  const result = record?.verificationResult
  const value = result?.result
  if (value === 'PASS') return { text: '真实 checkout 验证通过', type: 'success' }
  if (value === 'FAIL') return { text: '真实 checkout 验证未通过', type: 'danger' }
  if (value === 'INCONCLUSIVE') return { text: '真实 checkout 验证不确定', type: 'warning' }
  if (record?.status === 'VERIFICATION_FAILED') return { text: '验证未通过', type: 'danger' }
  if (record?.status === 'VERIFICATION_INCONCLUSIVE') return { text: '验证不确定', type: 'warning' }
  return { text: '未执行或结果未记录', type: 'info' }
}

function actionSummary(record) {
  const result = record?.actionResult
  if (!result || typeof result !== 'object') return ''
  const parts = []
  if (typeof result.success === 'boolean') parts.push(result.success ? 'success=true' : 'success=false')
  if (result.outcome) parts.push(`outcome=${result.outcome}`)
  if (result.activeBefore != null || result.activeAfter != null) {
    parts.push(`active ${result.activeBefore} → ${result.activeAfter}`)
  }
  return parts.join(' · ')
}

function verificationSummary(record) {
  const result = record?.verificationResult
  if (!result || typeof result !== 'object') return ''
  const parts = []
  if (result.result) parts.push(`result=${result.result}`)
  if (result.httpStatus != null) parts.push(`HTTP ${result.httpStatus}`)
  if (result.faultObserved != null) parts.push(`faultObserved=${result.faultObserved}`)
  if (result.requestId) parts.push(`requestId=${result.requestId}`)
  return parts.join(' · ')
}

function eventUpdateText(record) {
  if (record?.eventStatusUpdated) return '已更新为 RESOLVED'
  if (record?.status === 'SUCCEEDED') return '未更新（可能已被人工处理）'
  return '未更新'
}

function newIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `remediation-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function openDialog() {
  if (!actionEntryVisible.value) return
  if (!pendingKey.value) pendingKey.value = newIdempotencyKey()
  ackChecked.value = false
  operatorNote.value = ''
  submitError.value = ''
  dialogVisible.value = true
}

function isUnknownOutcome(ex) {
  if (!ex) return true
  if (ex.code === 'ECONNABORTED' || ex.code === 'ETIMEDOUT') return true
  if (/timeout/i.test(ex.message || '')) return true
  // 无 response 的网络异常、网关超时以及 5xx，都不能断言动作未执行。
  if (ex.status == null) return true
  if (ex.status === 408) return true
  return ex.status >= 500
}

function describeHttpError(ex) {
  const message = ex?.message || '请求失败'
  switch (ex?.status) {
    case 400:
      return `请求参数不合法：${message}`
    case 403:
      return `受控处置未开启或当前环境不允许执行：${message}`
    case 404:
      return `事件、诊断任务或处置记录不存在，请刷新页面后重试：${message}`
    case 409:
      return `当前状态不允许发起处置：${message}`
    default:
      return message
  }
}

function notifyResult(view) {
  const status = view?.status
  if (status === 'SUCCEEDED') {
    ElMessage.success('处置成功：动作已执行，真实 checkout 验证通过')
  } else if (status === 'ACTION_FAILED') {
    ElMessage.error('处置已记录：关闭实验故障的动作失败')
  } else if (status === 'VERIFICATION_FAILED') {
    ElMessage.error('处置已记录：动作成功，但真实 checkout 验证未通过')
  } else if (status === 'VERIFICATION_INCONCLUSIVE') {
    ElMessage.warning('处置已记录：无法确认恢复结果，请人工核查')
  } else if (status === 'RECOVERY_REQUIRED') {
    ElMessage.warning('处置需要人工核查，请查看最近处置详情')
  } else {
    ElMessage.info(`处置状态：${statusText(status)}`)
  }
}

async function submit() {
  if (submitDisabled.value) return
  const key = pendingKey.value || newIdempotencyKey()
  pendingKey.value = key
  submitting.value = true
  submitError.value = ''
  try {
    const view = await createRemediation(props.event.id, {
      actionType: ACTION_TYPE,
      diagnosisTaskId: eligibleTask.value.id,
      confirmed: true,
      idempotencyKey: key,
      requestedBy: 'rca-console',
      operatorNote: operatorNote.value.trim() || null,
      ...(ackRequired.value ? { recoveryAcknowledged: true } : {})
    })
    pendingKey.value = ''
    dialogVisible.value = false
    await loadHistory({ silent: true })
    emit('changed')
    notifyResult(view)
  } catch (ex) {
    await handleSubmitError(ex, key)
  } finally {
    submitting.value = false
  }
}

async function handleSubmitError(ex, key) {
  if (isUnknownOutcome(ex)) {
    unknownKey.value = key
    submitError.value = `请求超时或后端异常，无法确认处置是否已执行。请勿重复提交，使用幂等键 ${key} 查询结果。`
    dialogVisible.value = false
    await loadHistory({ silent: true })
    if (unknownKey.value) {
      ElMessage.warning('未收到明确处置结果，已保留幂等键供查询')
    } else {
      ElMessage.info('请求未收到明确响应，但后端已有该幂等键的处置记录，请查看最近一次处置详情')
    }
    return
  }

  pendingKey.value = ''
  submitError.value = describeHttpError(ex)
  await loadHistory({ silent: true })
  ElMessage.error(submitError.value)
}

async function queryUnknownResult() {
  if (!unknownKey.value || queryingUnknown.value) return
  queryingUnknown.value = true
  try {
    const view = await fetchRemediationByIdempotencyKey(props.event.id, unknownKey.value)
    unknownKey.value = ''
    pendingKey.value = ''
    submitError.value = ''
    await loadHistory({ silent: true })
    emit('changed')
    ElMessage.success(`已查询到处置记录：${statusText(view.status)}`)
  } catch (ex) {
    if (ex?.status === 404) {
      ElMessage.warning('尚未查询到该幂等键对应的记录，处置可能仍在执行，请不要重复提交')
    } else {
      ElMessage.error(ex?.message || '查询处置结果失败')
    }
  } finally {
    queryingUnknown.value = false
  }
}

function handleEventChanged() {
  history.value = []
  historyLoaded.value = false
  unknownKey.value = ''
  pendingKey.value = ''
  dialogVisible.value = false
  loadHistory()
}

watch(() => props.event?.id, handleEventChanged)

onMounted(() => loadHistory())
</script>

<template>
  <el-card shadow="never" class="panel-card remediation-panel">
    <template #header>
      <div class="card-header">
        <div>
          <div class="card-title">受控恢复处置</div>
          <div class="card-subtitle">
            白名单动作 {{ ACTION_TYPE }} · 关闭实验故障后执行一次真实 checkout 验证
          </div>
        </div>
        <div class="header-actions">
          <el-button :icon="Refresh" :loading="historyLoading" @click="loadHistory()">
            刷新处置记录
          </el-button>
          <el-button
            v-if="actionEntryVisible"
            type="primary"
            :icon="VideoPlay"
            @click="openDialog"
          >
            发起受控处置
          </el-button>
          <el-tag v-else-if="activeRemediation" type="warning" effect="dark">
            处置执行中
          </el-tag>
        </div>
      </div>
    </template>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="该操作只针对本事件关联的实验故障，不会执行任意命令或生产服务操作"
      description="真实恢复验证会发起一次 checkout，可能扣减库存。系统不会自动重试处置请求；请勿在结果未知时重复提交。"
    />

    <el-alert
      v-if="unknownKey"
      type="error"
      :closable="false"
      show-icon
      class="section-gap"
      title="处置结果未知，请先查询，不要重复提交"
    >
      <div class="alert-body">
        <div>
          请求超时或后端异常，无法确认是否已执行。幂等键：
          <code>{{ unknownKey }}</code>
        </div>
        <el-button
          size="small"
          type="danger"
          plain
          :icon="Search"
          :loading="queryingUnknown"
          @click="queryUnknownResult"
        >
          按幂等键查询结果
        </el-button>
      </div>
    </el-alert>

    <el-alert
      v-else-if="historyError"
      type="error"
      :closable="false"
      show-icon
      class="section-gap"
      :title="historyError"
    />

    <el-alert
      v-else-if="historyLoaded && succeededRemediation"
      type="success"
      :closable="false"
      show-icon
      class="section-gap"
      title="该事件已有成功处置记录，系统不会再次执行 checkout"
      :description="`处置 ${succeededRemediation.id} 已完成。请人工核查故障事件状态；即使事件仍为 OPEN，也不要重复发起处置。`"
    />

    <el-alert
      v-else-if="historyLoaded && !remediationReady"
      type="info"
      :closable="false"
      show-icon
      class="section-gap"
      :title="`暂不可发起处置：${ineligibleReason}`"
    />

    <el-alert
      v-if="ackRequired"
      type="error"
      :closable="false"
      show-icon
      class="section-gap"
      :title="ackAlertTitle"
      :description="latestRemediation?.failureReason || '前次动作或验证结果不完整，请先核查外部状态。'"
    />

    <div v-if="latestRemediation" class="latest-block">
      <div class="latest-head">
        <div>
          <span class="latest-title">最近一次处置</span>
          <span class="muted latest-id">{{ latestRemediation.id }}</span>
        </div>
        <el-tag :type="statusType(latestRemediation.status)" effect="dark">
          {{ statusText(latestRemediation.status) }}
        </el-tag>
      </div>
      <div class="latest-hint">{{ statusHint(latestRemediation) }}</div>

      <el-alert
        v-if="latestRemediation.status === 'RECOVERY_REQUIRED'"
        type="error"
        :closable="false"
        show-icon
        class="recovery-alert"
        title="该记录需要人工核查，不代表动作失败，也不代表恢复成功"
      >
        <div class="alert-body column">
          <div>
            中断原因：<strong>{{ failureText(latestRemediation.failureCode) }}</strong>
          </div>
          <div>{{ latestRemediation.failureReason || '后端未记录补充原因' }}</div>
          <div>
            系统没有在启动恢复时重新执行故障关闭、checkout 或 Agent，也没有自动解决故障事件。
            请人工核查实验故障、订单与库存状态；在确认前不要再次发起处置。
          </div>
        </div>
      </el-alert>

      <div class="result-tags">
        <el-tag :type="actionOutcome(latestRemediation).type" effect="plain">
          动作结果：{{ actionOutcome(latestRemediation).text }}
        </el-tag>
        <el-tag :type="verificationOutcome(latestRemediation).type" effect="plain">
          {{ verificationOutcome(latestRemediation).text }}
        </el-tag>
        <el-tag :type="latestRemediation.eventStatusUpdated ? 'success' : 'info'" effect="plain">
          故障事件：{{ eventUpdateText(latestRemediation) }}
        </el-tag>
      </div>

      <el-descriptions :column="2" border size="small" class="remediation-meta">
        <el-descriptions-item label="动作">
          {{ latestRemediation.actionType }}（条件关闭实验故障注入）
        </el-descriptions-item>
        <el-descriptions-item label="目标服务">
          {{ latestRemediation.targetService || 'inventory-service' }}
        </el-descriptions-item>
        <el-descriptions-item label="目标实验">
          <span class="break-all">{{ latestRemediation.experimentId || '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="关联诊断任务">
          <span class="break-all">{{ latestRemediation.diagnosisTaskId || '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="失败码 / 中断码">
          {{ latestRemediation.failureCode || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="请求人">
          {{ latestRemediation.requestedBy || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="失败原因" :span="2">
          {{ latestRemediation.failureReason || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="幂等键" :span="2">
          <span class="break-all">{{ latestRemediation.idempotencyKey || '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="请求时间">
          {{ formatTime(latestRemediation.requestedAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="完成时间">
          {{ formatTime(latestRemediation.completedAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="核查说明" :span="2">
          {{ latestRemediation.operatorNote || '—' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="result-grid">
        <div>
          <div class="json-title">动作执行结果（actionResult）</div>
          <div v-if="actionSummary(latestRemediation)" class="result-summary">
            {{ actionSummary(latestRemediation) }}
          </div>
          <pre class="json-block">{{ pretty(latestRemediation.actionResult) }}</pre>
        </div>
        <div>
          <div class="json-title">真实 checkout 验证结果（verificationResult）</div>
          <div v-if="verificationSummary(latestRemediation)" class="result-summary">
            {{ verificationSummary(latestRemediation) }}
          </div>
          <pre class="json-block">{{ pretty(latestRemediation.verificationResult) }}</pre>
        </div>
      </div>

      <div class="json-title">审计记录（auditTrace）</div>
      <el-timeline v-if="auditEntries(latestRemediation).length" class="audit-timeline">
        <el-timeline-item
          v-for="(entry, index) in auditEntries(latestRemediation)"
          :key="`${entry.step || 'audit'}-${index}`"
          :timestamp="formatTime(entry.at)"
        >
          <div class="audit-item">
            <el-tag :type="auditTagType(entry.step)" size="small">
              {{ auditStepText(entry.step) }}
            </el-tag>
            <span v-if="entry.detail" class="audit-detail">{{ entry.detail }}</span>
          </div>
          <div v-if="entry.recoveryCode" class="audit-extra">
            中断原因 <code>{{ entry.recoveryCode }}</code> · 动作 {{ entry.actionState || '—' }} ·
            验证 {{ entry.verificationState || '—' }}
          </div>
          <div v-if="entry.manualReviewRequired" class="audit-extra">需要人工核查</div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无审计记录" :image-size="50" />
    </div>

    <el-empty v-else-if="historyLoaded" description="尚未发起受控处置" :image-size="60" />

    <div v-if="history.length" class="history-block">
      <div class="json-title">当前事件处置历史（共 {{ history.length }} 条）</div>
      <el-table :data="history" stripe size="small">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="history-expand">
              <div class="history-expand-head">
                <span>核查说明：{{ row.operatorNote || '—' }}</span>
                <span class="muted">{{ formatTime(row.requestedAt) }}</span>
              </div>
              <div v-if="row.failureReason" class="history-failure">
                失败 / 中断原因：{{ row.failureReason }}
              </div>
              <el-alert
                v-if="row.status === 'RECOVERY_REQUIRED'"
                type="error"
                :closable="false"
                show-icon
                class="history-recovery-alert"
                :title="`需要人工核查：${failureText(row.failureCode)}`"
                :description="row.failureReason || '前次动作或验证结果不完整。'"
              />
              <div class="json-grid">
                <div>
                  <div class="json-title">动作结果</div>
                  <pre class="json-block">{{ pretty(row.actionResult) }}</pre>
                </div>
                <div>
                  <div class="json-title">真实 checkout 验证结果</div>
                  <pre class="json-block">{{ pretty(row.verificationResult) }}</pre>
                </div>
              </div>
              <div class="json-title">审计记录</div>
              <el-timeline v-if="auditEntries(row).length" class="audit-timeline">
                <el-timeline-item
                  v-for="(entry, index) in auditEntries(row)"
                  :key="`${row.id}-${entry.step || 'audit'}-${index}`"
                  :timestamp="formatTime(entry.at)"
                >
                  <div class="audit-item">
                    <el-tag :type="auditTagType(entry.step)" size="small">
                      {{ auditStepText(entry.step) }}
                    </el-tag>
                    <span v-if="entry.detail" class="audit-detail">{{ entry.detail }}</span>
                  </div>
                </el-timeline-item>
              </el-timeline>
              <span v-else class="muted">暂无审计记录</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="135">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="请求时间" width="175">
          <template #default="{ row }">{{ formatTime(row.requestedAt) }}</template>
        </el-table-column>
        <el-table-column label="完成时间" width="175">
          <template #default="{ row }">{{ formatTime(row.completedAt) }}</template>
        </el-table-column>
        <el-table-column prop="requestedBy" label="请求人" width="110" />
        <el-table-column label="失败码" min-width="210" show-overflow-tooltip>
          <template #default="{ row }">{{ row.failureCode || '—' }}</template>
        </el-table-column>
        <el-table-column label="幂等键" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.idempotencyKey || '—' }}</template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      title="人工确认受控恢复处置"
      width="min(720px, 94vw)"
      append-to-body
      :show-close="!submitting"
      :close-on-click-modal="false"
      :close-on-press-escape="!submitting"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="确认后将立即执行真实外部调用，不能自动撤销"
        description="系统会条件关闭本次实验故障，然后发起一次真实 checkout 验证，可能扣减库存。提交超时不代表执行失败，页面不会自动重试。"
      />

      <el-descriptions :column="1" border size="small" class="dialog-target">
        <el-descriptions-item label="动作">
          {{ ACTION_TYPE }}（条件关闭本次实验故障注入）
        </el-descriptions-item>
        <el-descriptions-item label="目标服务">
          inventory-service（后端固定目标，页面不可修改）
        </el-descriptions-item>
        <el-descriptions-item label="目标实验">
          <span class="break-all">{{ event.experimentId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="关联诊断任务">
          <span class="break-all">{{ eligibleTask?.id }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="幂等键">
          <span class="break-all">{{ pendingKey }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="ackRequired"
        type="error"
        :closable="false"
        show-icon
        class="dialog-ack-alert"
        :title="ackAlertTitle"
        :description="latestRemediation?.failureReason || '前次动作或验证结果不完整。'"
      />

      <el-checkbox v-if="ackRequired" v-model="ackChecked" class="ack-check">
        我已阅读前次处置结果，并已人工核查实验故障、订单和库存状态
      </el-checkbox>

      <div class="note-label">
        {{ ackRequired ? '核查说明（必填）' : '处置备注（可选）' }}
      </div>
      <el-input
        v-model="operatorNote"
        type="textarea"
        :rows="3"
        maxlength="512"
        show-word-limit
        :placeholder="
          ackRequired
            ? '请说明已核查的外部状态和再次处置的依据（至少填写一个字符）'
            : '可记录本次人工确认依据，最多 512 字'
        "
      />

      <el-alert
        v-if="submitError"
        type="error"
        :closable="false"
        show-icon
        class="dialog-error"
        :title="submitError"
      />

      <template #footer>
        <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" :disabled="submitDisabled" @click="submit">
          确认处置并验证
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.remediation-panel {
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
  align-items: center;
  gap: 8px;
}

.section-gap {
  margin-top: 12px;
}

.alert-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 6px;
}

.alert-body.column {
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.7;
}

.latest-block {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--rca-border);
}

.latest-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.latest-title {
  color: var(--rca-text);
  font-size: 14px;
  font-weight: 650;
}

.latest-id {
  margin-left: 10px;
  font-size: 12px;
}

.latest-hint {
  margin-top: 6px;
  color: var(--rca-muted);
  font-size: 12px;
}

.recovery-alert {
  margin-top: 12px;
}

.recovery-alert .alert-body {
  display: block;
}

.result-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.remediation-meta {
  margin-top: 12px;
}

.break-all {
  word-break: break-all;
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: 14px;
}

.json-title {
  margin-bottom: 7px;
  color: var(--rca-text);
  font-size: 12px;
  font-weight: 600;
}

.json-block {
  margin: 0;
  padding: 10px 12px;
  max-height: 240px;
  overflow: auto;
  border: 1px solid #e8eef8;
  border-radius: 8px;
  background: #f8fafd;
  color: #33415c;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.result-summary {
  margin-bottom: 6px;
  color: var(--rca-muted);
  font-size: 12px;
}

.audit-timeline {
  margin-top: 8px;
  padding-left: 4px;
}

.audit-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.audit-detail {
  color: var(--rca-text);
  font-size: 12px;
}

.audit-extra {
  margin-top: 4px;
  color: var(--rca-muted);
  font-size: 12px;
}

.history-block {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--rca-border);
}

.history-expand {
  padding: 4px 8px 8px;
}

.history-expand-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  font-size: 12px;
}

.history-recovery-alert {
  margin-bottom: 12px;
}

.history-failure {
  margin-bottom: 12px;
  color: #8a4b0f;
  font-size: 12px;
  line-height: 1.6;
}

.json-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.muted {
  color: var(--rca-muted);
}

.dialog-target {
  margin-top: 12px;
}

.dialog-ack-alert {
  margin-top: 12px;
}

.ack-check {
  display: block;
  margin-top: 12px;
  white-space: normal;
}

.note-label {
  margin: 14px 0 6px;
  color: var(--rca-text);
  font-size: 13px;
  font-weight: 600;
}

.dialog-error {
  margin-top: 12px;
}

code {
  padding: 1px 4px;
  border-radius: 4px;
  background: #eef3fb;
  color: #1f4f9c;
  font-size: 12px;
  word-break: break-all;
}

@media (max-width: 760px) {
  .card-header {
    flex-direction: column;
  }

  .result-grid,
  .json-grid {
    grid-template-columns: 1fr;
  }

  .alert-body {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>

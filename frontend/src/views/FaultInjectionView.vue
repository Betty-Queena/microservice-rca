<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Aim, Refresh, SwitchButton, VideoPlay, Warning } from '@element-plus/icons-vue'
import {
  disableFault,
  enableFault,
  executeExperiment,
  fetchExperimentInventory,
  fetchFaultStatus,
  resetFault,
  runControlCheckout
} from '../api/experiments'

const status = ref(null)
const inventory = ref([])
const loading = ref(false)
const actionLoading = ref(false)
const executing = ref(false)
const checking = ref(false)
const error = ref('')
const execution = ref(null)
const controlResult = ref(null)

const form = reactive({
  faultType: 'DELAY',
  delayMs: 3500,
  durationSeconds: 120,
  note: 'Day 2 故障注入实验'
})

const checkout = reactive({
  sku: 'SKU-1001',
  quantity: 1
})

let timer = null

const activeTagType = computed(() => (status.value?.active ? 'danger' : 'success'))
const activeText = computed(() => (status.value?.active ? '故障激活中' : '无激活故障'))
const experimentEnabled = computed(() => Boolean(status.value?.experimentEnabled))
const remainingText = computed(() => {
  if (!status.value?.active || status.value.remainingMs == null) return '—'
  const seconds = Math.max(0, Math.round(status.value.remainingMs / 1000))
  return `${seconds} 秒后自动过期`
})

const inventoryOptions = computed(() =>
  inventory.value.map((item) => ({
    ...item,
    label: `${item.sku} · ${item.name} · 可用 ${item.available}`
  }))
)

function formatTime(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function outcomeType(outcome) {
  if (!outcome) return 'info'
  if (outcome === 'SUCCESS') return 'success'
  if (['BUSINESS_CONFLICT', 'REQUEST_REJECTED'].includes(outcome)) return 'warning'
  if (outcome === 'SLOW_RESPONSE') return 'warning'
  return 'danger'
}

function outcomeText(outcome) {
  const labels = {
    SUCCESS: '成功',
    SLOW_RESPONSE: '慢响应（已记录事件）',
    BUSINESS_CONFLICT: '业务冲突（非系统故障）',
    REQUEST_REJECTED: '请求被拒绝',
    DOWNSTREAM_TIMEOUT: '下游超时',
    DOWNSTREAM_HTTP_5XX: '下游 5xx',
    DOWNSTREAM_CONNECTION_ERROR: '下游连接失败',
    DOWNSTREAM_PROTOCOL_ERROR: '下游响应异常',
    DOWNSTREAM_ERROR: '下游错误',
    SERVER_ERROR: '服务端错误',
    ORDER_SERVICE_UNREACHABLE: '订单服务不可达',
    UNEXPECTED_STATUS: '非预期状态'
  }
  return labels[outcome] || outcome || '—'
}

async function loadStatus() {
  loading.value = true
  error.value = ''
  try {
    status.value = await fetchFaultStatus()
  } catch (ex) {
    error.value = ex.message || '无法获取故障注入状态'
  } finally {
    loading.value = false
  }
}

async function loadInventory() {
  try {
    inventory.value = await fetchExperimentInventory()
    if (inventory.value.length > 0) {
      const exists = inventory.value.some((item) => item.sku === checkout.sku)
      if (!exists) checkout.sku = inventory.value[0].sku
    }
  } catch (ex) {
    error.value = ex.message || '无法获取库存列表'
  }
}

async function handleEnable() {
  actionLoading.value = true
  error.value = ''
  try {
    status.value = await enableFault({
      faultType: form.faultType,
      delayMs: form.faultType === 'DELAY' ? form.delayMs : null,
      durationSeconds: form.durationSeconds,
      note: form.note
    })
    ElMessage.success('故障注入已开启，请在过期前执行实验请求')
  } catch (ex) {
    error.value = ex.message || '开启故障注入失败'
  } finally {
    actionLoading.value = false
  }
}

async function handleDisable() {
  actionLoading.value = true
  error.value = ''
  try {
    status.value = await disableFault()
    ElMessage.success('故障已关闭')
  } catch (ex) {
    error.value = ex.message || '关闭故障注入失败'
  } finally {
    actionLoading.value = false
  }
}

async function handleReset() {
  actionLoading.value = true
  error.value = ''
  try {
    status.value = await resetFault()
    ElMessage.success('实验状态已重置')
  } catch (ex) {
    error.value = ex.message || '重置实验失败'
  } finally {
    actionLoading.value = false
  }
}

async function handleExecute() {
  if (!status.value?.active || !status.value?.experimentId) {
    ElMessage.warning('请先开启一个故障注入实验')
    return
  }
  executing.value = true
  error.value = ''
  try {
    execution.value = await executeExperiment(status.value.experimentId, {
      sku: checkout.sku,
      quantity: checkout.quantity
    })
    await loadStatus()
    await loadInventory()
    ElMessage.success('实验请求已执行，结果来自订单服务真实响应')
  } catch (ex) {
    error.value = ex.message || '执行实验请求失败'
  } finally {
    executing.value = false
  }
}

async function handleControlCheckout() {
  checking.value = true
  error.value = ''
  try {
    controlResult.value = await runControlCheckout({
      sku: checkout.sku,
      quantity: checkout.quantity
    })
    await loadInventory()
    ElMessage.success('对照请求已执行，未注入故障')
  } catch (ex) {
    error.value = ex.message || '执行对照请求失败'
  } finally {
    checking.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadStatus(), loadInventory()])
  timer = window.setInterval(loadStatus, 5000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="page-stack">
    <section class="hero">
      <div>
        <div class="hero-eyebrow">FAULT INJECTION LAB</div>
        <h1>故障注入实验</h1>
        <p>
          在本地实验环境中向库存服务注入真实故障，并通过订单服务的真实 HTTP 调用验证影响。
          故障只作用于 <code>/api/inventory/**</code> 业务接口，健康检查、观测查询和实验控制接口不受影响。
        </p>
      </div>
      <el-tag :type="experimentEnabled ? 'warning' : 'info'" effect="dark" size="large">
        {{ experimentEnabled ? '实验开关已启用' : '实验开关默认关闭' }}
      </el-tag>
    </section>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <el-alert
      v-if="status && !experimentEnabled"
      type="warning"
      :closable="false"
      show-icon
      title="故障注入默认禁用"
    >
      需要在库存服务启动前设置 <code>EXPERIMENT_FAULT_INJECTION_ENABLED=true</code>，并且只允许本地实验地址访问。
    </el-alert>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="14">
        <el-card shadow="never" class="panel-card" v-loading="loading">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">当前实验状态</div>
                <div class="card-subtitle">数据来源：inventory-service /api/experiments/faults</div>
              </div>
              <el-tag :type="activeTagType" effect="light" round>{{ activeText }}</el-tag>
            </div>
          </template>

          <el-descriptions :column="2" border>
            <el-descriptions-item label="实验开关">
              <el-tag :type="experimentEnabled ? 'success' : 'info'" effect="light">
                {{ experimentEnabled ? '已启用' : '已禁用' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="当前故障">
              <el-tag v-if="status?.active" type="danger" effect="light">
                {{ status.faultType }}{{ status.delayMs ? ` · ${status.delayMs} ms` : '' }}
              </el-tag>
              <span v-else class="muted">无</span>
            </el-descriptions-item>
            <el-descriptions-item label="实验 ID">
              <code>{{ status?.experimentId || '—' }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="剩余时间">{{ remainingText }}</el-descriptions-item>
            <el-descriptions-item label="开启时间">{{ formatTime(status?.activatedAt) }}</el-descriptions-item>
            <el-descriptions-item label="自动过期">{{ formatTime(status?.expiresAt) }}</el-descriptions-item>
            <el-descriptions-item label="最大延迟">{{ status?.maxDelayMs ?? '—' }} ms</el-descriptions-item>
            <el-descriptions-item label="最长持续时间">
              {{ status?.maxDurationSeconds ?? '—' }} 秒
            </el-descriptions-item>
          </el-descriptions>

          <div class="toolbar">
            <el-button
              type="danger"
              plain
              :icon="SwitchButton"
              :loading="actionLoading"
              :disabled="!status?.active"
              @click="handleDisable"
            >
              关闭故障
            </el-button>
            <el-button :icon="Refresh" :loading="actionLoading" @click="loadStatus">刷新状态</el-button>
            <el-button :loading="actionLoading" @click="handleReset">重置实验</el-button>
          </div>

          <el-alert type="info" :closable="false" show-icon class="inline-alert">
            故障状态保存在库存服务内存中，自动过期后失效，不写入业务数据库，也不会造成永久阻塞。
          </el-alert>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="10">
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">注入参数</div>
                <div class="card-subtitle">只支持已实现的两种实验故障</div>
              </div>
              <el-icon :size="18" color="#b45309"><Aim /></el-icon>
            </div>
          </template>

          <el-form label-position="top">
            <el-form-item label="故障类型">
              <el-radio-group v-model="form.faultType">
                <el-radio-button label="DELAY">响应延迟</el-radio-button>
                <el-radio-button label="HTTP_500">HTTP 500</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="form.faultType === 'DELAY'" label="延迟毫秒数">
              <el-input-number
                v-model="form.delayMs"
                :min="1"
                :max="status?.maxDelayMs || 4500"
                :step="500"
              />
              <div class="form-hint">
                延迟上限 4500 毫秒，低于订单服务 5 秒读超时；超过 3 秒会记录为慢响应。
              </div>
            </el-form-item>
            <el-form-item label="持续时间（秒）">
              <el-input-number
                v-model="form.durationSeconds"
                :min="1"
                :max="status?.maxDurationSeconds || 600"
                :step="30"
              />
            </el-form-item>
            <el-form-item label="实验备注">
              <el-input v-model="form.note" maxlength="200" show-word-limit />
            </el-form-item>
            <el-button
              type="primary"
              :icon="Warning"
              :loading="actionLoading"
              :disabled="!experimentEnabled"
              @click="handleEnable"
            >
              开启故障注入
            </el-button>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">实验请求</div>
                <div class="card-subtitle">真实调用 order-service → inventory-service</div>
              </div>
              <el-icon :size="18" color="#2563eb"><VideoPlay /></el-icon>
            </div>
          </template>

          <el-form label-position="top">
            <el-form-item label="商品 SKU">
              <el-select v-model="checkout.sku" filterable style="width: 100%">
                <el-option
                  v-for="item in inventoryOptions"
                  :key="item.sku"
                  :label="item.label"
                  :value="item.sku"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="数量">
              <el-input-number v-model="checkout.quantity" :min="1" :max="20" />
            </el-form-item>
          </el-form>

          <div class="toolbar toolbar--stretch">
            <el-button
              type="primary"
              :icon="VideoPlay"
              :loading="executing"
              :disabled="!status?.active"
              @click="handleExecute"
            >
              执行故障实验请求
            </el-button>
            <el-button :loading="checking" @click="handleControlCheckout">
              无故障对照请求
            </el-button>
          </div>

          <el-alert type="info" :closable="false" show-icon class="inline-alert">
            对照请求不注入故障，用于验证关闭故障后的正常业务是否恢复。
          </el-alert>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">最近一次真实结果</div>
                <div class="card-subtitle">来自订单服务 HTTP 响应，不是前端模拟状态</div>
              </div>
            </div>
          </template>

          <el-empty v-if="!execution && !controlResult" description="尚未执行请求" :image-size="64" />

          <template v-if="execution">
            <div class="result-heading">
              <span>故障实验请求</span>
              <el-tag :type="outcomeType(execution.outcome)" effect="light" round>
                {{ outcomeText(execution.outcome) }}
              </el-tag>
            </div>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="HTTP 状态">{{ execution.httpStatus ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="耗时">{{ execution.durationMs }} ms</el-descriptions-item>
              <el-descriptions-item label="下游错误类型">
                {{ execution.downstreamErrorType || '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="订单状态">{{ execution.orderStatus || '—' }}</el-descriptions-item>
              <el-descriptions-item label="订单号">{{ execution.orderId || '—' }}</el-descriptions-item>
              <el-descriptions-item label="剩余库存">
                {{ execution.remainingStock ?? '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="请求 ID" :span="2">
                <code>{{ execution.requestId }}</code>
              </el-descriptions-item>
              <el-descriptions-item label="预设故障" :span="2">
                <el-tag type="warning" effect="plain">{{ execution.presetFaultType || '无' }}</el-tag>
                <span class="muted result-note">仅为实验预设，不作为根因结论</span>
              </el-descriptions-item>
            </el-descriptions>
            <div class="result-actions">
              <router-link v-if="execution.faultEventId" :to="`/fault-events/${execution.faultEventId}`">
                <el-button type="primary" link>查看关联故障事件 →</el-button>
              </router-link>
              <span v-else class="muted">本次结果未记录为系统故障</span>
            </div>
          </template>

          <template v-if="controlResult">
            <el-divider />
            <div class="result-heading">
              <span>无故障对照请求</span>
              <el-tag :type="outcomeType(controlResult.outcome)" effect="light" round>
                {{ outcomeText(controlResult.outcome) }}
              </el-tag>
            </div>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="HTTP 状态">{{ controlResult.httpStatus ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="耗时">{{ controlResult.durationMs }} ms</el-descriptions-item>
              <el-descriptions-item label="订单状态">{{ controlResult.orderStatus || '—' }}</el-descriptions-item>
              <el-descriptions-item label="剩余库存">
                {{ controlResult.remainingStock ?? '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="请求 ID" :span="2">
                <code>{{ controlResult.requestId }}</code>
              </el-descriptions-item>
            </el-descriptions>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.panel-card {
  height: 100%;
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

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

.toolbar--stretch .el-button {
  flex: 1 1 180px;
  margin-left: 0;
}

.inline-alert {
  margin-top: 14px;
}

.form-hint {
  margin-top: 6px;
  color: var(--rca-muted);
  font-size: 12px;
  line-height: 1.6;
}

.result-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  color: var(--rca-text);
  font-size: 13px;
  font-weight: 600;
}

.result-note {
  margin-left: 8px;
  font-size: 12px;
}

.result-actions {
  display: flex;
  align-items: center;
  min-height: 32px;
  margin-top: 12px;
}

.muted {
  color: var(--rca-muted);
  font-size: 12px;
}

code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #eef2f8;
  color: #334155;
  font-size: 12px;
}

@media (max-width: 992px) {
  .panel-card {
    margin-bottom: 16px;
  }
}
</style>

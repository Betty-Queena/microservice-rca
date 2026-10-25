<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, Search } from '@element-plus/icons-vue'
import { fetchFaultEvents } from '../api/faultEvents'
import { fetchExternalServices } from '../api/externalServices'

const router = useRouter()
const loading = ref(false)
const error = ref('')
const events = ref([])
const total = ref(0)

const filters = reactive({
  status: '',
  serviceName: ''
})

const pagination = reactive({
  page: 0,
  size: 10
})

const statusOptions = [
  { value: 'OPEN', label: '待处理' },
  { value: 'DIAGNOSING', label: '诊断中' },
  { value: 'RESOLVED', label: '已解决' },
  { value: 'CLOSED', label: '已关闭' }
]

const serviceOptions = ref([
  { value: 'order-service', label: 'order-service' },
  { value: 'inventory-service', label: 'inventory-service' }
])

function sourceType(source) {
  return { EXPERIMENT: 'warning', EXTERNAL: 'success', MANUAL: 'info' }[source] || 'info'
}

function sourceText(source) {
  return { EXPERIMENT: '实验', EXTERNAL: '外部', MANUAL: '人工' }[source] || source || '—'
}

function statusType(status) {
  return {
    OPEN: 'danger',
    DIAGNOSING: 'warning',
    RESOLVED: 'success',
    CLOSED: 'info'
  }[status] || 'info'
}

function statusText(status) {
  return statusOptions.find((item) => item.value === status)?.label || status
}

function severityType(severity) {
  return { INFO: 'info', WARNING: 'warning', ERROR: 'danger', CRITICAL: 'danger' }[severity] || 'info'
}

function formatTime(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

async function loadServiceOptions() {
  try {
    const external = await fetchExternalServices({ enabledOnly: true })
    const builtIn = new Set(serviceOptions.value.map((item) => item.value))
    for (const service of external || []) {
      if (!builtIn.has(service.name)) {
        serviceOptions.value.push({ value: service.name, label: service.name })
      }
    }
  } catch {
    // 外部服务列表加载失败不影响故障事件主列表。
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await fetchFaultEvents({
      status: filters.status || undefined,
      serviceName: filters.serviceName || undefined,
      page: pagination.page,
      size: pagination.size
    })
    events.value = data.items || []
    total.value = data.total || 0
  } catch (ex) {
    error.value = ex.message || '无法加载故障事件'
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.page = 0
  load()
}

function handleReset() {
  filters.status = ''
  filters.serviceName = ''
  pagination.page = 0
  load()
}

function handlePageChange(page) {
  pagination.page = page - 1
  load()
}

function openDetail(row) {
  router.push(`/fault-events/${row.id}`)
}

onMounted(() => {
  loadServiceOptions()
  load()
})
</script>

<template>
  <div class="page-stack">
    <section class="hero">
      <div>
        <div class="hero-eyebrow">FAULT EVENTS</div>
        <h1>故障事件</h1>
        <p>
          展示实验请求真实观测后写入 PostgreSQL 的故障事件。实验预设故障类型与观测结果分开保存，
          预设类型不会被当作根因结论。
        </p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </section>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <el-card shadow="never" class="panel-card">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">事件列表</div>
            <div class="card-subtitle">数据来源：PostgreSQL fault_event 表 · 共 {{ total }} 条</div>
          </div>
          <el-tag type="info" effect="plain">真实持久化数据</el-tag>
        </div>
      </template>

      <div class="filter-bar">
        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 150px">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="filters.serviceName" placeholder="全部服务" clearable style="width: 190px">
          <el-option v-for="item in serviceOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="events"
        stripe
        row-key="id"
        class="event-table"
        @row-click="openDetail"
      >
        <el-table-column label="发生时间" width="170">
          <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column prop="serviceName" label="服务" width="170" />
        <el-table-column label="来源" width="80">
          <template #default="{ row }">
            <el-tag :type="sourceType(row.source)" effect="plain" size="small">
              {{ sourceText(row.source) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="eventType"
          label="观测类型"
          width="220"
          class-name="nowrap-cell"
          show-overflow-tooltip
        />
        <el-table-column label="严重级别" width="85">
          <template #default="{ row }">
            <el-tag :type="severityType(row.severity)" effect="light" size="small">{{ row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="85">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light" size="small">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="65">
          <template #default="{ row }">
            <el-button type="primary" link @click.stop="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无故障事件。可在“故障注入实验”页面执行真实实验请求。" :image-size="80" />
        </template>
      </el-table>

      <div class="pagination-row">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="total"
          :page-size="pagination.size"
          :current-page="pagination.page + 1"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
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

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  margin-bottom: 16px;
}

.event-table :deep(.el-table__row) {
  cursor: pointer;
}

.event-table :deep(.nowrap-cell .cell) {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>

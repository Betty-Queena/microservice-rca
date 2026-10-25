<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Refresh } from '@element-plus/icons-vue'
import {
  checkExternalService,
  fetchExternalServices,
  registerExternalService,
  startExternalDiagnosis
} from '../api/externalServices'

const router = useRouter()
const loading = ref(false)
const submitting = ref(false)
const checkingId = ref('')
const error = ref('')
const services = ref([])

const form = reactive({
  name: '',
  baseUrl: '',
  healthPath: '',
  metricsPath: '',
  logSourceUrl: '',
  description: '',
  enabled: true
})

const formRef = ref()

const rules = {
  name: [{ required: true, message: '请输入服务名称', trigger: 'blur' }],
  baseUrl: [{ required: true, message: '请输入服务基地址', trigger: 'blur' }]
}

function checkType(status) {
  return { UP: 'success', DOWN: 'danger', UNREACHABLE: 'danger', UNKNOWN: 'info' }[status] || 'info'
}

function formatTime(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    services.value = await fetchExternalServices()
  } catch (ex) {
    error.value = ex.message || '无法加载外部服务'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const created = await registerExternalService({
      name: form.name,
      baseUrl: form.baseUrl,
      healthPath: form.healthPath || undefined,
      metricsPath: form.metricsPath || undefined,
      logSourceUrl: form.logSourceUrl || undefined,
      description: form.description || undefined,
      enabled: form.enabled
    })
    ElMessage.success('外部服务已注册，建议先执行连通性检查')
    formRef.value.resetFields()
    form.enabled = true
    await load()
    if (created?.id) {
      await check(created)
    }
  } catch (ex) {
    ElMessage.error(ex.message || '注册失败')
  } finally {
    submitting.value = false
  }
}

async function check(row) {
  checkingId.value = row.id
  try {
    const result = await checkExternalService(row.id)
    ElMessage({
      type: result.available ? (result.status === 'UP' ? 'success' : 'warning') : 'error',
      message: `连通性检查：${result.status} · ${result.message || ''}`
    })
    await load()
  } catch (ex) {
    ElMessage.error(ex.message || '连通性检查失败')
  } finally {
    checkingId.value = ''
  }
}

async function diagnose(row) {
  try {
    await ElMessageBox.confirm(
      `将创建一条 source=EXTERNAL 的故障事件，并调用 Agent 基于真实 Actuator 证据诊断 ${row.name}。外部服务只读，不会执行重启、停止或修改。`,
      '开始外部服务诊断',
      { type: 'warning', confirmButtonText: '开始诊断', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const result = await startExternalDiagnosis(row.id)
    ElMessage.success('诊断任务已创建')
    router.push(`/fault-events/${result.faultEventId}`)
  } catch (ex) {
    ElMessage.error(ex.message || '启动诊断失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <section class="hero">
      <div>
        <div class="hero-eyebrow">EXTERNAL SERVICES</div>
        <h1>外部服务</h1>
        <p>
          注册本机或已授权的 Spring Boot 应用，基于 Actuator 健康与指标快照发起独立诊断。
          外部服务第一版只读，不提供重启、停止或修改配置的能力。
        </p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </section>

    <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />

    <el-card shadow="never" class="panel-card">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">注册外部服务</div>
            <div class="card-subtitle">
              仅允许 http/https、白名单主机与端口；不允许填写任意可访问地址
            </div>
          </div>
          <el-tag type="info" effect="plain">只读接入</el-tag>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="register-form">
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12">
            <el-form-item label="服务名称" prop="name">
              <el-input v-model="form.name" maxlength="64" placeholder="例如 petclinic" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="基地址" prop="baseUrl">
              <el-input v-model="form.baseUrl" maxlength="512" placeholder="例如 http://localhost:8083" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="健康端点">
              <el-input v-model="form.healthPath" maxlength="256" placeholder="/actuator/health" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="指标端点">
              <el-input v-model="form.metricsPath" maxlength="256" placeholder="/actuator/metrics（可选）" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="日志数据源">
              <el-input
                v-model="form.logSourceUrl"
                maxlength="512"
                placeholder="可选，必须与基地址同源；未配置时日志证据明确不可用"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="描述">
              <el-input v-model="form.description" maxlength="512" placeholder="可选" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="启用">
              <el-switch v-model="form.enabled" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label=" ">
              <el-button type="primary" :icon="Connection" :loading="submitting" @click="submit">
                注册并检查连通性
              </el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <el-card shadow="never" class="panel-card">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">已注册服务</div>
            <div class="card-subtitle">共 {{ services.length }} 个 · 诊断不会修改外部服务</div>
          </div>
          <el-tag type="warning" effect="plain">外部处置未开放</el-tag>
        </div>
      </template>

      <el-table v-loading="loading" :data="services" stripe row-key="id">
        <el-table-column prop="name" label="服务名称" width="150" />
        <el-table-column prop="baseUrl" label="基地址" min-width="200" show-overflow-tooltip />
        <el-table-column prop="healthPath" label="健康端点" width="170" show-overflow-tooltip />
        <el-table-column label="指标端点" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.metricsPath || '未配置' }}</template>
        </el-table-column>
        <el-table-column label="日志数据源" width="105">
          <template #default="{ row }">
            <el-tag :type="row.logSourceUrl ? 'success' : 'info'" effect="plain" size="small">
              {{ row.logSourceUrl ? '已配置' : '未配置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="95">
          <template #default="{ row }">
            <el-tag :type="checkType(row.lastCheckStatus)" effect="light" size="small">
              {{ row.lastCheckStatus || 'UNKNOWN' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近检查" width="170">
          <template #default="{ row }">{{ formatTime(row.lastCheckAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              :loading="checkingId === row.id"
              @click="check(row)"
            >
              检查连通性
            </el-button>
            <el-button type="warning" link :disabled="!row.enabled" @click="diagnose(row)">
              开始诊断
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="尚未注册外部服务" :image-size="80" />
        </template>
      </el-table>
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

.register-form {
  margin-top: 4px;
}

.register-form :deep(.el-form-item__label) {
  color: var(--rca-muted);
}
</style>

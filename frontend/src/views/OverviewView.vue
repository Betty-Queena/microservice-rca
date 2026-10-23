<script setup>
import { computed } from 'vue'
import { Connection, DataLine, Refresh } from '@element-plus/icons-vue'
import { useHealth } from '../composables/useHealth'

const {
  health,
  error,
  loading,
  clientLatencyMs,
  lastCheckedAt,
  status,
  databaseStatus,
  refresh
} = useHealth()

const statusText = computed(() => {
  switch (status.value) {
    case 'UP':
      return 'UP · 正常'
    case 'DEGRADED':
      return 'DEGRADED · 降级'
    case 'UNREACHABLE':
      return 'UNREACHABLE · 不可达'
    default:
      return 'UNKNOWN · 未知'
  }
})

const statusType = computed(() => {
  switch (status.value) {
    case 'UP':
      return 'success'
    case 'DEGRADED':
      return 'warning'
    case 'UNREACHABLE':
      return 'danger'
    default:
      return 'info'
  }
})

const databaseStatusText = computed(() => {
  switch (databaseStatus.value) {
    case 'UP':
      return 'UP · 连接正常'
    case 'DOWN':
      return 'DOWN · 连接失败'
    default:
      return 'UNKNOWN · 未知'
  }
})

const databaseStatusType = computed(() => {
  switch (databaseStatus.value) {
    case 'UP':
      return 'success'
    case 'DOWN':
      return 'danger'
    default:
      return 'info'
  }
})

const uptimeText = computed(() => {
  const seconds = health.value?.uptimeSeconds
  if (seconds == null) return '—'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = seconds % 60
  if (days > 0) return `${days} 天 ${hours} 小时 ${minutes} 分`
  if (hours > 0) return `${hours} 小时 ${minutes} 分 ${rest} 秒`
  if (minutes > 0) return `${minutes} 分 ${rest} 秒`
  return `${rest} 秒`
})

const checkedAtText = computed(() => {
  if (!lastCheckedAt.value) return '尚未检查'
  return lastCheckedAt.value.toLocaleString('zh-CN', { hour12: false })
})

const timestampText = computed(() => {
  if (!health.value?.timestamp) return '—'
  return new Date(health.value.timestamp).toLocaleString('zh-CN', { hour12: false })
})

const apiAddress = computed(() => `${import.meta.env.VITE_API_BASE_URL || ''}/api/health`)

const modules = [
  {
    name: '系统基础框架与健康检查',
    owner: '管理后端',
    status: '已完成',
    type: 'success',
    note: '统一响应、真实数据库探测、Flyway 迁移'
  },
  {
    name: '实验服务跨服务调用',
    owner: '订单 / 库存',
    status: '已完成',
    type: 'success',
    note: '订单服务通过 HTTP 真实调用库存服务'
  },
  {
    name: '故障注入实验与真实故障观测',
    owner: '库存 / 订单 / 管理后端',
    status: '已完成',
    type: 'success',
    note: '延迟与 HTTP 500 注入；真实跨服务请求触发并区分超时、5xx、业务冲突'
  },
  {
    name: '故障事件持久化与证据采集',
    owner: '管理后端 / PostgreSQL',
    status: '已完成',
    type: 'success',
    note: '实验预设与观测结果分离；证据写入 fault_event、diagnosis_task、tool_invocation'
  },
  {
    name: '真实日志、指标与诊断工具',
    owner: '观测层 / 工具层',
    status: '已完成',
    type: 'success',
    note: '结构化请求日志、分钟级指标、query_logs 与 query_metrics 只读工具'
  },
  {
    name: 'LLM Agent 工具调用',
    owner: 'Agent 层',
    status: '待开发',
    type: 'info',
    note: '工具注册表与 Agent 契约已就绪，Day 3 接入 DeepSeek'
  },
  {
    name: '根因结论与辅助处置',
    owner: '诊断层',
    status: '待开发',
    type: 'info',
    note: 'Day 2 不生成根因结论，避免把实验预设类型当作答案'
  },
  {
    name: '人工确认与处置闭环',
    owner: '管理面',
    status: '待开发',
    type: 'info',
    note: '后续迭代'
  }
]
</script>

<template>
  <div class="page-stack">
    <section class="hero">
      <div>
        <div class="hero-eyebrow">SYSTEM OVERVIEW</div>
        <h1>系统概览</h1>
        <p>
          查看管理后端与数据库的实时健康状态，并进入故障注入实验与故障事件页面验证完整链路。
          本页不展示任何模拟监控指标或 AI 分析结果。
        </p>
      </div>
      <el-button type="primary" :loading="loading" @click="refresh">
        <el-icon><Refresh /></el-icon>
        刷新状态
      </el-button>
    </section>

    <el-alert v-if="error" type="error" :closable="false" show-icon title="无法连接管理后端">
      <div class="alert-body">
        <span>{{ error }}</span>
        <span class="alert-hint">
          请确认 rca-backend 已在 8080 端口启动；开发环境下前端通过 Vite 代理访问 /api。
        </span>
      </div>
    </el-alert>

    <el-row :gutter="16">
      <el-col :xs="24" :sm="12" :lg="6" class="stat-col">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">管理后端</div>
          <div class="stat-value">
            <el-tag :type="statusType" effect="light" round>{{ statusText }}</el-tag>
          </div>
          <div class="stat-hint">{{ health?.service || 'rca-backend' }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6" class="stat-col">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">PostgreSQL</div>
          <div class="stat-value">
            <el-tag :type="databaseStatusType" effect="light" round>{{ databaseStatusText }}</el-tag>
          </div>
          <div class="stat-hint">
            探测耗时 {{ health?.database?.latencyMs != null ? `${health.database.latencyMs} ms` : '—' }}
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6" class="stat-col">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">前端请求耗时</div>
          <div class="stat-value stat-value--number">
            {{ clientLatencyMs != null ? `${clientLatencyMs} ms` : '—' }}
          </div>
          <div class="stat-hint">浏览器实测 /api/health 往返时间</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6" class="stat-col">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">服务版本</div>
          <div class="stat-value stat-value--number">{{ health?.version || '—' }}</div>
          <div class="stat-hint">最近检查 {{ checkedAtText }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="15" class="panel-col">
        <el-card shadow="never" class="panel-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">实时健康检查</div>
                <div class="card-subtitle">GET /api/health · 数据来自管理后端实时响应</div>
              </div>
              <el-tag type="info" effect="plain">真实接口</el-tag>
            </div>
          </template>

          <el-skeleton v-if="loading && !health" :rows="5" animated />

          <el-descriptions v-else :column="2" border class="health-descriptions">
            <el-descriptions-item label="服务名称">{{ health?.service || '—' }}</el-descriptions-item>
            <el-descriptions-item label="服务版本">{{ health?.version || '—' }}</el-descriptions-item>
            <el-descriptions-item label="整体状态">
              <el-tag :type="statusType" effect="light" round>{{ statusText }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="数据库状态">
              <el-tag :type="databaseStatusType" effect="light" round>{{ databaseStatusText }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="数据库探测耗时">
              {{ health?.database?.latencyMs != null ? `${health.database.latencyMs} ms` : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="前端请求耗时">
              {{ clientLatencyMs != null ? `${clientLatencyMs} ms` : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="服务运行时长">{{ uptimeText }}</el-descriptions-item>
            <el-descriptions-item label="后端时间戳">{{ timestampText }}</el-descriptions-item>
            <el-descriptions-item label="最近检查">{{ checkedAtText }}</el-descriptions-item>
            <el-descriptions-item label="请求地址"><code>{{ apiAddress }}</code></el-descriptions-item>
          </el-descriptions>

          <div class="data-note">
            数据库状态由后端执行 <code>SELECT 1</code> 得出；前端耗时由浏览器实测。数据库不可用时整体状态为
            DEGRADED，页面会如实展示。
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="9" class="panel-col">
        <el-card shadow="never" class="panel-card architecture-card">
          <template #header>
            <div class="card-header">
              <div>
                <div class="card-title">架构快照</div>
                <div class="card-subtitle">Day 2 已落地的组件</div>
              </div>
              <el-icon :size="18" color="#2563eb"><Connection /></el-icon>
            </div>
          </template>

          <ul class="architecture-list">
            <li>
              <strong>管理后端</strong>
              <span>统一 API、健康检查、诊断数据模型</span>
            </li>
            <li>
              <strong>实验服务</strong>
              <span>订单服务通过 HTTP 真实调用库存服务，支持可控故障注入</span>
            </li>
            <li>
              <strong>观测层</strong>
              <span>结构化请求日志、requestId 透传、分钟级真实请求指标</span>
            </li>
            <li>
              <strong>数据层</strong>
              <span>PostgreSQL 16 + Flyway V2；故障事件、诊断任务与工具审计</span>
            </li>
            <li>
              <strong>Agent 层</strong>
              <span>统一工具注册表已就绪，Day 3 接入模型</span>
            </li>
          </ul>

          <el-divider />

          <div class="truth-note">
            <el-icon><DataLine /></el-icon>
            <span>
              故障注入、故障事件、日志指标和证据工具均使用真实数据。Agent 根因诊断与独立工具审计页面
              标记为“待开发”，不展示虚构结论。
            </span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="panel-card">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">模块进度</div>
            <div class="card-subtitle">按 Day 1 实际情况标注</div>
          </div>
        </div>
      </template>

      <div class="module-list">
        <div v-for="item in modules" :key="item.name" class="module-row">
          <div class="module-main">
            <div class="module-name">{{ item.name }}</div>
            <div class="module-note">{{ item.note }}</div>
          </div>
          <div class="module-owner">{{ item.owner }}</div>
          <el-tag :type="item.type" effect="light" round>{{ item.status }}</el-tag>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.alert-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.alert-hint {
  color: #64748b;
  font-size: 12px;
}

.stat-col {
  margin-bottom: 16px;
}

.stat-card {
  height: 100%;
  border: 1px solid var(--rca-border);
  border-radius: var(--rca-radius);
}

.stat-label {
  color: var(--rca-muted);
  font-size: 12px;
  letter-spacing: 0.2px;
}

.stat-value {
  margin-top: 12px;
  font-size: 15px;
  font-weight: 600;
}

.stat-value--number {
  color: var(--rca-text);
  font-size: 22px;
  font-variant-numeric: tabular-nums;
  font-weight: 650;
}

.stat-hint {
  margin-top: 10px;
  color: var(--rca-muted);
  font-size: 12px;
}

.panel-col {
  margin-bottom: 16px;
}

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

.health-descriptions :deep(.el-descriptions__label) {
  width: 110px;
  color: var(--rca-muted);
  font-weight: 500;
}

.data-note {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: 9px;
  background: #f6f9fe;
  color: #526179;
  font-size: 12px;
  line-height: 1.7;
}

.data-note code,
.health-descriptions code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #eef2f8;
  color: #334155;
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 12px;
}

.architecture-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin: 4px 0 0;
  padding: 0;
  list-style: none;
}

.architecture-list li {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.architecture-list strong {
  color: var(--rca-text);
  font-size: 13px;
  font-weight: 600;
}

.architecture-list span {
  color: var(--rca-muted);
  font-size: 12px;
  line-height: 1.6;
}

.truth-note {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  color: var(--rca-muted);
  font-size: 12px;
  line-height: 1.7;
}

.truth-note .el-icon {
  margin-top: 2px;
  color: #2563eb;
}

.module-list {
  display: flex;
  flex-direction: column;
}

.module-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 150px 80px;
  align-items: center;
  gap: 16px;
  padding: 14px 4px;
  border-bottom: 1px solid #eef2f7;
}

.module-row:last-child {
  border-bottom: none;
}

.module-name {
  color: var(--rca-text);
  font-size: 13px;
  font-weight: 550;
}

.module-note {
  margin-top: 3px;
  color: var(--rca-muted);
  font-size: 12px;
}

.module-owner {
  color: var(--rca-muted);
  font-size: 12px;
}

@media (max-width: 640px) {
  .module-row {
    grid-template-columns: minmax(0, 1fr) 80px;
  }

  .module-owner {
    display: none;
  }
}
</style>

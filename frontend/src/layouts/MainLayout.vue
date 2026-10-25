<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Aim, Connection, Expand, Fold, Monitor, Refresh, Search, Tools, Warning } from '@element-plus/icons-vue'
import { useHealth } from '../composables/useHealth'

const route = useRoute()
const { status, loading, refresh } = useHealth()

const collapsed = ref(false)
const pageTitle = computed(() => route.meta?.title || '系统概览')

const statusMeta = computed(() => {
  switch (status.value) {
    case 'UP':
      return { text: '运行正常', type: 'success' }
    case 'DEGRADED':
      return { text: '服务降级', type: 'warning' }
    case 'UNREACHABLE':
      return { text: '后端不可达', type: 'danger' }
    default:
      return { text: '状态未知', type: 'info' }
  }
})

function handleResize() {
  if (window.innerWidth < 900) {
    collapsed.value = true
  }
}

onMounted(() => {
  handleResize()
  window.addEventListener('resize', handleResize)
  refresh()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
})
</script>

<template>
  <el-container class="app-shell">
    <el-aside :width="collapsed ? '64px' : '236px'" class="sidebar">
      <div class="brand">
        <div class="brand-mark">R</div>
        <div v-show="!collapsed" class="brand-copy">
          <strong>RCA Console</strong>
          <span>微服务故障分析与处置</span>
        </div>
      </div>

      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="nav-menu"
        background-color="transparent"
        text-color="#b9c6d8"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/overview">
          <el-icon><Monitor /></el-icon>
          <template #title>系统概览</template>
        </el-menu-item>
        <el-menu-item index="/fault-injection">
          <el-icon><Aim /></el-icon>
          <template #title>故障注入实验</template>
        </el-menu-item>
        <el-menu-item index="/fault-events">
          <el-icon><Warning /></el-icon>
          <template #title>故障事件</template>
        </el-menu-item>
        <el-menu-item index="/external-services">
          <el-icon><Connection /></el-icon>
          <template #title>外部服务</template>
        </el-menu-item>
        <el-menu-item index="/diagnosis-tasks">
          <el-icon><Search /></el-icon>
          <template #title>诊断任务</template>
        </el-menu-item>
        <el-menu-item index="/tool-invocations">
          <el-icon><Tools /></el-icon>
          <template #title>工具调用</template>
        </el-menu-item>
      </el-menu>

      <div v-show="!collapsed" class="sidebar-footer">
        <span>真实故障实验与 AI 诊断</span>
        <span>v0.3.0</span>
      </div>
    </el-aside>

    <el-container class="workspace">
      <el-header class="topbar">
        <div class="topbar-left">
          <el-button
            text
            class="collapse-button"
            :aria-label="collapsed ? '展开导航' : '收起导航'"
            @click="collapsed = !collapsed"
          >
            <el-icon :size="18">
              <Expand v-if="collapsed" />
              <Fold v-else />
            </el-icon>
          </el-button>
          <div>
            <div class="page-title">{{ pageTitle }}</div>
            <div class="page-subtitle">基于 LLM 的微服务故障分析与处置系统</div>
          </div>
        </div>

        <div class="topbar-right">
          <el-tag :type="statusMeta.type" effect="light" round class="status-tag">
            <span class="status-dot" :class="`status-dot--${statusMeta.type}`"></span>
            {{ statusMeta.text }}
          </el-tag>
          <el-button :loading="loading" circle aria-label="刷新健康状态" @click="refresh">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>
      </el-header>

      <el-main class="content">
        <router-view />
      </el-main>

      <el-footer class="footer">
        页面数据来自真实接口；AI 诊断、任务历史与人工处置状态均可追溯。
      </el-footer>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-shell {
  height: 100vh;
}

.sidebar {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--rca-sidebar);
  transition: width 0.2s ease;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px 18px;
}

.brand-mark {
  display: grid;
  flex: 0 0 38px;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 10px;
  background: linear-gradient(135deg, #4f8cff, #1d4ed8);
  color: #ffffff;
  font-size: 18px;
  font-weight: 700;
}

.brand-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  line-height: 1.3;
}

.brand-copy strong {
  color: #ffffff;
  font-size: 15px;
  letter-spacing: 0.2px;
}

.brand-copy span {
  margin-top: 3px;
  overflow: hidden;
  color: #8fa3bd;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-menu {
  flex: 1;
  padding: 6px 8px;
  border-right: none;
}

.nav-menu :deep(.el-menu-item) {
  height: 46px;
  margin: 4px 0;
  border-radius: 9px;
}

.nav-menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.06);
}

.nav-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, rgba(79, 140, 255, 0.38), rgba(79, 140, 255, 0.12));
}

.nav-menu :deep(.el-menu--collapse) {
  width: 56px;
}

.sidebar-footer {
  display: flex;
  justify-content: space-between;
  padding: 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  color: #7388a3;
  font-size: 11px;
}

.workspace {
  min-width: 0;
}

.topbar {
  display: flex;
  height: 72px;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid var(--rca-border);
  background: #ffffff;
}

.topbar-left {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

.collapse-button {
  flex: 0 0 auto;
  color: #5c6c82;
}

.page-title {
  color: var(--rca-text);
  font-size: 17px;
  font-weight: 650;
}

.page-subtitle {
  margin-top: 3px;
  color: var(--rca-muted);
  font-size: 12px;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.status-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #94a3b8;
}

.status-dot--success {
  background: #16a34a;
}

.status-dot--warning {
  background: #d97706;
}

.status-dot--danger {
  background: #dc2626;
}

.content {
  padding: 24px;
  background: var(--rca-bg);
}

.footer {
  height: 46px;
  padding: 0;
  background: transparent;
  color: var(--rca-muted);
  font-size: 12px;
  line-height: 46px;
  text-align: center;
}

@media (max-width: 900px) {
  .content {
    padding: 14px;
  }

  .topbar {
    padding: 0 12px;
  }

  .page-subtitle {
    display: none;
  }

  .status-tag {
    display: none;
  }
}
</style>

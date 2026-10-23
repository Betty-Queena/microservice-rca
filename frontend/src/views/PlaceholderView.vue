<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const planned = computed(() => route.meta?.planned || [])
</script>

<template>
  <div class="page-stack">
    <section class="hero">
      <div>
        <div class="hero-eyebrow">DETAIL ENTRY</div>
        <h1>{{ route.meta.title }}</h1>
        <p>{{ route.meta.description }}</p>
      </div>
      <el-tag type="info" effect="plain" size="large">详情页查看</el-tag>
    </section>

    <el-card shadow="never" class="panel-card">
      <el-empty :description="route.meta.emptyText || '请从故障事件详情页查看相关能力'">
        <div class="planned-list">
          <div class="planned-title">已具备能力（位于故障事件详情）</div>
          <ul>
            <li v-for="item in planned" :key="item">{{ item }}</li>
          </ul>
        </div>
      </el-empty>
    </el-card>
  </div>
</template>

<style scoped>
.panel-card {
  border: 1px solid var(--rca-border);
  border-radius: var(--rca-radius);
}

.planned-list {
  min-width: 260px;
  padding: 8px 20px 4px;
  text-align: left;
}

.planned-title {
  margin-bottom: 8px;
  color: var(--rca-text);
  font-size: 13px;
  font-weight: 600;
}

.planned-list ul {
  margin: 0;
  padding-left: 18px;
  color: var(--rca-muted);
  font-size: 13px;
  line-height: 1.9;
}
</style>

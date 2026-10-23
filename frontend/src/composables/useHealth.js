import { computed, ref } from 'vue'
import { fetchHealth } from '../api/system'

const health = ref(null)
const error = ref('')
const loading = ref(false)
const clientLatencyMs = ref(null)
const lastCheckedAt = ref(null)

export function useHealth() {
  const status = computed(() => {
    if (health.value?.status) return health.value.status
    if (error.value) return 'UNREACHABLE'
    return 'UNKNOWN'
  })

  const databaseStatus = computed(() => health.value?.database?.status || 'UNKNOWN')

  async function refresh() {
    loading.value = true
    error.value = ''
    try {
      const result = await fetchHealth()
      health.value = result.data
      clientLatencyMs.value = result.clientLatencyMs
    } catch (err) {
      health.value = null
      clientLatencyMs.value = null
      error.value = err?.response?.data?.message || err?.message || '无法连接管理后端'
    } finally {
      lastCheckedAt.value = new Date()
      loading.value = false
    }
  }

  return {
    health,
    error,
    loading,
    clientLatencyMs,
    lastCheckedAt,
    status,
    databaseStatus,
    refresh
  }
}

import http from './http'

/**
 * 调用管理后端真实健康接口，并记录一次完整请求的前端耗时。
 */
export async function fetchHealth() {
  const startedAt = performance.now()
  const response = await http.get('/api/health')
  const clientLatencyMs = Math.round(performance.now() - startedAt)
  const body = response.data

  if (!body || body.code !== 200) {
    throw new Error(body?.message || '健康检查返回异常')
  }

  return { data: body.data, clientLatencyMs }
}

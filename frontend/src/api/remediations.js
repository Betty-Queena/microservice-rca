import http from './http'

function unwrap(response) {
  const body = response.data
  if (!body || body.code !== 200) {
    const error = new Error(body?.message || '请求失败')
    // 已经收到 HTTP 响应，属于明确失败，不能当作“结果未知”。
    error.status = response.status
    error.code = body?.code
    throw error
  }
  return body.data
}

export async function fetchRemediations(eventId) {
  return unwrap(await http.get(`/api/fault-events/${eventId}/remediations`))
}

export async function fetchRemediationByIdempotencyKey(eventId, idempotencyKey) {
  return unwrap(
    await http.get(
      `/api/fault-events/${eventId}/remediations/by-idempotency-key/${encodeURIComponent(idempotencyKey)}`
    )
  )
}

export async function createRemediation(eventId, payload) {
  // 同步处置包含一次真实 checkout，默认 15 秒容易误判；超时后只查询，不自动重试。
  return unwrap(
    await http.post(`/api/fault-events/${eventId}/remediations`, payload, { timeout: 45000 })
  )
}

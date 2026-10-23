import http from './http'

function unwrap(response) {
  const body = response.data
  if (!body || body.code !== 200) {
    throw new Error(body?.message || '请求失败')
  }
  return body.data
}

export async function fetchFaultEvents(params) {
  return unwrap(await http.get('/api/fault-events', { params }))
}

export async function fetchFaultEventDetail(eventId) {
  return unwrap(await http.get(`/api/fault-events/${eventId}`))
}

export async function updateFaultEventStatus(eventId, status) {
  return unwrap(await http.patch(`/api/fault-events/${eventId}/status`, { status }))
}

export async function collectFaultEventEvidence(eventId, payload = {}) {
  return unwrap(await http.post(`/api/fault-events/${eventId}/evidence`, payload))
}

export async function fetchDiagnosticTools() {
  return unwrap(await http.get('/api/diagnosis/tools'))
}

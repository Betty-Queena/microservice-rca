import http from './http'

function unwrap(response) {
  const body = response.data
  if (!body || body.code !== 200) {
    throw new Error(body?.message || '请求失败')
  }
  return body.data
}

export async function fetchExternalServices(params = {}) {
  return unwrap(await http.get('/api/external-services', { params }))
}

export async function registerExternalService(payload) {
  return unwrap(await http.post('/api/external-services', payload))
}

export async function checkExternalService(serviceId) {
  return unwrap(await http.post(`/api/external-services/${serviceId}/check`))
}

export async function startExternalDiagnosis(serviceId, payload = {}) {
  return unwrap(await http.post(`/api/external-services/${serviceId}/diagnosis`, payload))
}

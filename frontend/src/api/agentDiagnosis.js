import http from './http'

function unwrap(response) {
  const body = response.data
  if (!body || body.code !== 200) {
    throw new Error(body?.message || '请求失败')
  }
  return body.data
}

export async function startAgentDiagnosis(eventId) {
  return unwrap(await http.post(`/api/fault-events/${eventId}/diagnosis`))
}

export async function fetchAgentTask(taskId) {
  return unwrap(await http.get(`/api/diagnosis/tasks/${taskId}`))
}

export async function fetchAgentTasks(eventId) {
  return unwrap(await http.get('/api/diagnosis/tasks', { params: { faultEventId: eventId } }))
}

export async function retryAgentTask(taskId) {
  return unwrap(await http.post(`/api/diagnosis/tasks/${taskId}/retry`))
}

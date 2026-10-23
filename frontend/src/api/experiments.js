import http from './http'

function unwrap(response) {
  const body = response.data
  if (!body || body.code !== 200) {
    throw new Error(body?.message || '请求失败')
  }
  return body.data
}

export async function fetchFaultStatus() {
  return unwrap(await http.get('/api/experiments/faults'))
}

export async function enableFault(payload) {
  return unwrap(await http.post('/api/experiments/faults/enable', payload))
}

export async function disableFault() {
  return unwrap(await http.post('/api/experiments/faults/disable'))
}

export async function resetFault() {
  return unwrap(await http.post('/api/experiments/faults/reset'))
}

export async function fetchExperimentInventory() {
  return unwrap(await http.get('/api/experiments/inventory'))
}

export async function executeExperiment(experimentId, payload) {
  return unwrap(await http.post(`/api/experiments/${experimentId}/execute`, payload))
}

export async function runControlCheckout(payload) {
  return unwrap(await http.post('/api/experiments/checkout', payload))
}

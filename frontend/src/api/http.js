import axios from 'axios'

function backendMessage(data) {
  if (data == null) return ''
  if (typeof data === 'string') {
    const text = data.trim()
    return text.startsWith('<') ? '' : text.slice(0, 300)
  }
  if (typeof data === 'object') {
    const message = data.message ?? data.error
    if (typeof message === 'string' && message.trim()) {
      return message.trim()
    }
  }
  return ''
}

function normalizeError(error) {
  if (!error) {
    return new Error('请求失败，请稍后重试')
  }
  if (error.code === 'ERR_CANCELED') {
    return error
  }
  if (error.__normalized) {
    return error
  }

  const status = error.response?.status
  let message = backendMessage(error.response?.data)
  if (!message) {
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT' || /timeout/i.test(error.message || '')) {
      message = '请求超时，请确认后端服务是否正常运行'
    } else if (!error.response) {
      message = '无法连接后端服务，请检查网络或确认服务已启动'
    } else if (status === 404) {
      message = '请求的资源不存在（HTTP 404）'
    } else if (status >= 500) {
      message = `后端服务异常（HTTP ${status}）`
    } else {
      message = `请求失败（HTTP ${status || '未知'}）`
    }
  }

  const normalized = new Error(message)
  normalized.status = status
  normalized.code = error.code
  normalized.cause = error
  normalized.__normalized = true
  return normalized
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 15000,
  headers: {
    Accept: 'application/json'
  }
})

http.interceptors.response.use(
  (response) => response,
  (error) => Promise.reject(normalizeError(error))
)

export default http

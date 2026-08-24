import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '',
  timeout: 15000,
})

let refreshing = null

function persistTokens(token, refreshToken) {
  if (token) {
    localStorage.setItem('token', token)
  }
  if (refreshToken) {
    localStorage.setItem('refreshToken', refreshToken)
  }
}

function clearLocalAuth() {
  localStorage.removeItem('token')
  localStorage.removeItem('refreshToken')
  localStorage.removeItem('role')
  localStorage.removeItem('userId')
}

async function refreshAccessToken() {
  const refreshToken = localStorage.getItem('refreshToken')
  if (!refreshToken) {
    throw new Error('no refresh token')
  }
  const res = await axios.post('/api/auth/refresh', { refreshToken })
  if (!res.data || res.data.code !== 200) {
    throw new Error((res.data && res.data.message) || 'refresh failed')
  }
  const data = res.data.data
  persistTokens(data.token, data.refreshToken)
  return data.token
}

request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  async (error) => {
    const { config, response } = error
    if (response) {
      const { status, data } = response
      if (status === 401 && config && !config.skipAuthRefresh && !config._retry) {
        config._retry = true
        try {
          if (!refreshing) {
            refreshing = refreshAccessToken().finally(() => {
              refreshing = null
            })
          }
          const newToken = await refreshing
          config.headers.Authorization = `Bearer ${newToken}`
          return request(config)
        } catch (e) {
          clearLocalAuth()
          ElMessage.error('登录已过期，请重新登录')
          if (window.location.pathname !== '/login') {
            window.location.href = '/login'
          }
          return Promise.reject(error)
        }
      }
      if (status === 401) {
        clearLocalAuth()
        ElMessage.error('登录已过期，请重新登录')
        if (window.location.pathname !== '/login') {
          window.location.href = '/login'
        }
      } else if (status === 403) {
        ElMessage.error((data && data.message) || '无权限访问')
      } else {
        const msg = (data && data.message) || `服务器错误(${status})`
        ElMessage.error(msg)
      }
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请稍后重试')
    } else {
      ElMessage.error('网络异常，请检查网络连接')
    }
    return Promise.reject(error)
  }
)

export default request

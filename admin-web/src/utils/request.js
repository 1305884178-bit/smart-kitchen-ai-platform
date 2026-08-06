import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '',
  timeout: 15000,
})

// 请求拦截器：自动注入 Authorization
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器：统一错误处理
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 后端 Result 统一响应：code !== 200 视为业务异常
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    if (error.response) {
      const { status, data } = error.response
      if (status === 401) {
        // Token 过期或未登录
        localStorage.removeItem('token')
        localStorage.removeItem('role')
        localStorage.removeItem('userId')
        ElMessage.error('登录已过期，请重新登录')
        // 跳转登录页（避免在登录页重复跳转）
        if (window.location.pathname !== '/login') {
          window.location.href = '/login'
        }
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

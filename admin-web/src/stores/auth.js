import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import request from '@/utils/request'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const role = ref(localStorage.getItem('role') || '')
  const userId = ref(localStorage.getItem('userId') || '')

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => role.value === 'ADMIN')

  /**
   * 账密登录
   * @param {string} username
   * @param {string} password
   * @returns {Promise<{token: string, refreshToken: string, role: string, userId: number}>}
   */
  async function login(username, password) {
    const res = await request.post('/api/auth/login', { username, password })
    const { token: t, refreshToken: rt, role: r, userId: uid } = res.data
    setAuth(t, r, uid, rt)
    return res.data
  }

  function setAuth(t, r, uid, rt) {
    token.value = t
    role.value = r
    userId.value = String(uid)
    localStorage.setItem('token', t)
    localStorage.setItem('role', r)
    localStorage.setItem('userId', String(uid))
    if (rt) {
      refreshToken.value = rt
      localStorage.setItem('refreshToken', rt)
    }
  }

  async function logout() {
    try {
      await request.post(
        '/api/auth/logout',
        { refreshToken: refreshToken.value || localStorage.getItem('refreshToken') },
        { skipAuthRefresh: true }
      )
    } catch (e) {
      // 本地清理仍继续
    }
    token.value = ''
    refreshToken.value = ''
    role.value = ''
    userId.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('role')
    localStorage.removeItem('userId')
  }

  return { token, refreshToken, role, userId, isLoggedIn, isAdmin, login, logout, setAuth }
})

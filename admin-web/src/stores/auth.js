import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import request from '@/utils/request'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const role = ref(localStorage.getItem('role') || '')
  const userId = ref(localStorage.getItem('userId') || '')

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => role.value === 'ADMIN')

  /**
   * 账密登录
   * @param {string} username
   * @param {string} password
   * @returns {Promise<{token: string, role: string, userId: number}>}
   */
  async function login(username, password) {
    const res = await request.post('/api/auth/login', { username, password })
    const { token: t, role: r, userId: uid } = res.data
    setAuth(t, r, uid)
    return res.data
  }

  function setAuth(t, r, uid) {
    token.value = t
    role.value = r
    userId.value = String(uid)
    localStorage.setItem('token', t)
    localStorage.setItem('role', r)
    localStorage.setItem('userId', String(uid))
  }

  function logout() {
    token.value = ''
    role.value = ''
    userId.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('role')
    localStorage.removeItem('userId')
  }

  return { token, role, userId, isLoggedIn, isAdmin, login, logout, setAuth }
})

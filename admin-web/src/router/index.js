import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', noAuth: true },
  },
  {
    path: '/',
    component: () => import('@/views/layout/index.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '仪表盘' },
      },
      {
        path: 'kitchen-board',
        name: 'KitchenBoard',
        component: () => import('@/views/kitchen-board/index.vue'),
        meta: { title: '厨房看板' },
      },
      {
        path: 'orders',
        name: 'Orders',
        component: () => import('@/views/orders/index.vue'),
        meta: { title: '订单管理' },
      },
      {
        path: 'dishes',
        name: 'Dishes',
        component: () => import('@/views/dishes/index.vue'),
        meta: { title: '菜品管理' },
      },
      {
        path: 'stock',
        name: 'Stock',
        component: () => import('@/views/stock/index.vue'),
        meta: { title: '库存管理' },
      },
      {
        path: 'predict',
        name: 'Predict',
        component: () => import('@/views/predict/index.vue'),
        meta: { title: 'AI 备菜预测' },
      },
      {
        path: 'knowledge',
        name: 'Knowledge',
        component: () => import('@/views/knowledge/index.vue'),
        meta: { title: 'AI 知识库' },
      },
      {
        path: 'review',
        name: 'Review',
        component: () => import('@/views/review/index.vue'),
        meta: { title: '评价管理' },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 权限守卫：校验登录态，仅 ADMIN 角色可访问
router.beforeEach((to, from, next) => {
  // 设置页面标题
  document.title = to.meta.title ? `${to.meta.title} - 智慧后厨` : '智慧后厨 - 管理后台'

  // 无需认证的页面直接放行
  if (to.meta.noAuth) {
    return next()
  }

  const token = localStorage.getItem('token')
  const role = localStorage.getItem('role')

  if (!token) {
    // 未登录，跳转登录页
    return next({ path: '/login', query: { redirect: to.fullPath } })
  }

  if (role !== 'ADMIN') {
    // 非管理员角色，拒绝访问
    return next({ path: '/login' })
  }

  next()
})

export default router

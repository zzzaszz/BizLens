import {createRouter, createWebHistory} from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    meta: { requiresAuth: true },
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue')
      },
      // AI 对话
      {
        path: 'ai',
        name: 'AiChat',
        component: () => import('@/views/AiChat.vue')
      },
      // AI 角色
      {
        path: 'ai/agents',
        name: 'AiAgentManage',
        component: () => import('@/views/AiAgentManage.vue')
      },
      {
        path: 'projects',
        name: 'CodeProjects',
        component: () => import('@/views/CodeProjects.vue')
      },
      {
        path: 'knowledge',
        name: 'KnowledgeSources',
        component: () => import('@/views/KnowledgeSources.vue')
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/Profile.vue')
      },
    ]
  },
  // 404 处理
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/Login.vue'),
    meta: { requiresAuth: false }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫 - 登录拦截 + 管理员权限
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  const requiresAuth = to.matched.some(record => record.meta.requiresAuth !== false)
    const requiresAdmin = to.matched.some(record => record.meta.requiresAdmin)

  if (requiresAuth && !token) {
    // 需要登录但没有 token，跳转登录页
    next('/login')
      return
  }
    if (to.path === '/login' && token) {
    // 已登录访问登录页，跳转首页
    next('/')
        return
    }
    if (requiresAdmin) {
        // 需要管理员权限
        let role = 0
        try {
            role = JSON.parse(localStorage.getItem('userInfo') || '{}').role || 0
        } catch {
        }
        if (role !== 1) {
            next('/')
            return
        }
    }
    next()
})

export default router

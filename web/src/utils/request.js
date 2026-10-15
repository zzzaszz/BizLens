import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

// 根据环境设置 baseURL
// 开发环境使用 /api，通过 vite 代理去掉
// 生产环境不使用 /api，直接请求后端
const isProd = import.meta.env.PROD
const request = axios.create({
  baseURL: isProd ? '' : '/api',
  timeout: 30000
})

// token 无效时的提示消息（匹配后端返回）
const TOKEN_INVALID_MSGS = ['token is null', 'userinfo is null', 'token已过期', '登录已过期']

// 清除所有登录相关数据
function clearAuthData() {
  localStorage.removeItem('token')
  localStorage.removeItem('accessToken')
  localStorage.removeItem('refreshToken')
  localStorage.removeItem('userInfo')
}

// 判断是否是刷新 token 的请求
function isRefreshRequest(config) {
  return config.url === '/api/auth/refresh' || config._isRefresh
}

// 刷新 token
async function doRefreshToken() {
  const refreshToken = localStorage.getItem('refreshToken')
  if (!refreshToken) {
    return false
  }

  try {
    // 使用独立的 axios 实例，避免触发拦截器循环
    const refreshClient = axios.create({
      baseURL: isProd ? '' : '/api',
      timeout: 10000
    })

    const response = await refreshClient.post('/api/auth/refresh', { refreshToken })

    if (response.data.code === 200 && response.data.data) {
      const data = response.data.data
      // 更新所有 token
      localStorage.setItem('token', data.token)
      localStorage.setItem('accessToken', data.accessToken)
      if (data.refreshToken) {
        localStorage.setItem('refreshToken', data.refreshToken)
      }
      // 同步更新 userInfo 中的主题颜色等字段
      try {
        const storedInfo = JSON.parse(localStorage.getItem('userInfo') || '{}')
        if (data.nickname) storedInfo.nickname = data.nickname
        if (data.avatar) storedInfo.avatar = data.avatar
        if (data.themeColor) storedInfo.themeColor = data.themeColor
        if (data.role !== undefined) storedInfo.role = data.role
        localStorage.setItem('userInfo', JSON.stringify(storedInfo))
      } catch { /* ignore */ }
      return true
    }
    return false
  } catch (error) {
    console.error('刷新 token 失败:', error)
    return false
  }
}

// 请求拦截器
request.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers['Authorization'] = token
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器
request.interceptors.response.use(
  response => {
    const res = response.data

    // 正常响应
    if (res.code === 200) {
      return res
    }

    // 401 且不是刷新请求，尝试刷新 token
    if (res.code === 401 && !isRefreshRequest(response.config)) {
      return handleTokenExpired(response)
    }

      // 403 无权限（管理员接口），提示后拒绝
      if (res.code === 403) {
          ElMessage.error(res.msg || '无权限访问')
          const err = new Error(res.msg || '无权限访问')
          err.code = res.code
          err.data = res.data
          return Promise.reject(err)
      }

    // token 无效相关错误，直接跳转登录页
    if (TOKEN_INVALID_MSGS.some(msg => res.msg && res.msg.includes(msg))) {
      ElMessage.error('登录已过期，请重新登录')
      clearAuthData()
      router.push('/login')
      return Promise.reject(new Error(res.msg || '登录已过期'))
    }

    // 其他错误
    ElMessage.error(res.msg || '请求失败')
    const err = new Error(res.msg || '请求失败')
    err.code = res.code
    err.data = res.data
    return Promise.reject(err)
  },
  async (error) => {
    // HTTP 401 状态码
    if (error.response && error.response.status === 401 && !isRefreshRequest(error.config)) {
      return handleTokenExpired(error)
    }

    ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

// 处理 token 过期
async function handleTokenExpired(originalResponseOrError) {
  const config = originalResponseOrError.config || originalResponseOrError.response?.config

  try {
    const isSuccess = await doRefreshToken()
    if (isSuccess) {
      // 刷新成功，重新发起原请求
      const token = localStorage.getItem('token')
      config.headers['Authorization'] = token
      config._isRefresh = true
      return request.request(config)
    } else {
      // 刷新失败
      ElMessage.error('登录已过期，请重新登录')
      clearAuthData()
      router.push('/login')
      return Promise.reject(new Error('登录已过期'))
    }
  } catch (error) {
    ElMessage.error('刷新登录状态失败，请重新登录')
    clearAuthData()
    router.push('/login')
    return Promise.reject(error)
  }
}

export default request

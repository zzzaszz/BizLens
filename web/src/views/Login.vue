<template>
  <div class="login-container">
    <div class="login-bg" aria-hidden="true"></div>

    <div class="login-card">
      <div class="logo-area">
        <div class="logo-box">BL</div>
        <h1>BizLens AI</h1>
        <p>代码知识库问答</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" class="login-form">
        <el-form-item prop="username">
          <el-input
              v-model="form.username"
              clearable
              maxlength="32"
              placeholder="账号"
              size="large"
          >
            <template #prefix>
              <el-icon>
                <User/>
              </el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item prop="password">
          <el-input
              v-model="form.password"
              :placeholder="isRegister ? '设置密码（6-32位）' : '密码'"
              :type="showPassword ? 'text' : 'password'"
              maxlength="32"
              size="large"
              @keydown.enter.prevent="handleSubmit"
          >
            <template #prefix>
              <el-icon>
                <Lock/>
              </el-icon>
            </template>
            <template #suffix>
              <el-icon class="pwd-toggle" @click="showPassword = !showPassword">
                <View v-if="showPassword"/>
                <Hide v-else/>
              </el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item v-if="isRegister" prop="nickname">
          <el-input
              v-model="form.nickname"
              maxlength="32"
            size="large"
              placeholder="昵称（选填，留空则随机）"
            clearable
          >
            <template #prefix>
              <el-icon>
                <EditPen/>
              </el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="submit-btn"
          @click="handleSubmit"
          :loading="loading"
        >
          {{ loading ? '处理中...' : (isRegister ? '注册并进入' : '登录') }}
        </el-button>
      </el-form>

      <div class="switch-mode">
        <span v-if="!isRegister">
          还没有账号？
          <el-link :underline="false" type="primary" @click="toggleMode(true)">立即注册</el-link>
        </span>
        <span v-else>
          已有账号？
          <el-link :underline="false" type="primary" @click="toggleMode(false)">返回登录</el-link>
        </span>
      </div>

      <div class="login-footer">
        BizLens AI v1.0
      </div>
    </div>
  </div>
</template>

<script setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {login, register} from '@/api'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)
const isRegister = ref(false)
const showPassword = ref(false)

const form = reactive({
  username: '',
  password: '',
  nickname: ''
})

const rules = computed(() => ({
  username: [
    {required: true, message: '请输入账号', trigger: 'blur'},
    {pattern: /^[A-Za-z0-9_]{3,32}$/, message: '账号为 3-32 位字母、数字或下划线', trigger: 'blur'}
  ],
  password: [
    {required: true, message: '请输入密码', trigger: 'blur'},
    {min: 6, max: 32, message: '密码长度应为 6-32 位', trigger: 'blur'}
  ],
  nickname: isRegister.value
      ? [{max: 32, message: '昵称最长 32 位', trigger: 'blur'}]
      : []
}))

function toggleMode(toRegister) {
  isRegister.value = toRegister
  showPassword.value = false
  formRef.value && formRef.value.clearValidate()
}

function persistLogin(data) {
  localStorage.setItem('token', data.token)
  localStorage.setItem('accessToken', data.accessToken)
  localStorage.setItem('refreshToken', data.refreshToken)
  localStorage.setItem('userInfo', JSON.stringify({
    userId: data.userId,
    nickname: data.nickname,
    avatar: data.avatar,
    role: data.role ?? 0,
    themeColor: data.themeColor || '#7C3AED'
  }))
  // 应用主题颜色到全局 CSS 变量
  applyThemeColor(data.themeColor || '#7C3AED')
}

/** 将主题颜色应用到全局 CSS 变量 */
function applyThemeColor(color) {
  const root = document.documentElement
  root.style.setProperty('--accent-color', color)
  // 生成对应的深色和浅色变体
  root.style.setProperty('--accent-dark', adjustColor(color, -20))
  root.style.setProperty('--accent-light', adjustColor(color, 20))
  root.style.setProperty('--sidebar-active-bg', color)
}

/** 简单的颜色明度调整（hex 颜色 ±amount） */
function adjustColor(hex, amount) {
  let r, g, b
  if (hex.startsWith('#')) {
    const num = parseInt(hex.slice(1), 16)
    r = (num >> 16) & 0xff
    g = (num >> 8) & 0xff
    b = num & 0xff
  } else {
    return hex
  }
  r = Math.max(0, Math.min(255, r + amount))
  g = Math.max(0, Math.min(255, g + amount))
  b = Math.max(0, Math.min(255, b + amount))
  return `#${((r << 16) | (g << 8) | b).toString(16).padStart(6, '0')}`
}

const handleSubmit = async () => {
  try {
    await formRef.value.validate()
    loading.value = true

    const payload = {
      username: form.username.trim(),
      password: form.password
    }
    if (isRegister.value && form.nickname.trim()) {
      payload.nickname = form.nickname.trim()
    }

    const res = isRegister.value
        ? await register(payload)
        : await login(payload)
    const data = res.data

    persistLogin(data)
    ElMessage.success(`${isRegister.value ? '注册成功，' : ''}欢迎，${data.nickname}`)
    router.push('/')
  } catch (error) {
    // 校验失败或接口失败（接口错误由拦截器统一提示）
    if (error && error.message) {
      console.error(isRegister.value ? '注册错误:' : '登录错误:', error)
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  const token = localStorage.getItem('token')
  if (token) {
    router.push('/')
  }
})
</script>

<style scoped>
.login-container {
  width: 100%;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
}

/* 背景：纯 CSS 渐变（光源 + 底色），不依赖背景图 */
.login-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 20%, rgba(124, 58, 237, 0.55), transparent 55%),
    radial-gradient(circle at 82% 80%, rgba(59, 130, 246, 0.45), transparent 55%),
    linear-gradient(135deg, #100e1c 0%, #1e1b4b 55%, #2a1d5e 100%);
}

/* 细网格纹理，替代原背景图的层次感 */
.login-bg::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.04) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(circle at 50% 50%, #000 20%, transparent 78%);
  -webkit-mask-image: radial-gradient(circle at 50% 50%, #000 20%, transparent 78%);
}

.login-card {
  width: 360px;
  background: var(--bg-white);
  border-radius: var(--radius-lg);
  padding: 48px 40px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06), 0 8px 24px rgba(0, 0, 0, 0.04);
  position: relative;
  z-index: 1;
  border: 1px solid var(--border-light);
}

.logo-area {
  text-align: center;
  margin-bottom: 36px;
}

/* 品牌标识：渐变色块 + 字母，替代原 logo 图片，跟随主题色 */
.logo-box {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 16px;
  background: linear-gradient(135deg, var(--accent-color), var(--accent-dark));
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.5px;
  box-shadow: 0 6px 16px rgba(124, 58, 237, 0.26);
}

.logo-area h1 {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 4px;
  letter-spacing: -0.02em;
}

.logo-area p {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0;
}

/* ========== 表单项：错误提示在下方，不影响输入框位置 ========== */
.login-form :deep(.el-form-item) {
  margin-bottom: 22px;
}

/* 错误从 absolute 改为 static，放进正常流，避免和输入框重叠 */
.login-form :deep(.el-form-item__error) {
  position: static;
  line-height: 20px;
  margin-top: 4px;
  font-size: 12px;
}

/* ========== 输入框：只覆盖背景、圆角、内边距，边框/焦点由 Element Plus 控制 ========== */
.login-form :deep(.el-input__wrapper) {
  border-radius: var(--radius-md);
  padding: 8px 14px;
  background: var(--bg-light);
}

/* focus 时仅切换背景色，边框颜色由 Element Plus 默认 */
.login-form :deep(.el-input__wrapper.is-focus) {
  background: var(--bg-white);
}

.login-form :deep(.el-input__inner) {
  font-size: 14px;
}

.login-form :deep(.el-input__prefix .el-icon),
.login-form :deep(.el-input__suffix .el-icon) {
  color: var(--text-muted);
}

.pwd-toggle {
  cursor: pointer;
  color: var(--text-muted);
}

.pwd-toggle:hover {
  color: var(--primary-light);
}

.submit-btn {
  width: 100%;
  height: 44px;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 600;
  background: var(--primary-color);
  border: none;
  transition: all var(--transition-fast);
  letter-spacing: 0.02em;
}

.submit-btn:hover {
  background: var(--primary-light);
}

.switch-mode {
  text-align: center;
  margin-top: 18px;
  font-size: 13px;
  color: var(--text-secondary);
}

.login-footer {
  text-align: center;
  margin-top: 20px;
  font-size: 12px;
  color: var(--text-muted);
}

@media (max-width: 480px) {
  .login-card {
    width: calc(100% - 32px);
    padding: 36px 24px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .submit-btn:hover {
    transform: none;
  }
}
</style>

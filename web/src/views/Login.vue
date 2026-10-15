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

        <el-form-item v-if="isRegister" prop="inviteCode">
          <el-input
              v-model="form.inviteCode"
              maxlength="8"
              placeholder="邀请码（向已有用户获取）"
              size="large"
              @input="form.inviteCode = form.inviteCode.toUpperCase()"
          >
            <template #prefix>
              <el-icon>
                <Key/>
              </el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item v-if="!isRegister && showCaptcha" prop="captchaCode">
          <div class="captcha-row">
            <el-input
                v-model="form.captchaCode"
                maxlength="4"
                placeholder="请输入验证码"
                size="large"
                @input="form.captchaCode = form.captchaCode.toUpperCase()"
            >
              <template #prefix>
                <el-icon>
                  <Picture/>
                </el-icon>
              </template>
            </el-input>
            <button
                type="button"
                class="captcha-image"
                aria-label="刷新验证码"
                @click="refreshCaptcha"
                v-html="captchaSvg"
            ></button>
          </div>
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
import {getCaptcha, login, register} from '@/api'
import {Key, Picture} from '@element-plus/icons-vue'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)
const isRegister = ref(false)
const showPassword = ref(false)
const showCaptcha = ref(false)
const failCount = ref(0)
const captchaId = ref('')
const captchaSvg = ref('')

const form = reactive({
  username: '',
  password: '',
  nickname: '',
  inviteCode: '',
  captchaCode: ''
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
  inviteCode: isRegister.value
      ? [
        {
          validator: (_rule, value, callback) => {
            if (!value || /^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$/.test(value)) {
              callback()
            } else {
              callback(new Error('邀请码为 8 位大写字母或数字'))
            }
          },
          trigger: 'blur'
        }
      ]
      : [],
  captchaCode: !isRegister.value && showCaptcha.value
      ? [
        {required: true, message: '请输入验证码', trigger: 'blur'},
        {pattern: /^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$/, message: '验证码格式不正确', trigger: 'blur'}
      ]
      : [],
  nickname: isRegister.value
      ? [{max: 32, message: '昵称最长 32 位', trigger: 'blur'}]
      : []
}))

function toggleMode(toRegister) {
  isRegister.value = toRegister
  showPassword.value = false
  if (toRegister) {
    form.captchaCode = ''
  }
  formRef.value && formRef.value.clearValidate()
}

async function refreshCaptcha() {
  try {
    const res = await getCaptcha({_t: Date.now()})
    captchaId.value = res.data.captchaId
    captchaSvg.value = res.data.svg
    form.captchaCode = ''
  } catch {
    captchaId.value = ''
    captchaSvg.value = ''
  }
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
    themeColor: data.themeColor || '#2563EB'
  }))
  // 应用主题颜色到全局 CSS 变量
  applyThemeColor(data.themeColor || '#2563EB')
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
    if (isRegister.value) {
      payload.inviteCode = form.inviteCode.trim().toUpperCase()
    } else if (showCaptcha.value) {
      payload.captchaId = captchaId.value
      payload.captchaCode = form.captchaCode.trim().toUpperCase()
    }

    const res = isRegister.value
        ? await register(payload)
        : await login(payload)
    const data = res.data

    failCount.value = 0
    showCaptcha.value = false
    captchaId.value = ''
    captchaSvg.value = ''
    persistLogin(data)
    ElMessage.success(`${isRegister.value ? '注册成功，' : ''}欢迎，${data.nickname}`)
    router.push('/')
  } catch (error) {
    // 校验失败或接口失败（接口错误由拦截器统一提示）
    if (!isRegister.value) {
      const backendFailCount = Number(error?.data?.failCount || 0)
      if (error?.code === 4001 || error?.data?.needCaptcha) {
        failCount.value = Math.max(failCount.value, backendFailCount, 5)
        showCaptcha.value = true
        await refreshCaptcha()
      } else if (error?.message?.includes('账号或密码错误')) {
        failCount.value += 1
        if (failCount.value >= 5) {
          showCaptcha.value = true
          await refreshCaptcha()
        }
      }
    }
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

/* 背景：纯 CSS，深板岩底色 + 一层细网格，无渐变、无光源装饰 */
.login-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background-color: var(--sidebar-bg);
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.03) 1px, transparent 1px);
  background-size: 32px 32px;
}

.login-card {
  width: 360px;
  background: var(--bg-white);
  border-radius: var(--radius-xl);
  padding: 40px 36px;
  position: relative;
  z-index: 1;
  border: 1px solid var(--border-light);
}

.logo-area {
  text-align: center;
  margin-bottom: 36px;
}

/* 品牌标识：实色方块 + 字母，跟随主题色 */
.logo-box {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-xl);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 16px;
  background: var(--accent-color);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.6px;
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

.captcha-row {
  width: 100%;
  display: flex;
  align-items: stretch;
  gap: 10px;
}

.captcha-row .el-input {
  min-width: 0;
  flex: 1;
}

.captcha-image {
  width: 120px;
  height: 42px;
  padding: 0;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  background: var(--bg-light);
  overflow: hidden;
  cursor: pointer;
  flex-shrink: 0;
}

.captcha-image :deep(svg) {
  display: block;
  width: 120px;
  height: 42px;
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

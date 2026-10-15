<template>
  <section class="profile-page">
    <div class="page-heading">
      <div>
        <h1>个人中心</h1>
        <p class="subtitle">管理个人资料、登录密码和可用邀请码。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新信息</el-button>
    </div>

    <div class="profile-grid">
      <el-card class="profile-card">
        <template #header>
          <div class="card-header">
            <span>账号信息</span>
            <el-tag :type="userInfo.status === 2 ? 'danger' : 'success'" size="small">
              {{ userInfo.status === 2 ? '已禁用' : '正常' }}
            </el-tag>
          </div>
        </template>
        <div class="account-summary">
          <el-avatar :size="64" class="profile-avatar">{{ userInitial }}</el-avatar>
          <div>
            <strong>{{ userInfo.nickname || userInfo.username || '-' }}</strong>
            <span>{{ userInfo.username || '-' }}</span>
          </div>
        </div>
        <dl class="info-list">
          <div><dt>用户 ID</dt><dd>{{ userInfo.id || '-' }}</dd></div>
          <div><dt>角色</dt><dd>{{ userInfo.role === 1 ? '管理员' : '普通用户' }}</dd></div>
          <div><dt>注册时间</dt><dd>{{ formatDate(userInfo.createdAt) }}</dd></div>
          <div><dt>最近登录</dt><dd>{{ formatDate(userInfo.lastLoginAt) }}</dd></div>
          <div><dt>最近登录 IP</dt><dd>{{ userInfo.lastLoginIp || '-' }}</dd></div>
        </dl>
      </el-card>

      <el-card class="profile-card">
        <template #header><span>个人资料</span></template>
        <el-form
          ref="profileFormRef"
          :model="profileForm"
          :rules="profileRules"
          label-width="82px"
          @submit.prevent
        >
          <el-form-item label="名称" prop="nickname">
            <el-input v-model="profileForm.nickname" maxlength="32" show-word-limit />
          </el-form-item>
          <el-form-item label="主题色">
            <div class="theme-field">
              <el-color-picker v-model="profileForm.themeColor" />
              <span>{{ profileForm.themeColor || '默认蓝色' }}</span>
            </div>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="profileSaving" @click="saveProfile">保存资料</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>

    <div class="profile-grid lower-grid">
      <el-card class="profile-card">
        <template #header><span>修改密码</span></template>
        <el-form
          ref="passwordFormRef"
          :model="passwordForm"
          :rules="passwordRules"
          label-width="96px"
          @submit.prevent
        >
          <el-form-item label="旧密码" prop="oldPassword">
            <el-input v-model="passwordForm.oldPassword" type="password" show-password autocomplete="current-password" />
          </el-form-item>
          <el-form-item label="新密码" prop="newPassword">
            <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" />
          </el-form-item>
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="passwordSaving" @click="changePassword">修改密码</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card class="profile-card invite-card">
        <template #header>
          <div class="card-header">
            <span>我的邀请码</span>
            <el-button
              type="primary"
              size="small"
              :icon="Plus"
              :loading="inviteCreating"
              :disabled="activeInviteCount >= 5"
              @click="createCode"
            >
              生成邀请码
            </el-button>
          </div>
        </template>
        <p class="invite-hint">未使用邀请码最多保留 5 个，邀请码仅可使用一次。</p>
        <el-table v-loading="inviteLoading" :data="inviteCodes" size="small" empty-text="暂无邀请码">
          <el-table-column prop="code" label="邀请码" min-width="120">
            <template #default="{ row }"><code>{{ row.code }}</code></template>
          </el-table-column>
          <el-table-column label="状态" width="84">
            <template #default="{ row }">
              <el-tag :type="inviteStatusType(row.status)" size="small">{{ inviteStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="有效期" min-width="150">
            <template #default="{ row }">{{ formatDate(row.expireAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="172" fixed="right">
            <template #default="{ row }">
              <div class="invite-actions">
                <el-button link type="primary" :icon="CopyDocument" :disabled="row.status !== 0" @click="copyCode(row.code)">复制</el-button>
                <el-button link type="danger" :icon="Delete" :disabled="row.status !== 0" @click="revokeCode(row)">作废</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Delete, Plus, Refresh } from '@element-plus/icons-vue'
import {
  createInviteCode,
  getUserInfo,
  listInviteCodes,
  revokeInviteCode,
  updatePassword,
  updateProfile
} from '@/api'
import router from '@/router'

const loading = ref(false)
const profileSaving = ref(false)
const passwordSaving = ref(false)
const inviteLoading = ref(false)
const inviteCreating = ref(false)
const profileFormRef = ref()
const passwordFormRef = ref()
const userInfo = reactive({})
const profileForm = reactive({ nickname: '', themeColor: '#2563EB' })
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const inviteCodes = ref([])

const userInitial = computed(() => (userInfo.nickname || userInfo.username || '?').charAt(0).toUpperCase())
const activeInviteCount = computed(() => inviteCodes.value.filter(item => item.status === 0).length)

const profileRules = {
  nickname: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { min: 1, max: 32, message: '名称长度应为 1-32 个字符', trigger: 'blur' }
  ]
}
const passwordRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度应为 6-32 个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => value === passwordForm.newPassword
        ? callback()
        : callback(new Error('两次输入的新密码不一致')),
      trigger: 'blur'
    }
  ]
}

async function loadInviteCodes() {
  inviteLoading.value = true
  try {
    const res = await listInviteCodes()
    inviteCodes.value = Array.isArray(res.data) ? res.data : []
  } finally {
    inviteLoading.value = false
  }
}

async function loadAll() {
  loading.value = true
  try {
    const [userRes] = await Promise.all([getUserInfo(), loadInviteCodes()])
    Object.assign(userInfo, userRes.data || {})
    profileForm.nickname = userInfo.nickname || ''
    profileForm.themeColor = userInfo.themeColor || '#2563EB'
    localStorage.setItem('userInfo', JSON.stringify({
      ...JSON.parse(localStorage.getItem('userInfo') || '{}'),
      userId: userInfo.id,
      nickname: userInfo.nickname,
      avatar: userInfo.avatar,
      role: userInfo.role,
      themeColor: userInfo.themeColor
    }))
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  if (!(await profileFormRef.value?.validate().catch(() => false))) return
  profileSaving.value = true
  try {
    await updateProfile(profileForm)
    Object.assign(userInfo, profileForm)
    const stored = JSON.parse(localStorage.getItem('userInfo') || '{}')
    localStorage.setItem('userInfo', JSON.stringify({ ...stored, nickname: profileForm.nickname, themeColor: profileForm.themeColor }))
    applyThemeColor(profileForm.themeColor)
    window.dispatchEvent(new Event('user-info-updated'))
    ElMessage.success('个人资料已保存')
  } finally {
    profileSaving.value = false
  }
}

async function changePassword() {
  if (!(await passwordFormRef.value?.validate().catch(() => false))) return
  passwordSaving.value = true
  try {
    await updatePassword({ ...passwordForm, refreshToken: localStorage.getItem('refreshToken') })
    clearAuthData()
    ElMessage.success('密码修改成功，请重新登录')
    await router.push('/login')
  } finally {
    passwordSaving.value = false
  }
}

async function createCode() {
  if (activeInviteCount.value >= 5) return ElMessage.warning('未使用的邀请码已达到上限')
  inviteCreating.value = true
  try {
    const res = await createInviteCode()
    await loadInviteCodes()
    if (res.data?.code) await copyCode(res.data.code)
    ElMessage.success('邀请码已生成')
  } finally {
    inviteCreating.value = false
  }
}

async function revokeCode(row) {
  try {
    await ElMessageBox.confirm('作废后该邀请码将不能继续使用，是否继续？', '作废邀请码', { type: 'warning' })
    await revokeInviteCode(row.id)
    await loadInviteCodes()
    ElMessage.success('邀请码已作废')
  } catch {
    // 取消确认时不提示
  }
}

async function copyCode(code) {
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(code)
    else {
      const input = document.createElement('textarea')
      input.value = code
      input.style.position = 'fixed'
      input.style.opacity = '0'
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      input.remove()
    }
    ElMessage.success('邀请码已复制')
  } catch {
    ElMessage.warning('复制失败，请手动复制邀请码')
  }
}

function clearAuthData() {
  ;['token', 'accessToken', 'refreshToken', 'userInfo'].forEach(key => localStorage.removeItem(key))
}

function applyThemeColor(color) {
  if (!color) return
  document.documentElement.style.setProperty('--accent-color', color)
  document.documentElement.style.setProperty('--sidebar-active-bg', color)
}

function formatDate(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}

function inviteStatusText(status) {
  return ({ 0: '未使用', 1: '已使用', 2: '已作废', 3: '已过期' })[status] || '未知'
}

function inviteStatusType(status) {
  return ({ 0: 'success', 1: 'info', 2: 'danger', 3: 'warning' })[status] || 'info'
}

onMounted(loadAll)
</script>

<style scoped>
.profile-page { height: 100%; overflow: auto; padding-bottom: 20px; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; margin-bottom: 16px; }
h1 { margin: 0; color: var(--text-primary); font-size: 18px; font-weight: 600; }
.subtitle { margin: 4px 0 0; color: var(--text-secondary); font-size: 13px; }
.profile-grid { display: grid; grid-template-columns: minmax(280px, .8fr) minmax(360px, 1.2fr); gap: 12px; margin-bottom: 12px; }
.lower-grid { grid-template-columns: minmax(280px, .8fr) minmax(480px, 1.2fr); }
.profile-card { min-width: 0; }
.card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.account-summary { display: flex; align-items: center; gap: 14px; padding-bottom: 18px; border-bottom: 1px solid var(--border-light); }
.profile-avatar { background: var(--primary-color); color: #fff; font-weight: 600; }
.account-summary strong, .account-summary span { display: block; }
.account-summary strong { font-size: 18px; margin-bottom: 4px; }
.account-summary span, .invite-hint, .theme-field span { color: var(--text-secondary); font-size: 12px; }
.info-list { margin: 16px 0 0; }
.info-list div { display: flex; justify-content: space-between; gap: 16px; padding: 7px 0; }
.info-list dt { color: var(--text-muted); }
.info-list dd { margin: 0; text-align: right; color: var(--text-primary); word-break: break-all; }
.theme-field { display: flex; align-items: center; gap: 10px; }
.invite-hint { margin: 0 0 10px; }
.invite-card :deep(.el-card__body) { padding-top: 12px; }
.invite-card :deep(.el-table) { width: 100%; }
.invite-actions { display: flex; align-items: center; gap: 4px; flex-wrap: nowrap; white-space: nowrap; }
.invite-actions :deep(.el-button + .el-button) { margin-left: 0; }
code { color: var(--primary-dark); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
@media (max-width: 900px) {
  .profile-grid, .lower-grid { grid-template-columns: 1fr; }
}
@media (max-width: 620px) {
  .page-heading { flex-direction: column; align-items: stretch; }
  .page-heading .el-button { align-self: flex-start; }
}
</style>

<template>
  <a href="#main-content" class="skip-link">跳转到主要内容</a>
  <el-container class="layout-container">
    <div v-if="isMobile && mobileDrawerOpen" class="mobile-overlay" @click="mobileDrawerOpen = false"></div>

    <el-aside :width="sidebarWidth" class="sidebar" :class="{ 'mobile-drawer': isMobile && mobileDrawerOpen }" aria-label="主导航菜单">
      <div class="logo" :class="{ collapsed: isCollapsed }">
        <div class="logo-icon">BL</div>
        <transition name="fade">
          <div v-if="!isCollapsed" class="logo-text">
            <h2>BizLens AI</h2>
            <p>代码知识库问答</p>
          </div>
        </transition>
      </div>

      <el-scrollbar class="nav-scrollbar">
        <el-menu
          :default-openeds="['chat']"
          :default-active="$route.meta.activeMenu || $route.path"
          router
          :collapse="isCollapsed"
          :collapse-transition="false"
          class="sidebar-menu"
          background-color="transparent"
          text-color="#cbd5e1"
          active-text-color="#ffffff"
          aria-label="站点导航"
        >
          <el-menu-item index="/dashboard">
            <el-icon><HomeFilled /></el-icon>
            <span>工作台</span>
          </el-menu-item>

          <el-menu-item index="/ai">
            <el-icon><ChatDotRound /></el-icon>
            <span>业务问答</span>
          </el-menu-item>

          <el-menu-item index="/ai/agents">
            <el-icon><Cpu /></el-icon>
            <span>AI 角色</span>
          </el-menu-item>

          <el-menu-item index="/projects">
            <el-icon><FolderOpened /></el-icon>
            <span>代码项目</span>
          </el-menu-item>

          <el-menu-item index="/knowledge">
            <el-icon><Collection /></el-icon>
            <span>个人知识库</span>
          </el-menu-item>

          <el-menu-item index="/profile">
            <el-icon><User /></el-icon>
            <span>个人中心</span>
          </el-menu-item>

        </el-menu>
      </el-scrollbar>

      <div class="sidebar-footer" v-if="!isCollapsed">
        <span>&copy; 2026 BizLens AI</span>
      </div>
    </el-aside>

    <el-container class="main-container">
      <el-header class="header" height="60px">
        <div class="header-left">
          <div
            class="collapse-btn"
            role="button"
            tabindex="0"
            :aria-label="isMobile ? '打开导航菜单' : (isCollapsed ? '展开侧边栏' : '折叠侧边栏')"
            @click="isMobile ? (mobileDrawerOpen = !mobileDrawerOpen) : toggleCollapse()"
            @keydown.enter="handleCollapseKey"
            @keydown.space.prevent="handleCollapseKey"
          >
            <el-icon :size="20">
              <Fold v-if="!isCollapsed" />
              <Expand v-else />
            </el-icon>
          </div>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="currentMenu">{{ currentMenu }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-dropdown>
            <span class="user-dropdown" tabindex="0">
              <el-avatar :size="32" class="user-avatar">
                {{ userInitial }}
              </el-avatar>
              <span class="user-name">{{ userName }}</span>
              <el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/profile')">
                  <el-icon><User /></el-icon>
                  个人中心
                </el-dropdown-item>
                <el-dropdown-item @click="openInviteDialog">
                  <el-icon><Tickets /></el-icon>
                  我的邀请码
                </el-dropdown-item>
                <el-dropdown-item divided :disabled="isLoggingOut" @click="handleLogout">
                  <el-icon v-if="isLoggingOut" class="is-loading"><Loading /></el-icon>
                  {{ isLoggingOut ? '退出中...' : '退出登录' }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main-content" id="main-content">
        <router-view v-slot="{ Component, route: r }">
          <component :is="Component" :key="r.fullPath" />
        </router-view>
      </el-main>
    </el-container>

    <el-dialog
      v-model="inviteDialogVisible"
      title="我的邀请码"
      width="680px"
      destroy-on-close
    >
      <div class="invite-toolbar">
        <span class="invite-hint">未使用邀请码最多保留 5 个</span>
        <el-button
          type="primary"
          :loading="inviteCreating"
          :disabled="activeInviteCount >= 5"
          @click="handleCreateInvite"
        >
          <el-icon><Plus /></el-icon>
          生成邀请码
        </el-button>
      </div>

      <el-table
        v-loading="inviteLoading"
        :data="inviteCodes"
        size="small"
        empty-text="暂无邀请码"
      >
        <el-table-column prop="code" label="邀请码" min-width="130">
          <template #default="{ row }">
            <code>{{ row.code }}</code>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="inviteStatusType(row.status)" size="small">
              {{ inviteStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="过期时间" min-width="165">
          <template #default="{ row }">{{ formatDate(row.expireAt) }}</template>
        </el-table-column>
        <el-table-column label="使用者" min-width="120">
          <template #default="{ row }">{{ row.usedByUsername || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              text
              type="primary"
              :disabled="row.status !== 0"
              @click="copyInviteCode(row.code)"
            >
              <el-icon><CopyDocument /></el-icon>
              复制
            </el-button>
            <el-button
              text
              type="danger"
              :disabled="row.status !== 0"
              @click="handleRevokeInvite(row)"
            >
              <el-icon><Delete /></el-icon>
              作废
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </el-container>
</template>

<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {
  ArrowDown,
  ChatDotRound,
  Collection,
  Cpu,
  Expand,
  Fold,
  FolderOpened,
  HomeFilled,
  Loading,
  Tickets,
  User,
  Plus,
  CopyDocument,
  Delete
} from '@element-plus/icons-vue'
import {createInviteCode, listInviteCodes, logout, revokeInviteCode} from '@/api'

const router = useRouter()
const route = useRoute()
const isCollapsed = ref(false)
const isLoggingOut = ref(false)
const isMobile = ref(false)
const mobileDrawerOpen = ref(false)
const inviteDialogVisible = ref(false)
const inviteLoading = ref(false)
const inviteCreating = ref(false)
const inviteCodes = ref([])
const userInfoVersion = ref(0)

const refreshUserInfo = () => {
  userInfoVersion.value += 1
}

function checkMobile() {
  isMobile.value = window.innerWidth < 768
  if (!isMobile.value) {
    mobileDrawerOpen.value = false
  }
}

onMounted(() => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
  window.addEventListener('user-info-updated', refreshUserInfo)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', checkMobile)
  window.removeEventListener('user-info-updated', refreshUserInfo)
})

const sidebarWidth = computed(() => {
  if (isMobile.value) {
    return mobileDrawerOpen.value ? '240px' : '0px'
  }
  return isCollapsed.value ? '64px' : '240px'
})

const userName = computed(() => {
  userInfoVersion.value
  try {
    const userInfo = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return userInfo.nickname || '未登录'
  } catch {
    return '未登录'
  }
})

const userInitial = computed(() => {
  return userName.value.charAt(0).toUpperCase()
})

const currentMenu = computed(() => {
  const path = route.path
  const pathMap = {
    '/dashboard': '工作台',
    '/ai': '业务问答',
    '/ai/agents': 'AI 角色',
    '/projects': '代码项目',
    '/knowledge': '个人知识库',
    '/profile': '个人中心',
  }
  return pathMap[path] || ''
})

const toggleCollapse = () => {
  isCollapsed.value = !isCollapsed.value
}

const handleCollapseKey = () => {
  if (isMobile.value) {
    mobileDrawerOpen.value = !mobileDrawerOpen.value
  } else {
    toggleCollapse()
  }
}

const activeInviteCount = computed(() => {
  return inviteCodes.value.filter(item => item.status === 0).length
})

const openInviteDialog = async () => {
  inviteDialogVisible.value = true
  await loadInviteCodes()
}

const loadInviteCodes = async () => {
  inviteLoading.value = true
  try {
    const res = await listInviteCodes()
    inviteCodes.value = Array.isArray(res.data) ? res.data : []
  } finally {
    inviteLoading.value = false
  }
}

const handleCreateInvite = async () => {
  if (activeInviteCount.value >= 5) {
    ElMessage.warning('未使用的邀请码已达到上限')
    return
  }
  inviteCreating.value = true
  try {
    const res = await createInviteCode()
    const code = res.data?.code
    if (code) {
      await loadInviteCodes()
      await copyInviteCode(code)
      ElMessage.success(`邀请码 ${code} 已生成并复制`)
    }
  } finally {
    inviteCreating.value = false
  }
}

const handleRevokeInvite = async (row) => {
  try {
    await revokeInviteCode(row.id)
    await loadInviteCodes()
    ElMessage.success('邀请码已作废')
  } catch {
    // 请求拦截器负责显示服务端错误
  }
}

const copyInviteCode = async (code) => {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(code)
    } else {
      const input = document.createElement('textarea')
      input.value = code
      input.style.position = 'fixed'
      input.style.opacity = '0'
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      input.remove()
    }
  } catch {
    ElMessage.warning('复制失败，请手动复制邀请码')
  }
}

const inviteStatusText = status => {
  return {0: '未使用', 1: '已使用', 2: '已作废', 3: '已过期'}[status] || '未知'
}

const inviteStatusType = status => {
  return {0: 'success', 1: 'info', 2: 'danger', 3: 'warning'}[status] || 'info'
}

const formatDate = value => {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}

const handleLogout = async () => {
  isLoggingOut.value = true
  const accessToken = localStorage.getItem('accessToken')
  const refreshToken = localStorage.getItem('refreshToken')
  try {
    await logout(accessToken, refreshToken)
  } catch {
    // 即使接口调用失败也要清除本地状态
  } finally {
    localStorage.removeItem('token')
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('userInfo')
    ElMessage.success('退出登录成功')
    router.push('/login')
  }
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
  overflow: hidden;
}

/* ============ Skip-to-content link ============ */
.skip-link {
  position: absolute;
  top: -100px;
  left: 50%;
  transform: translateX(-50%);
  z-index: var(--z-skip-link);
  padding: 12px 24px;
  background: var(--primary-color);
  color: #fff;
  font-weight: 600;
  border-radius: var(--radius-md);
  text-decoration: none;
  transition: top var(--transition-fast);
}

.skip-link:focus {
  top: 12px;
}

/* ============ 侧边栏 ============ */
.sidebar {
  background: var(--sidebar-bg);
  color: var(--sidebar-text);
  display: flex;
  flex-direction: column;
  transition: width var(--transition-normal);
  overflow: hidden;
  position: relative;
  border-right: 1px solid var(--sidebar-border);
}

.sidebar.mobile-drawer {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  z-index: var(--z-drawer);
  box-shadow: var(--shadow-xl);
}

/* Logo区域：实色方块，无渐变 */
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  background: transparent;
  border-bottom: 1px solid var(--sidebar-border);
  gap: 10px;
  flex-shrink: 0;
}

.logo.collapsed {
  justify-content: center;
  padding: 0;
}

.logo-icon {
  width: 28px;
  height: 28px;
  background: var(--accent-color);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.6px;
  color: #fff;
  flex-shrink: 0;
}

.logo-text {
  overflow: hidden;
  white-space: nowrap;
}

.logo-text h2 {
  font-size: 14px;
  font-weight: 600;
  color: #F8FAFC;
  margin: 0;
  line-height: 1.3;
}

.logo-text p {
  font-size: 11px;
  color: var(--sidebar-text-muted);
  margin: 0;
}

/* 导航滚动条 */
.nav-scrollbar {
  flex: 1;
}

.nav-scrollbar :deep(.el-scrollbar__bar.is-vertical) {
  width: 4px;
}

.nav-scrollbar :deep(.el-scrollbar__thumb) {
  background-color: rgba(255, 255, 255, 0.16);
  border-radius: 2px;
}

/* ============ 菜单：扁平列表，选中用左侧竖条 ============ */
.sidebar-menu {
  border-right: none !important;
  padding: 8px 0;
}

.sidebar-menu :deep(.el-menu) {
  background-color: transparent !important;
}

.sidebar-menu :deep(.el-menu-item) {
  position: relative;
  background-color: transparent !important;
  color: var(--sidebar-text) !important;
  height: 38px;
  line-height: 38px;
  font-size: 13px;
  margin: 0 !important;
  border-radius: 0;
  transition: background-color var(--transition-fast), color var(--transition-fast);
}

.sidebar-menu :deep(.el-menu-item:hover) {
  background-color: var(--sidebar-hover-bg) !important;
  color: #F8FAFC !important;
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  background-color: rgba(255, 255, 255, 0.07) !important;
  color: #FFFFFF !important;
  margin: 0 !important;
  border-radius: 0;
}

/* 选中态左侧 2px 竖条：用伪元素，避免与折叠态的 padding 打架 */
.sidebar-menu :deep(.el-menu-item.is-active)::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--sidebar-active-bg);
}

.sidebar-menu :deep(.el-menu-item .el-icon) {
  font-size: 15px;
  margin-right: 8px;
  color: inherit;
}

/* 分组标题 */
.sidebar-menu :deep(.el-menu-item-group__title) {
  color: rgba(255, 255, 255, 0.35) !important;
  font-size: 11px;
  padding: 12px 16px 4px;
  letter-spacing: 1px;
  background-color: transparent !important;
}

/* 侧边栏底部 */
.sidebar-footer {
  padding: 10px 16px;
  background: transparent;
  border-top: 1px solid var(--sidebar-border);
  font-size: 11px;
  text-align: center;
  color: var(--sidebar-text-muted);
  flex-shrink: 0;
}

/* ============ 主内容区 ============ */
.main-container {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ============ 顶栏 ============ */
.header {
  background: var(--bg-white);
  border-bottom: 1px solid var(--border-light);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  z-index: var(--z-sticky);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.collapse-btn {
  width: 40px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-md);
  cursor: pointer;
  color: var(--text-secondary);
  transition: background-color var(--transition-fast), color var(--transition-fast);
}

.collapse-btn:hover {
  background: var(--bg-light);
  color: var(--primary-color);
}

.collapse-btn:focus-visible {
  outline: 2px solid var(--focus-ring-color);
  outline-offset: 2px;
  background: var(--bg-light);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

/* 面包屑 */
.header :deep(.el-breadcrumb) {
  font-size: 14px;
}

.header :deep(.el-breadcrumb__inner) {
  color: var(--text-secondary);
}

/* 用户下拉 */
.user-dropdown {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-md);
  transition: background var(--transition-fast);
}

.user-dropdown:hover {
  background: var(--bg-light);
}

.user-dropdown:focus-visible {
  outline: 2px solid var(--focus-ring-color);
  outline-offset: 2px;
  background: var(--bg-light);
}

.user-avatar {
  background: var(--accent-color) !important;
  font-size: 13px;
  font-weight: 600;
}

.user-name {
  font-size: 14px;
  color: var(--text-primary);
  font-weight: 500;
}

.invite-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.invite-hint {
  color: var(--text-secondary);
  font-size: 13px;
}

.invite-toolbar :deep(.el-button .el-icon) {
  margin-right: 4px;
}

.invite-toolbar + :deep(.el-table) code {
  color: var(--text-primary);
  font-size: 13px;
  letter-spacing: 0.06em;
}

/* ============ 主内容 ============ */
.main-content {
  background: var(--bg-color);
  padding: 20px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* ============ 路由切换动画 ============ */
.page-fade-enter-active {
  transition: all var(--transition-normal);
}

.page-fade-leave-active {
  transition: all var(--transition-fast);
}

.page-fade-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.page-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

.fade-enter-active {
  transition: opacity var(--transition-fast), transform var(--transition-fast);
}

.fade-leave-active {
  transition: opacity var(--transition-fast);
}

.fade-enter-from {
  opacity: 0;
  transform: translateX(-4px);
}

.fade-leave-to {
  opacity: 0;
}

/* ============ 移动端遮罩层 ============ */
.mobile-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: var(--z-overlay);
  backdrop-filter: blur(2px);
}

/* ============ 响应式布局 ============ */
@media (max-width: 767px) {
  .sidebar-footer {
    display: none;
  }

  .header {
    padding: 0 12px;
  }

  .user-name {
    display: none;
  }

  .main-content {
    padding: 12px;
  }

  .invite-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
}

/* ============ 减少动画偏好 ============ */
@media (prefers-reduced-motion: reduce) {
  .sidebar {
    transition: none;
  }
  .collapse-btn {
    transition: none;
  }
  .user-dropdown {
    transition: none;
  }
  .page-fade-enter-active,
  .page-fade-leave-active {
    transition: none;
  }
  .fade-enter-active,
  .fade-leave-active {
    transition: none;
  }
  .skip-link {
    transition: none;
  }
}
</style>

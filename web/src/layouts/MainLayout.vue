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
          <el-menu-item index="/ai">
            <el-icon><MagicStick /></el-icon>
            <span>AI 对话</span>
          </el-menu-item>

          <el-menu-item index="/dashboard">
            <el-icon><HomeFilled /></el-icon>
            <span>工作台</span>
          </el-menu-item>

          <el-menu-item index="/ai/agents">
            <el-icon><Cpu /></el-icon>
            <span>AI 角色</span>
          </el-menu-item>

          <el-menu-item index="/projects">
            <el-icon><FolderOpened /></el-icon>
            <span>代码项目</span>
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
  </el-container>
</template>

<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {
  ArrowDown,
  Cpu,
  Expand,
  Fold,
  FolderOpened,
  HomeFilled,
  Loading,
  MagicStick
} from '@element-plus/icons-vue'
import {logout} from '@/api'

const router = useRouter()
const route = useRoute()
const isCollapsed = ref(false)
const isLoggingOut = ref(false)
const isMobile = ref(false)
const mobileDrawerOpen = ref(false)

function checkMobile() {
  isMobile.value = window.innerWidth < 768
  if (!isMobile.value) {
    mobileDrawerOpen.value = false
  }
}

onMounted(() => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', checkMobile)
})

const sidebarWidth = computed(() => {
  if (isMobile.value) {
    return mobileDrawerOpen.value ? '240px' : '0px'
  }
  return isCollapsed.value ? '64px' : '240px'
})

const userName = computed(() => {
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
    '/ai': 'AI 对话',
    '/ai/agents': 'AI 角色',
    '/projects': '代码项目',
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
}

.sidebar.mobile-drawer {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  z-index: var(--z-drawer);
  box-shadow: var(--shadow-xl);
}

/* Logo区域 */
.logo {
  height: 64px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  background: var(--sidebar-header-bg);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  gap: 12px;
  flex-shrink: 0;
}

.logo.collapsed {
  justify-content: center;
  padding: 0;
}

.logo-icon {
  width: 36px;
  height: 36px;
  background: linear-gradient(135deg, var(--accent-color), var(--accent-dark));
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: #fff;
  flex-shrink: 0;
}

.logo-text {
  overflow: hidden;
  white-space: nowrap;
}

.logo-text h2 {
  font-size: 16px;
  font-weight: 700;
  color: #ecf0f1;
  margin: 0;
  line-height: 1.3;
}

.logo-text p {
  font-size: 12px;
  color: #94a3b8;
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
  background-color: rgba(255, 255, 255, 0.2);
  border-radius: 2px;
}

/* ============ 菜单样式 - 深色侧边栏适配 ============ */
.sidebar-menu {
  border-right: none !important;
}

/* 菜单整体背景透明 */
.sidebar-menu :deep(.el-menu) {
  background-color: transparent !important;
}

/* 子菜单标题 */
.sidebar-menu :deep(.el-sub-menu__title) {
  background-color: transparent !important;
  color: #cbd5e1 !important;
  height: 48px;
  line-height: 48px;
  font-size: 14px;
}

.sidebar-menu :deep(.el-sub-menu__title:hover) {
  background-color: rgba(255, 255, 255, 0.08) !important;
  color: #ecf0f1 !important;
}

.sidebar-menu :deep(.el-sub-menu__title .el-icon) {
  font-size: 18px;
  color: #cbd5e1;
}

.sidebar-menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #ecf0f1 !important;
}

/* 菜单项 */
.sidebar-menu :deep(.el-menu-item) {
  background-color: transparent !important;
  color: #cbd5e1 !important;
  height: 44px;
  line-height: 44px;
  font-size: 13px;
  transition: all var(--transition-fast);
}

.sidebar-menu :deep(.el-menu-item:hover) {
  background-color: rgba(255, 255, 255, 0.08) !important;
  color: #ecf0f1 !important;
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  background-color: var(--sidebar-active-bg) !important;
  color: #fff !important;
  border-radius: 4px;
  margin: 2px 8px;
  padding-left: 20px !important;
}

.sidebar-menu :deep(.el-menu-item .el-icon) {
  font-size: 16px;
  margin-right: 8px;
  color: inherit;
}

/* 子菜单展开后的菜单项 */
.sidebar-menu :deep(.el-sub-menu .el-menu) {
  background-color: transparent !important;
}

.sidebar-menu :deep(.el-sub-menu .el-menu .el-menu-item) {
  background-color: transparent !important;
  min-width: auto !important;
  padding-left: 48px !important;
  height: 44px;
  line-height: 44px;
  font-size: 13px;
  color: rgba(203, 213, 225, 0.9) !important;
}

.sidebar-menu :deep(.el-sub-menu .el-menu .el-menu-item:hover) {
  background-color: rgba(255, 255, 255, 0.08) !important;
  color: #ecf0f1 !important;
}

.sidebar-menu :deep(.el-sub-menu .el-menu .el-menu-item.is-active) {
  background-color: var(--sidebar-active-bg) !important;
  color: #fff !important;
}

/* 分组标题 */
.sidebar-menu :deep(.el-menu-item-group__title) {
  color: rgba(255, 255, 255, 0.35) !important;
  font-size: 12px;
  padding: 12px 20px 4px;
  letter-spacing: 1px;
  background-color: transparent !important;
}

/* el-menu 弹出层（折叠时） */
.sidebar-menu :deep(.el-menu--collapse .el-sub-menu__title) {
  background-color: transparent !important;
}

/* 侧边栏底部 */
.sidebar-footer {
  padding: 12px 16px;
  background: var(--sidebar-footer-bg);
  font-size: 12px;
  text-align: center;
  color: #94a3b8;
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
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
  z-index: var(--z-sticky);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.collapse-btn {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  cursor: pointer;
  color: var(--text-secondary);
  transition: all var(--transition-fast);
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
  border-radius: 8px;
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
  background: linear-gradient(135deg, var(--accent-color), var(--accent-dark)) !important;
  font-size: 14px;
  font-weight: 600;
}

.user-name {
  font-size: 14px;
  color: var(--text-primary);
  font-weight: 500;
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

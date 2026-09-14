<template>
  <section class="dashboard-page">
    <div class="page-heading">
      <div>
        <h1>工作台</h1>
        <p class="subtitle">按「登记项目 → 索引 → 创建角色 → 业务问答」的顺序接入代码知识库。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadHealth">刷新状态</el-button>
    </div>

    <div class="status-grid">
      <article class="status-card">
        <span class="card-label">服务状态</span>
        <strong :class="statusClass(health.status)">{{ statusText(health.status) }}</strong>
        <span class="card-meta">{{ health.service || 'bizlens-ai' }}</span>
      </article>
      <article class="status-card">
        <span class="card-label">PostgreSQL</span>
        <strong :class="statusClass(health.database)">{{ statusText(health.database) }}</strong>
        <span class="card-meta">数据表脚本已准备</span>
      </article>
      <article class="status-card">
        <span class="card-label">安全策略</span>
        <strong class="status-up">已启用</strong>
        <span class="card-meta">输入检测与输出拦截</span>
      </article>
    </div>

    <div class="quick-grid">
      <button class="quick-card" type="button" @click="router.push('/projects')">
        <el-icon><FolderOpened /></el-icon>
        <span><b>登记代码项目</b><small>填写 Git 地址与凭据，排队索引</small></span>
        <el-icon class="arrow"><ArrowRight /></el-icon>
      </button>
      <button class="quick-card" type="button" @click="router.push('/ai/agents')">
        <el-icon><Cpu /></el-icon>
        <span><b>创建 AI 角色</b><small>每个项目一个角色，配置模型与 API Key</small></span>
        <el-icon class="arrow"><ArrowRight /></el-icon>
      </button>
      <button class="quick-card" type="button" @click="router.push('/ai')">
        <el-icon><ChatDotRound /></el-icon>
        <span><b>开始业务问答</b><small>解释代码背后的业务规则与处理流程</small></span>
        <el-icon class="arrow"><ArrowRight /></el-icon>
      </button>
    </div>

    <el-alert
      title="代码知识库尚处于基础阶段"
      description="当前已支持 Git 临时索引和轻量符号切片；下一阶段将接入完整 AST、方法级检索、向量化和 Code Graph。"
      type="info"
      :closable="false"
    />
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, ChatDotRound, Cpu, FolderOpened, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'

const router = useRouter()
const loading = ref(false)
const health = ref({ status: 'UNKNOWN', database: 'UNKNOWN', service: 'bizlens-ai' })

function statusText(value) {
  return value === 'UP' ? '正常' : value === 'DEGRADED' ? '部分可用' : value === 'DOWN' ? '不可用' : '未知'
}

function statusClass(value) {
  return value === 'UP' ? 'status-up' : value === 'DEGRADED' ? 'status-warn' : 'status-down'
}

async function loadHealth() {
  loading.value = true
  try {
    // 后端统一返回 R 信封，实际数据在 data 里
    const res = await request({ url: '/api/system/health', method: 'get' })
    health.value = res.data || {}
  } catch {
    health.value = { status: 'DOWN', database: 'UNKNOWN', service: 'bizlens-ai' }
    ElMessage.warning('暂时无法连接后端服务')
  } finally {
    loading.value = false
  }
}

onMounted(loadHealth)
</script>

<style scoped>
.dashboard-page { max-width: 1280px; margin: 0 auto; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; margin-bottom: 16px; }
h1 { margin: 0; color: var(--text-primary); font-size: 18px; font-weight: 600; }
.subtitle { margin: 4px 0 0; color: var(--text-secondary); font-size: 13px; }
.status-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 12px; }
.status-card { min-height: 96px; padding: 16px 18px; background: var(--bg-white); border: 1px solid var(--border-light); border-radius: var(--radius-md); display: flex; flex-direction: column; gap: 6px; }
.card-label { color: var(--text-muted); font-size: 12px; }
.status-card strong { font-size: 20px; font-weight: 600; }
.card-meta { color: var(--text-secondary); font-size: 12px; }
.status-up { color: var(--success-color); }.status-warn { color: var(--warning-color); }.status-down { color: var(--danger-color); }
.quick-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 12px; }
.quick-card { border: 1px solid var(--border-light); border-radius: var(--radius-md); background: var(--bg-white); padding: 16px 18px; display: flex; align-items: center; gap: 12px; text-align: left; cursor: pointer; color: var(--text-primary); transition: border-color var(--transition-fast); }
.quick-card:hover { border-color: var(--accent-color); }
.quick-card > .el-icon { color: var(--accent-color); font-size: 20px; flex-shrink: 0; }.quick-card span { flex: 1; display: flex; flex-direction: column; gap: 3px; }.quick-card b { font-size: 13px; font-weight: 600; }.quick-card small { color: var(--text-secondary); font-size: 12px; }.quick-card .arrow { color: var(--text-muted); font-size: 14px; }
@media (max-width: 960px) { .quick-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 720px) { .page-heading { flex-direction: column; }.status-grid, .quick-grid { grid-template-columns: 1fr; } }
</style>

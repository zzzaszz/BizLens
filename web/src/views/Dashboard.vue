<template>
  <section class="dashboard-page">
    <div class="page-heading">
      <div>
        <h1>工作台</h1>
        <p class="subtitle">按项目查看索引、角色与问答入口，适合同时维护多个代码知识库。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新状态</el-button>
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
        <span class="card-label">代码项目</span>
        <strong class="status-up">{{ projectStats.ready }} / {{ projectStats.total }}</strong>
        <span class="card-meta">已就绪 / 全部项目</span>
      </article>
    </div>

    <div class="project-toolbar">
      <el-select v-model="selectedProjectId" placeholder="选择项目" class="project-select">
        <el-option label="全部项目" value="all" />
        <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="String(p.id)" />
      </el-select>
      <div class="toolbar-actions">
        <el-button :icon="FolderOpened" @click="router.push('/projects')">代码项目</el-button>
        <el-button :icon="Cpu" @click="router.push('/ai/agents')">AI 角色</el-button>
        <el-button type="primary" :icon="ChatDotRound" @click="router.push('/ai')">业务问答</el-button>
      </div>
    </div>

    <div class="project-summary">
      <article class="summary-item">
        <span class="card-label">当前视角</span>
        <strong>{{ selectedProject ? selectedProject.name : '全部项目' }}</strong>
        <span class="card-meta">{{ visibleProjects.length }} 个项目纳入视图</span>
      </article>
      <article class="summary-item">
        <span class="card-label">AI 角色</span>
        <strong>{{ visibleAgentCount }}</strong>
        <span class="card-meta">关联到当前视图的角色数</span>
      </article>
      <article class="summary-item">
        <span class="card-label">索引数据</span>
        <strong>{{ visibleDocumentCount }} 文件</strong>
        <span class="card-meta">{{ visibleChunkCount }} 切片 / {{ visibleSymbolCount }} 符号</span>
      </article>
    </div>

    <el-table v-loading="loading" :data="visibleProjects" border empty-text="暂无代码项目" class="project-table">
      <el-table-column prop="name" label="项目" min-width="170" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="projectStatusType(row.status)" size="small">{{ projectStatusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="索引统计" min-width="190">
        <template #default="{ row }">
          {{ row.documentCount || 0 }} 文件 / {{ row.chunkCount || 0 }} 切片 / {{ row.symbolChunkCount || 0 }} 符号
        </template>
      </el-table-column>
      <el-table-column label="AI 角色" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <template v-if="agentsOf(row.id).length">
            <el-tag v-for="a in agentsOf(row.id)" :key="a.id" size="small" effect="plain" class="agent-tag">
              {{ a.name }}
            </el-tag>
          </template>
          <span v-else class="muted">未创建</span>
        </template>
      </el-table-column>
      <el-table-column label="下一步" width="220" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status !== 'READY'" link type="primary" @click="router.push('/projects')">去索引</el-button>
          <el-button v-else-if="!agentsOf(row.id).length" link type="primary" @click="createAgent(row)">创建角色</el-button>
          <el-button v-else link type="primary" @click="router.push('/ai')">开始问答</el-button>
          <el-button link @click="router.push('/projects')">管理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert
      title="代码知识库尚处于基础阶段"
      description="当前已支持 Git 临时索引和轻量符号切片；下一阶段将接入完整 AST、方法级检索、向量化和 Code Graph。"
      type="info"
      :closable="false"
    />
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Cpu, FolderOpened, Refresh } from '@element-plus/icons-vue'
import { aiAgentList, codeProjectList } from '@/api'
import request from '@/utils/request'

const router = useRouter()
const loading = ref(false)
const health = ref({ status: 'UNKNOWN', database: 'UNKNOWN', service: 'bizlens-ai' })
const projects = ref([])
const agents = ref([])
const selectedProjectId = ref('all')

const selectedProject = computed(() => {
  if (selectedProjectId.value === 'all') return null
  return projects.value.find(p => String(p.id) === String(selectedProjectId.value)) || null
})

const visibleProjects = computed(() => selectedProject.value ? [selectedProject.value] : projects.value)

const projectStats = computed(() => {
  const total = projects.value.length
  return {
    total,
    ready: projects.value.filter(p => p.status === 'READY').length,
    indexing: projects.value.filter(p => p.status === 'QUEUED' || p.status === 'INDEXING').length,
    failed: projects.value.filter(p => p.status === 'FAILED').length
  }
})

const visibleAgentCount = computed(() => {
  const ids = new Set(visibleProjects.value.map(p => String(p.id)))
  return agents.value.filter(a => (a.projectIds || []).some(id => ids.has(String(id)))).length
})

const visibleDocumentCount = computed(() => sumVisible('documentCount'))
const visibleChunkCount = computed(() => sumVisible('chunkCount'))
const visibleSymbolCount = computed(() => sumVisible('symbolChunkCount'))

function statusText(value) {
  return value === 'UP' ? '正常' : value === 'DEGRADED' ? '部分可用' : value === 'DOWN' ? '不可用' : '未知'
}

function statusClass(value) {
  return value === 'UP' ? 'status-up' : value === 'DEGRADED' ? 'status-warn' : 'status-down'
}

function sumVisible(key) {
  return visibleProjects.value.reduce((sum, p) => sum + Number(p[key] || 0), 0)
}

function projectStatusText(status) {
  return ({ PENDING: '待索引', QUEUED: '排队中', INDEXING: '索引中', READY: '已就绪', FAILED: '失败' })[status] || status || '未知'
}

function projectStatusType(status) {
  return ({ READY: 'success', FAILED: 'danger', INDEXING: 'warning', QUEUED: 'warning' })[status] || 'info'
}

function agentsOf(projectId) {
  return agents.value.filter(a => (a.projectIds || []).some(id => String(id) === String(projectId)))
}

function createAgent(project) {
  router.push({ path: '/ai/agents', query: { projectId: project.id } })
}

async function loadHealth() {
  try {
    // 后端统一返回 R 信封，实际数据在 data 里
    const res = await request({ url: '/api/system/health', method: 'get' })
    health.value = res.data || {}
  } catch {
    health.value = { status: 'DOWN', database: 'UNKNOWN', service: 'bizlens-ai' }
    ElMessage.warning('暂时无法连接后端服务')
  }
}

async function loadAll() {
  loading.value = true
  try {
    const [projectRes, agentRes] = await Promise.all([
      codeProjectList(), aiAgentList(), loadHealth()
    ])
    if (projectRes.code === 200) projects.value = projectRes.data || []
    if (agentRes.code === 200) agents.value = agentRes.data || []
    if (selectedProjectId.value !== 'all' && !projects.value.some(p => String(p.id) === String(selectedProjectId.value))) {
      selectedProjectId.value = 'all'
    }
  } catch {
    /* 请求拦截器会处理通用错误；健康检查有自己的降级状态 */
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.dashboard-page { height: 100%; display: flex; flex-direction: column; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; margin-bottom: 16px; }
h1 { margin: 0; color: var(--text-primary); font-size: 18px; font-weight: 600; }
.subtitle { margin: 4px 0 0; color: var(--text-secondary); font-size: 13px; }
.status-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 12px; }
.status-card { min-height: 96px; padding: 16px 18px; background: var(--bg-white); border: 1px solid var(--border-light); border-radius: var(--radius-md); display: flex; flex-direction: column; gap: 6px; }
.card-label { color: var(--text-muted); font-size: 12px; }
.status-card strong { font-size: 20px; font-weight: 600; }
.card-meta { color: var(--text-secondary); font-size: 12px; }
.status-up { color: var(--success-color); }.status-warn { color: var(--warning-color); }.status-down { color: var(--danger-color); }
.project-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.project-select { width: 280px; max-width: 100%; }
.toolbar-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.project-summary { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 12px; }
.summary-item { min-height: 84px; padding: 14px 16px; background: var(--bg-white); border: 1px solid var(--border-light); border-radius: var(--radius-md); display: flex; flex-direction: column; gap: 5px; }
.summary-item strong { font-size: 18px; font-weight: 600; color: var(--text-primary); }
.project-table { flex: 1; min-height: 0; }
.agent-tag { margin: 2px 4px 2px 0; }
.muted { color: var(--text-muted); }
@media (max-width: 960px) { .project-summary { grid-template-columns: 1fr; } }
@media (max-width: 720px) { .page-heading, .project-toolbar { flex-direction: column; align-items: stretch; }.status-grid { grid-template-columns: 1fr; } }
</style>

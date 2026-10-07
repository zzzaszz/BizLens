<template>
  <div class="projects-page">
    <div class="page-head">
      <div>
        <h2>代码项目</h2>
        <p>登记 Git 项目元数据；项目完成索引后，可为它创建多个 AI 角色</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增项目</el-button>
    </div>

    <el-table v-loading="loading" :data="projects" border empty-text="暂无代码项目">
      <el-table-column prop="name" label="项目名称" min-width="180" />
      <el-table-column prop="gitUrl" label="Git 地址" min-width="260" show-overflow-tooltip />
      <el-table-column prop="gitBranch" label="分支" width="120" />
      <el-table-column label="索引统计" width="200">
        <template #default="{ row }">
          {{ row.documentCount || 0 }} 文件 / {{ row.chunkCount || 0 }} 切片 / {{ row.symbolChunkCount || 0 }} 符号
        </template>
      </el-table-column>
      <!-- 这一列只反映"字段里填没填"：不要据此推断仓库是公开还是私有，
           也不要说"没填就没凭据"——clone 的凭据来源既不是这个字段，也不是机器，而是暂时没有 -->
      <el-table-column label="Git 凭据" width="130">
        <template #default="{ row }">
          <el-tooltip placement="top" :show-after="100">
            <template #content>
              <div class="err-tip">
                该字段不参与克隆。为免借用服务账号身份读取私有代码，克隆已<b>停用运行机器的 Git 凭据</b>
                （全局配置、~/.git-credentials、SSH key 一律不继承），而按项目的凭据注入尚未实现，
                因此当前只能索引可匿名访问的仓库。
              </div>
            </template>
            <el-tag v-if="row.credentialRef" type="info" effect="plain" size="small">已填写（未生效）</el-tag>
            <el-tag v-else type="info" effect="plain" size="small">未填写</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="240">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          <!-- 索引失败要能看到原因，否则只显示一个「失败」标签没法排查 -->
          <el-tooltip v-if="row.status === 'FAILED' && row.lastError" placement="top" :show-after="100">
            <template #content>
              <div class="err-tip">{{ row.lastError }}</div>
            </template>
            <p class="err-line">{{ errorHint(row) || row.lastError }}</p>
          </el-tooltip>
        </template>
      </el-table-column>
      <!-- 一个项目可以有多个角色，这里把它们都列出来 -->
      <el-table-column label="AI 角色" min-width="170">
        <template #default="{ row }">
          <template v-if="agentsOf(row.id).length">
            <el-tag v-for="a in agentsOf(row.id)" :key="a.id" size="small" effect="plain" class="agent-tag">
              {{ a.name }}
            </el-tag>
          </template>
          <span v-else class="agent-none">未创建</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <!-- 索引进行中禁止再点（loading 只覆盖请求过程，排完队按钮就恢复可点，容易连点）。
               但判定要带时间窗：进程被杀会让状态永远停在 INDEXING，那时必须放行重排 -->
          <el-button link :loading="indexingId === row.id"
            :disabled="isIndexRunning(row)"
            @click="queueIndex(row)">{{ indexActionText(row) }}</el-button>
          <el-tooltip v-if="row.status !== 'READY'"
            placement="top" content="项目完成索引后才能创建角色">
            <span class="btn-disabled-wrap"><el-button link type="primary" disabled>创建角色</el-button></span>
          </el-tooltip>
          <el-button v-else link type="primary" @click="createAgent(row)">创建角色</el-button>
          <el-button link @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removeProject(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑项目' : '新增项目'" width="520px" align-center>
      <el-form :model="form" label-width="90px">
        <el-form-item label="项目名称" required>
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="Git 地址" required>
          <el-input v-model="form.gitUrl" placeholder="https://git.example.com/org/repo.git" />
        </el-form-item>
        <el-form-item label="分支">
          <el-input v-model="form.gitBranch" placeholder="main" />
        </el-form-item>
        <el-form-item label="Git 凭据">
          <el-input v-model="form.credentialRef" placeholder="暂不参与克隆，可留空" />
        </el-form-item>
      </el-form>
      <p class="form-tip">
        <strong>此字段不参与克隆</strong>。索引时的 <code>git clone</code> 已停用运行机器的 Git 凭据
        （全局配置、<code>~/.git-credentials</code>、SSH key 一律不继承）——
        否则任何人都能借服务账号的身份读私有代码。按项目的凭据注入尚未实现，
        因此<strong>当前只能索引可匿名访问的仓库</strong>。
        索引状态为「已就绪」的项目才能用来创建角色并发起对话。
      </p>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveProject">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  codeProjectCreate, codeProjectDelete, codeProjectList,
  codeProjectQueueIndex, codeProjectUpdate, aiAgentList
} from '@/api'

const router = useRouter()
const projects = ref([])
const agents = ref([])
const loading = ref(false)
const saving = ref(false)
const indexingId = ref(null)
const visible = ref(false)
const form = ref(emptyForm())

function emptyForm() { return { id: null, name: '', gitUrl: '', gitBranch: 'main', credentialRef: '' } }
function statusText(status) { return ({ PENDING: '待索引', QUEUED: '排队中', INDEXING: '索引中', READY: '已就绪', FAILED: '失败' })[status] || status || '未知' }
function statusType(status) { return ({ READY: 'success', FAILED: 'danger', INDEXING: 'warning', QUEUED: 'warning' })[status] || 'info' }

/** 索引任务视为"还活着"的时间窗（分钟），与后端 AiCodeProjectServiceImpl.STUCK_MINUTES 保持一致 */
const STUCK_MINUTES = 10

/** 是否已有索引任务在跑：状态 + 时间窗双重判定，光是 INDEXING 可能只是上次进程被杀留下的残留状态 */
function isIndexRunning(row) {
  if (row.status !== 'QUEUED' && row.status !== 'INDEXING') return false
  if (!row.updatedAt) return false
  const elapsed = (Date.now() - new Date(row.updatedAt.replace(' ', 'T')).getTime()) / 60000
  return elapsed < STUCK_MINUTES
}

function indexActionText(row) {
  if (row.status === 'FAILED') return '重试索引'
  // 状态卡在排队/索引中但已超时：给一个明确的"重排"动作，否则用户没有出口
  if ((row.status === 'QUEUED' || row.status === 'INDEXING') && !isIndexRunning(row)) return '重新索引'
  return '排队索引'
}

/** 项目下已创建的角色（一个项目可以配置多个） */
function agentsOf(projectId) {
  return agents.value.filter(a => a.projectid === projectId)
}

/** 把 Git 的原始报错归成一类可操作提示；完整报错在 tooltip 里 */
function errorHint(row) {
  const err = (row.lastError || '').toLowerCase()
  if (err.includes('authentication failed') || err.includes('could not read username')
      || err.includes('terminal prompts disabled') || err.includes('403')) {
    return '私有仓库暂时无法索引：克隆不再借用运行机器的凭据，需改为可匿名访问的地址'
  }
  if (err.includes('could not resolve host') || err.includes('connection timed out')
      || err.includes('connection refused')) {
    return '无法连接到 Git 服务器，检查网络与地址'
  }
  if (err.includes('remote branch') || err.includes('not found')
      || err.includes('repository not found')) {
    return '仓库或分支不存在，检查分支名与地址'
  }
  return ''
}

async function loadProjects() {
  loading.value = true
  try {
    const [projectRes, agentRes] = await Promise.all([codeProjectList(), aiAgentList()])
    if (projectRes.code === 200) projects.value = projectRes.data || []
    if (agentRes.code === 200) agents.value = agentRes.data || []
  } finally {
    loading.value = false
  }
}

function openCreate() { form.value = emptyForm(); visible.value = true }
function openEdit(row) { form.value = { id: row.id, name: row.name, gitUrl: row.gitUrl, gitBranch: row.gitBranch || 'main', credentialRef: row.credentialRef || '' }; visible.value = true }

function createAgent(row) {
  router.push({ path: '/ai/agents', query: { projectId: row.id } })
}

async function saveProject() {
  if (!form.value.name.trim() || !form.value.gitUrl.trim()) { ElMessage.warning('请填写项目名称和 Git 地址'); return }
  saving.value = true
  try {
    const res = form.value.id ? await codeProjectUpdate(form.value) : await codeProjectCreate(form.value)
    if (res.code !== 200) throw new Error(res.msg || '保存失败')
    visible.value = false; ElMessage.success('项目已保存'); await loadProjects()
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { saving.value = false }
}

async function queueIndex(row) {
  indexingId.value = row.id
  try {
    const res = await codeProjectQueueIndex(row.id)
    if (res.code !== 200) throw new Error(res.msg || '排队失败')
    ElMessage.success('索引任务已排队'); await loadProjects()
  } catch (e) { ElMessage.error(e.message || '排队失败') } finally { indexingId.value = null }
}

async function removeProject(row) {
  const bound = agentsOf(row.id)
  const tip = bound.length
    ? `确定删除项目“${row.name}”吗？该项目下的 ${bound.length} 个角色（${bound.map(a => a.name).join('、')}）会一并删除。`
    : `确定删除项目“${row.name}”吗？`
  try { await ElMessageBox.confirm(tip, '删除确认', { type: 'warning' }) } catch { return }
  try {
    const res = await codeProjectDelete(row.id)
    if (res.code !== 200) throw new Error(res.msg || '删除失败')
    ElMessage.success('项目已删除'); await loadProjects()
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

onMounted(loadProjects)
</script>

<style scoped>
/* 与「AI 角色」页保持同一套布局：占满内容区、不加 max-width，
   否则两页宽度不一致，且表格会被挤到需要横向滚动 */
.projects-page { height: 100%; display: flex; flex-direction: column; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; margin-bottom: 16px; }
.page-head h2 { font-size: 18px; font-weight: 600; color: var(--text-primary); margin: 0 0 4px; }
.page-head p { font-size: 13px; color: var(--text-secondary); margin: 0; }
.agent-none { color: var(--text-muted); }
/* 多个角色时的标签间距 */
.agent-tag { margin: 2px 4px 2px 0; }

/* 索引失败原因：列表里一行省略，悬停看完整报错 */
.err-line {
  margin: 4px 0 0; font-size: 12px; color: var(--danger-color);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: help;
}
.err-tip { max-width: 420px; line-height: 1.6; word-break: break-all; }
.form-tip { margin: 0; font-size: 12px; color: var(--text-muted); line-height: 1.6; }
.form-tip strong { color: var(--warning-color); font-weight: 600; }
/* 禁用的按钮不触发鼠标事件，tooltip 要挂在外层 span 上 */
.btn-disabled-wrap { display: inline-block; }
@media (max-width: 720px) { .page-head { flex-direction: column; } }
</style>

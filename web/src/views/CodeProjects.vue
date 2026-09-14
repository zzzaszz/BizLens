<template>
  <section class="projects-page">
    <div class="page-heading">
      <div>
        <h1>代码项目</h1>
        <p class="subtitle">登记 Git 项目元数据；项目完成索引后，可为它创建一个 AI 角色</p>
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
      <!-- 只反映凭据字段本身有没有填，不据此推断仓库是公开还是私有：
           私有仓库没填凭据同样显示"未配置"，那才是最需要提示的情况 -->
      <el-table-column label="访问凭据" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.credentialRef" type="success" effect="plain" size="small">已配置</el-tag>
          <el-tag v-else type="warning" effect="plain" size="small">未配置</el-tag>
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
      <el-table-column label="AI 角色" width="120">
        <template #default="{ row }">
          <span v-if="agentOf(row.id)" class="agent-bound">{{ agentOf(row.id).name }}</span>
          <span v-else class="agent-none">未创建</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link :loading="indexingId === row.id" @click="queueIndex(row)">排队索引</el-button>
          <el-button v-if="!agentOf(row.id)" link type="primary" @click="createAgent(row)">创建角色</el-button>
          <el-button link @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removeProject(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑项目' : '新增项目'" width="520px">
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
          <el-input v-model="form.credentialRef" placeholder="公开仓库可留空；私有仓库填凭据引用，不要填明文密码" />
        </el-form-item>
      </el-form>
      <p class="form-tip">公开仓库不需要凭据；私有仓库需填写。索引状态为「已就绪」的项目才能用来创建角色并发起对话。</p>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveProject">保存</el-button>
      </template>
    </el-dialog>
  </section>
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

/** 项目已创建的角色（一个项目最多一个） */
function agentOf(projectId) {
  return agents.value.find(a => a.projectid === projectId) || null
}

/** 把 Git 的原始报错归成一类可操作提示；完整报错在 tooltip 里 */
function errorHint(row) {
  const err = (row.lastError || '').toLowerCase()
  if (err.includes('authentication failed') || err.includes('could not read username')
      || err.includes('terminal prompts disabled') || err.includes('403')) {
    return '访问凭据不正确，或该私有仓库需要 Token'
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
  const bound = agentOf(row.id)
  const tip = bound
    ? `确定删除项目“${row.name}”吗？该项目下的角色「${bound.name}」会一并删除。`
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
.projects-page { max-width: 1280px; margin: 0 auto; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; margin-bottom: 16px; }
h1 { margin: 0; color: var(--text-primary); font-size: 18px; font-weight: 600; }
.subtitle { margin: 4px 0 0; color: var(--text-secondary); font-size: 13px; }
.agent-bound { color: var(--text-primary); }
.agent-none { color: var(--text-muted); }

/* 索引失败原因：列表里一行省略，悬停看完整报错 */
.err-line {
  margin: 4px 0 0; font-size: 12px; color: var(--danger-color);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: help;
}
.err-tip { max-width: 420px; line-height: 1.6; word-break: break-all; }
.form-tip { margin: 0; font-size: 12px; color: var(--text-muted); line-height: 1.6; }
@media (max-width: 720px) { .page-heading { flex-direction: column; } }
</style>

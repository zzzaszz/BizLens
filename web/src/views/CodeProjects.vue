<template>
  <section class="projects-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">CODE KNOWLEDGE</p>
        <h1>代码项目</h1>
        <p class="subtitle">登记 Git 项目元数据，源码只在后续索引任务的临时工作区中使用。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增项目</el-button>
    </div>

    <el-table v-loading="loading" :data="projects" border empty-text="暂无代码项目">
      <el-table-column prop="name" label="项目名称" min-width="180" />
      <el-table-column prop="gitUrl" label="Git 地址" min-width="280" show-overflow-tooltip />
      <el-table-column prop="gitBranch" label="分支" width="140" />
      <el-table-column label="索引统计" width="220">
        <template #default="{ row }">
          <span>{{ row.documentCount || 0 }} 文件 / {{ row.chunkCount || 0 }} 切片 / {{ row.symbolChunkCount || 0 }} 符号</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="Commit" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.gitCommit || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :loading="indexingId === row.id" @click="queueIndex(row)">排队索引</el-button>
          <el-button link @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removeProject(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑项目' : '新增项目'" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="项目名称" required><el-input v-model="form.name" maxlength="128" /></el-form-item>
        <el-form-item label="Git 地址" required><el-input v-model="form.gitUrl" placeholder="https://git.example.com/org/repo.git" /></el-form-item>
        <el-form-item label="分支"><el-input v-model="form.gitBranch" placeholder="main" /></el-form-item>
        <el-form-item label="凭据引用"><el-input v-model="form.credentialRef" placeholder="Secret Manager 中的引用，不要填写明文密码" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveProject">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  codeProjectCreate, codeProjectDelete, codeProjectList,
  codeProjectQueueIndex, codeProjectUpdate
} from '@/api'

const projects = ref([])
const loading = ref(false)
const saving = ref(false)
const indexingId = ref(null)
const visible = ref(false)
const form = ref(emptyForm())

function emptyForm() { return { id: null, name: '', gitUrl: '', gitBranch: 'main', credentialRef: '' } }
function statusText(status) { return ({ PENDING: '待索引', QUEUED: '排队中', INDEXING: '索引中', READY: '已就绪', FAILED: '失败' })[status] || status || '未知' }
function statusType(status) { return ({ READY: 'success', FAILED: 'danger', INDEXING: 'warning', QUEUED: 'warning' })[status] || 'info' }

async function loadProjects() {
  loading.value = true
  try { const res = await codeProjectList(); if (res.code === 200) projects.value = res.data || [] } finally { loading.value = false }
}
function openCreate() { form.value = emptyForm(); visible.value = true }
function openEdit(row) { form.value = { id: row.id, name: row.name, gitUrl: row.gitUrl, gitBranch: row.gitBranch || 'main', credentialRef: row.credentialRef || '' }; visible.value = true }
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
  try { const res = await codeProjectQueueIndex(row.id); if (res.code !== 200) throw new Error(res.msg || '排队失败'); ElMessage.success('索引任务已排队'); await loadProjects() } catch (e) { ElMessage.error(e.message || '排队失败') } finally { indexingId.value = null }
}
async function removeProject(row) {
  try { await ElMessageBox.confirm(`确定删除项目“${row.name}”吗？`, '删除确认', { type: 'warning' }) } catch { return }
  try { const res = await codeProjectDelete(row.id); if (res.code !== 200) throw new Error(res.msg || '删除失败'); ElMessage.success('项目已删除'); await loadProjects() } catch (e) { ElMessage.error(e.message || '删除失败') }
}
onMounted(loadProjects)
</script>

<style scoped>
.projects-page { max-width: 1180px; margin: 0 auto; padding: 8px 0 24px; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 24px; margin-bottom: 24px; }
.eyebrow { margin: 0 0 8px; color: var(--accent-color); font-size: 12px; font-weight: 700; letter-spacing: 1.5px; }
h1 { margin: 0; color: var(--text-primary); font-size: 26px; }.subtitle { margin: 10px 0 0; color: var(--text-secondary); font-size: 14px; }
@media (max-width: 720px) { .page-heading { flex-direction: column; } }
</style>

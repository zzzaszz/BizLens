<template>
  <div class="knowledge-page">
    <div class="page-head">
      <div>
        <h2>个人知识库</h2>
        <p>接入 Git 托管的 Markdown 知识，与代码项目一起用于 AI 问答</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" :loading="queuingAll" :disabled="!sources.length" @click="queueIndexAll">一键索引排队</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增知识库</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="sources" border empty-text="暂无个人知识库">
      <el-table-column prop="name" label="名称" min-width="170" />
      <el-table-column prop="gitUrl" label="Git 地址" min-width="250" show-overflow-tooltip />
      <el-table-column prop="rootPath" label="子目录" width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.rootPath || '/' }}</template>
      </el-table-column>
      <el-table-column label="索引统计" width="180">
        <template #default="{ row }">{{ row.documentCount || 0 }} 文档 / {{ row.chunkCount || 0 }} 切片</template>
      </el-table-column>
      <el-table-column label="状态" width="240">
        <template #default="{ row }">
          <el-tag :type="statusType(row)" size="small">{{ statusText(row) }}</el-tag>
          <el-progress v-if="progress(row) != null" :percentage="progress(row)" :stroke-width="5" class="index-progress" />
          <p v-if="row.indexCurrentFile && isRunning(row)" class="status-file" :title="row.indexCurrentFile">
            正在处理：{{ row.indexCurrentFile }}
          </p>
          <p v-if="row.lastError" class="error-line" :title="row.lastError">{{ row.lastError }}</p>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link :loading="indexingId === row.id" :disabled="isRunning(row)" @click="queueIndex(row)">
            {{ row.status === 'FAILED' || isInterrupted(row) ? '重新索引' : '排队索引' }}
          </el-button>
          <el-button link @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removeSource(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="pagination-bar">
      <el-pagination v-model:current-page="page" :page-size="PAGE_SIZE" layout="total, prev, pager, next" :total="total" @current-change="loadSources" />
    </div>

    <el-dialog v-model="visible" :title="form.id ? '编辑知识库' : '新增知识库'" width="540px" align-center>
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" maxlength="128" placeholder="如：个人 Java 知识库" /></el-form-item>
        <el-form-item label="Git 地址" required><el-input v-model="form.gitUrl" placeholder="https://git.example.com/me/notes.git" /></el-form-item>
        <el-form-item label="分支"><el-input v-model="form.gitBranch" placeholder="main" /></el-form-item>
        <el-form-item label="索引子目录"><el-input v-model="form.rootPath" placeholder="留空索引整个仓库，如 notes/" /></el-form-item>
        <el-form-item label="访问令牌">
          <el-input v-model="form.credentialToken" type="password" show-password clearable :placeholder="form.id ? '留空表示不修改' : '私有仓库必填，公开仓库可留空'" />
          <p v-if="form.id" class="form-hint">{{ form.credentialEnc ? `当前已配置（${form.credentialEnc}），留空表示不修改` : '当前未配置' }}</p>
        </el-form-item>
        <el-form-item label="令牌用户名"><el-input v-model="form.credentialUser" placeholder="留空按平台默认，如 GitHub x-access-token" /></el-form-item>
      </el-form>
      <p class="form-tip">仅索引 `.md` / `.markdown` 文件；内容会按 Markdown 标题切片，敏感信息会在落库前脱敏。凭据加密保存。</p>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSource">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  knowledgeSourceCreate, knowledgeSourceDelete, knowledgeSourceList,
  knowledgeSourceQueueIndex, knowledgeSourceQueueIndexAll, knowledgeSourceUpdate
} from '@/api'

const sources = ref([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const queuingAll = ref(false)
const indexingId = ref(null)
const visible = ref(false)
const form = ref(emptyForm())
const PAGE_SIZE = 10
const HEARTBEAT_SECONDS = 180
const nowTick = ref(Date.now())
const lastFetchedAt = ref(Date.now())
let pollTimer = null
let tickTimer = null

function emptyForm() { return { id: null, name: '', gitUrl: '', gitBranch: 'main', rootPath: '', credentialToken: '', credentialUser: '', credentialEnc: '' } }
function secondsSinceUpdate(row) {
  const baseMs = row.heartbeatElapsedMs != null ? row.heartbeatElapsedMs : row.elapsedMs
  if (baseMs == null) return Infinity
  return Number(baseMs) / 1000 + (nowTick.value - lastFetchedAt.value) / 1000
}
function isRunning(row) { return (row.status === 'QUEUED' || row.status === 'INDEXING') && secondsSinceUpdate(row) < HEARTBEAT_SECONDS }
function isInterrupted(row) { return (row.status === 'QUEUED' || row.status === 'INDEXING') && !isRunning(row) }
function statusText(row) { if (isInterrupted(row)) return '索引中断'; return ({ PENDING: '待索引', QUEUED: '排队中', INDEXING: '索引中', READY: '已就绪', FAILED: '失败' })[row.status] || '未知' }
function statusType(row) { if (isInterrupted(row)) return 'info'; return ({ READY: 'success', FAILED: 'danger', INDEXING: 'warning', QUEUED: 'warning' })[row.status] || 'info' }
function progress(row) { const totalFiles = Number(row.indexTotalFiles || 0); return totalFiles ? Math.round(Math.min(Number(row.indexProcessedFiles || 0), totalFiles) * 100 / totalFiles) : null }

async function loadSources(nextPage = page.value, quiet = false) {
  page.value = nextPage
  if (!quiet) loading.value = true
  try {
    const res = await knowledgeSourceList({ page: page.value, pageSize: PAGE_SIZE })
    if (res.code === 200) {
      const data = res.data || {}
      sources.value = data.records || []
      total.value = Number(data.total || 0)
      lastFetchedAt.value = Date.now()
      nowTick.value = lastFetchedAt.value
    }
  } finally {
    if (!quiet) loading.value = false
    syncPolling()
  }
}
function syncPolling() {
  const busy = sources.value.some(isRunning)
  if (busy && !pollTimer) pollTimer = setInterval(() => loadSources(page.value, true), 3000)
  if (!busy && pollTimer) { clearInterval(pollTimer); pollTimer = null }
  if (busy && !tickTimer) tickTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
  if (!busy && tickTimer) { clearInterval(tickTimer); tickTimer = null }
}
function openCreate() { form.value = emptyForm(); visible.value = true }
function openEdit(row) { form.value = { id: row.id, name: row.name, gitUrl: row.gitUrl, gitBranch: row.gitBranch || 'main', rootPath: row.rootPath || '', credentialToken: '', credentialUser: row.credentialUser || '', credentialEnc: row.credentialEnc || '' }; visible.value = true }
async function saveSource() {
  if (!form.value.name.trim() || !form.value.gitUrl.trim()) { ElMessage.warning('请填写名称和 Git 地址'); return }
  saving.value = true
  try {
    const payload = { id: form.value.id, name: form.value.name.trim(), gitUrl: form.value.gitUrl.trim(), gitBranch: form.value.gitBranch, rootPath: form.value.rootPath, credentialToken: form.value.credentialToken || null, credentialUser: form.value.credentialUser || null }
    const res = form.value.id ? await knowledgeSourceUpdate(payload) : await knowledgeSourceCreate(payload)
    if (res.code !== 200) throw new Error(res.msg || '保存失败')
    visible.value = false; ElMessage.success('知识库已保存'); await loadSources()
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { saving.value = false }
}
async function queueIndex(row) { indexingId.value = row.id; try { const res = await knowledgeSourceQueueIndex(row.id); if (res.code !== 200) throw new Error(res.msg || '排队失败'); ElMessage.success('索引任务已排队'); await loadSources() } catch (e) { ElMessage.error(e.message || '排队失败') } finally { indexingId.value = null } }
async function queueIndexAll() { try { await ElMessageBox.confirm('将把全部个人知识库重新排队索引，确定继续？', '一键索引排队', { type: 'warning' }) } catch { return } queuingAll.value = true; try { const res = await knowledgeSourceQueueIndexAll(); if (res.code !== 200) throw new Error(res.msg || '排队失败'); ElMessage.success(`已排队 ${res.data?.queued || 0} 个`); await loadSources() } catch (e) { ElMessage.error(e.message || '排队失败') } finally { queuingAll.value = false } }
async function removeSource(row) { try { await ElMessageBox.confirm(`确定删除知识库“${row.name}”？关联角色不会删除，只会解除来源。`, '删除确认', { type: 'warning' }) } catch { return } try { const res = await knowledgeSourceDelete(row.id); if (res.code !== 200) throw new Error(res.msg || '删除失败'); ElMessage.success('知识库已删除'); await loadSources() } catch (e) { ElMessage.error(e.message || '删除失败') } }
onMounted(() => loadSources())
onBeforeUnmount(() => { if (pollTimer) clearInterval(pollTimer); if (tickTimer) clearInterval(tickTimer) })
</script>

<style scoped>
.knowledge-page { height: 100%; display: flex; flex-direction: column; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; margin-bottom: 16px; }
.page-head h2 { font-size: 18px; font-weight: 600; color: var(--text-primary); margin: 0 0 4px; }
.page-head p { font-size: 13px; color: var(--text-secondary); margin: 0; }
.head-actions { display: flex; gap: 8px; }
.pagination-bar { display: flex; justify-content: flex-end; padding: 14px 0 0; }
.index-progress { width: 150px; margin-top: 5px; }
.error-line { margin: 4px 0 0; color: var(--danger-color); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.status-file { margin: 3px 0 0; max-width: 205px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-secondary); font-size: 12px; }
.form-tip, .form-hint { color: var(--text-muted); font-size: 12px; line-height: 1.6; }
@media (max-width: 720px) { .page-head { flex-direction: column; } .head-actions { width: 100%; } }
</style>

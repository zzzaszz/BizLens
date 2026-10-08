<template>
  <div class="projects-page">
    <div class="page-head">
      <div>
        <h2>代码项目</h2>
        <p>登记 Git 项目元数据；项目完成索引后，可为它创建多个 AI 角色</p>
      </div>
      <div class="head-actions">
        <!-- 一键把下面所有项目重新排队索引；正在索引的由后端跳过 -->
        <el-tooltip placement="bottom" :show-after="100"
          content="把下面全部项目重新排队索引。正在索引的项目会自动跳过；索引期间会逐个重新克隆仓库并重写切片数据。">
          <el-button :icon="Refresh" :loading="queuingAll" :disabled="!projects.length" @click="queueIndexAll">
            一键索引排队
          </el-button>
        </el-tooltip>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增项目</el-button>
      </div>
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
      <!-- 只反映"令牌配没配"，不要据此推断仓库是公开还是私有 -->
      <el-table-column label="访问令牌" width="130">
        <template #default="{ row }">
          <el-tooltip placement="top" :show-after="100">
            <template #content>
              <div class="err-tip">
                私有仓库需要填访问令牌。索引时把该令牌注入给 git，<b>运行机器自己的 Git 凭据一律不参与</b>
                （全局配置、~/.git-credentials、SSH key 都不继承），避免借用服务账号身份读私有代码。
              </div>
            </template>
            <el-tag v-if="row.credentialEnc" type="success" effect="plain" size="small">已配置</el-tag>
            <el-tag v-else type="info" effect="plain" size="small">未配置（公开仓库可留空）</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="270">
        <template #default="{ row }">
          <el-tag :type="statusTypeOf(row)" size="small">{{ statusText(row) }}</el-tag>
          <el-progress v-if="indexProgress(row) != null" :percentage="indexProgress(row)"
            :status="row.status === 'FAILED' ? 'exception' : (row.status === 'READY' ? 'success' : undefined)"
            :stroke-width="5" class="index-progress" />
          <!-- 进度/时间：索引中显示已用多久与预计，完成显示完成时间，中断显示中断时间 -->
          <p v-if="statusTimeText(row)" class="status-time">{{ statusTimeText(row) }}</p>
          <p v-if="row.indexCurrentFile && isIndexRunning(row)" class="status-file" :title="row.indexCurrentFile">
            正在处理：{{ row.indexCurrentFile }}
          </p>
          <!-- 索引失败/中断要能看到原因，否则只有一个标签没法排查 -->
          <el-tooltip v-if="row.lastError" placement="top" :show-after="100">
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
    <div class="pagination-bar">
      <el-pagination v-model:current-page="projectPage" :page-size="PROJECT_PAGE_SIZE"
        layout="total, prev, pager, next" :total="projectTotal" @current-change="loadProjects" />
    </div>

    <el-dialog v-model="visible" :title="form.id ? '编辑项目' : '新增项目'" width="520px" align-center>
      <el-form :model="form" label-width="90px">
        <el-form-item label="项目名称" required>
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="Git 地址" required>
          <el-input v-model="form.gitUrl" placeholder="https://git.example.com/org/repo.git" />
          <p v-if="platform" class="form-hint" :class="{ warn: platform.warn }">
            <template v-if="platform.warn">{{ platform.warn }}</template>
            <template v-else>
              识别为 <b>{{ platform.name }}</b>：公开仓库直接索引；私有仓库需在下面填访问令牌。
              <template v-if="platform.userHint">令牌用户名建议填 <code>{{ platform.userHint }}</code>。</template>
              <template v-if="platform.extra">{{ platform.extra }}。</template>
            </template>
          </p>
        </el-form-item>
        <el-form-item label="分支">
          <el-input v-model="form.gitBranch" placeholder="main" />
        </el-form-item>
        <el-form-item label="访问令牌">
          <el-input v-model="form.credentialToken" type="password" show-password clearable
            :placeholder="form.id ? '留空表示不修改' : '私有仓库必填；公开仓库留空即可'" />
          <p v-if="form.id" class="form-hint" :class="form.credentialEnc ? '' : 'warn'">
            <template v-if="form.credentialEnc">当前已配置（{{ form.credentialEnc }}），留空表示不修改</template>
            <template v-else>当前未配置：私有仓库索引会失败</template>
          </p>
        </el-form-item>
        <el-form-item label="令牌用户名">
          <el-input v-model="form.credentialUser"
            :placeholder="platform && platform.userHint ? `留空按平台默认（${platform.userHint}）` : '留空按平台默认'" />
        </el-form-item>
      </el-form>
      <p class="form-tip">
        令牌用 AES 加密后落库，不存明文。索引时只把<b>本项目的令牌</b>注入给 git，
        运行机器自身的 Git 凭据（全局配置、<code>~/.git-credentials</code>、SSH key）一律不参与，
        以免任何人借服务账号身份读私有代码。<br>
        GitHub 支持 <code>https://github.com/组/库.git</code>、Gitee、GitLab 以及自建 GitLab
        （<code>https://gitlab.公司域名/组/库.git</code>）都走同一套填写方式；公开仓库令牌留空即可。
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
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  codeProjectCreate, codeProjectDelete, codeProjectList,
  codeProjectQueueIndex, codeProjectQueueIndexAll, codeProjectUpdate, aiAgentList
} from '@/api'

const router = useRouter()
const projects = ref([])
const agents = ref([])
const projectPage = ref(1)
const projectTotal = ref(0)
const PROJECT_PAGE_SIZE = 10
const loading = ref(false)
const saving = ref(false)
const indexingId = ref(null)
/** 一键索引请求进行中，避免连点 */
const queuingAll = ref(false)
const visible = ref(false)
const form = ref(emptyForm())
/** 当前地址对应的 Git 平台，用于提示用户名怎么填 */
const platform = computed(() => platformOf(form.value.gitUrl))

function emptyForm() { return { id: null, name: '', gitUrl: '', gitBranch: 'main', credentialToken: '', credentialUser: '', credentialEnc: '' } }

/**
 * 从地址判断 Git 平台，用于给出针对性的填法提示。
 * 只看主机名，不限制域名——自建 GitLab 的域名通常不含 gitlab，所以兜底也按 GitLab 说明。
 */
function platformOf(url) {
  const u = (url || '').toLowerCase()
  if (!u) return null
  // SSH 地址当前走不通：隔离环境不继承 ~/.ssh，也不支持部署密钥
  if (u.startsWith('ssh://') || /^[^/@\s]+@[^:]+:.+/.test(u)) {
    return { name: 'SSH 地址（当前不支持）', userHint: '', warn: '克隆在隔离环境执行，不继承 SSH 密钥，暂时只能用 HTTPS 地址' }
  }
  if (u.includes('github.')) return { name: 'GitHub', userHint: 'x-access-token' }
  if (u.includes('gitee.')) return { name: 'Gitee', userHint: '账号名', extra: 'Gitee 必须填账号名，不能用 oauth2' }
  if (u.includes('gitlab')) return { name: 'GitLab', userHint: 'oauth2' }
  if (u.includes('bitbucket.')) return { name: 'Bitbucket', userHint: 'x-token-auth' }
  return { name: '自建 Git（多为自建 GitLab）', userHint: 'oauth2' }
}
function statusType(status) { return ({ READY: 'success', FAILED: 'danger', INDEXING: 'warning', QUEUED: 'warning' })[status] || 'info' }

/**
 * 心跳宽限（秒），与后端 AiCodeIndexServiceImpl.HEARTBEAT_GRACE_SECONDS 保持一致。
 * 索引运行期间每 30 秒会把 updated_at 推一次，所以"超过 3 分钟没动静"就等于进程没了。
 */
const HEARTBEAT_GRACE_SECONDS = 180

/**
 * 每秒跳一下的时钟。
 * 用 ref 而不是直接 Date.now()：不这样的话渲染函数不会重新执行，页面上的"已用 3 分 12 秒"就定住不动。
 */
const nowTick = ref(Date.now())
/** 最近一次列表拉取的时刻，用来把服务端给的 elapsedMs 往后推 */
const lastFetchedAt = ref(Date.now())

/**
 * 状态距今多少秒。
 * <p>
 * 运行状态优先使用服务端按独立心跳字段计算的 heartbeatElapsedMs，
 * 这里只叠加"拉取之后又过了多久"。普通 elapsedMs 只作为旧数据库结构的降级：
 * updated_at 是数据库时钟写的，浏览器是客户端时钟，两者不一致时（本机实测差 13 秒）
 * 会出现"刚开始就显示已用 13 秒"。
 */
function secondsSinceUpdate(row) {
  const baseMs = row.heartbeatElapsedMs != null ? row.heartbeatElapsedMs : row.elapsedMs
  if (baseMs == null) {
    // 后端还没升级、没返回 elapsedMs 时的降级：退回用本地时钟算。
    // 会受客户端/服务端时钟偏差影响（本机实测差 13 秒），数字可能偏大，但不会显示错状态。
    if (!row.updatedAt) return Infinity
    return (nowTick.value - new Date(row.updatedAt.replace(' ', 'T')).getTime()) / 1000
  }
  const sinceFetch = (nowTick.value - lastFetchedAt.value) / 1000
  return Number(baseMs) / 1000 + sinceFetch
}

/** 索引真的在跑：状态是排队/索引中，且最近还有心跳 */
function isIndexRunning(row) {
  if (row.status !== 'QUEUED' && row.status !== 'INDEXING') return false
  return secondsSinceUpdate(row) < HEARTBEAT_GRACE_SECONDS
}

/**
 * 索引中断：状态停在排队/索引中，但已经没有心跳了。
 * 典型场景是服务在索引途中被重启——finally 没执行，状态没回写，光看状态会以为还在跑。
 */
function isIndexInterrupted(row) {
  if (row.status !== 'QUEUED' && row.status !== 'INDEXING') return false
  return secondsSinceUpdate(row) >= HEARTBEAT_GRACE_SECONDS
}

function statusText(row) {
  if (isIndexInterrupted(row)) return '索引中断'
  return ({ PENDING: '待索引', QUEUED: '排队中', INDEXING: '索引中', READY: '已就绪', FAILED: '失败' })[row.status] || row.status || '未知'
}

/** 秒数 → 「12 秒 / 3 分 12 秒 / 1 小时 5 分」 */
function fmtDuration(seconds) {
  const s = Math.max(0, Math.round(seconds))
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  if (m < 60) return s % 60 ? `${m} 分 ${s % 60} 秒` : `${m} 分`
  return `${Math.floor(m / 60)} 小时 ${m % 60} 分`
}

/** 「2026-10-07 15:22:33」→「10-07 15:22」 */
function fmtDateTime(value) {
  return value ? String(value).slice(5, 16) : ''
}

/**
 * 状态标签下面那行小字：跑到哪一步、用了多久、什么时候完成/失败。
 * 进度只能估：拿"上一次成功索引的耗时"当参考，没有参考值（从没成功过）就不给预计。
 */
function statusTimeText(row) {
  if (isIndexInterrupted(row)) {
    return `中断于 ${fmtDateTime(row.updatedAt)}，可重新索引`
  }
  if (isIndexRunning(row)) {
    const elapsed = secondsSinceUpdate(row)
    const lastMs = Number(row.lastIndexMs || 0)
    if (!lastMs) {
      return `已用 ${fmtDuration(elapsed)}`
    }
    const lastSec = lastMs / 1000
    const remain = lastSec - elapsed
    return remain > 0
      ? `已用 ${fmtDuration(elapsed)}，预计还需约 ${fmtDuration(remain)}`
      : `已用 ${fmtDuration(elapsed)}（上次用 ${fmtDuration(lastSec)}）`
  }
  if (row.status === 'READY') {
    return row.lastIndexedAt ? `${fmtDateTime(row.lastIndexedAt)} 完成` : ''
  }
  if (row.status === 'FAILED') {
    return row.updatedAt ? `${fmtDateTime(row.updatedAt)} 失败` : ''
  }
  return ''
}

function indexProgress(row) {
  if (!row || row.indexTotalFiles == null || Number(row.indexTotalFiles) <= 0) return null
  const total = Number(row.indexTotalFiles)
  const processed = Math.min(Number(row.indexProcessedFiles || 0), total)
  return Math.round(processed * 100 / total)
}

/** 中断的索引按"信息态"显示，不要用和"进行中"一样的黄色，否则看不出来它已经死了 */
function statusTypeOf(row) {
  if (isIndexInterrupted(row)) return 'info'
  return statusType(row.status)
}

function indexActionText(row) {
  if (row.status === 'FAILED' || isIndexInterrupted(row)) return '重新索引'
  return '排队索引'
}

/** 关联到该项目的角色（一个项目可被多个角色关联；角色也可同时关联多个项目） */
function agentsOf(projectId) {
  return agents.value.filter(a => (a.projectIds || []).includes(projectId))
}

/** 把 Git 的原始报错归成一类可操作提示；完整报错在 tooltip 里 */
function errorHint(row) {
  const err = (row.lastError || '').toLowerCase()
  if (err.includes('authentication failed') || err.includes('could not read username')
      || err.includes('terminal prompts disabled') || err.includes('403')) {
    return '访问令牌被拒或未配置，请检查令牌与令牌用户名'
  }
  if (err.includes('could not resolve host') || err.includes('connection timed out')
      || err.includes('connection refused') || err.includes('connection was reset')
      || err.includes('failed to connect')) {
    return '无法连接到 Git 服务器，检查网络与地址（外网地址可能需要在服务端配置 BIZLENS_GIT_PROXY）'
  }
  if (err.includes('索引任务被中断')) {
    return '索引被中断：服务在索引途中重启过，重新排队即可'
  }
  if (err.includes('remote branch') || err.includes('not found')
      || err.includes('repository not found')) {
    return '仓库或分支不存在，检查分支名与地址'
  }
  return ''
}

/** 轮询间隔：索引在后台异步跑，页面必须自己把状态追回来 */
const POLL_INTERVAL = 3000
let pollTimer = null
let tickTimer = null

/** 有索引在跑时每秒重算一次"已用多久"，让进度真的在走 */
function syncTicker() {
  const busy = projects.value.some(p => isIndexRunning(p))
  if (busy && !tickTimer) {
    tickTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
  } else if (!busy && tickTimer) {
    clearInterval(tickTimer)
    tickTimer = null
  } else if (busy) {
    nowTick.value = Date.now()
  }
}

/**
 * 有项目在排队/索引中时轮询刷新。
 * <p>
 * 索引是异步任务：点完「排队索引」页面拿到的是 QUEUED，之后任务在后台跑完或失败，
 * 页面一无所知——之前就是只加载一次，所以会一直停在「索引中」，
 * 用户必须手动刷新才能看到结果。
 * 只在真的有任务在跑时轮询，跑完立即停；用 isIndexRunning 判定，
 * 与"卡死超过 10 分钟就不再算在跑"的口径保持一致，避免永久轮询。
 */
function syncPolling() {
  const busy = projects.value.some(p => isIndexRunning(p))
  if (busy && !pollTimer) {
    pollTimer = setInterval(() => loadProjects(projectPage.value, true), POLL_INTERVAL)
  } else if (!busy && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** @param quiet 轮询时用：不动 loading，避免每次刷新整表闪一下 */
async function loadProjects(page = projectPage.value, quiet = false) {
  if (typeof page !== 'number') page = projectPage.value
  projectPage.value = page
  if (!quiet) loading.value = true
  try {
    const [projectRes, agentRes] = await Promise.all([
      codeProjectList({ page, pageSize: PROJECT_PAGE_SIZE }), aiAgentList({ page: 1, pageSize: 100 })
    ])
    if (projectRes.code === 200) {
      const data = projectRes.data || {}
      projects.value = data.records || []
      projectTotal.value = Number(data.total || 0)
    }
    if (agentRes.code === 200) agents.value = agentRes.data || []
    // 记下这次拉取的时刻：服务端 elapsedMs 是"拉取那一刻"的值，之后由本地时钟往上叠加
    lastFetchedAt.value = Date.now()
  } finally {
    if (!quiet) loading.value = false
    syncPolling()
    syncTicker()
  }
}

function openCreate() { form.value = emptyForm(); visible.value = true }
function openEdit(row) {
  form.value = {
    id: row.id, name: row.name, gitUrl: row.gitUrl, gitBranch: row.gitBranch || 'main',
    // 令牌不回显明文（服务端也不回传），只带脱敏串用于提示"已配置"
    credentialToken: '', credentialUser: row.credentialUser || '', credentialEnc: row.credentialEnc || ''
  }
  visible.value = true
}

function createAgent(row) {
  router.push({ path: '/ai/agents', query: { projectId: row.id } })
}

async function saveProject() {
  if (!form.value.name.trim() || !form.value.gitUrl.trim()) { ElMessage.warning('请填写项目名称和 Git 地址'); return }
  saving.value = true
  try {
    // 显式构造提交体：不要把表单里用于展示的 credentialEnc（脱敏串）一起传回去
    const payload = {
      id: form.value.id,
      name: form.value.name.trim(),
      gitUrl: form.value.gitUrl.trim(),
      gitBranch: form.value.gitBranch,
      credentialToken: form.value.credentialToken || null,
      credentialUser: form.value.credentialUser || null
    }
    const res = form.value.id ? await codeProjectUpdate(payload) : await codeProjectCreate(payload)
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

/**
 * 一键把全部项目重新排队索引。
 * 逐个重排会让每个项目都重新克隆 + 重写切片，属于重操作，所以先确认再发。
 * 正在索引的项目由后端跳过（同一项目并发跑两个索引会互相覆盖切片）。
 */
async function queueIndexAll() {
  if (!projects.value.length) return
  try {
    await ElMessageBox.confirm(
      `将把全部 ${projects.value.length} 个项目的索引重新排队，每个项目都会重新克隆仓库并重写切片数据。`
      + '正在索引的项目会自动跳过。确定继续？',
      '一键索引排队', { type: 'warning' })
  } catch { return }
  queuingAll.value = true
  try {
    const res = await codeProjectQueueIndexAll()
    if (res.code !== 200) throw new Error(res.msg || '排队失败')
    const data = res.data || {}
    const skipped = (data.skippedNames && data.skippedNames.length)
      ? `，跳过 ${data.skipped} 个正在索引的（${data.skippedNames.join('、')}）`
      : ''
    ElMessage.success(`已排队 ${data.queued} 个${skipped}`)
    await loadProjects()
  } catch (e) {
    ElMessage.error(e.message || '排队失败')
  } finally {
    queuingAll.value = false
  }
}

async function removeProject(row) {
  const bound = agentsOf(row.id)
  // 角色与项目是多对多：删项目只解除关联，不删角色（角色可能还关联着别的项目）
  const tip = bound.length
    ? `确定删除项目“${row.name}”吗？该项目及其索引数据会删除；关联的 ${bound.length} 个角色（${bound.map(a => a.name).join('、')}）不会被删除，只是解除与它的关联。`
    : `确定删除项目“${row.name}”吗？项目及其索引数据会一并删除。`
  try { await ElMessageBox.confirm(tip, '删除确认', { type: 'warning' }) } catch { return }
  try {
    const res = await codeProjectDelete(row.id)
    if (res.code !== 200) throw new Error(res.msg || '删除失败')
    ElMessage.success('项目已删除'); await loadProjects()
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

onMounted(loadProjects)

onBeforeUnmount(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  if (tickTimer) {
    clearInterval(tickTimer)
    tickTimer = null
  }
})
</script>

<style scoped>
/* 与「AI 角色」页保持同一套布局：占满内容区、不加 max-width，
   否则两页宽度不一致，且表格会被挤到需要横向滚动 */
.projects-page { height: 100%; display: flex; flex-direction: column; }
.pagination-bar { display: flex; justify-content: flex-end; padding: 14px 0 0; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; margin-bottom: 16px; }
.page-head h2 { font-size: 18px; font-weight: 600; color: var(--text-primary); margin: 0 0 4px; }
.page-head p { font-size: 13px; color: var(--text-secondary); margin: 0; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.agent-none { color: var(--text-muted); }
/* 多个角色时的标签间距 */
.agent-tag { margin: 2px 4px 2px 0; }

/* 索引进度/时间：小字一行 */
.status-time { margin: 4px 0 0; font-size: 12px; color: var(--text-muted); line-height: 1.5; }
.status-file { margin: 2px 0 0; max-width: 235px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--text-secondary); line-height: 1.5; }
.index-progress { width: 150px; margin-top: 5px; }

/* 索引失败原因：列表里一行省略，悬停看完整报错 */
.err-line {
  margin: 4px 0 0; font-size: 12px; color: var(--danger-color);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: help;
}
.err-tip { max-width: 420px; line-height: 1.6; word-break: break-all; }
.form-tip { margin: 0; font-size: 12px; color: var(--text-muted); line-height: 1.6; }
.form-tip strong { color: var(--warning-color); font-weight: 600; }
.form-tip code { padding: 1px 4px; border-radius: 2px; background: rgba(15, 23, 42, .07); font-size: 11px; }
/* 表单里跟在输入框下方的说明：平台识别结果、令牌当前状态 */
.form-hint { width: 100%; margin: 4px 0 0; font-size: 12px; line-height: 1.6; color: var(--text-muted); }
.form-hint.warn { color: var(--warning-color); }
.form-hint code { padding: 1px 4px; border-radius: 2px; background: rgba(15, 23, 42, .07); font-size: 11px; }
/* 禁用的按钮不触发鼠标事件，tooltip 要挂在外层 span 上 */
.btn-disabled-wrap { display: inline-block; }
@media (max-width: 720px) { .page-head { flex-direction: column; } }
</style>

<script>
export default { name: 'AiChat' }
</script>

<template>
  <div class="ai-chat">
    <div class="ai-sidebar">
      <el-button type="primary" class="new-btn" :icon="Plus" @click="openCreate">新建会话</el-button>
      <!-- 会话历史：已关闭的会话也是历史，能翻回来看、也能接着聊 -->
      <el-radio-group v-model="scope" size="small" class="scope-switch">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="open">进行中</el-radio-button>
        <el-radio-button value="closed">已关闭</el-radio-button>
      </el-radio-group>
      <div class="conv-list">
        <div
          v-for="c in visibleConversations"
          :key="c.id"
          class="conv-item"
          :class="{ active: current && current.id === c.id, closed: c.status !== 1 }"
          @click="openConversation(c)"
          @contextmenu.prevent="openMenu($event, 'conversation', c)"
        >
          <div class="conv-title">
            {{ c.title }}
            <el-tag v-if="c.status !== 1" size="small" type="info" effect="plain">已关闭</el-tag>
          </div>
          <div class="conv-meta">
            <span>{{ c.agentid ? agentName(c.agentid) : '未绑定角色' }}</span>
            <span class="conv-time">{{ fmtTime(c.updatedat) }}</span>
          </div>
        </div>
        <el-empty v-if="!visibleConversations.length" description="暂无会话" :image-size="48" />
      </div>
      <div class="conv-pagination">
        <el-pagination v-model:current-page="conversationPage" :page-size="CONVERSATION_PAGE_SIZE"
          layout="prev, pager, next" :total="conversationTotal" @current-change="loadConversations" />
      </div>
    </div>

    <div class="ai-main">
      <template v-if="current">
        <div class="ai-header">
          <div class="header-left">
            <h2>{{ current.title }}</h2>
            <el-tag v-if="current.agentid" size="small" effect="light">{{ agentName(current.agentid) }}</el-tag>
            <el-tag v-if="current.status !== 1" size="small" type="info" effect="plain">已关闭</el-tag>
          </div>
          <el-button link size="small" type="danger" :icon="Close" @click="closeConversation">关闭会话</el-button>
        </div>

        <div ref="msgBox" class="ai-messages" @click="closeMenu">
          <div
            v-for="(m, i) in messages"
            :key="m.id || i"
            class="bubble-row"
            :class="{ mine: m.sendertype === 'USER' }"
            @contextmenu.prevent="openMenu($event, 'message', m)"
          >
            <div class="bubble-avatar" :class="{ ai: m.sendertype !== 'USER' }">
              <el-icon v-if="m.sendertype !== 'USER'"><ChatDotRound /></el-icon>
              <span v-else>{{ myNickname[0] }}</span>
            </div>
            <div class="bubble-body">
              <div class="bubble-name">{{ m.sendertype === 'USER' ? myNickname : (current.agentid ? agentName(current.agentid) : 'AI') }}</div>
              <!-- 思考中：首字还没到，先给个明确的"在跑"反馈，否则气泡一直是空的 -->
              <div v-if="m.thinking" class="bubble-thinking">
                <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                思考中 {{ (m.elapsedMs / 1000).toFixed(1) }}s
              </div>
              <div
                v-else
                class="bubble-content markdown-body"
                :class="{ mine: m.sendertype === 'USER' }"
                v-html="renderMarkdown(m.content)"
              ></div>
              <div v-if="m.timingText" class="bubble-meta">{{ m.timingText }}</div>
            </div>
          </div>
          <el-empty v-if="!messages.length" description="向 AI 发送第一条消息开始对话" :image-size="56" />
        </div>

        <!-- 右键菜单：消息与会话共用（Element Plus 没有右键菜单组件，用一个绝对定位的 div） -->
        <div v-if="menu.show" class="msg-menu" :style="{ left: menu.x + 'px', top: menu.y + 'px' }" @click.stop>
          <div v-if="menu.kind === 'message'" class="msg-menu-item" @click="removeMessage">删除这条消息</div>
          <div v-else class="msg-menu-item" @click="removeConversation">删除整个会话</div>
        </div>

        <!-- 未绑定角色的会话（早期数据）没有知识库可依托，续聊必定失败，不能给它"能继续"的假承诺 -->
        <p v-if="!canContinue" class="closed-tip closed-tip--blocked">
          该会话未绑定 AI 角色，只能查看历史，无法继续对话。请在「代码项目」登记项目并完成索引后，用它创建角色再新建会话
        </p>
        <p v-else-if="current.status !== 1" class="closed-tip">
          该会话已关闭，发送消息会自动重新打开并继续这段对话
        </p>

        <div class="ai-input">
          <el-input
            v-model="inputText"
            placeholder="输入消息，Enter 发送"
            size="large"
            clearable
            :disabled="sending || !canContinue"
            @keydown.enter.prevent="sendMsg"
          >
            <template #append>
              <el-button type="primary" :loading="sending" :disabled="!inputText.trim() || !canContinue" @click="sendMsg">发送</el-button>
            </template>
          </el-input>
        </div>
      </template>

      <div v-else class="ai-empty">
        <div class="empty-icon"><el-icon :size="36"><ChatDotRound /></el-icon></div>
        <h3>选择一个会话开始</h3>
        <p>向角色提问代码背后的业务规则与处理流程</p>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建会话</el-button>
      </div>
    </div>

    <!-- 新建会话弹窗：会话必须绑定角色，角色由代码项目创建 -->
    <el-dialog v-model="createVisible" title="新建会话" width="420px" :append-to-body="true">
      <el-form label-width="70px">
        <el-form-item label="角色" required>
          <el-select v-model="createForm.agentId" placeholder="选择 AI 角色" style="width:100%">
            <el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" :disabled="!a.apikeyenc">
              <span>{{ a.name }}</span>
              <span v-if="!a.apikeyenc" class="opt-meta">未配置 API Key</span>
            </el-option>
          </el-select>
          <p v-if="!agents.length" class="dialog-tip">
            还没有可用角色。请先在「代码项目」登记项目并完成索引，再为它创建角色。
          </p>
          <p v-else-if="agents.every(a => !a.apikeyenc)" class="dialog-tip">
            现有角色都还没配置 API Key，无法发起对话。请先到「AI 角色」页为角色补上 Key。
          </p>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="createForm.title" placeholder="留空则使用角色名称" maxlength="32" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="!createForm.agentId" @click="createConversation">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Close, ChatDotRound } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { renderMarkdown } from '@/utils/markdown'
import {
  aiCreateConversation,
  aiConversations,
  aiCloseConversation,
  aiReopenConversation,
  aiHistory,
  aiAgentList,
  aiDeleteMessage,
  aiDeleteConversation
} from '@/api'

const route = useRoute()
const router = useRouter()
const msgBox = ref(null)
const inputText = ref('')
const messages = ref([])
const conversations = ref([])
const conversationPage = ref(1)
const conversationTotal = ref(0)
const CONVERSATION_PAGE_SIZE = 20
const agents = ref([])
const current = ref(null)
const sending = ref(false)
const creating = ref(false)
const createVisible = ref(false)
const createForm = ref({ title: '', agentId: null })
/** 会话历史筛选：默认只看进行中，历史/已关闭按需切过去 */
const scope = ref('open')

/** 右键菜单：kind 决定菜单项（'message' 删单条 / 'conversation' 删整个会话） */
const menu = ref({ show: false, x: 0, y: 0, kind: null, target: null })

/** 侧栏展示的会话；状态筛选已经下推到服务端，保留过滤只作为数据防护。 */
const visibleConversations = computed(() => {
  if (scope.value === 'open') return conversations.value.filter(c => c.status === 1)
  if (scope.value === 'closed') return conversations.value.filter(c => c.status !== 1)
  return conversations.value
})

/** 能否继续对话：会话必须绑定了角色（未绑定的早期会话只有历史，没有知识库可依托） */
const canContinue = computed(() => !!(current.value && current.value.agentid))

const myNickname = (() => {
  try { return JSON.parse(localStorage.getItem('userInfo') || '{}').nickname || '我' } catch { return '我' }
})()

function agentName(agentId) {
  const a = agents.value.find(x => x.id === agentId)
  return a ? a.name : 'AI'
}

function fmtTime(t) {
  return t ? dayjs(t).format('MM-DD HH:mm') : ''
}

function scrollBottom() {
  nextTick(() => { if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight })
}

async function loadConversations(page = conversationPage.value) {
  conversationPage.value = page
  try {
    const status = scope.value === 'open' ? 1 : (scope.value === 'closed' ? 0 : null)
    const res = await aiConversations({ page, pageSize: CONVERSATION_PAGE_SIZE, status })
    if (res.code === 200) {
      const data = res.data || {}
      conversations.value = data.records || []
      conversationTotal.value = Number(data.total || 0)
    }
  } catch { /* 忽略 */ }
}

watch(scope, async () => {
  conversationPage.value = 1
  await loadConversations(1)
})

async function loadAgents() {
  try {
    const res = await aiAgentList()
    if (res.code === 200) agents.value = res.data || []
  } catch { /* 忽略 */ }
}

async function openConversation(c) {
  current.value = c
  messages.value = []
  try {
    const res = await aiHistory(c.id)
    if (res.code === 200) messages.value = res.data || []
  } catch { /* 忽略 */ }
  scrollBottom()
}

function openCreate() {
  createForm.value = { title: '', agentId: null }
  // 只有一个角色且它配了 Key 时才预选——没配 Key 的角色选不了，预选只会让人白高兴
  if (agents.value.length === 1 && agents.value[0].apikeyenc) {
    createForm.value.agentId = agents.value[0].id
  }
  createVisible.value = true
}

async function createConversation() {
  if (!createForm.value.agentId) {
    ElMessage.warning('请选择 AI 角色：会话必须绑定角色才能对话')
    return
  }
  creating.value = true
  try {
    const res = await aiCreateConversation({ title: createForm.value.title, agentId: createForm.value.agentId })
    if (res.code === 200) {
      createVisible.value = false
      ElMessage.success('会话已创建')
      await loadConversations()
      await openConversation(res.data)
    } else {
      ElMessage.error(res.msg || '创建失败')
    }
  } catch {
    ElMessage.error('创建失败')
  } finally {
    creating.value = false
  }
}

async function closeConversation() {
  if (!current.value) return
  // 关闭是终态：会话不再出现在列表里，也不能再发消息（后端已按 Status 拦），先说清楚
  try {
    await ElMessageBox.confirm('关闭后该会话将从列表中移除，历史消息仍保留在库中。确定关闭？',
      '关闭会话', { type: 'warning' })
  } catch { return }
  try {
    await aiCloseConversation({ conversationId: current.value.id })
  } catch { /* 忽略 */ }
  current.value = null
  messages.value = []
  await loadConversations()
}

async function sendMsg() {
  const text = inputText.value.trim()
  if (!text || sending.value || !current.value) return
  if (!current.value.agentid) {
    ElMessage.warning('该会话未绑定 AI 角色，无法继续对话')
    return
  }
  sending.value = true
  inputText.value = ''
  // 占位气泡的下标：catch 里也要用它写失败原因，所以必须在 try 之外声明
  const aiIdx = messages.value.length + 1
  const startedAt = Date.now()
  // 首字未到之前的"思考中"计时器，收到首个增量或结束时清掉
  let tickTimer = null
  const stopTick = () => {
    if (tickTimer) {
      clearInterval(tickTimer)
      tickTimer = null
    }
  }

  try {
    // 已关闭的会话先"续上"：后端对 status!=1 的会话直接拒收消息，
    // 这里把重新打开做在发送动作里，用户不需要先去点别的按钮
    if (current.value.status !== 1) {
      const reopenRes = await aiReopenConversation({ conversationId: current.value.id })
      if (reopenRes.code !== 200) {
        ElMessage.error(reopenRes.msg || '该会话已关闭，重新打开失败')
        inputText.value = text
        return
      }
      current.value.status = 1
      await loadConversations()
    }

    // 本地先追加用户消息与 AI 占位气泡；占位气泡先进入"思考中"状态
    messages.value.push({ sendertype: 'USER', content: text, createtime: dayjs().format('YYYY-MM-DD HH:mm:ss') })
    messages.value.push({ sendertype: 'AI', content: '', thinking: true, startedAt, elapsedMs: 0, timingText: '' })
    tickTimer = setInterval(() => {
      const bubble = messages.value[aiIdx]
      if (bubble && bubble.thinking) bubble.elapsedMs = Date.now() - startedAt
    }, 100)
    scrollBottom()

    const token = localStorage.getItem('token') || ''
    // 与 axios baseURL 保持一致：开发环境 /api（vite 代理去掉），生产环境直接请求
    const apiBase = import.meta.env.PROD ? '' : '/api'
    const res = await fetch(apiBase + '/ai/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: token },
      body: JSON.stringify({ conversationId: current.value.id, message: text })
    })
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // 按空行切分 SSE 事件，提取 data: / event:error:
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const chunk = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        handleSseChunk(chunk, aiIdx)
      }
      scrollBottom()
    }
    if (buffer.trim()) handleSseChunk(buffer, aiIdx)
    scrollBottom()
    // 收尾：思考中的状态一定要在这里落定，否则气泡会一直停在"思考中"
    const bubble = messages.value[aiIdx]
    if (bubble) {
      bubble.thinking = false
      const totalMs = Date.now() - startedAt
      bubble.timingText = bubble.thinkingMs != null
        ? `思考 ${(bubble.thinkingMs / 1000).toFixed(1)}s · 本次回答 ${(totalMs / 1000).toFixed(1)}s`
        : `本次回答 ${(totalMs / 1000).toFixed(1)}s`
    }
    await backfillMessageIds()
    await loadConversations()
  } catch (e) {
    if (messages.value[aiIdx]) {
      const bubble = messages.value[aiIdx]
      bubble.thinking = false
      bubble.content = bubble.content || `（发送失败：${(e && e.message) || '网络错误'}）`
    }
  } finally {
    stopTick()
    sending.value = false
  }
}

/**
 * 解析单条 SSE 事件块。
 * <p>
 * 事件约定：`delta` 是增量片段，逐段追加即打字机效果；`reset` 要求整段替换（安全拦截）；
 * `error` 是失败原因；`done` 不带 data，仅表示结束。
 * 按 SSE 规范：一个事件可以有多行 `data:`，必须全部取出后用换行拼回——
 * 只取第一行会丢掉多行 Markdown 回复的正文（服务端已按行拆分发送）。
 */
function handleSseChunk(chunk, aiIdx) {
  const lines = chunk.split('\n')
  const event = lines.find(l => l.startsWith('event:')) || ''
  const dataLines = lines.filter(l => l.startsWith('data:'))
  if (!dataLines.length) return
  // 服务端按 "data:" + 内容 写出，不带前导空格，所以直接 slice(5)；
  // 这里不做 trim，避免吃掉 Markdown 的缩进和空行
  const data = dataLines.map(l => l.slice(5)).join('\n')
  // done 事件的 data 是空的，这里直接跳过
  if (!data) return
  // 安全拦截：服务端要求整段替换（增量已经显示出去了，收不回来，只能纠正）
  if (event.includes('reset')) {
    if (messages.value[aiIdx]) messages.value[aiIdx].content = data
    return
  }
  if (event.includes('error')) {
    ElMessage.error(data)
    // 流式请求走的是原生 fetch，不经过 axios 拦截器，登录失效得自己处理：
    // 否则用户只会看到一句"发送失败"，停在原地反复重试
    if (data.includes('登录已过期') || data.includes('token')) {
      localStorage.removeItem('token')
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('userInfo')
      router.push('/login')
      return
    }
    // 把后端给的具体原因（"请先为角色配置 API Key"、"项目尚未完成索引"等）留在气泡里，
    // 只弹 toast 的话一闪而过，用户只看到一句没有信息量的"发送失败"
    if (messages.value[aiIdx]) {
      const partial = messages.value[aiIdx].content
      messages.value[aiIdx].content = partial ? `${partial}\n\n（${data}）` : `（${data}）`
    }
    return
  }
  const bubble = messages.value[aiIdx]
  if (!bubble) return
  // 第一个增量到了：记下思考耗时并退出"思考中"状态
  if (bubble.thinking) {
    bubble.thinking = false
    bubble.thinkingMs = Date.now() - (bubble.startedAt || Date.now())
  }
  bubble.content += data
}

/**
 * 用服务端历史给本地消息补 ID。
 * <p>
 * 发送时本地先追加了用户消息和 AI 气泡，这两个对象没有服务端 ID，
 * 而右键删除要靠 ID 定位消息——不补的话"刚聊完的消息"永远删不掉。
 * 按「发送方 + 内容」匹配，只补 ID 不重建列表：重建会把刚算出来的耗时文案丢掉。
 * 匹配不上（例如消息被截断过）就跳过，只是这条删不了，不影响对话。
 */
async function backfillMessageIds() {
  if (!current.value) return
  try {
    const res = await aiHistory(current.value.id)
    if (res.code !== 200) return
    const server = res.data || []
    const used = new Set(messages.value.filter(m => m.id).map(m => String(m.id)))
    for (const m of messages.value) {
      if (m.id) continue
      const hit = server.find(s => !used.has(String(s.id))
        && s.sendertype === m.sendertype && s.content === m.content)
      if (hit) {
        m.id = hit.id
        used.add(String(hit.id))
      }
    }
  } catch {
    /* 补不上 ID 只影响右键删除，不打断对话 */
  }
}

/** 右键：打开自定义菜单（消息与会话共用） */
function openMenu(event, kind, target) {
  if (kind === 'message' && !target.id) return // 本地占位消息没有 id，删不了
  menu.value = { show: true, x: event.clientX, y: event.clientY, kind, target }
}

function closeMenu() {
  if (menu.value.show) menu.value = { show: false, x: 0, y: 0, kind: null, target: null }
}

async function removeMessage() {
  const message = menu.value.target
  closeMenu()
  if (!message || !message.id) return
  try {
    await ElMessageBox.confirm('确定删除这条消息？删除后不会出现在历史里，也不会再作为上下文发给模型。',
      '删除消息', { type: 'warning' })
  } catch { return }
  try {
    const res = await aiDeleteMessage(message.id)
    if (res.code !== 200) {
      ElMessage.error(res.msg || '删除失败')
      return
    }
    ElMessage.success('已删除')
    messages.value = messages.value.filter(m => m.id !== message.id)
  } catch {
    ElMessage.error('删除失败')
  }
}

async function removeConversation() {
  const conv = menu.value.target
  closeMenu()
  if (!conv) return
  try {
    await ElMessageBox.confirm(`确定删除会话「${conv.title}」？该会话下的全部消息会一起删除，且不可恢复。`,
      '删除会话', { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' })
  } catch { return }
  try {
    const res = await aiDeleteConversation(conv.id)
    if (res.code !== 200) {
      ElMessage.error(res.msg || '删除失败')
      return
    }
    ElMessage.success('会话已删除')
    // 删的正好是当前打开的那条时，把右侧清空
    if (current.value && current.value.id === conv.id) {
      current.value = null
      messages.value = []
    }
    await loadConversations()
  } catch {
    ElMessage.error('删除失败')
  }
}

onMounted(async () => {
  // 点页面任意处收起右键菜单
  window.addEventListener('click', closeMenu)
  await Promise.all([loadAgents(), loadConversations()])
  // 从「AI 角色」页点「对话」跳过来时带着 conversationId，直接把这条会话打开，
  // 否则用户落到一个什么都不选中的空页面上，看起来像没反应
  const targetId = route.query.conversationId
  if (!targetId) return
  const target = conversations.value.find(c => String(c.id) === String(targetId))
  if (target) await openConversation(target)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', closeMenu)
})
</script>

<style scoped>
.ai-chat {
  height: 100%; display: flex; gap: 12px; min-height: 0;
}

/* ====== 左侧会话列表 ====== */
.ai-sidebar {
  width: 220px; flex-shrink: 0; display: flex; flex-direction: column;
  background: var(--bg-white); border: 1px solid var(--border-light);
  border-radius: var(--radius-md); overflow: hidden;
}
.new-btn { margin: 12px; }
/* 会话历史筛选 */
.scope-switch { margin: 0 12px 10px; width: calc(100% - 24px); }
.scope-switch :deep(.el-radio-button__inner) { padding: 5px 8px; font-size: 12px; }
.conv-list { flex: 1; overflow-y: auto; padding: 0 8px 12px; }
.conv-pagination { flex-shrink: 0; padding: 8px 4px 10px; border-top: 1px solid var(--border-light); }
.conv-pagination :deep(.el-pagination) { justify-content: center; }
.conv-item {
  padding: 10px 12px; border-radius: var(--radius-md); cursor: pointer;
  border-left: 2px solid transparent;
  transition: background .15s;
}
.conv-item:hover { background: var(--bg-light); }
/* 已关闭的会话仍可翻阅、可续聊，但视觉上要弱一档 */
.conv-item.closed { opacity: .65; }
.conv-item.active {
  background: rgba(37, 99, 235, .06);
  border-left-color: var(--accent-color);
}
.conv-title {
  font-size: 13px; font-weight: 600; color: var(--text-primary);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  display: flex; align-items: center; gap: 4px;
}
.conv-title .el-tag { flex-shrink: 0; }
.conv-meta { display: flex; justify-content: space-between; font-size: 11px; color: var(--text-muted); margin-top: 2px; }
.conv-time { flex-shrink: 0; }

/* ====== 主区域 ====== */
.ai-main {
  flex: 1; min-width: 0; display: flex; flex-direction: column;
  background: var(--bg-white); border: 1px solid var(--border-light);
  border-radius: var(--radius-md); overflow: hidden;
}
.ai-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 16px; border-bottom: 1px solid var(--border-light); flex-shrink: 0;
}
.header-left { display: flex; align-items: center; gap: 8px; }
.ai-header h2 { font-size: 15px; font-weight: 600; color: var(--text-primary); margin: 0; }

.ai-messages {
  flex: 1; overflow-y: auto; padding: 16px;
  display: flex; flex-direction: column; gap: 14px;
}
.bubble-row { display: flex; gap: 10px; }
.bubble-row.mine { flex-direction: row-reverse; }
.bubble-avatar {
  width: 32px; height: 32px; border-radius: var(--radius-md); flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: var(--accent-color);
  color: #fff; font-size: 13px; font-weight: 600;
}
.bubble-avatar.ai { background: #475569; }
.bubble-body { max-width: 78%; }
.bubble-row.mine .bubble-body { text-align: right; }
.bubble-name { font-size: 12px; color: var(--text-muted); margin-bottom: 3px; }
.bubble-content {
  display: inline-block; padding: 12px 16px; border-radius: var(--radius-md);
  background: var(--bg-light); border: 1px solid var(--border-light);
  font-size: 13px; line-height: 1.7;
  color: var(--text-primary); word-break: break-word; text-align: left;
}
.bubble-content.mine { background: var(--accent-color); border-color: var(--accent-color); color: #fff; }

/* ====== 思考中 / 耗时 ====== */
.bubble-thinking {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 9px 13px; border-radius: var(--radius-md);
  background: var(--bg-light); border: 1px solid var(--border-light);
  font-size: 12px; color: var(--text-muted);
}
.bubble-thinking .dot {
  width: 4px; height: 4px; border-radius: 50%; background: var(--text-muted);
  animation: think-blink 1.2s infinite ease-in-out;
}
.bubble-thinking .dot:nth-child(2) { animation-delay: .2s; }
.bubble-thinking .dot:nth-child(3) { animation-delay: .4s; margin-right: 2px; }
@keyframes think-blink { 0%, 80%, 100% { opacity: .25 } 40% { opacity: 1 } }
@media (prefers-reduced-motion: reduce) {
  .bubble-thinking .dot { animation: none; opacity: .6; }
}

.bubble-meta { margin-top: 4px; font-size: 11px; color: var(--text-muted); }

/* ====== 消息右键菜单 ====== */
.msg-menu {
  position: fixed; z-index: var(--z-overlay, 2000);
  min-width: 132px; padding: 4px 0;
  background: var(--bg-white); border: 1px solid var(--border-light);
  border-radius: var(--radius-md); box-shadow: var(--shadow-lg);
  font-size: 13px;
}
.msg-menu-item { padding: 7px 14px; cursor: pointer; color: var(--danger-color); }
.msg-menu-item:hover { background: var(--bg-light); }

/* Markdown 正文样式在文件末尾的**非 scoped** 样式块里，原因见那里的注释 */

.ai-input { padding: 12px 16px; border-top: 1px solid var(--border-light); flex-shrink: 0; }
.closed-tip {
  margin: 0; padding: 8px 16px; flex-shrink: 0;
  font-size: 12px; color: var(--warning-color);
  background: rgba(230, 162, 60, .08); border-top: 1px solid var(--border-light);
}
.closed-tip--blocked { color: var(--text-secondary); background: var(--bg-light); }

/* 空态 */
.ai-empty {
  flex: 1; display: flex; flex-direction: column; align-items: center;
  justify-content: center; gap: 8px; color: var(--text-secondary);
}
.empty-icon {
  width: 64px; height: 64px; border-radius: var(--radius-xl);
  background: rgba(37, 99, 235, .08); color: var(--accent-color);
  display: flex; align-items: center; justify-content: center;
}
.ai-empty h3 { font-size: 16px; font-weight: 600; margin: 8px 0 0; color: var(--text-primary); }
.ai-empty p { font-size: 13px; color: var(--text-muted); margin: 0 0 8px; }

.dialog-tip { margin: 6px 0 0; font-size: 12px; color: var(--text-muted); line-height: 1.6; }

/* 角色下拉里对没配 Key 的角色做说明 */
.opt-meta { float: right; color: var(--warning-color); font-size: 12px; }

@media (max-width: 768px) {
  .ai-sidebar { width: 160px; }
}
</style>

<!--
  Markdown 正文样式必须放在**非 scoped** 的样式块里。

  原因：正文是 renderMarkdown() 产出的 HTML 经 v-html 插入的，这些节点不经过 Vue 编译，
  身上没有 scoped 属性，而 `<style scoped>` 会把 `.markdown-body h2` 编译成
  `.markdown-body h2[data-v-xxx]` —— h2 上没有这个属性，规则一条都匹配不上。
  结果就是标题、列表全用浏览器默认样式，再被全局 reset 抹掉内外边距，
  整段文字糊成一片（就是"挤在一起"的观感）。

  类名 .markdown-body 只在本组件出现，放全局不会影响别处。
-->
<style>
.markdown-body { white-space: normal; line-height: 1.75; }
.markdown-body > *:first-child { margin-top: 0; }
.markdown-body > *:last-child { margin-bottom: 0; }
.markdown-body p { margin: 0 0 10px; }
.markdown-body h1, .markdown-body h2, .markdown-body h3,
.markdown-body h4, .markdown-body h5, .markdown-body h6 {
  margin: 20px 0 10px; font-weight: 600; line-height: 1.4; letter-spacing: .2px;
}
.markdown-body > h1:first-child, .markdown-body > h2:first-child,
.markdown-body > h3:first-child { margin-top: 0; }
.markdown-body h1 { font-size: 19px; }
/* 二级标题带一条下划线，长答复里靠它分段，比单纯加粗清楚得多 */
.markdown-body h2 {
  font-size: 16px; padding-bottom: 6px;
  border-bottom: 1px solid #E2E8F0;
}
.markdown-body h3 { font-size: 15px; }
.markdown-body h4 { font-size: 14px; }
.markdown-body h5, .markdown-body h6 { font-size: 13px; }
.markdown-body ul, .markdown-body ol { margin: 0 0 12px; padding-left: 22px; }
.markdown-body li { margin: 0 0 8px; padding-left: 2px; }
.markdown-body li:last-child { margin-bottom: 0; }
.markdown-body li strong { font-weight: 600; }
.markdown-body code {
  padding: 1.5px 5px; border-radius: 2px;
  background: rgba(15, 23, 42, .07); font-size: 12px;
  font-family: Consolas, Monaco, "Courier New", monospace;
}
.markdown-body pre {
  margin: 0 0 12px; padding: 12px 14px; overflow-x: auto;
  background: rgba(15, 23, 42, .06); border-radius: 4px;
}
.markdown-body pre code { padding: 0; background: none; font-size: 12px; line-height: 1.6; }
.markdown-body blockquote {
  margin: 0 0 12px; padding: 6px 12px;
  border-left: 3px solid #CBD5E1; color: #475569;
  background: rgba(15, 23, 42, .03); border-radius: 0 2px 2px 0;
}
.markdown-body table {
  margin: 0 0 12px; border-collapse: collapse; font-size: 12px; width: 100%;
}
.markdown-body th, .markdown-body td {
  padding: 6px 10px; border: 1px solid #E2E8F0; text-align: left; vertical-align: top;
}
.markdown-body th { background: rgba(15, 23, 42, .04); font-weight: 600; white-space: nowrap; }
.markdown-body hr { margin: 16px 0; border: none; border-top: 1px solid #E2E8F0; }
.markdown-body a { color: #2563EB; text-decoration: underline; }

/* 用户气泡是深底，反色处理代码/表格底衬 */
.bubble-content.mine .markdown-body code,
.bubble-content.mine .markdown-body pre { background: rgba(255, 255, 255, .18); }
.bubble-content.mine .markdown-body th { background: rgba(255, 255, 255, .12); }
.bubble-content.mine .markdown-body a { color: #fff; }
</style>

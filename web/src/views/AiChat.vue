<script>
export default { name: 'AiChat' }
</script>

<template>
  <div class="ai-chat">
    <div class="ai-sidebar">
      <el-button type="primary" class="new-btn" :icon="Plus" @click="openCreate">新建会话</el-button>
      <div class="conv-list">
        <div
          v-for="c in conversations"
          :key="c.id"
          class="conv-item"
          :class="{ active: current && current.id === c.id }"
          @click="openConversation(c)"
        >
          <div class="conv-title">{{ c.title }}</div>
          <div class="conv-meta">
            <span>{{ c.agentid ? agentName(c.agentid) : '未绑定角色' }}</span>
            <span class="conv-time">{{ fmtTime(c.updatedat) }}</span>
          </div>
        </div>
        <el-empty v-if="!conversations.length" description="暂无会话" :image-size="48" />
      </div>
    </div>

    <div class="ai-main">
      <template v-if="current">
        <div class="ai-header">
          <div class="header-left">
            <h2>{{ current.title }}</h2>
            <el-tag v-if="current.agentid" size="small" effect="light">{{ agentName(current.agentid) }}</el-tag>
          </div>
          <el-button link size="small" type="danger" :icon="Close" @click="closeConversation">关闭会话</el-button>
        </div>

        <div ref="msgBox" class="ai-messages">
          <div v-for="(m, i) in messages" :key="i" class="bubble-row" :class="{ mine: m.sendertype === 'USER' }">
            <div class="bubble-avatar" :class="{ ai: m.sendertype !== 'USER' }">
              <el-icon v-if="m.sendertype !== 'USER'"><ChatDotRound /></el-icon>
              <span v-else>{{ myNickname[0] }}</span>
            </div>
            <div class="bubble-body">
              <div class="bubble-name">{{ m.sendertype === 'USER' ? myNickname : (current.agentid ? agentName(current.agentid) : 'AI') }}</div>
              <div class="bubble-content" :class="{ mine: m.sendertype === 'USER' }">{{ m.content }}</div>
            </div>
          </div>
          <el-empty v-if="!messages.length" description="向 AI 发送第一条消息开始对话" :image-size="56" />
        </div>

        <div class="ai-input">
          <el-input
            v-model="inputText"
            placeholder="输入消息，Enter 发送"
            size="large"
            clearable
            :disabled="sending"
            @keydown.enter.prevent="sendMsg"
          >
            <template #append>
              <el-button type="primary" :loading="sending" :disabled="!inputText.trim()" @click="sendMsg">发送</el-button>
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
            <el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
          <p v-if="!agents.length" class="dialog-tip">
            还没有可用角色。请先在「代码项目」登记项目并完成索引，再为它创建角色。
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
import { ref, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Close, ChatDotRound } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import {
  aiCreateConversation,
  aiConversations,
  aiCloseConversation,
  aiHistory,
  aiAgentList
} from '@/api'

const msgBox = ref(null)
const inputText = ref('')
const messages = ref([])
const conversations = ref([])
const agents = ref([])
const current = ref(null)
const sending = ref(false)
const creating = ref(false)
const createVisible = ref(false)
const createForm = ref({ title: '', agentId: null })

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

async function loadConversations() {
  try {
    const res = await aiConversations()
    if (res.code === 200) conversations.value = res.data || []
  } catch { /* 忽略 */ }
}

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
  // 只有一个角色时直接预选
  if (agents.value.length === 1) {
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
  sending.value = true
  inputText.value = ''

  // 本地先追加用户消息与 AI 占位气泡
  const aiIdx = messages.value.length + 1
  messages.value.push({ sendertype: 'USER', content: text, createtime: dayjs().format('YYYY-MM-DD HH:mm:ss') })
  messages.value.push({ sendertype: 'AI', content: '' })
  scrollBottom()

  try {
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
  } catch {
    if (messages.value[aiIdx]) messages.value[aiIdx].content = messages.value[aiIdx].content || '（发送失败，请稍后重试）'
  } finally {
    sending.value = false
  }
}

/**
 * 解析单条 SSE 事件块。
 * 按 SSE 规范：一个事件可以有多行 `data:`，必须全部取出后用换行拼回。
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
  if (!data) return
  if (event.includes('error')) {
    ElMessage.error(data)
    if (messages.value[aiIdx]) messages.value[aiIdx].content = messages.value[aiIdx].content || '（发送失败，请稍后重试）'
    return
  }
  if (messages.value[aiIdx]) messages.value[aiIdx].content += data
}

onMounted(() => {
  loadAgents()
  loadConversations()
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
.conv-list { flex: 1; overflow-y: auto; padding: 0 8px 12px; }
.conv-item {
  padding: 10px 12px; border-radius: var(--radius-md); cursor: pointer;
  border-left: 2px solid transparent;
  transition: background .15s;
}
.conv-item:hover { background: var(--bg-light); }
.conv-item.active {
  background: rgba(37, 99, 235, .06);
  border-left-color: var(--accent-color);
}
.conv-title { font-size: 13px; font-weight: 600; color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
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
.bubble-body { max-width: 70%; }
.bubble-row.mine .bubble-body { text-align: right; }
.bubble-name { font-size: 12px; color: var(--text-muted); margin-bottom: 3px; }
.bubble-content {
  display: inline-block; padding: 9px 13px; border-radius: var(--radius-md);
  background: var(--bg-light); border: 1px solid var(--border-light);
  font-size: 13px; line-height: 1.6;
  color: var(--text-primary); word-break: break-word; text-align: left; white-space: pre-wrap;
}
.bubble-content.mine { background: var(--accent-color); border-color: var(--accent-color); color: #fff; }

.ai-input { padding: 12px 16px; border-top: 1px solid var(--border-light); flex-shrink: 0; }

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

@media (max-width: 768px) {
  .ai-sidebar { width: 160px; }
}
</style>

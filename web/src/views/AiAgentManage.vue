<script>
export default { name: 'AiAgentManage' }
</script>

<template>
  <div class="agent-manage">
    <div class="page-head">
      <div>
        <h2>AI 角色</h2>
        <p>角色关联一个或多个代码项目（前后端可共用一个角色），且必须配置 API Key 才能发起对话</p>
      </div>
      <el-button type="primary" :icon="Plus" :disabled="!creatableProjects.length" @click="openCreate">
        {{ createButtonText }}
      </el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="agents"
      border
      empty-text="暂无角色。请先在「代码项目」登记项目，再回到此处为项目创建角色"
    >
      <el-table-column label="角色" min-width="200">
        <template #default="{ row }">
          <div class="agent-cell">
            <span class="agent-mark" :style="{ background: row.avatar || defaultMark }">{{ (row.name || '?').charAt(0) }}</span>
            <span>{{ row.name }}</span>
          </div>
        </template>
      </el-table-column>
      <!-- 一个角色可以关联多个项目（例如前后端共用一个角色） -->
      <el-table-column label="关联项目" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ projectNames(row.projectIds) }}</template>
      </el-table-column>
      <el-table-column label="模型" width="150">
        <template #default="{ row }">{{ row.model || '默认' }}</template>
      </el-table-column>
      <el-table-column label="API Key" width="120">
        <template #default="{ row }">
          <el-tag :type="row.apikeyenc ? 'success' : 'danger'" effect="plain" size="small">
            {{ row.apikeyenc ? '已配置' : '未配置' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :loading="startingId === row.id" @click="startChat(row)">对话</el-button>
          <el-button link @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removeAgent(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 创建 / 编辑角色弹窗；align-center 让弹窗垂直居中 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑角色' : '新建角色'" width="540px" align-center :append-to-body="true">
      <el-form label-width="90px">
        <el-form-item label="关联项目" required>
          <el-select v-model="form.projectIds" multiple collapse-tags collapse-tags-tooltip
            placeholder="可多选：一个角色可以覆盖多个项目（如前端 + 后端）" style="width:100%">
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id"
              :disabled="p.status !== 'READY'">
              <span>{{ p.name }}</span>
              <span class="opt-meta">{{ projectStatusText(p.status) }}</span>
            </el-option>
          </el-select>
          <p class="dialog-tip-inline">
            角色会在所选的<b>全部项目</b>里检索知识库，所以项目必须都已完成索引。
            <template v-if="projects.some(p => p.status !== 'READY')">
              灰显的项目尚未完成索引，请先在「代码项目」页排队索引。
            </template>
          </p>
        </el-form-item>

        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="角色名称，如 订单业务助手" maxlength="32" />
        </el-form-item>

        <el-form-item label="标识色">
          <el-input v-model="form.avatar" placeholder="账号列表色块，填 CSS 颜色，留空用默认色">
            <template #prefix>
              <span class="mark-preview" :style="{ background: form.avatar || defaultMark }">{{ (form.name || '?').charAt(0) }}</span>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="角色设定">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="4"
            placeholder="系统提示词，如：你是订单业务助手，只解释业务规则与处理流程，不输出源代码、SQL 和配置" />
        </el-form-item>

        <el-form-item label="厂商" required>
          <el-select v-model="form.provider" style="width:100%" @change="onProviderChange">
            <el-option v-for="p in PROVIDERS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>

        <el-form-item label="接口地址">
          <el-input v-model="form.apiBaseUrl" :placeholder="form.provider === 'custom' ? '必填，例如 https://your-gateway/v1' : '留空使用该厂商默认地址'" />
        </el-form-item>

        <el-form-item label="模型">
          <div class="model-row">
            <el-select
              v-model="form.model"
              filterable
              allow-create
              default-first-option
              clearable
              class="model-select"
              placeholder="选择或直接输入模型名"
            >
              <el-option v-for="m in modelSelectOptions" :key="m" :label="m" :value="m" />
            </el-select>
            <el-button link type="primary" :loading="loadingModels" @click="fetchModels">获取模型</el-button>
          </div>
          <p v-if="modelSource" class="model-hint" :class="modelSource === 'PROVIDER' ? 'ok' : 'warn'">
            <template v-if="modelSource === 'PROVIDER'">已从厂商接口获取 {{ modelOptions.length }} 个模型</template>
            <template v-else-if="modelSource === 'PRESET'">{{ modelHint }}</template>
            <template v-else>未获取到模型列表，请手动输入模型名</template>
          </p>
        </el-form-item>

        <el-form-item label="API Key" required>
          <el-input v-model="form.apiKey" type="password" show-password clearable
            :placeholder="form.id ? '留空表示不修改' : '模型服务 API Key，未配置无法发起对话'" />
          <!-- 编辑时输入框必然是空的（不回显 Key），不加这行会被当成"未配置" -->
          <p v-if="form.id" class="key-state" :class="form.apikeyenc ? 'ok' : 'warn'">
            <template v-if="form.apikeyenc">当前已配置（{{ form.apikeyenc }}），留空表示不修改</template>
            <template v-else>当前尚未配置，未配置 Key 的角色无法发起对话</template>
          </p>
        </el-form-item>

        <el-form-item>
          <!-- 标签里带问号：温度该填多少没有标准答案，把分场景的推荐值直接挂在旁边 -->
          <template #label>
            <span class="label-with-help">
              采样温度
              <el-tooltip placement="top" :show-after="100" :width="300">
                <template #content>
                  <div class="temp-tip">
                    <p class="temp-tip-intro">
                      温度越低回答越稳定、越贴事实，越高越发散。按用途选一个区间即可，不确定就用默认 0.7。
                    </p>
                    <table class="temp-tip-table">
                      <tbody>
                        <tr v-for="t in TEMPERATURE_TIPS" :key="t.scene">
                          <td>{{ t.scene }}</td>
                          <td class="temp-tip-value">{{ t.range }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </template>
                <el-icon class="help-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAgent">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, QuestionFilled } from '@element-plus/icons-vue'
import {
  aiAgentList, aiAgentCreate, aiAgentUpdate, aiAgentDelete, aiAgentModels,
  aiCreateConversation, aiConversations, codeProjectList
} from '@/api'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const agents = ref([])
const projects = ref([])
const formVisible = ref(false)
const saving = ref(false)
const loadingModels = ref(false)
/** 正在打开对话的角色 id，仅用于按钮 loading */
const startingId = ref(null)
const modelOptions = ref([])
/** 模型来源：PROVIDER=厂商接口 / PRESET=预置兜底 / EMPTY=没拿到，用于提示用户 */
const modelSource = ref(null)
const modelHint = ref('')
const defaultMark = '#2563EB'
const form = ref(emptyForm())

/** 采样温度的分场景推荐值，挂在标签的问号 tooltip 里 */
const TEMPERATURE_TIPS = [
  { scene: 'SQL', range: '0～0.2' },
  { scene: 'Java / JS 编程', range: '0～0.3' },
  { scene: '技术问题', range: '0～0.3' },
  { scene: '数据分析', range: '0～0.3' },
  { scene: '数学计算', range: '0～0.2' },
  { scene: '文档编写', range: '0.3～0.6' },
  { scene: '普通聊天', range: '0.5～0.8' },
  { scene: '头脑风暴', range: '0.7～1.0' },
  { scene: '小说/创意写作', range: '0.8～1.2' },
  { scene: '创意广告文案', range: '0.8～1.2' }
]

/** 厂商选项；baseUrl 在切换厂商时自动带入「接口地址」，custom 留空强制手填 */
const PROVIDERS = [
  { value: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com' },
  { value: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1' },
  { value: 'qwen', label: '通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { value: 'custom', label: '自定义（OpenAI 兼容）', baseUrl: '' }
]

function emptyForm() {
  return {
    id: null, projectIds: [], name: '', avatar: '', systemPrompt: '',
    provider: 'deepseek', apiBaseUrl: 'https://api.deepseek.com',
    model: '', temperature: 0.7, apiKey: '', apikeyenc: ''
  }
}

/** 下拉选项 = 接口返回的列表；若当前已选模型不在其中（历史值/手输），补到最前面 */
const modelSelectOptions = computed(() => {
  const list = [...modelOptions.value]
  const current = form.value.model
  if (current && !list.includes(current)) list.unshift(current)
  return list
})

/**
 * 能建角色的项目：已完成索引即可。
 * 一个项目可以配置多个角色，所以不再排除"已建过角色"的项目。
 */
const creatableProjects = computed(() => projects.value.filter(p => p.status === 'READY'))

/** 按钮文案要说清为什么不能点，不能一律写"请先创建代码项目" */
const createButtonText = computed(() => {
  if (creatableProjects.value.length) return '新建角色'
  return projects.value.length ? '项目完成索引后才能创建角色' : '请先创建代码项目'
})

function projectName(projectId) {
  const p = projects.value.find(x => x.id === projectId)
  return p ? p.name : '—'
}

/** 角色关联的多个项目名；空数组说明角色还没关联项目 */
function projectNames(projectIds) {
  if (!projectIds || !projectIds.length) return '未关联项目'
  return projectIds.map(projectName).join('、')
}

/** 角色关联的项目实体（找不到的会被过滤掉，例如项目已删除） */
function linkedProjects(agent) {
  return (agent.projectIds || []).map(id => projects.value.find(p => p.id === id)).filter(Boolean)
}

function projectStatusText(status) {
  return ({ PENDING: '待索引', QUEUED: '排队中', INDEXING: '索引中', READY: '已就绪', FAILED: '索引失败' })[status] || status || '未知'
}

async function loadAll() {
  loading.value = true
  try {
    const [agentRes, projectRes] = await Promise.all([aiAgentList(), codeProjectList()])
    if (agentRes.code === 200) agents.value = agentRes.data || []
    if (projectRes.code === 200) projects.value = projectRes.data || []
  } catch {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

/** 切换厂商：自动带出该厂商默认接口地址，并重新拉一次模型列表 */
function onProviderChange(value) {
  const provider = PROVIDERS.find(p => p.value === value)
  form.value.apiBaseUrl = provider ? provider.baseUrl : ''
  fetchModels()
}

/**
 * 拉取模型列表：后端先真调厂商 /models，失败回退预置；始终 200，不阻断表单。
 * 返回体带 source，用来告诉用户拿到的是真实列表还是预置兜底——不做静默回退。
 */
async function fetchModels() {
  loadingModels.value = true
  try {
    const res = await aiAgentModels({
      provider: form.value.provider || 'deepseek',
      apiBaseUrl: form.value.apiBaseUrl || null,
      apiKey: form.value.apiKey || null,
      agentId: form.value.id || null
    })
    if (res.code === 200) {
      const data = res.data || {}
      modelOptions.value = data.models || []
      modelSource.value = data.source || null
      modelHint.value = data.message || ''
    }
  } catch {
    /* 提示由请求拦截器统一处理；下拉仍可手动输入 */
    modelSource.value = null
    modelHint.value = ''
  } finally {
    loadingModels.value = false
  }
}

function resetModelState() {
  modelOptions.value = []
  modelSource.value = null
  modelHint.value = ''
}

/**
 * 取 URL 上带的项目ID。
 * 雪花 ID 经后端序列化后是**字符串**（见 JacksonConfig 的 Long→String），
 * 这里统一按字符串处理——早先写成 Number() 转数字再与 p.id 比，恒不相等，预选一直是失效的。
 */
function queryProjectId() {
  const raw = route.query.projectId
  return raw == null || raw === '' ? null : String(raw)
}

function openCreate() {
  form.value = emptyForm()
  resetModelState()
  // 从「代码项目」页带着 projectId 跳过来时直接预选该项目
  const fromQuery = queryProjectId()
  if (fromQuery && creatableProjects.value.some(p => String(p.id) === fromQuery)) {
    form.value.projectIds = [fromQuery]
  } else if (creatableProjects.value.length === 1) {
    // 只剩一个可选项目时也直接预选，少点一次
    form.value.projectIds = [creatableProjects.value[0].id]
  }
  formVisible.value = true
  // 此时还没有 Key，后端会回退预置列表，先把下拉填上
  fetchModels()
}

function openEdit(a) {
  form.value = {
    id: a.id, projectIds: [...(a.projectIds || [])], name: a.name, avatar: a.avatar || '',
    systemPrompt: a.systemprompt || '', model: a.model || '',
    provider: a.provider || 'deepseek', apiBaseUrl: a.apibaseurl || '',
    temperature: a.temperature != null ? Number(a.temperature) : 0.7, apiKey: '',
    // 接口返回的是脱敏串（****xxxx），仅用来告诉用户"Key 已经在了"
    apikeyenc: a.apikeyenc || ''
  }
  resetModelState()
  formVisible.value = true
  // 编辑时表单不回传 Key，后端改用库里已存的 Key 去拉
  fetchModels()
}

async function saveAgent() {
  if (!form.value.projectIds || !form.value.projectIds.length) {
    ElMessage.warning('请至少选择一个关联项目'); return
  }
  if (!form.value.name || !form.value.name.trim()) {
    ElMessage.warning('请填写角色名称'); return
  }
  if (!form.value.id && !form.value.apiKey.trim()) {
    ElMessage.warning('请填写 API Key：未配置 Key 的角色无法发起对话'); return
  }
  saving.value = true
  try {
    const data = {
      projectIds: form.value.projectIds,
      name: form.value.name.trim(),
      avatar: form.value.avatar || null,
      systemPrompt: form.value.systemPrompt || null,
      provider: form.value.provider || null,
      apiBaseUrl: form.value.apiBaseUrl || null,
      model: form.value.model || null,
      temperature: form.value.temperature,
      apiKey: form.value.apiKey || null
    }
    const res = form.value.id
      ? await aiAgentUpdate({ ...data, id: form.value.id })
      : await aiAgentCreate(data)
    if (res.code === 200) {
      ElMessage.success('保存成功')
      formVisible.value = false
      await loadAll()
    } else {
      ElMessage.error(res.msg || '保存失败')
    }
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function removeAgent(a) {
  try {
    await ElMessageBox.confirm(`确定删除角色「${a.name}」？历史会话仍会保留`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    const res = await aiAgentDelete(a.id)
    if (res.code === 200) {
      ElMessage.success('已删除')
      await loadAll()
    } else {
      ElMessage.error(res.msg || '删除失败')
    }
  } catch {
    ElMessage.error('删除失败')
  }
}

/**
 * 打开该角色的对话。
 * <p>
 * 三件事：① 前置校验 Key 与索引状态，不满足就地引导（原来要等发出第一条消息才报错）；
 * ② 复用该角色已有的会话——原来每点一次就建一条新的，连点几次就堆几个空会话；
 * ③ 跳转时带上 conversationId，让对话页直接把这条会话打开（原来只跳到 /ai，什么都不选中）。
 */
async function startChat(a) {
  if (!a.apikeyenc) {
    ElMessage.warning('该角色尚未配置 API Key，请先补上再对话')
    openEdit(a)
    return
  }
  // 角色可能关联多个项目，任意一个没就绪都会让知识库缺一块，所以全部要就绪
  const linked = linkedProjects(a)
  if (!linked.length) {
    ElMessage.warning('该角色未关联代码项目，请先在编辑里关联至少一个项目')
    openEdit(a)
    return
  }
  const notReady = linked.find(p => p.status !== 'READY')
  if (notReady) {
    ElMessage.warning(`关联项目「${notReady.name}」尚未完成索引，请先排队索引`)
    router.push('/projects')
    return
  }
  startingId.value = a.id
  try {
    // 会话列表已按 UpdatedAt 倒序，取第一条匹配的就是该角色最近一次会话；
    // 只认未关闭的（status=1），已关闭的会话应该新建一条而不是把旧的翻出来
    const convRes = await aiConversations()
    const list = convRes.code === 200 ? (convRes.data || []) : []
    let conversation = list.find(c => c.agentid === a.id && c.status === 1)
    if (!conversation) {
      const res = await aiCreateConversation({ agentId: a.id, title: a.name })
      if (res.code !== 200) {
        ElMessage.error(res.msg || '创建会话失败')
        return
      }
      conversation = res.data
    }
    router.push({ path: '/ai', query: { conversationId: conversation.id } })
  } catch {
    ElMessage.error('打开会话失败')
  } finally {
    startingId.value = null
  }
}

onMounted(async () => {
  await loadAll()
  // 从「代码项目」页点「创建角色」跳过来时，直接把新建弹窗打开并预选该项目，
  // 少一步"跳过来了还要再点一次新建角色"；不能建的情况就地说明原因，别开一个空弹窗
  const fromQuery = queryProjectId()
  if (!fromQuery) return
  const project = projects.value.find(p => String(p.id) === fromQuery)
  if (!project) return
  if (project.status !== 'READY') {
    ElMessage.warning(`项目「${project.name}」尚未完成索引，请先在「代码项目」页排队索引`)
    return
  }
  openCreate()
})
</script>

<style scoped>
.agent-manage { height: 100%; display: flex; flex-direction: column; }

.page-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 24px; margin-bottom: 16px;
}
.page-head h2 { font-size: 18px; font-weight: 600; color: var(--text-primary); margin: 0 0 4px; }
.page-head p { font-size: 13px; color: var(--text-secondary); margin: 0; }

.agent-cell { display: flex; align-items: center; gap: 8px; }
.agent-mark {
  width: 22px; height: 22px; border-radius: var(--radius-md); flex-shrink: 0;
  color: #fff; display: inline-flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 600;
}

.model-row { display: flex; align-items: center; gap: 8px; width: 100%; }
.model-select { flex: 1; min-width: 0; }

/* 模型来源提示：预置兜底要显性提示，避免用户以为下拉里就是当前可用模型 */
.model-hint { width: 100%; margin: 4px 0 0; font-size: 12px; line-height: 1.6; }
.model-hint.ok { color: var(--text-muted); }
.model-hint.warn { color: var(--warning-color); }

.opt-meta { float: right; color: var(--text-muted); font-size: 12px; }

/* 「采样温度」标签旁的问号：鼠标移上去看推荐值 */
.label-with-help { display: inline-flex; align-items: center; gap: 4px; }
.help-icon { color: var(--text-muted); cursor: help; font-size: 13px; }
.help-icon:hover { color: var(--primary-color); }

/* tooltip 是深色底，正文用浅色，别用页面上的深灰 */
.temp-tip-intro { margin: 0 0 8px; line-height: 1.6; color: rgba(255, 255, 255, .78); }
.temp-tip-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.temp-tip-table td { padding: 2px 0; line-height: 1.7; color: #fff; }
.temp-tip-value { text-align: right; padding-left: 16px; font-variant-numeric: tabular-nums; }

/* API Key 当前状态提示：编辑弹窗里输入框必然是空的，靠这行说明"其实已经配了" */
.key-state { width: 100%; margin: 4px 0 0; font-size: 12px; line-height: 1.6; }
.key-state.ok { color: var(--text-muted); }
.key-state.warn { color: var(--warning-color); }

.dialog-tip-inline { margin: 4px 0 0; font-size: 12px; color: var(--warning-color); line-height: 1.6; }

.mark-preview {
  display: inline-flex; width: 18px; height: 18px; border-radius: var(--radius-sm);
  color: #fff; align-items: center; justify-content: center; font-size: 10px;
}
</style>

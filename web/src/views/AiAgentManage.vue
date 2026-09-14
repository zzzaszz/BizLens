<script>
export default { name: 'AiAgentManage' }
</script>

<template>
  <div class="agent-manage">
    <div class="page-head">
      <div>
        <h2>AI 角色</h2>
        <p>角色由代码项目创建——一个项目只能创建一个角色，且必须配置 API Key 才能发起对话</p>
      </div>
      <el-button type="primary" :icon="Plus" :disabled="!availableProjects.length" @click="openCreate">
        {{ availableProjects.length ? '新建角色' : '请先创建代码项目' }}
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
      <el-table-column label="所属项目" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ projectName(row.projectid) }}</template>
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
          <el-button link type="primary" @click="startChat(row)">对话</el-button>
          <el-button link @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removeAgent(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 创建 / 编辑角色弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑角色' : '新建角色'" width="540px" :append-to-body="true">
      <el-form label-width="90px">
        <el-form-item label="所属项目" required>
          <el-select v-if="!form.id" v-model="form.projectId" placeholder="选择代码项目" style="width:100%">
            <el-option v-for="p in availableProjects" :key="p.id" :label="p.name" :value="p.id">
              <span>{{ p.name }}</span>
              <span class="opt-meta">{{ projectStatusText(p.status) }}</span>
            </el-option>
          </el-select>
          <el-input v-else :model-value="projectName(form.projectId)" disabled />
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
        </el-form-item>

        <el-form-item label="采样温度">
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
import { Plus } from '@element-plus/icons-vue'
import {
  aiAgentList, aiAgentCreate, aiAgentUpdate, aiAgentDelete, aiAgentModels,
  aiCreateConversation, codeProjectList
} from '@/api'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const agents = ref([])
const projects = ref([])
const formVisible = ref(false)
const saving = ref(false)
const loadingModels = ref(false)
const modelOptions = ref([])
/** 模型来源：PROVIDER=厂商接口 / PRESET=预置兜底 / EMPTY=没拿到，用于提示用户 */
const modelSource = ref(null)
const modelHint = ref('')
const defaultMark = '#2563EB'
const form = ref(emptyForm())

/** 厂商选项；baseUrl 在切换厂商时自动带入「接口地址」，custom 留空强制手填 */
const PROVIDERS = [
  { value: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com' },
  { value: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1' },
  { value: 'qwen', label: '通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { value: 'custom', label: '自定义（OpenAI 兼容）', baseUrl: '' }
]

function emptyForm() {
  return {
    id: null, projectId: null, name: '', avatar: '', systemPrompt: '',
    provider: 'deepseek', apiBaseUrl: 'https://api.deepseek.com',
    model: '', temperature: 0.7, apiKey: ''
  }
}

/** 下拉选项 = 接口返回的列表；若当前已选模型不在其中（历史值/手输），补到最前面 */
const modelSelectOptions = computed(() => {
  const list = [...modelOptions.value]
  const current = form.value.model
  if (current && !list.includes(current)) list.unshift(current)
  return list
})

/** 已绑定角色的项目 ID —— 一个项目只能创建一个角色 */
const usedProjectIds = computed(() => new Set(agents.value.map(a => a.projectid)))

/** 还能创建角色的项目 */
const availableProjects = computed(() => projects.value.filter(p => !usedProjectIds.value.has(p.id)))

function projectName(projectId) {
  const p = projects.value.find(x => x.id === projectId)
  return p ? p.name : '—'
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

function openCreate() {
  form.value = emptyForm()
  resetModelState()
  // 从「代码项目」页带着 projectId 跳过来时直接预选该项目
  const fromQuery = Number(route.query.projectId)
  if (fromQuery && availableProjects.value.some(p => p.id === fromQuery)) {
    form.value.projectId = fromQuery
  } else if (availableProjects.value.length === 1) {
    // 只剩一个可选项目时也直接预选，少点一次
    form.value.projectId = availableProjects.value[0].id
  }
  formVisible.value = true
  // 此时还没有 Key，后端会回退预置列表，先把下拉填上
  fetchModels()
}

function openEdit(a) {
  form.value = {
    id: a.id, projectId: a.projectid, name: a.name, avatar: a.avatar || '',
    systemPrompt: a.systemprompt || '', model: a.model || '',
    provider: a.provider || 'deepseek', apiBaseUrl: a.apibaseurl || '',
    temperature: a.temperature != null ? Number(a.temperature) : 0.7, apiKey: ''
  }
  resetModelState()
  formVisible.value = true
  // 编辑时表单不回传 Key，后端改用库里已存的 Key 去拉
  fetchModels()
}

async function saveAgent() {
  if (!form.value.id && !form.value.projectId) {
    ElMessage.warning('请选择所属项目'); return
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
      projectId: form.value.projectId,
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

async function startChat(a) {
  try {
    const res = await aiCreateConversation({ agentId: a.id, title: a.name })
    if (res.code === 200) {
      ElMessage.success('会话已创建')
      router.push('/ai')
    } else {
      ElMessage.error(res.msg || '创建会话失败')
    }
  } catch {
    ElMessage.error('创建会话失败')
  }
}

onMounted(loadAll)
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
.mark-preview {
  display: inline-flex; width: 18px; height: 18px; border-radius: var(--radius-sm);
  color: #fff; align-items: center; justify-content: center; font-size: 10px;
}
</style>

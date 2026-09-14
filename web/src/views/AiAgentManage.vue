<script>
export default { name: 'AiAgentManage' }
</script>

<template>
  <div class="agent-manage">
    <div class="page-head">
      <div>
        <h2>AI 角色</h2>
        <p>系统预置角色对所有用户开放，你也可以创建自己的专属角色</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建角色</el-button>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="系统角色" name="system">
        <div class="agent-grid">
          <div v-for="a in systemAgents" :key="a.id" class="agent-card">
            <div class="ac-avatar" :style="{ background: a.avatar || defaultGradient }">{{ (a.name || '?')[0] }}</div>
            <div class="ac-info">
              <div class="ac-name">{{ a.name }} <el-tag size="small" type="info" effect="plain">系统</el-tag></div>
              <div class="ac-desc">{{ (a.systemprompt || '通用 AI 助手').slice(0, 60) }}</div>
            </div>
            <el-button size="small" :icon="ChatDotRound" @click="startChat(a)">对话</el-button>
          </div>
          <el-empty v-if="!systemAgents.length" description="暂无系统角色" :image-size="60" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="我的角色" name="mine">
        <div class="agent-grid">
          <div v-for="a in myAgents" :key="a.id" class="agent-card">
            <div class="ac-avatar" :style="{ background: a.avatar || defaultGradient }">{{ (a.name || '?')[0] }}</div>
            <div class="ac-info">
              <div class="ac-name">{{ a.name }}</div>
              <div class="ac-desc">{{ (a.systemprompt || '通用 AI 助手').slice(0, 60) }}</div>
              <div class="ac-actions">
                <el-button link size="small" :icon="Edit" @click="openEdit(a)">编辑</el-button>
                <el-button link size="small" type="danger" :icon="Delete" @click="removeAgent(a)">删除</el-button>
                <el-button link size="small" type="primary" :icon="ChatDotRound" @click="startChat(a)">对话</el-button>
              </div>
            </div>
          </div>
          <el-empty v-if="!myAgents.length" description="还没有自己的角色，点击右上角新建" :image-size="60" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 创建 / 编辑角色弹窗 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑角色' : '新建角色'" width="520px" :append-to-body="true">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="角色名称，如 英语老师" maxlength="32" />
        </el-form-item>
        <el-form-item label="头像">
          <el-input v-model="form.avatar" placeholder="可填图标或图片 URL，留空自动取色">
            <template #prefix>
              <span class="avatar-preview" :style="{ background: form.avatar || defaultGradient }">{{ (form.name || '?')[0] }}</span>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="角色设定">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="4"
            placeholder="系统提示词，如：你是一名专业英语老师，请用简单英语和我对话并纠正语法错误" />
        </el-form-item>
        <el-form-item label="模型">
          <el-select v-model="form.model" placeholder="留空使用系统默认模型" clearable style="width:100%">
            <el-option label="deepseek-chat" value="deepseek-chat" />
            <el-option label="gpt-4o-mini" value="gpt-4o-mini" />
            <el-option label="qwen-plus" value="qwen-plus" />
          </el-select>
        </el-form-item>
        <el-form-item label="温度">
          <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" placeholder="留空使用系统默认 Key" show-password clearable />
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
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, ChatDotRound } from '@element-plus/icons-vue'
import { aiAgentList, aiAgentCreate, aiAgentUpdate, aiAgentDelete, aiCreateConversation } from '@/api'

const router = useRouter()
const tab = ref('system')
const agents = ref([])
const formVisible = ref(false)
const saving = ref(false)
const defaultGradient = 'linear-gradient(135deg,#7C3AED,#3B82F6)'
const form = ref(emptyForm())

function emptyForm() {
  return { id: null, name: '', avatar: '', systemPrompt: '', model: '', temperature: 0.7, apiKey: '' }
}

const systemAgents = computed(() => agents.value.filter(a => a.issystem === 1))
const myAgents = computed(() => agents.value.filter(a => a.issystem !== 1))

async function loadAgents() {
  try {
    const res = await aiAgentList()
    if (res.code === 200) agents.value = res.data || []
  } catch { /* 忽略 */ }
}

function openCreate() {
  form.value = emptyForm()
  formVisible.value = true
}

function openEdit(a) {
  form.value = {
    id: a.id, name: a.name, avatar: a.avatar || '',
    systemPrompt: a.systemprompt || '', model: a.model || '',
    temperature: a.temperature != null ? Number(a.temperature) : 0.7, apiKey: ''
  }
  formVisible.value = true
}

async function saveAgent() {
  if (!form.value.name || !form.value.name.trim()) {
    ElMessage.warning('请填写角色名称'); return
  }
  saving.value = true
  try {
    const data = {
      name: form.value.name.trim(),
      avatar: form.value.avatar || null,
      systemPrompt: form.value.systemPrompt || null,
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
      await loadAgents()
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
      await loadAgents()
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

onMounted(loadAgents)
</script>

<style scoped>
.agent-manage { height: 100%; display: flex; flex-direction: column; }

.page-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  margin-bottom: 16px;
}
.page-head h2 { font-size: 20px; font-weight: 700; color: var(--text-primary); margin: 0 0 4px; }
.page-head p { font-size: 13px; color: var(--text-secondary); margin: 0; }

.agent-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}
.agent-card {
  display: flex; align-items: center; gap: 12px;
  background: var(--bg-white); border: 1px solid var(--border-light);
  border-radius: var(--radius-md); padding: 14px 16px;
}
.ac-avatar {
  width: 44px; height: 44px; border-radius: 11px; flex-shrink: 0;
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-size: 18px; font-weight: 700;
}
.ac-info { flex: 1; min-width: 0; }
.ac-name { font-size: 14px; font-weight: 600; color: var(--text-primary); display: flex; align-items: center; gap: 6px; }
.ac-desc {
  font-size: 12px; color: var(--text-muted); margin-top: 2px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ac-actions { margin-top: 4px; display: flex; gap: 2px; }

.avatar-preview {
  display: inline-flex; width: 20px; height: 20px; border-radius: 5px;
  color: #fff; align-items: center; justify-content: center; font-size: 11px;
}
</style>

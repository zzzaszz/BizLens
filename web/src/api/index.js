import request from '@/utils/request'

// ============ 鉴权 ==========
export function login(data) {
  return request({url: '/api/auth/login', method: 'post', data})
}

export function register(data) {
  return request({url: '/api/auth/register', method: 'post', data})
}

export function logout(accessToken, refreshToken) {
  return request({url: '/api/auth/logout', method: 'post', data: {accessToken, refreshToken}})
}

export function getUserInfo() {
  return request({url: '/api/auth/getInfo', method: 'get'})
}

export function updatePassword(data) {
  return request({url: '/api/auth/updatePassword', method: 'post', data})
}

export function updateProfile(data) {
  return request({url: '/api/auth/updateProfile', method: 'post', data})
}

// ============ AI 对话 ==========
export function aiCreateConversation(data) {
  return request({url: '/ai/chat/create', method: 'post', data})
}

export function aiConversations() {
  return request({url: '/ai/chat/conversations', method: 'get'})
}

export function aiCloseConversation(data) {
  return request({url: '/ai/chat/close', method: 'post', data})
}

export function aiSend(data) {
  return request({url: '/ai/chat/send', method: 'post', data})
}

export function aiHistory(conversationId) {
  return request({url: '/ai/chat/history', method: 'get', params: {conversationId}})
}

// ============ AI 角色 ==========
export function aiAgentList() {
  return request({url: '/ai/agent/list', method: 'get'})
}

export function aiAgentCreate(data) {
  return request({url: '/ai/agent/create', method: 'post', data})
}

export function aiAgentUpdate(data) {
  return request({url: '/ai/agent/update', method: 'post', data})
}

export function aiAgentDelete(id) {
  return request({url: `/ai/agent/${id}`, method: 'delete'})
}

/** 拉取厂商可用模型列表（后端失败时回退预置列表，始终 200） */
export function aiAgentModels(data) {
  return request({url: '/ai/agent/models', method: 'post', data})
}

// ============ 代码项目 ==========
export function codeProjectList() {
  return request({url: '/ai/project/list', method: 'get'})
}

export function codeProjectCreate(data) {
  return request({url: '/ai/project/create', method: 'post', data})
}

export function codeProjectUpdate(data) {
  return request({url: '/ai/project/update', method: 'post', data})
}

export function codeProjectQueueIndex(id) {
  return request({url: `/ai/project/${id}/index`, method: 'post'})
}

export function codeProjectDelete(id) {
  return request({url: `/ai/project/${id}`, method: 'delete'})
}

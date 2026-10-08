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

export function aiConversations(params) {
  return request({url: '/ai/chat/conversations', method: 'get', params})
}

export function aiCloseConversation(data) {
  return request({url: '/ai/chat/close', method: 'post', data})
}

/** 重新打开已关闭的会话，继续对话 */
export function aiReopenConversation(data) {
  return request({url: '/ai/chat/reopen', method: 'post', data})
}

/** 为早期未绑定角色的历史会话补绑角色 */
export function aiBindConversationAgent(data) {
  return request({url: '/ai/chat/bindAgent', method: 'post', data})
}

export function aiSend(data) {
  return request({url: '/ai/chat/send', method: 'post', data})
}

export function aiHistory(conversationId) {
  return request({url: '/ai/chat/history', method: 'get', params: {conversationId}})
}

/** 删除单条消息（对话页右键删除） */
export function aiDeleteMessage(id) {
  return request({url: `/ai/chat/message/${id}`, method: 'delete'})
}

/** 删除整个会话及其全部消息（对话页右键删除） */
export function aiDeleteConversation(id) {
  return request({url: `/ai/chat/conversation/${id}`, method: 'delete'})
}

// ============ AI 角色 ==========
export function aiAgentList(params) {
  return request({url: '/ai/agent/list', method: 'get', params})
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
export function codeProjectList(params) {
  return request({url: '/ai/project/list', method: 'get', params})
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

/** 一键把全部项目重新排队索引；返回 { total, queued, skipped, skippedNames } */
export function codeProjectQueueIndexAll() {
  return request({url: '/ai/project/indexAll', method: 'post'})
}

export function codeProjectDelete(id) {
  return request({url: `/ai/project/${id}`, method: 'delete'})
}

// ============ 个人知识库 ============
export function knowledgeSourceList(params) {
  return request({url: '/ai/knowledge/list', method: 'get', params})
}

export function knowledgeSourceCreate(data) {
  return request({url: '/ai/knowledge/create', method: 'post', data})
}

export function knowledgeSourceUpdate(data) {
  return request({url: '/ai/knowledge/update', method: 'post', data})
}

export function knowledgeSourceQueueIndex(id) {
  return request({url: `/ai/knowledge/${id}/index`, method: 'post'})
}

export function knowledgeSourceQueueIndexAll() {
  return request({url: '/ai/knowledge/indexAll', method: 'post'})
}

export function knowledgeSourceDelete(id) {
  return request({url: `/ai/knowledge/${id}`, method: 'delete'})
}

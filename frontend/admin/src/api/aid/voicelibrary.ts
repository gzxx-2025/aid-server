import { request } from '@/utils/request'

// 读类 POST 接口跳过前端防重复提交拦截
const READ_HEADERS = { repeatSubmit: false } as any

// ==================== 音色库 CRUD ====================

// 查询 AI 音色库分页列表（POST）
export function listVoiceLibrary(data) {
  return request({
    url: '/aid/voice-library/list',
    method: 'post',
    data: data,
    headers: READ_HEADERS
  })
}

// 查询音色详情
export function getVoiceLibrary(id) {
  return request({
    url: '/aid/voice-library/' + id,
    method: 'get'
  })
}

// 新增音色
export function addVoiceLibrary(data) {
  return request({
    url: '/aid/voice-library/add',
    method: 'post',
    data: data
  })
}

// 修改音色
export function updateVoiceLibrary(data) {
  return request({
    url: '/aid/voice-library',
    method: 'put',
    data: data
  })
}

// 启用 / 停用
export function updateVoiceLibraryStatus(data) {
  return request({
    url: '/aid/voice-library/status',
    method: 'put',
    data: data
  })
}

// 批量软删除
export function delVoiceLibrary(ids) {
  return request({
    url: '/aid/voice-library/' + ids,
    method: 'delete'
  })
}

// ==================== 音色标签字典 CRUD ====================

// 查询音色标签列表（POST）
export function listVoiceTag(data) {
  return request({
    url: '/aid/voice-tag/list',
    method: 'post',
    data: data,
    headers: READ_HEADERS
  })
}

// 查询音色标签详情
export function getVoiceTag(id) {
  return request({
    url: '/aid/voice-tag/' + id,
    method: 'get'
  })
}

// 新增音色标签
export function addVoiceTag(data) {
  return request({
    url: '/aid/voice-tag/add',
    method: 'post',
    data: data
  })
}

// 修改音色标签
export function updateVoiceTag(data) {
  return request({
    url: '/aid/voice-tag',
    method: 'put',
    data: data
  })
}

// 批量软删除音色标签
export function delVoiceTag(ids) {
  return request({
    url: '/aid/voice-tag/' + ids,
    method: 'delete'
  })
}

// ==================== 下拉复用（后台既有接口） ====================

// 复用后台服务商下拉
export function listProviderOptions(query) {
  return request({
    url: '/aid/aidprovider/list',
    method: 'get',
    params: query || {}
  })
}

// 复用后台模型下拉（默认按 modelType=audio 过滤）
export function listAudioModels(query) {
  return request({
    url: '/aid/aidmodel/list',
    method: 'get',
    params: Object.assign({ modelType: 'audio', pageSize: 500 }, query || {})
  })
}


// ==================== 远程音色同步（v2.39.0 新增，当前仅 MiniMax 支持） ====================

// 按模型 id 触发全量远程同步（旧接口保留兼容）
export function syncVoiceLibrary(modelId: number) {
  return request({
    url: '/aid/voice-library/sync/' + modelId,
    method: 'post'
  })
}

// 拉取远程音色列表（不入库，仅供前端展示选择）
export function fetchRemoteVoices(modelId: number) {
  return request({
    url: '/aid/voice-library/sync/fetch-remote/' + modelId,
    method: 'post'
  })
}

// 按用户选择同步音色（多选入库 + 取消选择的软删）
export function applySyncSelected(data: { modelId: number; selectedVoiceCodes: string[]; removedVoiceCodes: string[] }) {
  return request({
    url: '/aid/voice-library/sync/apply',
    method: 'post',
    data
  })
}

// 清除过期音色（offline_time ≤ NOW() 的活跃音色批量软删）
export function cleanExpiredVoices() {
  return request({
    url: '/aid/voice-library/clean-expired',
    method: 'post'
  })
}

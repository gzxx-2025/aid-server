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

// ==================== 通用音色工作台 ====================

export type VoiceWorkbenchOperation =
  | 'SYNTHESIZE'
  | 'REGISTER_CLONE'
  | 'REFERENCE_CLONE'
  | 'DESIGN'

export interface VoiceWorkbenchSampleLimit {
  formats?: string[];
  maxBytes?: number;
  maxEncodedBytes?: number;
  minDurationMs?: number;
  maxDurationMs?: number;
  minSampleRate?: number;
  channels?: number;
}

export interface VoiceWorkbenchOperationCapability {
  operation: VoiceWorkbenchOperation;
  supported: boolean;
  disabledReason?: string;
  requiredFields?: string[];
  optionalFields?: string[];
  textLimit?: number;
  sampleLimit?: VoiceWorkbenchSampleLimit;
  audioFormats?: string[];
  sampleRates?: number[];
  canPublish?: boolean;
  publishTargetModelIds?: number[];
}

export interface VoiceWorkbenchCapabilities {
  modelId: number;
  modelCode?: string;
  realModelCode?: string;
  modelName?: string;
  providerId?: number;
  providerName?: string;
  configVersion?: number;
  payer?: 'SITE_OWNER';
  notice?: string;
  operations: VoiceWorkbenchOperationCapability[];
}

export interface VoiceWorkbenchSample {
  fileId: string;
  name: string;
  mime?: string;
  sizeBytes: number;
  durationMs?: number;
  sampleRate?: number;
  channels?: number;
  verified: boolean;
  warnings?: string[];
}

export interface VoiceWorkbenchRequestFields {
  modelId: number;
  operation: VoiceWorkbenchOperation;
  voiceCode?: string;
  text?: string;
  sourceFileId?: string;
  description?: string;
  rightsConfirmed: boolean;
  audioFormat?: string;
  sampleRate?: number;
  speechRate?: number;
  loudnessRate?: number;
  pitch?: number;
  emotion?: string;
  /** 声音设计时由供应商自动优化/生成试听文本。 */
  optimizeTextPreview?: boolean;
}

export interface VoiceWorkbenchQuoteLine {
  label: string;
  amount: number;
  quantity?: number;
  unit?: string;
  estimated?: boolean;
}

export interface VoiceWorkbenchQuote {
  quoteRef?: string;
  pricingStatus: 'READY' | 'FREE' | 'MISSING';
  payer: 'SITE_OWNER';
  currency: string;
  totalCost?: number;
  breakdown?: VoiceWorkbenchQuoteLine[];
  expiresAt?: string;
  warnings?: string[];
}

export interface VoiceWorkbenchTaskResult {
  voiceCode?: string;
  audioUrl?: string;
}

export interface VoiceWorkbenchTask {
  taskId: number;
  operation: VoiceWorkbenchOperation;
  status: string;
  progress?: number;
  result?: VoiceWorkbenchTaskResult;
  quotedCost?: number;
  finalCost?: number;
  currency?: string;
  failureReason?: string;
  draftVoiceId?: number;
  payer?: 'SITE_OWNER';
}

export interface VoiceWorkbenchPublishRequest {
  taskId: number;
  targetModelId?: number;
  name: string;
  publishStatus: 'DRAFT' | 'PUBLISHED';
  language: string;
  gender: string;
  ageRange: string;
}

export interface VoiceWorkbenchPublishResult {
  voiceId: number;
  status: 'DRAFT' | 'PUBLISHED';
}

export function getVoiceWorkbenchCapabilities(modelId: number) {
  return request<VoiceWorkbenchCapabilities>({
    url: '/aid/voice-workbench/capabilities', method: 'get', params: { modelId }
  })
}

export function uploadVoiceWorkbenchSample(modelId: number, file: File, rightsConfirmed: true) {
  const data = new FormData()
  data.append('modelId', String(modelId))
  data.append('rightsConfirmed', String(rightsConfirmed))
  data.append('file', file)
  return request<VoiceWorkbenchSample>({
    url: '/aid/voice-workbench/samples', method: 'post', data,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function quoteVoiceWorkbench(data: VoiceWorkbenchRequestFields) {
  return request<VoiceWorkbenchQuote>({ url: '/aid/voice-workbench/quote', method: 'post', data })
}

export function createVoiceWorkbenchTask(data: VoiceWorkbenchRequestFields & {
  quoteRef: string;
  chargeConfirmed: true;
  idempotencyKey: string;
}) {
  return request<VoiceWorkbenchTask>({ url: '/aid/voice-workbench/create', method: 'post', data })
}

export function getVoiceWorkbenchTask(taskId: number) {
  return request<VoiceWorkbenchTask>({ url: `/aid/voice-workbench/tasks/${taskId}`, method: 'get' })
}

export function publishVoiceWorkbenchResult(data: VoiceWorkbenchPublishRequest) {
  return request<VoiceWorkbenchPublishResult>({ url: '/aid/voice-workbench/publish', method: 'post', data })
}

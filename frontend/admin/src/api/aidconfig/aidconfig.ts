import { request } from '@/utils/request'

// 查询配置信息列表
export function listAidconfig(query) {
  return request({
    url: '/aidconfig/aidconfig/list',
    method: 'get',
    params: query
  })
}

// 查询配置信息详细
export function getAidconfig(id) {
  return request({
    url: '/aidconfig/aidconfig/' + id,
    method: 'get'
  })
}

// 新增配置信息
export function addAidconfig(data) {
  return request({
    url: '/aidconfig/aidconfig',
    method: 'post',
    data: data
  })
}

// 修改配置信息
export function updateAidconfig(data) {
  return request({
    url: '/aidconfig/aidconfig',
    method: 'put',
    data: data
  })
}

// 删除配置信息
export function delAidconfig(id) {
  return request({
    url: '/aidconfig/aidconfig/' + id,
    method: 'delete'
  })
}

// 刷新配置（让后端重新从数据库加载配置）
export function refreshConfig(category) {
  return request({
    url: `/${category}/config/refresh`,
    method: 'post'
  })
}

// 获取当前生效的配置
export function getCurrentConfig(category) {
  return request({
    url: `/${category}/config/current`,
    method: 'get'
  })
}

/** 测试消息队列连接（无参） */
export function testMqSend() {
  return request({
    url: '/mq/config/testSend',
    method: 'post'
  })
}

/** 测试短信发送 */
export function testSmsSend(data: { phone: string; code?: string }) {
  return request({
    url: '/sms/config/testSend',
    method: 'post',
    data
  })
}

export interface WechatNotifyTemplateConfig {
  enabled?: boolean
  title?: string
  templateId?: string
  fields?: Record<string, string>
}

export interface WechatNotifyConfig {
  enabled?: boolean
  jumpUrlBase?: string
  dailyUserLimit?: number
  minuteUserLimit?: number
  balanceReminderThreshold?: number
  templates?: Record<string, WechatNotifyTemplateConfig>
}

export interface WechatNotifyStatus {
  enabled?: boolean
  wxLoginEnabled?: boolean
  appIdConfigured?: boolean
  secretConfigured?: boolean
  tokenConfigured?: boolean
  encodingAesKeyConfigured?: boolean
  templateConfigured?: boolean
  balanceReminderThreshold?: number
  ready?: boolean
  wxLoginCategory?: string
  missingItems?: string[]
  rules?: string[]
}

export interface WechatTemplateSendResult {
  errcode?: number
  errmsg?: string
  msgid?: number
  rawResponse?: string
}

/** 读取微信公众号模板消息推送配置 */
export function getWechatNotifyConfig() {
  return request({
    url: '/aidconfig/wxnotify/config',
    method: 'get'
  })
}

/** 保存微信公众号模板消息推送配置 */
export function saveWechatNotifyConfig(data: WechatNotifyConfig) {
  return request({
    url: '/aidconfig/wxnotify/config',
    method: 'post',
    data
  })
}

/** 读取微信公众号推送前置配置状态 */
export function getWechatNotifyStatus() {
  return request({
    url: '/aidconfig/wxnotify/status',
    method: 'get'
  })
}

/** 获取公众号已选用模板列表 */
export function getWechatNotifyTemplates() {
  return request({
    url: '/aidconfig/wxnotify/templates',
    method: 'get'
  })
}

/** 测试发送微信公众号模板消息 */
export function testWechatNotifySend(data: { openid: string; eventType?: string }) {
  return request({
    url: '/aidconfig/wxnotify/test',
    method: 'post',
    data
  })
}

/** 读取媒体处理(MPS)配置（密钥脱敏） */
export function getMediaProcessConfig() {
  return request({
    url: '/aidconfig/mps/config',
    method: 'get'
  })
}

/** 整组保存媒体处理(MPS)配置 */
export function saveMediaProcessConfig(data) {
  return request({
    url: '/aidconfig/mps/config',
    method: 'post',
    data
  })
}

/** 整组保存文件存储配置，并在服务端校验媒体处理方式、存储厂商和地域归属。 */
export function saveStorageConfig(data) {
  return request({
    url: '/aidconfig/storage/config',
    method: 'post',
    data
  })
}

/** 读取文件存储配置（密钥由服务端脱敏）。 */
export function getStorageConfig() {
  return request({
    url: '/aidconfig/storage/config',
    method: 'get'
  })
}

/** 读取腾讯云语音识别配置（密钥脱敏） */
export function getTencentAsrConfig() {
  return request({
    url: '/aidconfig/tencent-asr/config',
    method: 'get'
  })
}

/** 整组保存腾讯云语音识别配置 */
export function saveTencentAsrConfig(data) {
  return request({
    url: '/aidconfig/tencent-asr/config',
    method: 'post',
    data
  })
}

export interface TencentAsrTestCue {
  startSeconds?: number
  endSeconds?: number
  speaker?: string
  text?: string
  source?: string
}

export interface TencentAsrTestResult {
  fileName?: string
  fileSize?: number
  durationSeconds?: number
  text?: string
  rawText?: string
  cueCount?: number
  cues?: TencentAsrTestCue[]
  elapsedMs?: number
}

/** 上传临时视频并等待腾讯云返回最终识别结果。 */
export function testTencentAsr(file: File) {
  const formData = new FormData()
  formData.append('file', file, file.name)
  return request<TencentAsrTestResult>({
    url: '/aidconfig/tencent-asr/test',
    method: 'post',
    headers: { 'Content-Type': 'multipart/form-data' },
    data: formData,
    // 后端允许单次最长 600 秒、最多 3 次尝试，并需预留提交与网络请求耗时。
    timeout: 36 * 60 * 1000
  })
}

/**
 * 解析微信支付 V3 证书文件，提取证书序列号（serialNo）。
 * 仅解析内容不落盘，前端拿到后走常规配置保存流程。
 */
export function parseWxpayCert(formData: FormData) {
  return request({
    url: '/wxpay/config/parse-cert',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

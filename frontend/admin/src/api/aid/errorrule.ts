import { request } from '@/utils/request'

// ==================== 类型定义 ====================

export interface ProviderErrorRule {
  id?: number
  /** 厂商编码（NULL 表示全局规则） */
  providerCode?: string | null
  /** 模型编码（NULL 表示厂商所有模型） */
  modelCode?: string | null
  /** 规则名称 */
  ruleName: string
  /** 匹配类型：HTTP_STATUS / CODE / KEYWORD / REGEX / JSON_PATH */
  matchType: 'HTTP_STATUS' | 'CODE' | 'KEYWORD' | 'REGEX' | 'JSON_PATH'
  /** 匹配内容 */
  matchPattern: string
  /** JSON_PATH 模式下的字段路径 */
  matchField?: string | null
  /** 是否区分大小写（0 否 1 是） */
  caseSensitive?: number
  /** 映射到的 TaskErrorCode */
  errorCode: string
  /** 覆盖默认 userMessage */
  userMessage?: string | null
  /** 优先级（小者优先） */
  priority?: number
  /** 启用 (0 禁 1 启) */
  enabled?: number
  /** 内置规则 (1 内置不可删除) */
  isBuiltin?: number
  /** 备注 */
  remark?: string | null
}

export interface ErrorRuleQueryParams {
  pageNum?: number
  pageSize?: number
  providerCode?: string
  modelCode?: string
  errorCode?: string
  enabled?: number
}

export interface ErrorRuleTestRequest {
  providerCode?: string
  modelCode?: string
  httpStatus?: number
  rawMessage: string
}

export interface ErrorRuleTestResult {
  errorCode: string
  errorType: string
  errorSource: string
  userMessage: string
  retryable: boolean
  needRecharge: boolean
}

export interface TaskErrorCodeOption {
  code: string
  errorType: string
  errorSource: string
  userMessage: string
  retryable: boolean
  needRecharge: boolean
}

// ==================== API ====================

export function listErrorRule(params: ErrorRuleQueryParams) {
  return request({ url: '/aid/errorrule/list', method: 'get', params })
}

export function getErrorRule(id: number) {
  return request({ url: `/aid/errorrule/${id}`, method: 'get' })
}

export function addErrorRule(data: ProviderErrorRule) {
  return request({ url: '/aid/errorrule', method: 'post', data })
}

export function updateErrorRule(data: ProviderErrorRule) {
  return request({ url: '/aid/errorrule', method: 'put', data })
}

export function delErrorRule(ids: number | number[]) {
  const arr = Array.isArray(ids) ? ids.join(',') : ids
  return request({ url: `/aid/errorrule/${arr}`, method: 'delete' })
}

export function toggleErrorRule(id: number, enabled: number) {
  return request({ url: '/aid/errorrule/toggle', method: 'post', data: { id, enabled } })
}

export function testErrorRule(data: ErrorRuleTestRequest) {
  return request<ErrorRuleTestResult>({ url: '/aid/errorrule/test', method: 'post', data })
}

export function rebuildErrorRuleCache() {
  return request({ url: '/aid/errorrule/cache/rebuild', method: 'post' })
}

export function listTaskErrorCodes() {
  return request<TaskErrorCodeOption[]>({ url: '/aid/errorrule/error-codes', method: 'get' })
}

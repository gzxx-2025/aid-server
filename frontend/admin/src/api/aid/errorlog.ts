import { request } from '@/utils/request'
import type { ProviderErrorRule } from '@/api/aid/errorrule'

export interface ErrorLog {
  id: number
  taskId?: string
  providerCode?: string
  modelCode?: string
  httpStatus?: number
  rawMessage?: string
  matchedRuleId?: number | null
  matchedErrorCode?: string
  occurrenceCount?: number
  sampleHash?: string
  firstSeen?: string
  lastSeen?: string
}

export interface ErrorLogQueryParams {
  pageNum?: number
  pageSize?: number
  providerCode?: string
  /** true=只看未识别 */
  onlyUnmatched?: boolean
}

export interface ErrorLogConvertRequest {
  /** 来源错误样本 ID */
  errorLogId: number
  /** 管理员确认后的完整规则 */
  rule: ProviderErrorRule
}

export function listErrorLog(params: ErrorLogQueryParams) {
  return request({ url: '/aid/errorlog/list', method: 'get', params })
}

export function getErrorLog(id: number) {
  return request<ErrorLog>({ url: `/aid/errorlog/${id}`, method: 'get' })
}

/** 根据未识别错误样本生成可编辑的规则草稿。 */
export function getErrorRuleDraft(id: number) {
  return request<ProviderErrorRule>({ url: `/aid/errorlog/${id}/rule-draft`, method: 'get' })
}

/** 创建规则并同步把来源样本标记为已处理。 */
export function convertErrorLogToRule(data: ErrorLogConvertRequest) {
  return request<number>({ url: '/aid/errorlog/convert', method: 'post', data })
}

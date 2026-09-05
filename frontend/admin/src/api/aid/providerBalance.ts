import { request } from '@/utils/request'

export interface BalanceSettings {
  enabled: boolean
  dailyReportEnabled: boolean
  dailyReportTime: string
  defaultRepeatIntervalMinutes: number
  failureRetryMinutes: number
  snapshotRetentionDays: number
  deliveryRetentionDays: number
  smsTemplateId?: string
  wechatTemplateId?: string
  wechatJumpUrl?: string
  wechatProviderField?: string
  wechatBalanceField?: string
  wechatStatusField?: string
  wechatTimeField?: string
}

export interface ChannelCapability {
  enabled: boolean
  templateReady: boolean
  disabledReason?: string
}

export interface BalanceOverview {
  settings: BalanceSettings
  channels: Record<'EMAIL' | 'SMS' | 'WECHAT', ChannelCapability>
  providerCount: number
  monitoredCount: number
  warningCount: number
  criticalCount: number
  openIncidentCount: number
  recipientCount: number
}

export interface BalanceProvider {
  id?: number
  providerId: number
  providerCode: string
  providerName: string
  logoUrl?: string
  providerStatus?: string
  apiSupported: boolean
  apiBalanceUnit?: string
  enabled: boolean
  apiEnabled: boolean
  simulatedEnabled: boolean
  errorRuleEnabled: boolean
  forecastEnabled: boolean
  currency: string
  initialAmount?: number
  initialTime?: string
  costUnitMultiplier?: number
  warningThreshold: number
  criticalThreshold: number
  recoveryThreshold: number
  forecastDays: number
  repeatIntervalMinutes: number
  queryIntervalMinutes: number
  confirmCount: number
  currentStatus?: 'NORMAL' | 'WARNING' | 'CRITICAL'
  currentSource?: string
  currentBalance?: number
  simulatedBalance?: number
  runwayDays?: number
  lastCheckTime?: string
  lastSuccessTime?: string
  lastError?: string
  silenceUntil?: string
}

export interface BalanceRecipient {
  id: number
  recipientName: string
  channel: 'EMAIL' | 'SMS' | 'WECHAT'
  displayValue: string
  wechatNickname?: string
  enabled: boolean
  dailyReportEnabled: boolean
  providerIds: number[]
  createTime?: string
}

const inFlight = new Map<string, Promise<any>>()
const recent = new Map<string, { at: number; value: any }>()

/** 同一业务键只保留一个进行中请求；force 只绕过短时结果缓存。 */
function readOnce<T>(key: string, factory: () => Promise<T>, force = false, ttl = 800): Promise<T> {
  const running = inFlight.get(key)
  if (running) return running
  const cached = recent.get(key)
  if (!force && cached && Date.now() - cached.at < ttl) return Promise.resolve(cached.value)
  const promise = factory()
    .then((value) => {
      recent.set(key, { at: Date.now(), value })
      return value
    })
    .finally(() => inFlight.delete(key))
  inFlight.set(key, promise)
  return promise
}

export const getBalanceOverview = (force = false) =>
  readOnce('provider-balance:overview', () => request<BalanceOverview>({
    url: '/aid/provider-balance/overview', method: 'get'
  }), force)

export const listBalanceProviders = (force = false) =>
  readOnce('provider-balance:providers', () => request<BalanceProvider[]>({
    url: '/aid/provider-balance/providers', method: 'get'
  }), force)

export const listBalanceRecipients = (force = false) =>
  readOnce('provider-balance:recipients', () => request<BalanceRecipient[]>({
    url: '/aid/provider-balance/recipients', method: 'get'
  }), force)

export const saveBalanceSettings = (data: BalanceSettings) =>
  request({ url: '/aid/provider-balance/settings', method: 'put', data })

export const saveBalanceProvider = (providerId: number, data: any) =>
  request({ url: `/aid/provider-balance/providers/${providerId}`, method: 'put', data })

export const checkBalanceProvider = (providerId: number) =>
  request({ url: `/aid/provider-balance/providers/${providerId}/check`, method: 'post' })

export const addBalanceAdjustment = (providerId: number, data: { amount: number; type: string; remark?: string }) =>
  request({ url: `/aid/provider-balance/providers/${providerId}/adjustments`, method: 'post', data })

export const addBalanceRecipient = (data: any) =>
  request({ url: '/aid/provider-balance/recipients', method: 'post', data })

export const updateBalanceRecipient = (data: any) =>
  request({ url: '/aid/provider-balance/recipients', method: 'put', data })

export const deleteBalanceRecipient = (ids: number | number[]) =>
  request({
    url: `/aid/provider-balance/recipients/${Array.isArray(ids) ? ids.join(',') : ids}`,
    method: 'delete'
  })

export const testBalanceRecipient = (id: number) =>
  request({ url: `/aid/provider-balance/recipients/${id}/test`, method: 'post' })

export const createWechatRecipientQr = (data: { recipientName?: string; providerIds: number[] }) =>
  request<{ sceneStr: string; qrCodeUrl: string; expireSeconds: number }>({
    url: '/aid/provider-balance/recipients/wechat/qrcode', method: 'post', data
  })

export const getWechatRecipientQrStatus = (sceneStr: string) =>
  readOnce(`provider-balance:qr:${sceneStr}`, () => request<any>({
    url: '/aid/provider-balance/recipients/wechat/qrcode/status', method: 'get', params: { sceneStr }
  }), true, 0)

export const listBalanceIncidents = (params: any) =>
  readOnce(`provider-balance:incidents:${JSON.stringify(params)}`, () => request({
    url: '/aid/provider-balance/incidents', method: 'get', params
  }), true, 0)

export const listBalanceDeliveries = (params: any) =>
  readOnce(`provider-balance:deliveries:${JSON.stringify(params)}`, () => request({
    url: '/aid/provider-balance/deliveries', method: 'get', params
  }), true, 0)

export const listBalanceRules = (params: any) =>
  readOnce(`provider-balance:rules:${JSON.stringify(params)}`, () => request({
    url: '/aid/provider-balance/balance-rules', method: 'get', params
  }), true, 0)

export const addBalanceRule = (data: any) =>
  request({ url: '/aid/provider-balance/balance-rules', method: 'post', data })

export const updateBalanceRule = (data: any) =>
  request({ url: '/aid/provider-balance/balance-rules', method: 'put', data })

export const toggleBalanceRule = (id: number, enabled: number) =>
  request({ url: `/aid/provider-balance/balance-rules/${id}/toggle`, method: 'post', data: { enabled } })

export const deleteBalanceRule = (id: number) =>
  request({ url: `/aid/provider-balance/balance-rules/${id}`, method: 'delete' })

export const acknowledgeBalanceIncident = (id: number, silenceMinutes?: number) =>
  request({
    url: `/aid/provider-balance/incidents/${id}/acknowledge`, method: 'post', data: { silenceMinutes }
  })

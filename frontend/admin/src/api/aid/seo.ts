import { request } from '@/utils/request'

export interface SeoSettings {
  siteUrl?: string
  siteName?: string
  titleSuffix?: string
  defaultDescription?: string
  defaultKeywords?: string
  baiduEnabled: boolean
  baiduSite?: string
  baiduTokenConfigured: boolean
  submitBatchSize: number
  robotsDisallow?: string
  robotsPreview?: string
  sitemapUrl?: string
  robotsUrl?: string
}

export interface SeoSettingsSave extends Omit<SeoSettings, 'baiduTokenConfigured' | 'robotsPreview' | 'sitemapUrl' | 'robotsUrl'> {
  baiduToken?: string
  clearBaiduToken?: boolean
}

export interface SeoOverview {
  totalPages: number
  indexablePages: number
  pendingPages: number
  acceptedPages: number
  retryPages: number
  lastScanTime?: string
  lastSubmitTime?: string
  providerRemain?: number
  baiduReady: boolean
}

export interface SeoPage {
  id: number
  sourceType: string
  sourceId?: string
  pagePath: string
  canonicalUrl: string
  pageTitle: string
  metaDescription?: string
  metaKeywords?: string
  ogImageUrl?: string
  indexable: boolean
  sitemapEnabled: boolean
  status: string
  apiStatus?: string
  manualStatus?: string
  attemptCount?: number
  lastAttemptTime?: string
  acceptedTime?: string
  nextRetryTime?: string
  lastErrorMessage?: string
  updateTime?: string
}

export interface SeoPageSave {
  id?: number
  sourceType?: string
  sourceId?: string
  pagePath: string
  pageTitle: string
  metaDescription?: string
  metaKeywords?: string
  ogImageUrl?: string
  indexable: boolean
  sitemapEnabled: boolean
  status?: string
}

export interface SeoPageResult {
  total: number
  items: SeoPage[]
}

export interface SeoDispatchResult {
  batchNo?: string
  selected: number
  accepted: number
  rejected: number
  deferred: number
  remain?: number
  message?: string
}

export interface SeoLog {
  id: number
  batchNo: string
  pageId: number
  provider: string
  channel: string
  triggerType: string
  submitStatus: string
  urlSnapshot: string
  httpStatus?: number
  responseSummary?: string
  errorCode?: string
  errorMessage?: string
  operatorName?: string
  createTime?: string
}

export interface SeoPageQuery {
  pageNum: number
  pageSize: number
  keyword?: string
  sourceType?: string
  submitStatus?: string
  indexable?: boolean
  onlyUnsubmitted?: boolean
}

const inFlight = new Map<string, Promise<any>>()
const recent = new Map<string, { at: number; value: any }>()

/** 相同业务键始终共用进行中的请求；force 只绕过短时结果缓存。 */
function once<T>(key: string, factory: () => Promise<T>, force = false, ttl = 600): Promise<T> {
  const running = inFlight.get(key)
  if (running) return running
  const cached = recent.get(key)
  if (!force && cached && Date.now() - cached.at < ttl) return Promise.resolve(cached.value)
  const run = factory()
    .then((value) => {
      recent.set(key, { at: Date.now(), value })
      return value
    })
    .finally(() => inFlight.delete(key))
  inFlight.set(key, run)
  return run
}

function mutateOnce<T>(key: string, factory: () => Promise<T>): Promise<T> {
  return once(`mutation:${key}`, factory, true, 0)
}

export const getSeoOverview = (force = false) =>
  once('seo:overview', () => request<SeoOverview>({ url: '/aid/seo/overview', method: 'get' }), force)

export const getSeoSettings = (force = false) =>
  once('seo:settings', () => request<SeoSettings>({ url: '/aid/seo/settings', method: 'get' }), force)

export const listSeoPages = (params: SeoPageQuery, force = false) =>
  once(`seo:pages:${JSON.stringify(params)}`, () => request<SeoPageResult>({
    url: '/aid/seo/pages', method: 'get', params
  }), force)

export const listSeoLogs = (pageId?: number, limit = 50, force = false) =>
  once(`seo:logs:${pageId || 'all'}:${limit}`, () => request<SeoLog[]>({
    url: '/aid/seo/logs', method: 'get', params: { pageId, limit }
  }), force)

export const saveSeoSettings = (data: SeoSettingsSave) =>
  mutateOnce('settings', () => request({ url: '/aid/seo/settings', method: 'put', data }))

export const addSeoPage = (data: SeoPageSave) =>
  mutateOnce(`page:add:${data.pagePath}`, () => request<SeoPage>({ url: '/aid/seo/pages', method: 'post', data }))

export const updateSeoPage = (data: SeoPageSave) =>
  mutateOnce(`page:update:${data.id}`, () => request<SeoPage>({ url: '/aid/seo/pages', method: 'put', data }))

export const archiveSeoPage = (pageId: number) =>
  mutateOnce(`page:archive:${pageId}`, () => request({ url: `/aid/seo/pages/${pageId}`, method: 'delete' }))

export const scanSeoPages = () =>
  mutateOnce('scan', () => request<number>({ url: '/aid/seo/scan', method: 'post' }))

export const submitSeoPages = (pageIds: number[]) =>
  mutateOnce(`baidu:${[...pageIds].sort((a, b) => a - b).join(',') || 'queue'}`, () => request<SeoDispatchResult>({
    url: '/aid/seo/submit/baidu', method: 'post', data: { pageIds }
  }))

export const confirmManualSeoPages = (pageIds: number[]) =>
  mutateOnce(`manual:${[...pageIds].sort((a, b) => a - b).join(',')}`, () => request<number>({
    url: '/aid/seo/submit/manual-confirm', method: 'post', data: { pageIds }
  }))

/** 业务 API 各域共享的通用工具：信封解包、分页/列表响应解析、列表请求去重与短时缓存。 */
import type { ApiEnvelope,PaginatedListResult } from '~/types/business-api';

/** 分页列表默认每页条数（与产品约定一致） */
export const API_DEFAULT_PAGE_SIZE = 20

export function unwrap<T>(res: ApiEnvelope<T>): T {
  return res.data as T
}

/** 从列表接口响应中取出记录数组（优先 `data` 为数组；兼容根级 `rows` 等旧结构） */
export function extractPageRows<T>(res: unknown): { rows: T[]; total: number } {
  const r = res as Record<string, unknown> | null
  if (!r || typeof r !== 'object') return { rows: [], total: 0 }
  const rawData = r.data
  if (Array.isArray(rawData)) {
    const total = typeof r.total === 'number' ? r.total : rawData.length
    return { rows: rawData as T[], total }
  }
  const dataObj =
    rawData && typeof rawData === 'object' && !Array.isArray(rawData)
      ? (rawData as Record<string, unknown>)
      : undefined
  const pickArray = (x: unknown): T[] | null => (Array.isArray(x) ? (x as T[]) : null)
  const rows =
    pickArray(r.rows) ??
    pickArray(dataObj?.rows) ??
    pickArray(dataObj?.list) ??
    pickArray(r.list) ??
    pickArray(dataObj?.records) ??
    pickArray(r.records) ??
    []
  const totalRaw =
    (typeof r.total === 'number' ? r.total : undefined) ??
    (typeof dataObj?.total === 'number' ? (dataObj.total as number) : undefined)
  const total = typeof totalRaw === 'number' ? totalRaw : rows.length
  return { rows, total }
}

/** 从分页接口响应解析 rows / total / pageNum / pageSize / hasMore */
export function extractPaginatedResponse<T>(
  res: unknown,
  fallbackPageNum = 1,
  fallbackPageSize = API_DEFAULT_PAGE_SIZE
): PaginatedListResult<T> {
  const { rows, total } = extractPageRows<T>(res)
  const r = res as Record<string, unknown> | null
  const pageNum =
    r && typeof r.pageNum === 'number' && r.pageNum > 0 ? r.pageNum : fallbackPageNum
  const pageSize =
    r && typeof r.pageSize === 'number' && r.pageSize > 0 ? r.pageSize : fallbackPageSize
  return {
    rows,
    total,
    pageNum,
    pageSize,
    hasMore: pageNum * pageSize < total
  }
}

/** 从标准信封取出 `data` 数组 */
export function extractDataArray<T>(res: unknown): T[] {
  const r = res as Record<string, unknown> | null
  if (!r || typeof r !== 'object') return []
  const d = r.data
  return Array.isArray(d) ? (d as T[]) : []
}

export const OSS_UPLOAD_TIMEOUT_MS = 300000

/** 刷新/路由抖动时同一请求体会在短时间内连发多次；并发合并 + 短时缓存避免 Network 里同名「list」刷屏 */
const API_LIST_BURST_CACHE_MS = 450

export function stableRequestKey(body: unknown): string {
  if (body == null || typeof body !== 'object') return JSON.stringify(body)
  const o = body as Record<string, unknown>
  const keys = Object.keys(o).sort()
  const sorted: Record<string, unknown> = {}
  for (const k of keys) sorted[k] = o[k]
  return JSON.stringify(sorted)
}

/** 列表类接口共用的：短时 burst 缓存 + 同 key 并发合并（避免四处复制同一套 if/inflight/finally） */
export type ListBurstSlot<T> = { current: { key: string; data: T; at: number } | null }

export function runListDedupe<T>(
  key: string,
  inflight: Map<string, Promise<T>>,
  burst: ListBurstSlot<T>,
  fetcher: () => Promise<T>,
  burstMs = API_LIST_BURST_CACHE_MS
): Promise<T> {
  const now = Date.now()
  const b = burst.current
  if (b && b.key === key && now - b.at < burstMs) {
    return Promise.resolve(b.data)
  }
  const existing = inflight.get(key)
  if (existing) return existing
  // 持有器先于闭包声明，规避 const 自引用的 TS 暂时性死区检查
  const self: { p?: Promise<T> } = {}
  const p = (async () => {
    const data = await fetcher()
    // 仍是当前在途任务才写 burst 缓存（避免被后来的同 key 任务覆盖）
    if (inflight.get(key) === self.p) {
      burst.current = { key, data, at: Date.now() }
    }
    return data
  })().finally(() => {
    if (inflight.get(key) === self.p) inflight.delete(key)
  })
  self.p = p
  inflight.set(key, p)
  return p
}

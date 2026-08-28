import { request } from '@/utils/request'

/** 单个模型的实时排队 / 并发监控行 */
export interface ModelQueueStat {
  id: number
  modelCode: string
  modelName?: string
  realModelCode?: string
  modelType?: string
  generateMode?: string
  providerId?: number
  providerName?: string
  status?: string
  /** 模型并发上限；null 表示不限制 */
  concurrencyLimit?: number | null
  limited: boolean
  running: number
  waiting: number
  usagePercent?: number | null
  saturated: boolean
  recentUsage?: number | null
}

/** 单个服务商维度的实时排队 / 并发监控行 */
export interface ProviderQueueStat {
  providerId: number
  providerName?: string
  status?: string
  concurrencyLimit?: number | null
  limited: boolean
  running: number
  waiting: number
  usagePercent?: number | null
  saturated: boolean
  modelCount: number
}

/** 模型健康时间轴单格（30分钟/格） */
export interface ModelHealthBucket {
  /** 该格起始时间 yyyy-MM-dd HH:mm:ss */
  bucketTime: string
  /** operational=正常 degraded=降级 error=异常 none=该时段无调用 */
  status: 'operational' | 'degraded' | 'error' | 'none'
  successCount: number
  failCount: number
  /** 该格成功任务平均耗时（毫秒），无成功调用为 null */
  avgLatencyMs?: number | null
  /** 该格最近一次上游错误摘要（后台管理视图返回） */
  errorMessage?: string | null
}

/** 单个模型的健康时间线（固定48格，30分钟/格，最近24小时） */
export interface ModelHealthTimeline {
  providerId?: number
  providerCode?: string
  providerName?: string
  modelCode: string
  modelName?: string
  modelType?: string
  /** 模型是否启用 */
  enabled?: boolean
  /** 最新状态：operational / degraded / error / none（窗口内无调用=none） */
  latestStatus: 'operational' | 'degraded' | 'error' | 'none'
  /** 24小时可用率（百分比，两位小数）；无调用为 null */
  availabilityPct?: number | null
  /** 24小时总调用（成功+失败） */
  totalChecks?: number
  successCount?: number
  failCount?: number
  /** 24小时成功平均耗时（毫秒） */
  avgLatencyMs?: number | null
  /** 最新延迟（毫秒）：最近一个有成功调用的时间格平均耗时 */
  latestLatencyMs?: number | null
  /** 7天可用率（百分比，两位小数）；无调用为 null */
  availability7dPct?: number | null
  /** 15天可用率（百分比，两位小数）；无调用为 null */
  availability15dPct?: number | null
  /** 30天可用率（百分比，两位小数）；无调用为 null */
  availability30dPct?: number | null
  /** 7天成功平均耗时（毫秒）；无成功调用为 null */
  avgLatency7dMs?: number | null
  items: ModelHealthBucket[]
}

/** 模型健康总览（并入快照返回；含停用模型，全量不分页） */
export interface ModelHealthBoard {
  total?: number
  /** 统计窗口，固定 24h */
  trendPeriod?: string
  /** 数据生成时间 yyyy-MM-dd HH:mm:ss */
  lastUpdated?: string
  /** 整体状态：operational / degraded / error / none */
  overallStatus?: 'operational' | 'degraded' | 'error' | 'none'
  /** 整体状态横幅文案：所有服务运行正常 / 部分服务降级 / 部分服务异常 */
  overallStatusText?: string
  operationalCount?: number
  degradedCount?: number
  errorCount?: number
  noDataCount?: number
  /** 全部模型时间线 */
  providerTimelines?: ModelHealthTimeline[]
}

/** 模型上游请求并发 / 排队监控总快照 */
export interface ModelQueueSnapshot {
  generatedAt: number
  globalLimit: number
  globalRunning: number
  globalUsagePercent: number
  totalWaiting: number
  /** 未归属任何在册模型（如合成任务）的排队条数 */
  unassignedProviderWaiting: number
  userDefaultLimit: number
  usageWindowHours: number
  models: ModelQueueStat[]
  providers: ProviderQueueStat[]
  /** 模型健康总览（最近24小时48格时间轴；查询异常时为 null） */
  health?: ModelHealthBoard | null
}

/**
 * 获取 AI 模型排队 / 并发实时监控快照。
 * 服务端已做短 TTL 缓存合并，前端可放心高频轮询，不会放大线上 Redis / DB 压力。
 */
export function getModelQueueSnapshot() {
  return request<ModelQueueSnapshot>({
    url: '/aid/modelmonitor/snapshot',
    method: 'get'
  })
}

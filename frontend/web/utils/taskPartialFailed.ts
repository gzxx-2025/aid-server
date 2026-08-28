import type { BillingQuoteRequest } from '~/types/business-api'
import { userTaskResume } from '~/utils/businessApi'
import { parseTaskResultPayload } from '~/utils/taskChainOutcome'
/** SSE partial_failed / complete 批量结果中的失败项 */
export interface TaskPartialFailedItem {
  batchIndex?: number
  batchId?: number
  sceneId?: number
  scene_id?: number
  sceneName?: string
  message?: string
  reason?: string
  [key: string]: unknown
}

/** 分镜视频出片 SSE complete / partial_failed 成功项 */
export interface TaskVideoBatchSuccessItem {
  storyboardId: number
  recordId: number
  videoUrl: string
}

/** SSE partial_failed 事件 data 结构（与 complete 批量结果同构） */
export interface TaskPartialFailedData {
  totalCount?: number
  successCount?: number
  failCount?: number
  successItems?: unknown[]
  failedItems?: TaskPartialFailedItem[]
  /** 分镜视频出片：成功镜头明细（含 videoUrl） */
  items?: TaskVideoBatchSuccessItem[]
  /** 分镜视频出片：成功记录 ID 列表 */
  recordIds?: number[]
  /** 分镜视频出片：每镜头 shots 明细 */
  shots?: Array<{
    storyboardId?: number
    successCount?: number
    recordIds?: number[]
    [key: string]: unknown
  }>
  totalSubtasks?: number
  /** 合并接口串联出图/出片：全部子任务 ID（优先） */
  chainChildTaskIds?: number[]
  /** 兼容旧字段：第一批子任务 ID */
  chainChildTaskId?: number | null
  chainChildTaskType?: string | null
  /** 提示词已完成，但合并接口的自动出图/出片提交失败 */
  chainFailed?: boolean
  chainMessage?: string
}

export function normUserTaskType(ty: unknown): string {
  return String(ty ?? '')
    .trim()
    .toLowerCase()
    .replace(/-/g, '_')
}

export function isUserTaskStatusPartialFailed(status: unknown): boolean {
  return String(status ?? '').toUpperCase() === 'PARTIAL_FAILED'
}

export function isUserTaskStatusCancelled(status: unknown): boolean {
  return String(status ?? '').toUpperCase() === 'CANCELLED'
}

/** 用户手动取消且可调统一续生接口的任务 */
export function isCancelledResumableTask(task: { taskType?: unknown; status?: unknown }): boolean {
  return isUserTaskStatusCancelled(task.status) && isPartialFailedResumableTaskType(task.taskType)
}

/** 支持 POST /api/user/task/resume 续生的任务类型（与接口文档一致） */
export function isPartialFailedResumableTaskType(ty: unknown): boolean {
  const n = normUserTaskType(ty)
  return (
    n === 'asset_extract' ||
    n === 'storyboard_script_batch' ||
    n === 'storyboard_image_prompt_batch' ||
    n === 'storyboard_video_prompt_batch' ||
    n === 'storyboard_video_generate' ||
    n === 'storyboard_image_generate' ||
    n === 'storyboard_image_batch'
  )
}

/** 分镜视频出片：FAILED 终态也可续生（与 PARTIAL_FAILED 同入口） */
export function isStoryboardVideoGenerateResumableStatus(status: unknown): boolean {
  const s = String(status ?? '').toUpperCase()
  return s === 'PARTIAL_FAILED' || s === 'FAILED'
}

export function isStoryboardVideoGenerateTaskType(ty: unknown): boolean {
  return normUserTaskType(ty) === 'storyboard_video_generate'
}

export function isStoryboardImageGenerateTaskType(ty: unknown): boolean {
  const n = normUserTaskType(ty)
  return n === 'storyboard_image_generate' || n === 'storyboard_image_batch'
}

/** 分镜图出片：FAILED 终态也可续生（与 PARTIAL_FAILED 同入口） */
export function isStoryboardImageGenerateResumableStatus(status: unknown): boolean {
  const s = String(status ?? '').toUpperCase()
  return s === 'PARTIAL_FAILED' || s === 'FAILED'
}

/** 从 SSE / resultData 解析分镜视频出片成功项 */
export function parseVideoBatchSuccessItems(data: unknown): TaskVideoBatchSuccessItem[] {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return []
  const o = data as Record<string, unknown>
  const rawItems = Array.isArray(o.items) ? o.items : []
  const out: TaskVideoBatchSuccessItem[] = []
  const seen = new Set<number>()
  for (const raw of rawItems) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue
    const item = raw as Record<string, unknown>
    const storyboardId = Number(item.storyboardId)
    const recordId = Number(item.recordId)
    const videoUrl = String(item.videoUrl ?? '').trim()
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) continue
    if (!Number.isFinite(recordId) || recordId <= 0) continue
    if (!videoUrl) continue
    if (seen.has(storyboardId)) continue
    seen.add(storyboardId)
    out.push({ storyboardId, recordId, videoUrl })
  }
  return out
}

/** 从 partial_failed / complete 推导失败分镜 ID（优先 failedItems，否则目标集 − 成功集） */
export function resolveVideoBatchFailedStoryboardIds(
  data: unknown,
  targetStoryboardIds: number[],
  successItems?: TaskVideoBatchSuccessItem[]
): Set<number> {
  const failed = new Set<number>()
  const o =
    data && typeof data === 'object' && !Array.isArray(data)
      ? (data as Record<string, unknown>)
      : null
  const failedItems = Array.isArray(o?.failedItems) ? o!.failedItems : []
  for (const raw of failedItems) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue
    const sid = Number((raw as Record<string, unknown>).storyboardId)
    if (Number.isFinite(sid) && sid > 0) failed.add(sid)
  }
  if (failed.size) return failed

  const successIds = new Set(
    (successItems ?? parseVideoBatchSuccessItems(data)).map((x) => x.storyboardId)
  )
  // shots 里 successCount=0 的镜头也算失败
  const shots = Array.isArray(o?.shots) ? o!.shots : []
  for (const raw of shots) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue
    const shot = raw as Record<string, unknown>
    const sid = Number(shot.storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) continue
    const successCount = Number(shot.successCount)
    if (Number.isFinite(successCount) && successCount <= 0) failed.add(sid)
  }
  if (failed.size) {
    for (const sid of successIds) failed.delete(sid)
    return failed
  }

  const successCount = Number(o?.successCount)
  const failCount = Number(o?.failCount)
  const hasCountEvidence =
    (Number.isFinite(successCount) && successCount >= 0) ||
    (Number.isFinite(failCount) && failCount > 0)
  // 无 items / failedItems / shots / 计数时不臆测失败，交给列表刷新判定
  if (!successIds.size && !hasCountEvidence) return failed

  for (const sid of targetStoryboardIds) {
    if (!successIds.has(sid)) failed.add(sid)
  }
  return failed
}

export function parseTaskPartialFailedData(data: unknown): TaskPartialFailedData | null {
  const o = parseTaskResultPayload(data)
  if (!o) return null
  const num = (v: unknown) => {
    const n = Number(v)
    return Number.isFinite(n) ? n : undefined
  }
  const chainChildTaskIds = Array.isArray(o.chainChildTaskIds)
    ? [
        ...new Set(
          o.chainChildTaskIds.map((v) => Number(v)).filter((n) => Number.isFinite(n) && n > 0)
        )
      ]
    : undefined
  const chainChildTaskId = num(o.chainChildTaskId) ?? null
  const videoItems = parseVideoBatchSuccessItems(o)
  const recordIds = Array.isArray(o.recordIds)
    ? [...new Set(o.recordIds.map((v) => Number(v)).filter((n) => Number.isFinite(n) && n > 0))]
    : undefined
  const shots = Array.isArray(o.shots) ? (o.shots as TaskPartialFailedData['shots']) : undefined
  return {
    totalCount: num(o.totalCount) ?? num(o.totalSubtasks),
    successCount: num(o.successCount),
    failCount: num(o.failCount),
    successItems: Array.isArray(o.successItems) ? o.successItems : undefined,
    failedItems: Array.isArray(o.failedItems)
      ? (o.failedItems as TaskPartialFailedItem[])
      : undefined,
    ...(videoItems.length ? { items: videoItems } : {}),
    ...(recordIds?.length ? { recordIds } : {}),
    ...(shots?.length ? { shots } : {}),
    ...(num(o.totalSubtasks) != null ? { totalSubtasks: num(o.totalSubtasks) } : {}),
    ...(chainChildTaskIds?.length ? { chainChildTaskIds } : {}),
    ...(chainChildTaskId != null ? { chainChildTaskId } : {}),
    ...(typeof o.chainChildTaskType === 'string'
      ? { chainChildTaskType: o.chainChildTaskType }
      : {}),
    ...(o.chainFailed === true ? { chainFailed: true } : {}),
    ...(typeof o.chainMessage === 'string' && o.chainMessage.trim()
      ? { chainMessage: o.chainMessage.trim() }
      : {})
  }
}

/** 从 PARTIAL_FAILED 明细提取 aid_scene_plot.id，供 /generate/script 选择性重试 */
export function extractSceneIdsFromPartialFailed(data?: TaskPartialFailedData | null): number[] {
  if (!data?.failedItems?.length) return []
  const ids = new Set<number>()
  for (const item of data.failedItems) {
    const raw = item.sceneId ?? item.scene_id ?? item.batchId ?? item.batch_id
    const n = Number(raw)
    if (Number.isFinite(n) && n > 0) ids.add(n)
  }
  return [...ids]
}

export function formatPartialFailedMessage(
  data?: TaskPartialFailedData | null,
  fallback?: string
): string {
  if (!data) return fallback || '部分生成失败，可续生'
  const s = data.successCount
  const f = data.failCount
  const t = data.totalCount
  if (s != null && f != null && t != null) {
    return `部分成功：${s}/${t} 已完成，${f} 项失败，可点击续生`
  }
  return fallback || '部分生成失败，可续生'
}

export function taskTypeLabelForResume(ty: unknown): string {
  const n = normUserTaskType(ty)
  if (n === 'asset_extract') return '智能提取'
  if (n === 'storyboard_script_batch') return '分镜脚本生成'
  if (n === 'storyboard_image_prompt_batch') return '分镜图提示词生成'
  if (n === 'storyboard_video_prompt_batch') return '分镜视频提示词生成'
  if (n === 'storyboard_video_generate') return '分镜视频出片'
  if (n === 'storyboard_audio_generate') return '批量分镜配音'
  if (n === 'storyboard_image_generate' || n === 'storyboard_image_batch') return '分镜图生成'
  return String(ty || '任务')
}

/** 统一续生：POST /api/user/task/resume，复用原 taskId */
export async function resumeUserTask(
  taskId: number,
  taskType: unknown
): Promise<{ taskId: number; totalBatches?: number; totalShots?: number }> {
  const n = normUserTaskType(taskType)
  if (!isPartialFailedResumableTaskType(n)) {
    throw new Error('该任务类型不支持续生')
  }
  const r = await userTaskResume({ taskId })
  return { taskId: r.taskId, totalBatches: r.totalBatches, totalShots: r.totalShots }
}

/** 续生报价只接收原任务 ID，服务端按任务快照还原实际计费计划。 */
export function buildTaskResumeBillingRequest(taskId: unknown): BillingQuoteRequest | null {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return null
  return {
    quoteType: 'TASK_RESUME',
    payload: { taskId: id },
    quantity: 1
  }
}

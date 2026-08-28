import {
fetchUserTaskDetailOnce,
normalizeTaskStatus
} from '~/composables/useTaskSseFollow'
import type { TaskStreamResult } from '~/composables/useTaskStream'
import type {
StoryboardGenerateImageData
} from '~/types/business-api'
import { openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { userTaskDetailCached } from '~/utils/businessApi'
import { formatStoryboardImageSubmitRejections } from '~/utils/storyboardImageSubmitItems'
import {
fetchStoryboardRecordsForStoryboard,
type ProjectEpisodeContext
} from '~/utils/storyboardRecordBatch'
export type ImageResultItem = {
  recordId?: number
  imageUrl?: string
  imageId?: number
  storyboardId?: number
}

export function parseCompleteData(raw: unknown): {
  imageUrl: string | null
  recordId: number | null
  items: ImageResultItem[]
  successCount?: number
  totalCount?: number
} {
  if (raw == null) {
    return { imageUrl: null, recordId: null, items: [] }
  }

  let o: Record<string, unknown>
  if (typeof raw === 'string') {
    const s = raw.trim()
    if (!s) return { imageUrl: null, recordId: null, items: [] }
    try {
      o = JSON.parse(s) as Record<string, unknown>
    } catch {
      return { imageUrl: null, recordId: null, items: [] }
    }
  } else if (typeof raw === 'object' && !Array.isArray(raw)) {
    o = raw as Record<string, unknown>
  } else {
    return { imageUrl: null, recordId: null, items: [] }
  }

  const imageUrl =
    typeof o.imageUrl === 'string' && o.imageUrl.trim()
      ? o.imageUrl.trim()
      : typeof o.ossUrl === 'string' && o.ossUrl.trim()
        ? o.ossUrl.trim()
        : null

  const ridRaw = o.recordId ?? o.genRecordId ?? o.imageId ?? o.id
  const recordId =
    ridRaw != null && Number.isFinite(Number(ridRaw)) && Number(ridRaw) > 0 ? Number(ridRaw) : null

  const items: ImageResultItem[] = Array.isArray(o.items)
    ? (o.items as ImageResultItem[])
    : []

  const recordIds = Array.isArray(o.recordIds)
    ? o.recordIds
        .map((id) => Number(id))
        .filter((id) => Number.isFinite(id) && id > 0)
    : []

  const successCount =
    typeof o.successCount === 'number' && Number.isFinite(o.successCount)
      ? o.successCount
      : typeof o.totalShots === 'number' && Number.isFinite(o.totalShots)
        ? o.totalShots
        : undefined
  const totalCount =
    typeof o.totalCount === 'number' && Number.isFinite(o.totalCount)
      ? o.totalCount
      : typeof o.totalShots === 'number' && Number.isFinite(o.totalShots)
        ? o.totalShots
        : undefined

  const resolvedRecordId =
    recordId ??
    (recordIds.length ? recordIds[recordIds.length - 1] : null) ??
    (items.length
      ? Number(items[items.length - 1]?.recordId ?? items[items.length - 1]?.imageId) || null
      : null)

  return {
    imageUrl,
    recordId: resolvedRecordId,
    items,
    successCount,
    totalCount
  }
}

export async function fetchRecordFileUrl(
  projectEpisode: ProjectEpisodeContext | null | undefined,
  storyboardId: number,
  recordId: number
): Promise<string | null> {
  if (!projectEpisode) return null
  try {
    const rows = await fetchStoryboardRecordsForStoryboard(projectEpisode, storyboardId, 'image')
    const row = rows.find((r) => Number(r.id) === recordId)
    const url = String(row?.fileUrl ?? '').trim()
    if (url) return url
  } catch {
    /* ignore */
  }
  return null
}

export type StoryboardImageGenerateProgress = {
  message?: string
  percent?: number
  stepTitle?: string
  stepIndex?: number
  stepTotal?: number
  successCount?: number
  totalCount?: number
  recordId?: number | null
  taskId?: number
  items?: ImageResultItem[]
}

export type StoryboardImageGenerateResult =
  | {
      ok: true
      taskId: number
      recordId: number | null
      imageUrl: string | null
      items?: ImageResultItem[]
    }
  | { ok: false; errorMessage: string; deferred?: boolean }

export type StoryboardImageBatchGenerateResult =
  | { ok: true; taskId: number; partial?: boolean; submitWarning?: string }
  | { ok: false; errorMessage: string; deferred?: boolean }

export const TASK_BACKGROUND_RUNNING_MESSAGE = '任务仍在后台执行，请稍候或刷新页面自动恢复进度'

export function isStoryboardImageTaskOngoingStatus(status: unknown): boolean {
  const st = String(status ?? '').trim().toUpperCase()
  return (
    st === 'PENDING' ||
    st === 'PROCESSING' ||
    st === 'QUEUED' ||
    st === 'RUNNING' ||
    st === 'WAITING' ||
    st === '0'
  )
}

/** 判断分镜图任务是否仍在进行中（刷新恢复用；走 3s 缓存，避免弹窗重进时 force detail 风暴） */
export async function isStoryboardImageTaskOngoing(taskId: number): Promise<boolean> {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return false
  const detail = await userTaskDetailCached(id)
  if (!detail) return false
  return isStoryboardImageTaskOngoingStatus(detail.status)
}

function pickItemsForStoryboard(items: ImageResultItem[], storyboardId: number): ImageResultItem[] {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return items
  const filtered = items.filter((it) => Number(it.storyboardId) === sid)
  return filtered.length ? filtered : items
}

export async function buildStoryboardImageSuccess(payload: {
  parsed: ReturnType<typeof parseCompleteData>
  taskId: number
  storyboardId: number
  recordId: number | null
  projectEpisode?: ProjectEpisodeContext | null
}): Promise<StoryboardImageGenerateResult> {
  const { parsed, taskId, storyboardId, recordId, projectEpisode } = payload
  const scopedItems = pickItemsForStoryboard(parsed.items, storyboardId)
  const lastItem = scopedItems[scopedItems.length - 1]
  const resolvedRecordId =
    parsed.recordId ??
    recordId ??
    (lastItem?.recordId != null && Number(lastItem.recordId) > 0 ? Number(lastItem.recordId) : null) ??
    (lastItem?.imageId != null && Number(lastItem.imageId) > 0 ? Number(lastItem.imageId) : null)

  const imageUrl =
    parsed.imageUrl ||
    (lastItem?.imageUrl ?? null) ||
    (resolvedRecordId != null && Number.isFinite(storyboardId) && storyboardId > 0
      ? await fetchRecordFileUrl(projectEpisode, storyboardId, resolvedRecordId)
      : null)

  return {
    ok: true,
    taskId,
    recordId: resolvedRecordId,
    imageUrl,
    items: scopedItems.length ? scopedItems : parsed.items.length ? parsed.items : undefined
  }
}

export async function resultFromStreamTerminal(
  r: TaskStreamResult,
  ctx: {
    taskId: number
    storyboardId: number
    recordId: number | null
    projectEpisode?: ProjectEpisodeContext | null
  }
): Promise<StoryboardImageGenerateResult | null> {
  if (r.type !== 'complete' && r.type !== 'partial_failed') return null
  const parsed = parseCompleteData(r.data)
  if (!parsed.imageUrl && !parsed.recordId && !parsed.items.length && ctx.recordId == null) {
    return null
  }
  return buildStoryboardImageSuccess({ parsed, ...ctx })
}

export const STORYBOARD_IMAGE_GEN_SSE_COMPLETE_EVENT = 'storyboard-image-gen-sse-complete'
export const STORYBOARD_IMAGE_GEN_SSE_TERMINAL_EVENT = 'storyboard-image-gen-sse-terminal'

export function dispatchStoryboardImageGenSseTerminal(detail: {
  taskId: number
  storyboardId?: number
  ok: boolean
  errorMessage?: string
}) {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(STORYBOARD_IMAGE_GEN_SSE_TERMINAL_EVENT, { detail }))
}

function dispatchStoryboardImageGenSseComplete(detail: {
  taskId: number
  storyboardId: number
  recordId: number | null
  items: ImageResultItem[]
  successCount?: number
  totalCount?: number
}) {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(STORYBOARD_IMAGE_GEN_SSE_COMPLETE_EVENT, { detail }))
}

export function emitStoryboardImageCompleteProgress(
  onProgress: ((p: StoryboardImageGenerateProgress) => void) | undefined,
  payload: {
    taskId: number
    storyboardId: number
    data: unknown
    fallbackRecordId?: number | null
  }
) {
  const parsed = parseCompleteData(payload.data)
  const scopedItems = pickItemsForStoryboard(parsed.items, payload.storyboardId)
  const recordId =
    parsed.recordId ??
    payload.fallbackRecordId ??
    (scopedItems.length
      ? Number(
          scopedItems[scopedItems.length - 1]?.recordId ?? scopedItems[scopedItems.length - 1]?.imageId
        ) || null
      : null)
  const items = scopedItems.length ? scopedItems : parsed.items
  const storyboardId =
    payload.storyboardId > 0
      ? payload.storyboardId
      : Number(items[0]?.storyboardId) || 0

  if (!recordId && !items.length && !parsed.imageUrl && parsed.successCount == null) {
    return
  }

  const progress: StoryboardImageGenerateProgress = {
    taskId: payload.taskId,
    successCount: parsed.successCount,
    totalCount: parsed.totalCount,
    recordId,
    items,
    message: '生成完成',
    stepTitle: '生成完成'
  }
  onProgress?.(progress)
  dispatchStoryboardImageGenSseComplete({
    taskId: payload.taskId,
    storyboardId,
    recordId,
    items,
    successCount: parsed.successCount,
    totalCount: parsed.totalCount
  })
}

export async function resultFromTaskDetail(
  d: Awaited<ReturnType<typeof fetchUserTaskDetailOnce>>,
  ctx: {
    taskId: number
    storyboardId: number
    recordId: number | null
    projectEpisode?: ProjectEpisodeContext | null
  }
): Promise<StoryboardImageGenerateResult | null> {
  if (!d) return null
  const st = normalizeTaskStatus(d.status)
  if (st === 'SUCCEEDED' || st === 'PARTIAL_FAILED') {
    const parsed = parseCompleteData(d.resultData)
    if (parsed.imageUrl || parsed.recordId || parsed.items.length || ctx.recordId != null) {
      return buildStoryboardImageSuccess({ parsed, ...ctx })
    }
    if (st === 'PARTIAL_FAILED') {
      return { ok: false, errorMessage: String(d.errorMessage || '部分分镜图生成失败') }
    }
    return { ok: false, errorMessage: '任务成功但未解析到图片' }
  }
  if (st === 'CANCELLED') {
    return { ok: false, errorMessage: '任务已取消' }
  }
  if (st === 'FAILED') {
    return { ok: false, errorMessage: String(d.errorMessage || '生成失败') }
  }
  return null
}

export function validateSubmitItems(submitted: StoryboardGenerateImageData): string | null {
  const items = submitted?.items ?? []
  if (!items.length) return null
  const accepted = items.filter((item) => item.accepted)
  const rejected = items.filter((item) => !item.accepted)
  if (accepted.length === 0) {
    return rejected.map((item) => item.reason).filter(Boolean).join('；') || '全部失败'
  }
  return null
}

export async function submitAndFollowStoryboardImageBatchTask(payload: {
  submit: () => Promise<StoryboardGenerateImageData>
  followTask: (payload: {
    taskId: number
    onProgress?: (progress: StoryboardImageGenerateProgress) => void
  }) => Promise<StoryboardImageBatchGenerateResult>
  onProgress?: (p: StoryboardImageGenerateProgress) => void
  onSubmitted?: (p: { taskId: number }) => void
  progressMessage: string
  submitErrorMessage: string
}): Promise<StoryboardImageBatchGenerateResult> {
  const { submit, onProgress, onSubmitted, submitErrorMessage } = payload

  let submitted: StoryboardGenerateImageData
  try {
    submitted = await submit()
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    const msg = String(err?.msg || err?.message || submitErrorMessage)
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }

  const rejectReason = validateSubmitItems(submitted)
  if (rejectReason) {
    return { ok: false, errorMessage: rejectReason }
  }

  const submitWarning = formatStoryboardImageSubmitRejections(submitted?.items)

  const taskId = Number(submitted?.taskId)
  if (!Number.isFinite(taskId) || taskId <= 0) {
    const items = submitted?.items ?? []
    const reason = items
      .filter((item) => !item.accepted)
      .map((item) => item.reason)
      .filter(Boolean)
      .join('；')
    return { ok: false, errorMessage: reason || '提交失败：未返回任务ID' }
  }

  onSubmitted?.({ taskId })

  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }

  const submitStatus = String(submitted.status || '').toUpperCase()
  if (submitStatus === 'SUCCEEDED') {
    return { ok: true, taskId, ...(submitWarning ? { submitWarning } : {}) }
  }
  if (submitStatus === 'PARTIAL_FAILED') {
    return { ok: true, taskId, partial: true, ...(submitWarning ? { submitWarning } : {}) }
  }
  if (submitStatus === 'FAILED') {
    return { ok: false, errorMessage: '分镜图生成失败' }
  }

  const followed = await payload.followTask({ taskId, onProgress })
  if (followed.ok && submitWarning) {
    return { ...followed, submitWarning }
  }
  return followed
}

/**
 * 追踪已提交的批量分镜图父任务（SSE 等待终态）。
 */

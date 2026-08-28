import {
fetchUserTaskDetailOnce,
normalizeTaskStatus,
shouldDeferModalTaskFollowFailure,
TASK_SSE_TIMEOUT_MS,
waitUserTaskSseTerminal
} from '~/composables/useTaskSseFollow'
import type { TaskStreamResult } from '~/composables/useTaskStream'
import type {
UserStoryboardGenerateMultiViewGridImageRequest,
} from '~/types/business-api'
import { handleSseErrorRecharge,openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { userStoryboardGenerateMultiViewGridImage } from '~/utils/businessApi'
import { isBenignTaskSseDisconnectMessage } from '~/utils/taskSseSilentDisconnect'

function parseResultFromRecord(o: Record<string, unknown>): {
  imageUrl: string | null
  recordId: number | null
} {
  const u = o?.imageUrl
  const imageUrl = typeof u === 'string' && u.trim() ? u.trim() : null
  const ridRaw = o?.recordId ?? o?.genRecordId ?? o?.imageId ?? o?.id
  const recordId =
    ridRaw != null && Number.isFinite(Number(ridRaw)) && Number(ridRaw) > 0 ? Number(ridRaw) : null
  return { imageUrl, recordId }
}

function parseResultData(raw: unknown): { imageUrl: string | null; recordId: number | null } {
  if (raw == null) return { imageUrl: null, recordId: null }
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    return parseResultFromRecord(raw as Record<string, unknown>)
  }
  const s = typeof raw === 'string' ? raw.trim() : ''
  if (!s) return { imageUrl: null, recordId: null }
  try {
    return parseResultFromRecord(JSON.parse(s) as Record<string, unknown>)
  } catch {
    return { imageUrl: null, recordId: null }
  }
}

export type StoryboardMultiViewGridImageTaskResult =
  | { ok: true; imageUrl: string; recordId: number | null }
  | { ok: false; errorMessage: string; deferred?: boolean }

/**
 * 分镜机位生图（v2.62）：POST /api/user/storyboard/generate/multi-view-grid-image
 * angles.length === 1 单机位；=== 9 九宫格。
 */
export async function runStoryboardMultiViewGridImageTask(payload: {
  storyboardId: number
  imageUrl: string
  angles: string[]
  modelCode: string
  aspectRatio?: string
  notifyGlobalTasks?: boolean
  onSubmitted?: (info: { taskId: number }) => void
  onProgress?: (p: { percent?: number; stepTitle?: string; message?: string }) => void
}): Promise<StoryboardMultiViewGridImageTaskResult> {
  const { storyboardId, imageUrl, angles, modelCode, aspectRatio, onProgress, onSubmitted } = payload
  const trimmedAngles = angles.map((a) => String(a || '').trim()).filter(Boolean)
  if (trimmedAngles.length !== 1 && trimmedAngles.length !== 9) {
    return { ok: false, errorMessage: '机位必须1或9个' }
  }
  if (trimmedAngles.length !== angles.length) {
    return { ok: false, errorMessage: '机位不能空' }
  }

  const isNineGrid = trimmedAngles.length === 9
  const taskLabel = isNineGrid ? '九宫格' : '多机位'
  const timeoutMs = TASK_SSE_TIMEOUT_MS

  let taskId: number
  try {
    const reqBody: UserStoryboardGenerateMultiViewGridImageRequest = {
      storyboardId,
      imageUrl,
      angles: trimmedAngles,
      modelCode,
      ...(aspectRatio ? { aspectRatio } : {})
    }
    const submitted = await userStoryboardGenerateMultiViewGridImage(reqBody)
    taskId = Number(submitted.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) {
      return { ok: false, errorMessage: '未返回有效任务ID' }
    }
    onSubmitted?.({ taskId })
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    const msg = String(err?.msg || err?.message || `${taskLabel}生图提交失败`)
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }

  if (payload.notifyGlobalTasks !== false && typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }

  return followStoryboardMultiViewGridImageTask({ taskId, taskLabel, timeoutMs, onProgress })
}

function buildGridSuccess(parsed: {
  imageUrl: string | null
  recordId: number | null
}): StoryboardMultiViewGridImageTaskResult | null {
  if (!parsed.imageUrl) return null
  return { ok: true, imageUrl: parsed.imageUrl, recordId: parsed.recordId }
}

function resultFromStreamTerminal(r: TaskStreamResult): StoryboardMultiViewGridImageTaskResult | null {
  if (r.type !== 'complete') return null
  return buildGridSuccess(parseResultData(r.data))
}

function resultFromTaskDetail(
  d: Awaited<ReturnType<typeof fetchUserTaskDetailOnce>>,
  taskLabel: string
): StoryboardMultiViewGridImageTaskResult | null {
  if (!d) return null
  const st = normalizeTaskStatus(d.status)
  if (st === 'SUCCEEDED') {
    const hit = buildGridSuccess(parseResultData(d.resultData))
    if (hit) return hit
    return { ok: false, errorMessage: '任务成功但未解析到图片地址' }
  }
  if (st === 'CANCELLED') {
    return { ok: false, errorMessage: '任务已取消' }
  }
  if (st === 'FAILED') {
    return { ok: false, errorMessage: String(d.errorMessage || `${taskLabel}生图失败`) }
  }
  return null
}

export async function followStoryboardMultiViewGridImageTask(payload: {
  taskId: number
  taskLabel: string
  timeoutMs: number
  onProgress?: (p: { percent?: number; stepTitle?: string; message?: string }) => void
}): Promise<StoryboardMultiViewGridImageTaskResult> {
  const { taskId, taskLabel, timeoutMs, onProgress } = payload

  try {
    const terminal = await waitUserTaskSseTerminal({ taskId, timeoutMs, onProgress })

    if (terminal.kind === 'superseded') {
      return { ok: false, errorMessage: 'Task SSE superseded', deferred: true }
    }

    if (terminal.kind === 'timeout') {
      const recovered = resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId), taskLabel)
      if (recovered) return recovered
      return { ok: false, errorMessage: `${taskLabel}生图任务超时，请在任务中心查看进度` }
    }

    const r = terminal.event
    const fromStream = resultFromStreamTerminal(r)
    if (fromStream) return fromStream

    if (r.type === 'error' && isBenignTaskSseDisconnectMessage(r.errorMessage)) {
      if (await shouldDeferModalTaskFollowFailure(taskId, r.errorMessage)) {
        return { ok: false, errorMessage: r.errorMessage, deferred: true }
      }
    }

    const recovered = resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId), taskLabel)
    if (recovered) return recovered

    if (r.type === 'error') {
      const msg = r.errorMessage || `${taskLabel}生图失败`
      if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
        return { ok: false, errorMessage: msg, deferred: true }
      }
      handleSseErrorRecharge(r.errorData, msg)
      return { ok: false, errorMessage: msg }
    }

    if (r.type === 'cancelled') {
      return { ok: false, errorMessage: r.message || '任务已取消' }
    }

    return { ok: false, errorMessage: `${taskLabel}任务未完成` }
  } catch (e: unknown) {
    const msg = String((e as Error)?.message || `${taskLabel}任务异常`)
    if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
      return { ok: false, errorMessage: msg, deferred: true }
    }
    const recovered = resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId), taskLabel)
    if (recovered) return recovered
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }
}

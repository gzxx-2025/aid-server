import {
fetchUserTaskDetailOnce,
normalizeTaskStatus,
shouldDeferModalTaskFollowFailure,
TASK_SSE_TIMEOUT_MS,
waitUserTaskSseTerminal
} from '~/composables/useTaskSseFollow'
import type { TaskStreamResult } from '~/composables/useTaskStream'
import type {
EditImageCompleteData,
UserStoryboardGenerateEditImageRequest
} from '~/types/business-api'
import { handleSseErrorRecharge,openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { userStoryboardGenerateEditImage } from '~/utils/businessApi'
import { isBenignTaskSseDisconnectMessage } from '~/utils/taskSseSilentDisconnect'

function parseResultData(raw: unknown): EditImageCompleteData | null {
  if (raw == null) return null
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    return raw as EditImageCompleteData
  }
  const s = typeof raw === 'string' ? raw.trim() : ''
  if (!s) return null
  try {
    return JSON.parse(s) as EditImageCompleteData
  } catch {
    return null
  }
}

export type StoryboardEditImageTaskResult =
  | {
      ok: true
      items: Array<{ imageId: number; imageUrl: string; recordId?: number }>
      failCount?: number
      failedItems?: Array<{ index: number; message: string }>
    }
  | { ok: false; errorMessage: string; deferred?: boolean }

/**
 * 分镜编辑图 / 对话作图：POST /api/user/storyboard/generate/edit-image
 * 结果落 aid_gen_record，SSE complete 结构与形态编辑图对齐。
 */
export async function runStoryboardEditImageTask(payload: {
  storyboardId: number
  referenceImage: string
  prompt: string
  modelCode: string
  aspectRatio: string
  size: string
  imageCount: number
  notifyGlobalTasks?: boolean
  onSubmitted?: (info: { taskId: number }) => void
  onProgress?: (p: {
    percent?: number
    stepTitle?: string
    message?: string
    successCount?: number
    totalCount?: number
    items?: Array<{ imageId: number; imageUrl: string; recordId?: number }>
  }) => void
}): Promise<StoryboardEditImageTaskResult> {
  const { storyboardId, referenceImage, prompt, modelCode, aspectRatio, size, imageCount, onSubmitted, onProgress } =
    payload

  let taskId: number
  try {
    const reqBody: UserStoryboardGenerateEditImageRequest = {
      storyboardId,
      referenceImage,
      prompt,
      modelCode,
      aspectRatio,
      size,
      imageCount
    }
    const submitted = await userStoryboardGenerateEditImage(reqBody)
    taskId = Number(submitted.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) {
      return { ok: false, errorMessage: '未返回有效任务ID' }
    }
    onSubmitted?.({ taskId })
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    const msg = String(err?.msg || err?.message || '对话作图提交失败')
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }

  if (payload.notifyGlobalTasks !== false && typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }

  return followStoryboardEditImageTask({ taskId, onProgress })
}

function buildEditSuccess(result: EditImageCompleteData | null): StoryboardEditImageTaskResult | null {
  if (!result?.items?.length) return null
  return {
    ok: true,
    items: result.items,
    failCount: result.failCount,
    failedItems: result.failedItems
  }
}

function emitEditImageCompleteProgress(
  onProgress: ((p: {
    percent?: number
    stepTitle?: string
    message?: string
    successCount?: number
    totalCount?: number
    items?: Array<{ imageId: number; imageUrl: string; recordId?: number }>
  }) => void) | undefined,
  data: unknown
) {
  if (!onProgress) return
  const parsed = parseResultData(data)
  if (!parsed?.items?.length) return
  onProgress({
    successCount: parsed.items.length,
    totalCount: parsed.imageCount ?? parsed.items.length,
    items: parsed.items,
    message: '生成完成',
    stepTitle: '生成完成'
  })
}
function resultFromStreamTerminal(r: TaskStreamResult): StoryboardEditImageTaskResult | null {
  if (r.type === 'complete' || r.type === 'partial_failed') {
    return buildEditSuccess(parseResultData(r.data))
  }
  return null
}

function resultFromTaskDetail(
  d: Awaited<ReturnType<typeof fetchUserTaskDetailOnce>>
): StoryboardEditImageTaskResult | null {
  if (!d) return null
  const st = normalizeTaskStatus(d.status)
  if (st === 'SUCCEEDED') {
    const hit = buildEditSuccess(parseResultData(d.resultData))
    if (hit) return hit
    return { ok: false, errorMessage: '任务成功但未解析到图片' }
  }
  if (st === 'CANCELLED') {
    const hit = buildEditSuccess(parseResultData(d.resultData))
    if (hit) return hit
    return { ok: false, errorMessage: '任务已取消' }
  }
  if (st === 'FAILED') {
    return { ok: false, errorMessage: String(d.errorMessage || '对话作图失败') }
  }
  return null
}

export async function followStoryboardEditImageTask(payload: {
  taskId: number
  onProgress?: (p: {
    percent?: number
    stepTitle?: string
    message?: string
    successCount?: number
    totalCount?: number
    items?: Array<{ imageId: number; imageUrl: string; recordId?: number }>
  }) => void
}): Promise<StoryboardEditImageTaskResult> {
  const taskId = Number(payload.taskId)
  const { onProgress } = payload

  try {
    const terminal = await waitUserTaskSseTerminal({
      taskId,
      timeoutMs: TASK_SSE_TIMEOUT_MS,
      onProgress: (p) => onProgress?.(p)
    })

    if (terminal.kind === 'superseded') {
      return { ok: false, errorMessage: 'Task SSE superseded', deferred: true }
    }

    if (terminal.kind === 'timeout') {
      const recovered = resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId))
      if (recovered) return recovered
      return { ok: false, errorMessage: '对话作图任务超时，请在任务中心查看进度' }
    }

    const r = terminal.event
    if (r.type === 'complete' || r.type === 'partial_failed') {
      emitEditImageCompleteProgress(onProgress, r.data)
    }
    const fromStream = resultFromStreamTerminal(r)
    if (fromStream) return fromStream

    if (r.type === 'error' && isBenignTaskSseDisconnectMessage(r.errorMessage)) {
      if (await shouldDeferModalTaskFollowFailure(taskId, r.errorMessage)) {
        return { ok: false, errorMessage: r.errorMessage, deferred: true }
      }
    }

    const recovered = resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId))
    if (recovered) return recovered

    if (r.type === 'error') {
      const msg = r.errorMessage || '对话作图失败'
      if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
        return { ok: false, errorMessage: msg, deferred: true }
      }
      handleSseErrorRecharge(r.errorData, msg)
      return { ok: false, errorMessage: msg }
    }

    if (r.type === 'cancelled') {
      return { ok: false, errorMessage: r.message || '任务已取消' }
    }

    return { ok: false, errorMessage: '对话作图任务未完成' }
  } catch (e: unknown) {
    const msg = String((e as Error)?.message || '对话作图任务异常')
    if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
      return { ok: false, errorMessage: msg, deferred: true }
    }
    const recovered = resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId))
    if (recovered) return recovered
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }
}

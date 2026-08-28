import {
fetchUserTaskDetailOnce,
isOngoingUserTaskStatus,
normalizeTaskStatus,
shouldDeferModalTaskFollowFailure,
TASK_SSE_TIMEOUT_MS,
waitUserTaskSseTerminal
} from '~/composables/useTaskSseFollow'
import type {
StoryboardGenerateImageData
} from '~/types/business-api'
import { handleSseErrorRecharge,openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { userTaskResume } from '~/utils/businessApi'
import {
type ProjectEpisodeContext
} from '~/utils/storyboardRecordBatch'
import { shouldPreferSseBusinessTerminalOverOngoingDetail } from '~/utils/taskSseSilentDisconnect'
import { dispatchStoryboardImageGenSseTerminal,emitStoryboardImageCompleteProgress,fetchRecordFileUrl,resultFromStreamTerminal,resultFromTaskDetail,submitAndFollowStoryboardImageBatchTask,TASK_BACKGROUND_RUNNING_MESSAGE,type StoryboardImageBatchGenerateResult,type StoryboardImageGenerateProgress,type StoryboardImageGenerateResult } from '~/hooks/storyboardImageGenerateTaskCore'

export async function followStoryboardImageBatchGenerateTask(payload: {
  taskId: number
  onProgress?: (p: StoryboardImageGenerateProgress) => void
}): Promise<StoryboardImageBatchGenerateResult> {
  const taskId = Number(payload.taskId)
  const { onProgress } = payload

  if (!Number.isFinite(taskId) || taskId <= 0) {
    return { ok: false, errorMessage: '任务ID无效' }
  }

  try {
    const terminal = await waitUserTaskSseTerminal({
      taskId,
      timeoutMs: TASK_SSE_TIMEOUT_MS,
      onProgress: (p) => {
        onProgress?.({
          taskId,
          percent: p.percent,
          stepTitle: p.stepTitle,
          message: p.message
        })
      }
    })

    if (terminal.kind === 'superseded') {
      return { ok: false, errorMessage: 'Task SSE superseded', deferred: true }
    }

    if (terminal.kind === 'timeout') {
      const detail = await fetchUserTaskDetailOnce(taskId)
      const st = normalizeTaskStatus(detail?.status)
      if (st === 'SUCCEEDED' || st === 'PARTIAL_FAILED') {
        return { ok: true, taskId, partial: st === 'PARTIAL_FAILED' }
      }
      if (isOngoingUserTaskStatus(st)) {
        return { ok: false, errorMessage: TASK_BACKGROUND_RUNNING_MESSAGE }
      }
      if (st === 'CANCELLED') return { ok: false, errorMessage: '任务已取消' }
      if (st === 'FAILED') {
        const msg = String(detail?.errorMessage || '生成失败')
        openRechargeModalFromInsufficientBalance(msg)
        return { ok: false, errorMessage: msg }
      }
      return { ok: false, errorMessage: '生成超时，请稍后在生成记录中查看' }
    }

    const r = terminal.event
    if (r.type === 'complete' || r.type === 'partial_failed') {
      emitStoryboardImageCompleteProgress(onProgress, { taskId, storyboardId: 0, data: r.data })
    }
    if (r.type === 'complete') {
      return { ok: true, taskId }
    }
    if (r.type === 'partial_failed') {
      return { ok: true, taskId, partial: true }
    }

    const detail = await fetchUserTaskDetailOnce(taskId)
    const st = normalizeTaskStatus(detail?.status)
    if (st === 'SUCCEEDED' || st === 'PARTIAL_FAILED') {
      return { ok: true, taskId, partial: st === 'PARTIAL_FAILED' }
    }
    // SSE 业务 error/cancelled 优先于滞后的 PROCESSING detail，避免永久保活 loading
    if (
      isOngoingUserTaskStatus(st) &&
      !shouldPreferSseBusinessTerminalOverOngoingDetail(r)
    ) {
      return { ok: false, errorMessage: TASK_BACKGROUND_RUNNING_MESSAGE }
    }

    if (r.type === 'error') {
      const msg = r.errorMessage || String(detail?.errorMessage || '生成失败')
      handleSseErrorRecharge(r.errorData, msg)
      dispatchStoryboardImageGenSseTerminal({ taskId, ok: false, errorMessage: msg })
      return { ok: false, errorMessage: msg }
    }

    if (r.type === 'cancelled') {
      const msg = r.message || '任务已取消'
      dispatchStoryboardImageGenSseTerminal({ taskId, ok: false, errorMessage: msg })
      return { ok: false, errorMessage: msg }
    }

    if (st === 'FAILED') {
      const msg = String(detail?.errorMessage || '生成失败')
      openRechargeModalFromInsufficientBalance(msg)
      dispatchStoryboardImageGenSseTerminal({ taskId, ok: false, errorMessage: msg })
      return { ok: false, errorMessage: msg }
    }

    const batchFallbackMsg = String(detail?.errorMessage || '分镜图生成未完成')
    dispatchStoryboardImageGenSseTerminal({ taskId, ok: false, errorMessage: batchFallbackMsg })
    return { ok: false, errorMessage: batchFallbackMsg }
  } catch (e: unknown) {
    const msg = String((e as Error)?.message || '分镜图生成任务异常')
    try {
      const detail = await fetchUserTaskDetailOnce(taskId)
      if (isOngoingUserTaskStatus(detail?.status)) {
        return { ok: false, errorMessage: TASK_BACKGROUND_RUNNING_MESSAGE }
      }
    } catch {
      /* keep original error */
    }
    openRechargeModalFromInsufficientBalance(msg)
    dispatchStoryboardImageGenSseTerminal({ taskId, ok: false, errorMessage: msg })
    return { ok: false, errorMessage: msg }
  }
}

/** PARTIAL_FAILED 续生：POST /api/user/task/resume */
export async function resumeStoryboardImageGenerateTask(payload: {
  taskId: number
  onProgress?: (p: StoryboardImageGenerateProgress) => void
}): Promise<StoryboardImageBatchGenerateResult> {
  const taskId = Number(payload.taskId)
  if (!Number.isFinite(taskId) || taskId <= 0) {
    return { ok: false, errorMessage: '任务ID无效' }
  }
  return submitAndFollowStoryboardImageBatchTask({
    submit: () => userTaskResume({ taskId }) as Promise<StoryboardGenerateImageData>,
    followTask: followStoryboardImageBatchGenerateTask,
    onProgress: payload.onProgress,
    progressMessage: '分镜图续生中…',
    submitErrorMessage: '续生分镜图失败'
  })
}

/**
 * 追踪已提交的分镜图生成（单镜头弹窗 / 恢复），并通过 SSE 追踪进度。
 */
export async function followStoryboardImageGenerateTask(payload: {
  taskId: number
  storyboardId: number
  recordId?: number | null
  projectEpisode?: ProjectEpisodeContext | null
  onProgress?: (p: StoryboardImageGenerateProgress) => void
}): Promise<StoryboardImageGenerateResult> {
  const taskId = Number(payload.taskId)
  const storyboardId = Number(payload.storyboardId)
  const recordId =
    payload.recordId != null && Number.isFinite(Number(payload.recordId)) && Number(payload.recordId) > 0
      ? Number(payload.recordId)
      : null
  const { onProgress, projectEpisode } = payload
  const ctx = { taskId, storyboardId, recordId, projectEpisode }

  if (!Number.isFinite(taskId) || taskId <= 0) {
    return { ok: false, errorMessage: '任务ID无效' }
  }

  try {
    const terminal = await waitUserTaskSseTerminal({
      taskId,
      timeoutMs: TASK_SSE_TIMEOUT_MS,
      onProgress: (p) => {
        onProgress?.({
          percent: p.percent,
          stepTitle: p.stepTitle,
          message: p.message,
          taskId,
          recordId
        })
      }
    })

    if (terminal.kind === 'superseded') {
      return { ok: false, errorMessage: 'Task SSE superseded', deferred: true }
    }

    if (terminal.kind === 'timeout') {
      if (recordId != null && Number.isFinite(storyboardId) && storyboardId > 0) {
        const url = await fetchRecordFileUrl(projectEpisode, storyboardId, recordId)
        if (url) return { ok: true, taskId, recordId, imageUrl: url }
      }
      const recovered = await resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId), ctx)
      if (recovered) {
        if (recovered.ok) {
          emitStoryboardImageCompleteProgress(onProgress, {
            taskId,
            storyboardId,
            data: {
              recordId: recovered.recordId,
              imageUrl: recovered.imageUrl,
              items: recovered.items
            },
            fallbackRecordId: recordId
          })
        }
        return recovered
      }
      return { ok: false, errorMessage: '生成超时，请稍后在生成记录中查看' }
    }

    const r = terminal.event
    if (r.type === 'error' && (await shouldDeferModalTaskFollowFailure(taskId, r.errorMessage))) {
      return { ok: false, errorMessage: r.errorMessage, deferred: true }
    }
    if (r.type === 'complete' || r.type === 'partial_failed') {
      emitStoryboardImageCompleteProgress(onProgress, {
        taskId,
        storyboardId,
        data: r.data,
        fallbackRecordId: recordId
      })
    }
    const fromStream = await resultFromStreamTerminal(r, ctx)
    if (fromStream) return fromStream

    const recovered = await resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId), ctx)
    if (recovered) {
      if (recovered.ok) {
        emitStoryboardImageCompleteProgress(onProgress, {
          taskId,
          storyboardId,
          data: {
            recordId: recovered.recordId,
            imageUrl: recovered.imageUrl,
            items: recovered.items
          },
          fallbackRecordId: recordId
        })
      } else {
        dispatchStoryboardImageGenSseTerminal({
          taskId,
          storyboardId,
          ok: false,
          errorMessage: 'errorMessage' in recovered ? recovered.errorMessage : '生成失败'
        })
      }
      return recovered
    }

    if (r.type === 'error') {
      const msg = r.errorMessage || '生成失败'
      handleSseErrorRecharge(r.errorData, msg)
      dispatchStoryboardImageGenSseTerminal({
        taskId,
        storyboardId,
        ok: false,
        errorMessage: msg
      })
      return { ok: false, errorMessage: msg }
    }

    if (r.type === 'cancelled') {
      const msg = r.message || '任务已取消'
      dispatchStoryboardImageGenSseTerminal({
        taskId,
        storyboardId,
        ok: false,
        errorMessage: msg
      })
      return { ok: false, errorMessage: msg }
    }

    if (recordId != null && Number.isFinite(storyboardId) && storyboardId > 0) {
      const url = await fetchRecordFileUrl(projectEpisode, storyboardId, recordId)
      if (url) return { ok: true, taskId, recordId, imageUrl: url }
    }

    const fallbackMsg = '分镜图生成未完成'
    dispatchStoryboardImageGenSseTerminal({
      taskId,
      storyboardId,
      ok: false,
      errorMessage: fallbackMsg
    })
    return { ok: false, errorMessage: fallbackMsg }
  } catch (e: unknown) {
    if (recordId != null && Number.isFinite(storyboardId) && storyboardId > 0) {
      const url = await fetchRecordFileUrl(projectEpisode, storyboardId, recordId)
      if (url) return { ok: true, taskId, recordId, imageUrl: url }
    }
    const msg = String((e as Error)?.message || '分镜图生成任务异常')
    openRechargeModalFromInsufficientBalance(msg)
    dispatchStoryboardImageGenSseTerminal({
      taskId,
      storyboardId,
      ok: false,
      errorMessage: msg
    })
    return { ok: false, errorMessage: msg }
  }
}

/** 批量出图：POST /api/user/storyboard/generate/image */

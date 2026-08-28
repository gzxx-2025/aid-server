import {
  fetchUserTaskDetailOnce,
  isOngoingUserTaskStatus,
  normalizeTaskStatus,
  shouldDeferModalTaskFollowFailure,
  TASK_SSE_TIMEOUT_MS,
  waitUserTaskSseTerminal
} from '~/composables/useTaskSseFollow'
import { handleSseErrorRecharge, openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { userStoryboardAudioTask } from '~/utils/businessApi'
import {
  parseLipSyncCompleteItem,
  resolveLipSyncProgressPreview,
  resolveLipSyncVideoDisplayUrl
} from '~/utils/storyboardLipSyncSse'
import { shouldPreferSseBusinessTerminalOverOngoingDetail } from '~/utils/taskSseSilentDisconnect'
import type { StoryboardDubbingGenerateProgress, StoryboardDubbingGenerateResult } from '~/composables/useStoryboardDubbingGenerate'

const TASK_BACKGROUND_RUNNING_MESSAGE = '任务仍在后台执行，请稍候或刷新页面自动恢复进度'

async function resolveVideoUrlFromComplete(opts: {
  completeData: unknown
  audioRecordId?: number
}): Promise<{
  videoUrl: string
  audioRecordId: number
  lipSyncVideoRecordId?: number
}> {
  const item = parseLipSyncCompleteItem(opts.completeData)
  const fromItem = resolveLipSyncVideoDisplayUrl(item?.lipSyncVideoUrl)
  const audioRecordId =
    finitePositive(item?.audioRecordId) ?? finitePositive(opts.audioRecordId) ?? 0
  const lipSyncVideoRecordId = finitePositive(item?.lipSyncVideoRecordId)

  if (fromItem && /^https?:\/\//i.test(fromItem)) {
    return {
      videoUrl: fromItem,
      audioRecordId,
      ...(lipSyncVideoRecordId != null ? { lipSyncVideoRecordId } : {})
    }
  }

  if (audioRecordId > 0) {
    try {
      const vo = await userStoryboardAudioTask(audioRecordId)
      const syncUrl = String(vo.syncVideoUrl || '').trim()
      if (syncUrl) {
        return {
          videoUrl: syncUrl,
          audioRecordId,
          ...(lipSyncVideoRecordId != null ? { lipSyncVideoRecordId } : {})
        }
      }
    } catch {
      /* ignore，回退 relative / 空 */
    }
  }

  if (fromItem) {
    return {
      videoUrl: fromItem,
      audioRecordId,
      ...(lipSyncVideoRecordId != null ? { lipSyncVideoRecordId } : {})
    }
  }

  throw new Error('对口型完成，但未返回可用地址')
}

function finitePositive(v: unknown): number | undefined {
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return undefined
  return Math.trunc(n)
}

/**
 * 单个对口型：订阅 /task/stream/{taskId} 至终态。
 * 配音阶段 progress 透出 audioUrl 供试听；不与 audio 轮询双跟。
 */
export async function followStoryboardLipSyncSseJob(payload: {
  taskId: number
  onProgress?: (p: StoryboardDubbingGenerateProgress) => void
}): Promise<StoryboardDubbingGenerateResult> {
  const taskId = Number(payload.taskId)
  if (!Number.isFinite(taskId) || taskId <= 0) {
    return { ok: false, errorMessage: '对口型任务信息无效' }
  }

  let lastAudioRecordId = 0

  try {
    payload.onProgress?.({
      taskId,
      message: '对口型任务已提交…',
      stepTitle: '对口型任务已提交…'
    })

    const terminal = await waitUserTaskSseTerminal({
      taskId,
      timeoutMs: TASK_SSE_TIMEOUT_MS,
      onProgress: (p) => {
        const preview = resolveLipSyncProgressPreview(p)
        if (preview?.audioRecordId) lastAudioRecordId = preview.audioRecordId
        else if (finitePositive(p.audioRecordId)) lastAudioRecordId = finitePositive(p.audioRecordId)!

        const message =
          preview?.message ||
          String(p.stepTitle || p.message || '').trim() ||
          '对口型处理中…'
        payload.onProgress?.({
          taskId,
          percent: p.percent,
          message,
          stepTitle: preview?.stepTitle || message,
          ...(lastAudioRecordId > 0 ? { audioRecordId: lastAudioRecordId } : {}),
          ...(preview?.audioUrl ? { audioUrl: preview.audioUrl } : {}),
          ...(preview?.durationMs != null ? { durationMs: preview.durationMs } : {})
        })
      }
    })

    if (terminal.kind === 'superseded') {
      return { ok: false, errorMessage: 'Task SSE superseded', deferred: true }
    }

    if (terminal.kind === 'timeout') {
      const detail = await fetchUserTaskDetailOnce(taskId)
      const st = normalizeTaskStatus(detail?.status)
      if (st === 'SUCCEEDED') {
        const resolved = await resolveVideoUrlFromComplete({
          completeData: detail?.resultData,
          audioRecordId: lastAudioRecordId
        })
        return {
          ok: true,
          composeBatchId: '',
          audioRecordId: resolved.audioRecordId,
          videoUrl: resolved.videoUrl,
          taskId,
          ...(resolved.lipSyncVideoRecordId != null
            ? { lipSyncVideoRecordId: resolved.lipSyncVideoRecordId }
            : {})
        }
      }
      if (isOngoingUserTaskStatus(st)) {
        return { ok: false, errorMessage: TASK_BACKGROUND_RUNNING_MESSAGE, deferred: true }
      }
      if (st === 'CANCELLED') return { ok: false, errorMessage: '任务已取消' }
      if (st === 'FAILED') {
        const msg = String(detail?.errorMessage || '对口型失败')
        openRechargeModalFromInsufficientBalance(msg)
        return { ok: false, errorMessage: msg }
      }
      return { ok: false, errorMessage: '对口型超时，请稍后在生成记录中查看' }
    }

    const ev = terminal.event

    if (ev.type === 'complete') {
      const resolved = await resolveVideoUrlFromComplete({
        completeData: ev.data,
        audioRecordId: lastAudioRecordId
      })
      return {
        ok: true,
        composeBatchId: '',
        audioRecordId: resolved.audioRecordId,
        videoUrl: resolved.videoUrl,
        taskId,
        ...(resolved.lipSyncVideoRecordId != null
          ? { lipSyncVideoRecordId: resolved.lipSyncVideoRecordId }
          : {})
      }
    }

    const detail = await fetchUserTaskDetailOnce(taskId)
    const st = normalizeTaskStatus(detail?.status)
    if (st === 'SUCCEEDED') {
      const resolved = await resolveVideoUrlFromComplete({
        completeData: detail?.resultData ?? (ev.type === 'partial_failed' ? ev.data : undefined),
        audioRecordId: lastAudioRecordId
      })
      return {
        ok: true,
        composeBatchId: '',
        audioRecordId: resolved.audioRecordId,
        videoUrl: resolved.videoUrl,
        taskId,
        ...(resolved.lipSyncVideoRecordId != null
          ? { lipSyncVideoRecordId: resolved.lipSyncVideoRecordId }
          : {})
      }
    }
    // SSE 业务 error/cancelled 优先于滞后的 PROCESSING detail，避免永久保活 loading
    if (
      isOngoingUserTaskStatus(st) &&
      !shouldPreferSseBusinessTerminalOverOngoingDetail(ev)
    ) {
      return { ok: false, errorMessage: TASK_BACKGROUND_RUNNING_MESSAGE, deferred: true }
    }

    if (ev.type === 'error') {
      const msg = ev.errorMessage || String(detail?.errorMessage || '对口型失败')
      if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
        return { ok: false, errorMessage: msg, deferred: true }
      }
      handleSseErrorRecharge(ev.errorData, msg)
      return { ok: false, errorMessage: msg }
    }

    if (ev.type === 'cancelled') {
      return { ok: false, errorMessage: ev.message || '任务已取消' }
    }

    if (st === 'FAILED') {
      const msg = String(detail?.errorMessage || '对口型失败')
      openRechargeModalFromInsufficientBalance(msg)
      return { ok: false, errorMessage: msg }
    }

    return { ok: false, errorMessage: String(detail?.errorMessage || '对口型未完成') }
  } catch (e: unknown) {
    const msg = String((e as Error)?.message || '对口型失败')
    try {
      if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
        return { ok: false, errorMessage: msg, deferred: true }
      }
      const detail = await fetchUserTaskDetailOnce(taskId)
      if (isOngoingUserTaskStatus(detail?.status)) {
        return { ok: false, errorMessage: TASK_BACKGROUND_RUNNING_MESSAGE, deferred: true }
      }
    } catch {
      /* keep */
    }
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }
}

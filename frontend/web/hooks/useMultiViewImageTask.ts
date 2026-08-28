import {
fetchUserTaskDetailOnce,
normalizeTaskStatus,
shouldDeferModalTaskFollowFailure,
TASK_SSE_TIMEOUT_MS,
waitUserTaskSseTerminal
} from '~/composables/useTaskSseFollow'
import type { TaskStreamResult } from '~/composables/useTaskStream'
import type { UserAssetExtractFormGenerateMultiViewImageRequest } from '~/types/business-api'
import { handleSseErrorRecharge,openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { userAssetExtractFormGenerateMultiViewImage } from '~/utils/businessApi'
import { isBenignTaskSseDisconnectMessage } from '~/utils/taskSseSilentDisconnect'

function parseResultData(raw: unknown): { imageUrl: string | null; imageId: number | null; formId: number | null } {
  if (raw == null) return { imageUrl: null, imageId: null, formId: null }
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    return parseResultFromRecord(raw as Record<string, unknown>)
  }
  const s = typeof raw === 'string' ? raw.trim() : ''
  if (!s) return { imageUrl: null, imageId: null, formId: null }
  try {
    const o = JSON.parse(s) as Record<string, unknown>
    return parseResultFromRecord(o)
  } catch {
    return { imageUrl: null, imageId: null, formId: null }
  }
}

function parseResultFromRecord(o: Record<string, unknown>): {
  imageUrl: string | null
  imageId: number | null
  formId: number | null
} {
  const u = o?.imageUrl
  const imageUrl = typeof u === 'string' && u.trim() ? u.trim() : null
  const idRaw = o?.imageId ?? o?.id
  const imageId = idRaw != null && Number.isFinite(Number(idRaw)) && Number(idRaw) > 0 ? Number(idRaw) : null
  const fRaw = o?.formId
  const formId = fRaw != null && Number.isFinite(Number(fRaw)) && Number(fRaw) > 0 ? Number(fRaw) : null
  return { imageUrl, imageId, formId }
}

function buildMultiViewSuccess(parsed: {
  imageUrl: string | null
  imageId: number | null
  formId: number | null
}): MultiViewImageTaskResult | null {
  if (!parsed.imageUrl) return null
  return {
    ok: true,
    imageUrl: parsed.imageUrl,
    imageId: parsed.imageId,
    formId: parsed.formId
  }
}

function resultFromStreamTerminal(r: TaskStreamResult): MultiViewImageTaskResult | null {
  if (r.type !== 'complete') return null
  return buildMultiViewSuccess(parseResultData(r.data))
}

function resultFromTaskDetail(
  d: Awaited<ReturnType<typeof fetchUserTaskDetailOnce>>
): MultiViewImageTaskResult | null {
  if (!d) return null
  const st = normalizeTaskStatus(d.status)
  if (st === 'SUCCEEDED') {
    const hit = buildMultiViewSuccess(parseResultData(d.resultData))
    if (hit) return hit
    return { ok: false, errorMessage: '任务成功但未解析到图片地址' }
  }
  if (st === 'CANCELLED') {
    return { ok: false, errorMessage: '任务已取消' }
  }
  if (st === 'FAILED') {
    return { ok: false, errorMessage: String(d.errorMessage || '多机位生图失败') }
  }
  return null
}

export type MultiViewImageTaskResult =
  | { ok: true; imageUrl: string; imageId: number | null; formId: number | null }
  | { ok: false; errorMessage: string; deferred?: boolean }

/**
 * 提交多机位形态生图任务 → SSE + 轮询任务详情兜底，解析 resultData。
 *
 * 对齐接口文档 v2.35.0：POST /api/user/asset/extract/form/generate-multi-view-image
 */
export async function runMultiViewImageTask(payload: {
  formId: number
  imageUrl: string
  anglePrompt: string
  modelCode: string
  aspectRatio?: string
  onProgress?: (p: { percent?: number; stepTitle?: string; message?: string }) => void
  onSubmitted?: (p: { taskId: number }) => void
}): Promise<MultiViewImageTaskResult> {
  const { formId, imageUrl, anglePrompt, modelCode, aspectRatio, onProgress } = payload

  // 1. 提交任务
  let taskId: number
  try {
    const reqBody: UserAssetExtractFormGenerateMultiViewImageRequest = {
      formId,
      imageUrl,
      anglePrompt,
      modelCode,
      ...(aspectRatio ? { aspectRatio } : {})
    }
    const submitted = await userAssetExtractFormGenerateMultiViewImage(reqBody)
    taskId = Number(submitted.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) {
      return { ok: false, errorMessage: '未返回有效任务ID' }
    }
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    const msg = String(err?.msg || err?.message || '多机位生图提交失败')
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }

  payload.onSubmitted?.({ taskId })

  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }

  return followMultiViewImageTask({ taskId, onProgress })
}

export async function followMultiViewImageTask(payload: {
  taskId: number
  onProgress?: (p: { percent?: number; stepTitle?: string; message?: string }) => void
}): Promise<MultiViewImageTaskResult> {
  const taskId = Number(payload.taskId)
  const { onProgress } = payload
  if (!Number.isFinite(taskId) || taskId <= 0) {
    return { ok: false, errorMessage: '任务ID无效' }
  }

  try {
    const terminal = await waitUserTaskSseTerminal({
      taskId,
      timeoutMs: TASK_SSE_TIMEOUT_MS,
      onProgress
    })

    if (terminal.kind === 'superseded') {
      return { ok: false, errorMessage: 'Task SSE superseded', deferred: true }
    }

    if (terminal.kind === 'timeout') {
      const recovered = resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId))
      if (recovered) return recovered
      return { ok: false, errorMessage: '多机位生图任务超时，请在任务中心查看进度' }
    }

    const r = terminal.event
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
      const msg = r.errorMessage || '多机位生图失败'
      if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
        return { ok: false, errorMessage: msg, deferred: true }
      }
      handleSseErrorRecharge(r.errorData, msg)
      return { ok: false, errorMessage: msg }
    }

    if (r.type === 'cancelled') {
      return { ok: false, errorMessage: r.message || '任务已取消' }
    }

    return { ok: false, errorMessage: '多机位任务未完成' }
  } catch (e: unknown) {
    const msg = String((e as Error)?.message || '多机位任务异常')
    if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
      return { ok: false, errorMessage: msg, deferred: true }
    }
    const recovered = resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId))
    if (recovered) return recovered
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }
}

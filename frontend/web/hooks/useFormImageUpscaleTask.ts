import {
fetchUserTaskDetailOnce,
normalizeTaskStatus,
shouldDeferModalTaskFollowFailure,
TASK_SSE_TIMEOUT_MS,
waitUserTaskSseTerminal
} from '~/composables/useTaskSseFollow'
import type { TaskStreamResult } from '~/composables/useTaskStream'
import type { EnhanceImageMode } from '~/types/enhanceImageMode'
import { handleSseErrorRecharge,openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { userAssetRpsFormImageUpscale } from '~/utils/businessApi'
import { isBenignTaskSseDisconnectMessage } from '~/utils/taskSseSilentDisconnect'

export type FormImageUpscaleResult =
  | { ok: true; imageUrl: string }
  | { ok: false; errorMessage: string; deferred?: boolean }

/** 变清晰模式 → 接口 resolution（不传 modelCode，后端使用默认高清模型） */
export function upscaleApiParamsForEnhanceMode(mode: EnhanceImageMode): {
  resolution?: string
} {
  if (mode === 'redraw_ultra') {
    return { resolution: '8k' }
  }
  return { resolution: '2k' }
}

function parseResultDataImageUrl(raw: unknown): string | null {
  if (raw == null) return null
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    const o = raw as Record<string, unknown>
    const u = o.imageUrl ?? o.image_url ?? o.url
    if (typeof u === 'string' && u.trim()) return u.trim()
  }
  const s = typeof raw === 'string' ? raw.trim() : ''
  if (!s) return null
  try {
    const o = JSON.parse(s) as { imageUrl?: string; image_url?: string; url?: string }
    const u = o?.imageUrl ?? o?.image_url ?? o?.url
    return typeof u === 'string' && u.trim() ? u.trim() : null
  } catch {
    return null
  }
}

function resultFromStreamTerminal(r: TaskStreamResult): FormImageUpscaleResult | null {
  if (r.type !== 'complete') return null
  const url = parseResultDataImageUrl(r.data)
  return url ? { ok: true, imageUrl: url } : null
}

function resultFromTaskDetail(d: Awaited<ReturnType<typeof fetchUserTaskDetailOnce>>): FormImageUpscaleResult | null {
  if (!d) return null
  const st = normalizeTaskStatus(d.status)
  if (st === 'SUCCEEDED') {
    const url = parseResultDataImageUrl(d.resultData)
    return url ? { ok: true, imageUrl: url } : null
  }
  if (st === 'FAILED') {
    return { ok: false, errorMessage: String(d.errorMessage || '高清任务失败') }
  }
  if (st === 'CANCELLED') {
    return { ok: false, errorMessage: String(d.errorMessage || '任务已取消') }
  }
  return null
}

/**
 * 提交 form-image upscale → SSE 等待终态，结束后最多补查一次 task/detail。
 */
export async function runFormImageUpscaleTask(payload: {
  imageId: number
  modelCode: string
  /** 来自 image_upscale 模型池 defaultSizeCode；未传则回退 mode 默认档 */
  resolution?: string
  /** 兼容旧调用：未传 resolution 时按 mode 回退 */
  mode?: EnhanceImageMode
  onProgress?: (p: { percent?: number; stepTitle?: string; message?: string }) => void
  onSubmitted?: (p: { taskId: number }) => void
}): Promise<FormImageUpscaleResult> {
  const { imageId, modelCode, onProgress } = payload
  const code = String(modelCode || '').trim()
  if (!code) {
    return { ok: false, errorMessage: '请选择模型' }
  }

  const explicit = String(payload.resolution || '').trim()
  const { resolution } = explicit
    ? { resolution: explicit }
    : upscaleApiParamsForEnhanceMode(payload.mode || 'redraw_ultra')

  let taskId: number
  try {
    const submitted = await userAssetRpsFormImageUpscale({ imageId, modelCode: code, resolution })
    taskId = Number(submitted.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) {
      return { ok: false, errorMessage: '未返回有效任务ID' }
    }
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    const msg = String(err?.msg || err?.message || '提交高清任务失败')
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }

  payload.onSubmitted?.({ taskId })

  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }

  return followFormImageUpscaleTask({ taskId, onProgress })
}

export async function followFormImageUpscaleTask(payload: {
  taskId: number
  onProgress?: (p: { percent?: number; stepTitle?: string; message?: string }) => void
}): Promise<FormImageUpscaleResult> {
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
      return { ok: false, errorMessage: '高清任务超时，请在任务中心查看进度' }
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
      const msg = r.errorMessage || '高清任务失败'
      if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
        return { ok: false, errorMessage: msg, deferred: true }
      }
      handleSseErrorRecharge(r.errorData, msg)
      return { ok: false, errorMessage: msg }
    }

    if (r.type === 'cancelled') {
      return { ok: false, errorMessage: r.message || '任务已取消' }
    }

    return { ok: false, errorMessage: '高清任务未完成' }
  } catch (e: unknown) {
    const msg = String((e as Error)?.message || '高清任务异常')
    if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
      return { ok: false, errorMessage: msg, deferred: true }
    }
    const recovered = resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId))
    if (recovered) return recovered
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }
}

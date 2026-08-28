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
FormCreationImageGenMode,
UserAssetExtractFormGenerateCreationImageRequest
} from '~/types/business-api'
import { handleSseErrorRecharge,openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { userAssetExtractFormGenerateCreationImage } from '~/utils/businessApi'
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

export type EditImageTaskProgress = {
  percent?: number
  stepTitle?: string
  message?: string
  processedCount?: number
  successCount?: number
  totalCount?: number
  items?: Array<{ imageId: number; imageUrl: string }>
}

export type EditImageTaskResult =
  | {
      ok: true
      items: Array<{ imageId: number; imageUrl: string }>
      failCount?: number
      failedItems?: Array<{ index: number; message: string }>
    }
  | { ok: false; errorMessage: string; deferred?: boolean }

/** 格式化 form_edit_chat 任务进度文案（按已处理张数 processedCount / totalCount） */
export function formatCreationImageProgressText(p: EditImageTaskProgress): string {
  const total = p.totalCount
  const processed =
    typeof p.processedCount === 'number'
      ? p.processedCount
      : typeof p.successCount === 'number'
        ? p.successCount
        : undefined
  if (total != null && processed != null && total > 0) {
    return `已处理 ${processed}/${total} 张…`
  }
  return p.stepTitle || p.message || '生图中…'
}

function mapStreamProgress(raw: Record<string, unknown>): EditImageTaskProgress {
  return {
    percent: typeof raw.progress === 'number' ? raw.progress : undefined,
    stepTitle: typeof raw.stepTitle === 'string' ? raw.stepTitle : undefined,
    message: typeof raw.message === 'string' ? raw.message : undefined,
    processedCount:
      typeof raw.processedCount === 'number'
        ? raw.processedCount
        : typeof raw.currentCount === 'number'
          ? raw.currentCount
          : undefined,
    successCount: typeof raw.successCount === 'number' ? raw.successCount : undefined,
    totalCount: typeof raw.totalCount === 'number' ? raw.totalCount : undefined,
    items: Array.isArray(raw.items)
      ? (raw.items as Array<{ imageId: number; imageUrl: string }>)
      : undefined
  }
}

/**
 * 提交形态图片创作任务（编辑图片 edit / 对话作图 chat）→ SSE + 轮询兜底。
 *
 * POST /api/user/asset/extract/form/generate-creation-image
 */
export async function runEditImageTask(payload: {
  formId: number
  genMode: FormCreationImageGenMode
  referenceImages?: string[]
  prompt: string
  modelCode: string
  aspectRatio: string
  size: string
  imageCount: number
  onProgress?: (p: EditImageTaskProgress) => void
  onSubmitted?: (p: { taskId: number }) => void
}): Promise<EditImageTaskResult> {
  const {
    formId,
    genMode,
    referenceImages,
    prompt,
    modelCode,
    aspectRatio,
    size,
    imageCount
  } = payload

  const mode = String(genMode || '').trim().toLowerCase() as FormCreationImageGenMode
  const refList = (referenceImages ?? []).map((u) => String(u || '').trim()).filter(Boolean)

  if (mode === 'edit' && refList.length === 0) {
    return { ok: false, errorMessage: '参考图不能空' }
  }

  let taskId: number
  try {
    const reqBody: UserAssetExtractFormGenerateCreationImageRequest = {
      formId,
      genMode: mode,
      prompt,
      modelCode,
      aspectRatio,
      size,
      imageCount,
      ...(refList.length > 0 ? { referenceImages: refList } : {})
    }
    const submitted = await userAssetExtractFormGenerateCreationImage(reqBody)
    taskId = Number(submitted.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) {
      return { ok: false, errorMessage: '未返回有效任务ID' }
    }
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    const msg = String(err?.msg || err?.message || '生图任务提交失败')
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }

  payload.onSubmitted?.({ taskId })

  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }

  return followEditImageTask({ taskId, onProgress: payload.onProgress })
}

function buildEditImageSuccess(result: EditImageCompleteData | null): EditImageTaskResult | null {
  if (!result?.items?.length) return null
  return {
    ok: true,
    items: result.items,
    failCount: result.failCount,
    failedItems: result.failedItems
  }
}

function resultFromStreamTerminal(r: TaskStreamResult): EditImageTaskResult | null {
  if (r.type === 'complete' || r.type === 'partial_failed') {
    return buildEditImageSuccess(parseResultData(r.data))
  }
  return null
}

function resultFromTaskDetail(
  d: Awaited<ReturnType<typeof fetchUserTaskDetailOnce>>
): EditImageTaskResult | null {
  if (!d) return null
  const st = normalizeTaskStatus(d.status)
  if (st === 'SUCCEEDED' || st === 'PARTIAL_FAILED') {
    const hit = buildEditImageSuccess(parseResultData(d.resultData))
    if (hit) return hit
    return { ok: false, errorMessage: '任务成功但未解析到图片' }
  }
  if (st === 'CANCELLED') {
    const hit = buildEditImageSuccess(parseResultData(d.resultData))
    if (hit) return hit
    return { ok: false, errorMessage: '任务已取消' }
  }
  if (st === 'FAILED') {
    return { ok: false, errorMessage: String(d.errorMessage || '生图失败') }
  }
  return null
}

/**
 * 追踪已提交的形态图片创作任务（SSE 等待终态，结束后最多补查一次 task/detail）。
 */
export async function followEditImageTask(payload: {
  taskId: number
  onProgress?: (p: EditImageTaskProgress) => void
}): Promise<EditImageTaskResult> {
  const taskId = Number(payload.taskId)
  const { onProgress } = payload
  if (!Number.isFinite(taskId) || taskId <= 0) {
    return { ok: false, errorMessage: '任务ID无效' }
  }

  try {
    const terminal = await waitUserTaskSseTerminal({
      taskId,
      timeoutMs: TASK_SSE_TIMEOUT_MS,
      onProgress: (p) => onProgress?.(mapStreamProgress(p as Record<string, unknown>))
    })

    if (terminal.kind === 'superseded') {
      return { ok: false, errorMessage: 'Task SSE superseded', deferred: true }
    }

    if (terminal.kind === 'timeout') {
      const recovered = resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId))
      if (recovered) return recovered
      return { ok: false, errorMessage: '生图任务超时，请在任务中心查看进度' }
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
      const msg = r.errorMessage || '生图失败'
      if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
        return { ok: false, errorMessage: msg, deferred: true }
      }
      handleSseErrorRecharge(r.errorData, msg)
      return { ok: false, errorMessage: msg }
    }

    if (r.type === 'cancelled') {
      return { ok: false, errorMessage: r.message || '任务已取消' }
    }

    return { ok: false, errorMessage: '生图任务未完成' }
  } catch (e: unknown) {
    const msg = String((e as Error)?.message || '生图任务异常')
    if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
      return { ok: false, errorMessage: msg, deferred: true }
    }
    const recovered = resultFromTaskDetail(await fetchUserTaskDetailOnce(taskId))
    if (recovered) return recovered
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }
}

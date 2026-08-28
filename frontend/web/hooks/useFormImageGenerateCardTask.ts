import {
fetchUserTaskDetailOnce,
normalizeTaskStatus,
shouldDeferModalTaskFollowFailure,
TASK_SSE_TIMEOUT_MS,
waitUserTaskSseTerminal
} from '~/composables/useTaskSseFollow'
import type { TaskStreamResult } from '~/composables/useTaskStream'
import type { UserAssetExtractFormGenerateCardImageRequest } from '~/types/business-api'
import { handleSseErrorRecharge,openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { userAssetExtractFormGenerateCardImage } from '~/utils/businessApi'
import { resolveFormImageBatchCompleteOutcome } from '~/utils/formImageTaskOutcome'
import { CHARACTER_CARD_SCENE_CODE,resolveProjectGenImageSubmitFields } from '~/utils/projectGenConfig'
import { isBenignTaskSseDisconnectMessage } from '~/utils/taskSseSilentDisconnect'

function parseResultFromRecord(o: Record<string, unknown>): {
  imageUrl: string | null
  imageId: number | null
} {
  const pickUrl = (v: unknown): string | null => {
    if (typeof v !== 'string' || !v.trim()) return null
    return v.trim()
  }
  const pickId = (v: unknown): number | null => {
    const n = Number(v)
    return Number.isFinite(n) && n > 0 ? n : null
  }

  const directUrl = pickUrl(
    o.imageUrl ?? o.image_url ?? o.url ?? o.cardImageUrl ?? o.card_image_url
  )
  const directId = pickId(
    o.imageId ?? o.image_id ?? o.id ?? o.cardImageId ?? o.card_image_id
  )
  if (directUrl) return { imageUrl: directUrl, imageId: directId }

  const body = o.body
  if (body && typeof body === 'object' && !Array.isArray(body)) {
    const nested = parseResultFromRecord(body as Record<string, unknown>)
    if (nested.imageUrl) return nested
  }

  const data = o.data
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    const nested = parseResultFromRecord(data as Record<string, unknown>)
    if (nested.imageUrl) return nested
  }

  const successItems = Array.isArray(o.successItems) ? o.successItems : []
  for (const item of successItems) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue
    const nested = parseResultFromRecord(item as Record<string, unknown>)
    if (nested.imageUrl) return nested
  }

  return { imageUrl: null, imageId: null }
}

function parseResultData(raw: unknown): { imageUrl: string | null; imageId: number | null } {
  if (raw == null) return { imageUrl: null, imageId: null }
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    return parseResultFromRecord(raw as Record<string, unknown>)
  }
  const s = typeof raw === 'string' ? raw.trim() : ''
  if (!s) return { imageUrl: null, imageId: null }
  try {
    const o = JSON.parse(s) as Record<string, unknown>
    return parseResultFromRecord(o)
  } catch {
    return { imageUrl: null, imageId: null }
  }
}

export function parseFormCardImageTaskResult(raw: unknown): {
  imageUrl: string | null
  imageId: number | null
} {
  return parseResultData(raw)
}

export type FormImageGenerateCardResult =
  | {
      ok: true
      imageUrl?: string
      imageId?: number | null
      batch?: {
        successCount: number
        failCount: number
        partialFailMessages?: string[]
      }
    }
  | { ok: false; errorMessage: string; deferred?: boolean }

function buildSingleSuccessResult(parsed: {
  imageUrl: string | null
  imageId: number | null
}): FormImageGenerateCardResult | null {
  if (!parsed.imageUrl) return null
  return { ok: true, imageUrl: parsed.imageUrl, imageId: parsed.imageId }
}

function resultFromBatchPayload(raw: unknown): FormImageGenerateCardResult | null {
  const batchOutcome = resolveFormImageBatchCompleteOutcome(raw)
  if (!batchOutcome) return null
  if (batchOutcome.ok === false) {
    return { ok: false, errorMessage: batchOutcome.errorMessage, ...(batchOutcome.failCount != null ? {} : {}) }
  }
  const parsed = parseResultData(raw)
  return {
    ok: true,
    batch: {
      successCount: batchOutcome.successCount,
      failCount: batchOutcome.failCount,
      ...(batchOutcome.partialFailMessages?.length
        ? { partialFailMessages: batchOutcome.partialFailMessages }
        : {})
    },
    ...(parsed.imageUrl ? { imageUrl: parsed.imageUrl, imageId: parsed.imageId } : {})
  }
}

function resultFromStreamTerminal(r: TaskStreamResult): FormImageGenerateCardResult | null {
  if (r.type === 'complete' || r.type === 'partial_failed') {
    const batch = resultFromBatchPayload(r.data)
    if (batch) return batch
    return buildSingleSuccessResult(parseResultData(r.data))
  }
  return null
}

/** 已取得 task/detail 时纯解析单张/批量终态，调用方无需再发 detail 请求。 */
export function resolveFormImageGenerateCardResultFromTaskDetail(
  d: Awaited<ReturnType<typeof fetchUserTaskDetailOnce>>
): FormImageGenerateCardResult | null {
  if (!d) return null
  const st = normalizeTaskStatus(d.status)
  if (st === 'SUCCEEDED' || st === 'PARTIAL_FAILED') {
    const batch = resultFromBatchPayload(d.resultData)
    if (batch) return batch
    const single = buildSingleSuccessResult(parseResultData(d.resultData))
    if (single) return single
    if (st === 'PARTIAL_FAILED') {
      return { ok: false, errorMessage: String(d.errorMessage || '部分设定卡生成失败') }
    }
    return { ok: false, errorMessage: '设定卡任务成功但未解析到图片' }
  }
  if (st === 'FAILED') {
    return { ok: false, errorMessage: String(d.errorMessage || '设定卡生成失败') }
  }
  if (st === 'CANCELLED') {
    return { ok: false, errorMessage: String(d.errorMessage || '任务已取消') }
  }
  return null
}

/** SSE 结束后补查一次 task/detail（不轮询） */
export async function recoverFormImageGenerateCardResultFromDetail(
  taskId: number
): Promise<FormImageGenerateCardResult | null> {
  return resolveFormImageGenerateCardResultFromTaskDetail(await fetchUserTaskDetailOnce(taskId))
}

/**
 * 提交角色设定卡生成（白底 ai_auto 主图 → 设定卡 ai_builder）
 * v2026-06：agentCode 必填；modelCode / resolution / aspectRatio 可选，未传走后端项目配置兜底。
 */
export async function runFormImageGenerateCardTask(payload: {
  imageId: number
  imageIds?: number[]
  projectId?: number | null
  /** 手动选择智能体；未传则走项目配置 → aid_config */
  agentCode?: string
  /** 手动选择模型；未传走后端 3 级兜底 */
  modelCode?: string
  /** 手动指定清晰度；未传走后端 3 级兜底 */
  resolution?: string
  /** 手动指定比例；未传走后端 3 级兜底 */
  aspectRatio?: string
  /** false 时仅提交任务，不跟 SSE（由页面 startTrackTask 接管） */
  followTask?: boolean
  onProgress?: (p: { percent?: number; stepTitle?: string; message?: string }) => void
  onSubmitted?: (p: { taskId: number }) => void
}): Promise<FormImageGenerateCardResult> {
  const { onProgress, projectId, followTask = true } = payload
  const imageIds = (payload.imageIds?.length ? payload.imageIds : [payload.imageId])
    .map((id) => Number(id))
    .filter((id) => Number.isFinite(id) && id > 0)
  if (!imageIds.length) {
    return { ok: false, errorMessage: '图片ID不能为空' }
  }

  const manualAgent = String(payload.agentCode || '').trim()
  const manualModel = String(payload.modelCode || '').trim()
  const manualResolution = String(payload.resolution || '').trim()
  const manualAspectRatio = String(payload.aspectRatio || '').trim()

  const genFields = await resolveProjectGenImageSubmitFields(
    projectId,
    CHARACTER_CARD_SCENE_CODE,
    {
      ...(manualAgent ? { agentCode: manualAgent } : {}),
      ...(manualModel ? { modelCode: manualModel } : {}),
      ...(manualResolution ? { resolution: manualResolution } : {}),
      ...(manualAspectRatio ? { aspectRatio: manualAspectRatio } : {})
    }
  )

  const agentCode = genFields.agentCode
  if (!agentCode) {
    return { ok: false, errorMessage: '请先在「生成配置」中为「角色设定卡」配置智能体' }
  }

  const body: UserAssetExtractFormGenerateCardImageRequest = {
    imageIds,
    agentCode,
    ...(genFields.modelCode ? { modelCode: genFields.modelCode } : {}),
    ...(genFields.resolution ? { resolution: genFields.resolution } : {}),
    ...(genFields.aspectRatio ? { aspectRatio: genFields.aspectRatio } : {})
  }

  let taskId: number
  try {
    const submitted = await userAssetExtractFormGenerateCardImage(body)
    taskId = Number(submitted.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) {
      return { ok: false, errorMessage: '未返回有效任务ID' }
    }
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    const msg = String(err?.msg || err?.message || '提交设定卡任务失败')
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }

  payload.onSubmitted?.({ taskId })

  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }

  if (!followTask) {
    return { ok: true }
  }

  return followFormImageGenerateCardTask({ taskId, onProgress })
}

/** 仅提交角色设定卡批量任务（不跟 SSE，由页面 startTrackTask 接管） */
export async function submitFormImageGenerateCardBatchTask(payload: {
  imageIds: number[]
  projectId?: number | null
  agentCode?: string
  modelCode?: string
  resolution?: string
  aspectRatio?: string
}): Promise<{ ok: true; taskId: number } | { ok: false; errorMessage: string }> {
  const firstId = payload.imageIds.find((id) => Number.isFinite(id) && id > 0)
  if (firstId == null) {
    return { ok: false, errorMessage: '图片ID不能为空' }
  }

  let taskId = 0
  const result = await runFormImageGenerateCardTask({
    imageId: firstId,
    imageIds: payload.imageIds,
    projectId: payload.projectId,
    agentCode: payload.agentCode,
    modelCode: payload.modelCode,
    resolution: payload.resolution,
    aspectRatio: payload.aspectRatio,
    followTask: false,
    onSubmitted: (p) => {
      taskId = p.taskId
    }
  })

  if (taskId > 0) {
    return { ok: true, taskId }
  }
  if (result.ok === false) {
    return { ok: false, errorMessage: result.errorMessage }
  }
  return { ok: false, errorMessage: '未返回有效任务ID' }
}

/** 批量提交角色设定卡生成（一次传入多个白底主图 ID） */
export async function runFormImageGenerateCardBatchTask(payload: {
  imageIds: number[]
  projectId?: number | null
  agentCode?: string
  modelCode?: string
  resolution?: string
  aspectRatio?: string
  onProgress?: (p: { percent?: number; stepTitle?: string; message?: string }) => void
  onSubmitted?: (p: { taskId: number }) => void
}): Promise<FormImageGenerateCardResult> {
  const firstId = payload.imageIds.find((id) => Number.isFinite(id) && id > 0)
  if (firstId == null) {
    return { ok: false, errorMessage: '图片ID不能为空' }
  }
  return runFormImageGenerateCardTask({
    imageId: firstId,
    imageIds: payload.imageIds,
    projectId: payload.projectId,
    agentCode: payload.agentCode,
    modelCode: payload.modelCode,
    resolution: payload.resolution,
    aspectRatio: payload.aspectRatio,
    onProgress: payload.onProgress,
    onSubmitted: payload.onSubmitted
  })
}

export async function followFormImageGenerateCardTask(payload: {
  taskId: number
  onProgress?: (p: { percent?: number; stepTitle?: string; message?: string }) => void
}): Promise<FormImageGenerateCardResult> {
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
      const recovered = await recoverFormImageGenerateCardResultFromDetail(taskId)
      if (recovered) return recovered
      return { ok: false, errorMessage: '设定卡任务超时，请在任务中心查看进度' }
    }

    const r = terminal.event
    const fromStream = resultFromStreamTerminal(r)
    if (fromStream) return fromStream

    if (r.type === 'error' && isBenignTaskSseDisconnectMessage(r.errorMessage)) {
      if (await shouldDeferModalTaskFollowFailure(taskId, r.errorMessage)) {
        return { ok: false, errorMessage: r.errorMessage, deferred: true }
      }
    }

    const recovered = await recoverFormImageGenerateCardResultFromDetail(taskId)
    if (recovered) return recovered

    if (r.type === 'error') {
      const msg = r.errorMessage || '设定卡生成失败'
      if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
        return { ok: false, errorMessage: msg, deferred: true }
      }
      handleSseErrorRecharge(r.errorData, msg)
      return { ok: false, errorMessage: msg }
    }

    if (r.type === 'cancelled') {
      return { ok: false, errorMessage: r.message || '任务已取消' }
    }

    return { ok: false, errorMessage: '设定卡任务未完成' }
  } catch (e: unknown) {
    const msg = String((e as Error)?.message || '设定卡任务异常')
    if (await shouldDeferModalTaskFollowFailure(taskId, msg)) {
      return { ok: false, errorMessage: msg, deferred: true }
    }
    const recovered = await recoverFormImageGenerateCardResultFromDetail(taskId)
    if (recovered) return recovered
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }
}

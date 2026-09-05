import { useCreationStore } from '~/stores/creation'
import type { UserAssetRpsFormUseBatchData } from '~/types/business-api'
import { userAssetRpsFormUnuse,userAssetRpsFormUse } from '~/utils/businessApi'
import { normUserTaskType } from '~/utils/taskPartialFailed'

/** 形态图生成完成后需主动调 /form/use 设为使用中的任务类型（设定卡批量任务由后端决定初始状态） */
export const FORM_IMAGE_AUTO_USE_TASK_TYPES = new Set([
  'form_image',
  'form_image_batch',
  'form_multi_view',
  'form_multi_grid',
  'form_card_image',
  'form_edit_chat'
])

export function isFormImageAutoUseTaskType(taskType: unknown): boolean {
  return FORM_IMAGE_AUTO_USE_TASK_TYPES.has(normUserTaskType(taskType))
}

/** 角色设定卡生成任务（单张历史类型 + v2026-06 批量类型） */
export function isFormCardImageTaskType(taskType: unknown): boolean {
  const n = normUserTaskType(taskType)
  return n === 'form_card_image' || n === 'form_card_image_batch'
}

function pickPositiveImageIds(v: unknown): number[] {
  if (!Array.isArray(v)) return []
  return v.map((x) => Number(x)).filter((n) => Number.isFinite(n) && n > 0)
}

/** 从 task inputSnapshot 解析 imageIds（form_card_image_batch 等） */
export function parseImageIdsFromTaskInputSnapshot(raw: unknown): number[] {
  if (raw == null) return []
  const s = typeof raw === 'string' ? raw.trim() : ''
  if (!s) return []
  try {
    const o = JSON.parse(s) as Record<string, unknown>
    const direct = pickPositiveImageIds(o.imageIds ?? o.image_ids)
    if (direct.length) return direct
    const body = o.body
    if (body && typeof body === 'object' && !Array.isArray(body)) {
      const b = body as Record<string, unknown>
      const nested = pickPositiveImageIds(b.imageIds ?? b.image_ids)
      if (nested.length) return nested
    }
    const single = Number(o.imageId ?? o.image_id)
    if (Number.isFinite(single) && single > 0) return [single]
  } catch {
    /* ignore */
  }
  const m = s.match(/"imageIds"\s*:\s*\[([^\]]*)\]/)
  if (m?.[1]) {
    return pickPositiveImageIds(
      m[1]
        .split(',')
        .map((x) => x.replace(/[^\d]/g, ''))
        .filter(Boolean)
    )
  }
  const m2 = s.match(/"imageId"\s*:\s*(\d+)/)
  if (m2?.[1]) return [Number(m2[1])]
  return []
}

function parseCompletePayload(data: unknown): Record<string, unknown> | null {
  if (data == null) return null
  if (typeof data === 'string') {
    const text = data.trim()
    if (!text) return null
    try {
      const parsed = JSON.parse(text) as unknown
      return typeof parsed === 'object' && parsed != null && !Array.isArray(parsed)
        ? (parsed as Record<string, unknown>)
        : null
    } catch {
      return null
    }
  }
  if (typeof data === 'object' && !Array.isArray(data)) {
    return data as Record<string, unknown>
  }
  return null
}

function pickImageId(raw: unknown): number | null {
  if (raw == null || typeof raw !== 'object' || Array.isArray(raw)) return null
  const rec = raw as Record<string, unknown>
  const idRaw = rec.imageId ?? rec.image_id ?? rec.id ?? rec.cardImageId ?? rec.card_image_id
  const n = Number(idRaw)
  return Number.isFinite(n) && n > 0 ? n : null
}

/** 设定卡批量 successItems：优先取新生成的 cardImageId，避免误用 sourceImageId / 白底 imageId */
function pickSettingCardImageId(raw: unknown): number | null {
  if (raw == null || typeof raw !== 'object' || Array.isArray(raw)) return null
  const rec = raw as Record<string, unknown>
  const idRaw =
    rec.cardImageId ??
    rec.card_image_id ??
    rec.imageId ??
    rec.image_id ??
    rec.id
  const n = Number(idRaw)
  return Number.isFinite(n) && n > 0 ? n : null
}

/** 从设定卡任务 complete / resultData 提取需设为使用中的设定卡 imageId 列表 */
export function extractSettingCardImageIdsFromTaskCompleteData(data: unknown): number[] {
  const o = parseCompletePayload(data)
  if (!o) return []

  const ids: number[] = []
  const push = (id: number | null) => {
    if (id != null && !ids.includes(id)) ids.push(id)
  }

  const successItems = Array.isArray(o.successItems) ? o.successItems : []
  for (const item of successItems) {
    push(pickSettingCardImageId(item))
  }

  if (!ids.length) {
    push(pickSettingCardImageId(o))
  }

  return ids.length ? ids : extractImageIdsFromTaskCompleteData(data)
}

/** 从设定卡批量 complete / resultData 提取白底主图 sourceImageId（用于清除 generating） */
export function extractSourceImageIdsFromSettingCardComplete(data: unknown): number[] {
  const o = parseCompletePayload(data)
  if (!o) return []

  const ids: number[] = []
  const push = (id: number | null) => {
    if (id != null && !ids.includes(id)) ids.push(id)
  }

  const pickSource = (raw: unknown): number | null => {
    if (raw == null || typeof raw !== 'object' || Array.isArray(raw)) return null
    const rec = raw as Record<string, unknown>
    const idRaw = rec.sourceImageId ?? rec.source_image_id ?? rec.imageId ?? rec.image_id
    const n = Number(idRaw)
    return Number.isFinite(n) && n > 0 ? n : null
  }

  const successItems = Array.isArray(o.successItems) ? o.successItems : []
  for (const item of successItems) {
    push(pickSource(item))
  }

  push(pickSource(o))
  return ids
}

/** 从 SSE complete / partial_failed / task.resultData 提取需接管的 imageId 列表 */
export function extractImageIdsFromTaskCompleteData(data: unknown): number[] {
  const o = parseCompletePayload(data)
  if (!o) return []

  const ids: number[] = []
  const push = (id: number | null) => {
    if (id != null && !ids.includes(id)) ids.push(id)
  }

  const successItems = Array.isArray(o.successItems) ? o.successItems : []
  for (const item of successItems) {
    push(pickImageId(item))
  }

  const items = Array.isArray(o.items) ? o.items : []
  for (const item of items) {
    push(pickImageId(item))
  }

  const imageIds = Array.isArray(o.imageIds) ? o.imageIds : []
  for (const raw of imageIds) {
    const n = Number(raw)
    push(Number.isFinite(n) && n > 0 ? n : null)
  }

  push(pickImageId(o))

  return ids
}

export function resolveFormUseProjectId(explicit?: number): number | null {
  if (explicit != null && Number.isFinite(explicit) && explicit > 0) return explicit
  try {
    // 非组件上下文，必须走 getState() 而不是 hook 调用
    const store = useCreationStore.getState()
    const pid = Number(store.currentProjectId)
    return Number.isFinite(pid) && pid > 0 ? pid : null
  } catch {
    return null
  }
}

/** 解析 /form/use|/form/unuse 批量结果：接口常为 code=200 + failCount，不可只看是否抛错 */
export function isFormImageBatchIdSucceeded(
  data: UserAssetRpsFormUseBatchData | null | undefined,
  imageId: number
): { ok: boolean; reason?: string } {
  const id = Number(imageId)
  if (!Number.isFinite(id) || id <= 0) return { ok: false, reason: '参数缺失' }
  // 无批量明细体：保持历史兼容（HTTP 200 且未抛错视为成功）
  if (!data) return { ok: true }
  const successIds = (data.successIds ?? []).map(Number).filter((n) => Number.isFinite(n) && n > 0)
  if (successIds.includes(id)) return { ok: true }
  const failure = (data.failures ?? []).find((f) => Number(f?.id) === id)
  if (failure) {
    return { ok: false, reason: String(failure.reason || '').trim() || '操作失败' }
  }
  const failCount = Number(data.failCount)
  const successCount = Number(data.successCount)
  if (Number.isFinite(failCount) && failCount > 0 && !(Number.isFinite(successCount) && successCount > 0)) {
    const firstReason = String(data.failures?.[0]?.reason || '').trim()
    return { ok: false, reason: firstReason || '操作失败' }
  }
  // 未点名失败该 id：视为成功（含幂等「本就未使用」）
  return { ok: true }
}

/** 单张形态图设为使用中（v2.63 须传 projectId） */
export async function setFormImageInUse(
  imageId: number,
  options?: { projectId?: number }
): Promise<boolean> {
  const projectId = resolveFormUseProjectId(options?.projectId)
  const id = Number(imageId)
  if (!projectId || !Number.isFinite(id) || id <= 0) return false
  try {
    const data = await userAssetRpsFormUse({ projectId, imageId: id })
    return isFormImageBatchIdSucceeded(data, id).ok
  } catch {
    return false
  }
}

/**
 * 单张取消使用中（v2.63 须传 projectId）。
 * 注意：同 form 仅剩 1 张 is_use=1 时后端会失败（不可删除，需保留至少一张）。
 */
export async function unsetFormImageInUse(
  imageId: number,
  options?: { projectId?: number }
): Promise<{ ok: boolean; reason?: string }> {
  const projectId = resolveFormUseProjectId(options?.projectId)
  const id = Number(imageId)
  if (!Number.isFinite(id) || id <= 0) return { ok: false, reason: '参数缺失' }
  try {
    const body =
      projectId != null
        ? { projectId, imageId: id, id }
        : { imageId: id, id }
    const data = await userAssetRpsFormUnuse(body)
    return isFormImageBatchIdSucceeded(data, id)
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    return { ok: false, reason: err?.msg || err?.message || '取消主图失败' }
  }
}

export interface ClaimFormImagesOptions {
  projectId?: number
  /** 角色/道具主图模式只接管最后一张，避免一次生成多张时产生多个主图。 */
  singleSelection?: boolean
}

/** 规范化任务产物；单选模式固定取最后一张（即最新生成结果）。 */
export function resolveFormImageClaimIds(
  imageIds: number[],
  singleSelection = false
): number[] {
  const validIds = imageIds
    .map((imageId) => Number(imageId))
    .filter((imageId) => Number.isFinite(imageId) && imageId > 0)
  if (singleSelection) return validIds.length > 0 ? [validIds[validIds.length - 1]!] : []
  return [...new Set(validIds)]
}

/**
 * 接管任务生成的形态图。接口已收敛为单个 imageId；多张场景按顺序逐张调用，
 * 角色/道具主图则由 singleSelection 只接管最新一张。
 */
export async function claimFormImagesByIds(
  imageIds: number[],
  options?: ClaimFormImagesOptions
): Promise<{ successIds: number[]; failCount: number }> {
  const targetIds = resolveFormImageClaimIds(imageIds, options?.singleSelection)
  if (!targetIds.length) return { successIds: [], failCount: 0 }

  const projectId = resolveFormUseProjectId(options?.projectId)
  if (!projectId) return { successIds: [], failCount: targetIds.length }

  const successIds: number[] = []
  let failCount = 0

  for (const imageId of targetIds) {
    try {
      const data = await userAssetRpsFormUse({ projectId, imageId })
      const result = isFormImageBatchIdSucceeded(data, imageId)
      if (result.ok) successIds.push(imageId)
      else failCount += 1
    } catch {
      failCount += 1
    }
  }

  return { successIds, failCount }
}

/**
 * 形态图任务终态后批量设为使用中（幂等）。
 * 仅在白名单任务类型下执行；v2.63 起合并为单次 / 分片批量请求。
 */
export async function claimFormImagesFromTaskComplete(
  taskType: unknown,
  completeData: unknown,
  options?: ClaimFormImagesOptions
): Promise<number[]> {
  if (!isFormImageAutoUseTaskType(taskType)) return []
  const imageIds = extractFormImageAutoUseIds(taskType, completeData)
  if (!imageIds.length) return []
  const { successIds } = await claimFormImagesByIds(imageIds, options)
  return successIds
}

export function extractFormImageAutoUseIds(taskType: unknown, completeData: unknown): number[] {
  if (!isFormImageAutoUseTaskType(taskType)) return []
  return isFormCardImageTaskType(taskType)
    ? extractSettingCardImageIdsFromTaskCompleteData(completeData)
    : extractImageIdsFromTaskCompleteData(completeData)
}

type FormImageTaskClaimState = {
  claimedIds: Set<number>
  tail: Promise<void>
}

/**
 * 单个任务消费方的形态图接管 owner。
 * SSE progress 与 terminal 可能携带同一批 imageId；同 owner 串行消费并只跳过已成功接管的 id，
 * 若前一次请求失败，后续 terminal 仍会重试，不会吞掉失败恢复。
 */
export function createFormImageTaskClaimOwner() {
  const stateByTaskId = new Map<number, FormImageTaskClaimState>()
  const maxRetainedTasks = 100

  const trimSettledTasks = () => {
    while (stateByTaskId.size > maxRetainedTasks) {
      const oldestTaskId = stateByTaskId.keys().next().value as number | undefined
      if (oldestTaskId == null) return
      stateByTaskId.delete(oldestTaskId)
    }
  }

  return {
    claim: async (
      taskId: unknown,
      taskType: unknown,
      completeData: unknown,
      options?: ClaimFormImagesOptions
    ) => {
      const id = Number(taskId)
      const imageIds = extractFormImageAutoUseIds(taskType, completeData)
      if (!Number.isFinite(id) || id <= 0 || imageIds.length === 0) return []

      let state = stateByTaskId.get(id)
      if (!state) {
        state = { claimedIds: new Set<number>(), tail: Promise.resolve() }
        stateByTaskId.set(id, state)
        trimSettledTasks()
      }

      const currentState = state
      const run = currentState.tail
        .catch(() => undefined)
        .then(async () => {
          const pendingIds = imageIds.filter((imageId) => !currentState.claimedIds.has(imageId))
          if (pendingIds.length === 0) return
          const { successIds } = await claimFormImagesByIds(pendingIds, options)
          for (const successId of successIds) currentState.claimedIds.add(successId)
        })
      currentState.tail = run
      await run
      return imageIds.filter((imageId) => currentState.claimedIds.has(imageId))
    }
  }
}

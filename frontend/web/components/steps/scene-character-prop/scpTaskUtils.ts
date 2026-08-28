import { isTaskOngoingStatus } from '~/hooks/useTaskOngoing'
import type { TaskStreamHandle } from '~/hooks/useTaskStream'
import type { AssetExtractType,UserAssetExtractFormGenerateData,UserAssetExtractFormGenerateImageData } from '~/types/business-api'
import { isFormCardImageTaskType } from '~/utils/formImageAutoUse'
import { formatPartialFailedMessage } from '~/utils/taskPartialFailed'
import type {
FormGenerateBatchOutcome,
FormImageSuccessItem,
Step3TabTaskProgress,
UserTaskSseOutcome
} from './types'

export function parseTaskId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

export function pickFinitePositiveNumber(v: unknown): number | null {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : null
}

export function normUserTaskType(ty: unknown): string {
  return String(ty ?? '')
    .trim()
    .toLowerCase()
    .replace(/-/g, '_')
}

export function isStep3FormGenerateTaskType(ty: unknown): boolean {
  const n = normUserTaskType(ty)
  return n === 'form_generate' || n === 'form_generate_batch'
}

export function isFormImageUserTaskType(ty: unknown): boolean {
  const n = normUserTaskType(ty)
  return n === 'form_image' || n === 'form_image_batch'
}

export function isImageUpscaleUserTaskType(ty: unknown): boolean {
  return normUserTaskType(ty) === 'image_upscale'
}

export function isOngoingUserTaskStatus(status: unknown): boolean {
  return isTaskOngoingStatus(status)
}

export function isStoryboardScriptBatchTaskType(ty: unknown): boolean {
  return (
    String(ty ?? '')
      .trim()
      .toLowerCase()
      .replace(/-/g, '_') === 'storyboard_script_batch'
  )
}

/** 列表接口不返回 inputSnapshot，需与详情接口共用同一解析逻辑 */
export function parseFormIdFromInputSnapshotRecord(rec: {
  inputSnapshot?: string | null
}): number | null {
  const raw = rec.inputSnapshot
  if (raw == null || String(raw).trim() === '') return null
  const s = String(raw).trim()
  const pickFiniteFormId = (v: unknown): number | null => {
    if (v == null || v === '') return null
    const n = Number(v)
    return Number.isFinite(n) && n > 0 ? n : null
  }
  try {
    const o = JSON.parse(s) as Record<string, unknown>
    let v = pickFiniteFormId(o.formId ?? o.form_id ?? o['formID'] ?? o['form_Id'])
    if (v != null) return v
    const idsRaw = o.formIds ?? o.form_ids ?? o['formIDList']
    if (Array.isArray(idsRaw) && idsRaw.length === 1) {
      v = pickFiniteFormId(idsRaw[0])
      if (v != null) return v
    }
    const single = o.form ?? o['Form']
    if (single && typeof single === 'object' && !Array.isArray(single)) {
      const sf = single as Record<string, unknown>
      v = pickFiniteFormId(sf.formId ?? sf.form_id ?? sf.id)
      if (v != null) return v
    }
    const body = o.body
    if (body && typeof body === 'object' && !Array.isArray(body)) {
      const b = body as Record<string, unknown>
      v = pickFiniteFormId(b.formId ?? b.form_id ?? b['formID'])
      if (v != null) return v
      const bids = b.formIds ?? b.form_ids
      if (Array.isArray(bids) && bids.length === 1) {
        v = pickFiniteFormId(bids[0])
        if (v != null) return v
      }
    }
  } catch {
    /* ignore */
  }
  const m = s.match(/"formId"\s*:\s*(\d+)/)
  if (m) return Number(m[1])
  const m2 = s.match(/"form_id"\s*:\s*(\d+)/)
  if (m2) return Number(m2[1])
  return null
}

/** 父任务 form_image_batch：inputSnapshot 内 formIds 列表 */
export function parseFormIdsFromBatchInputSnapshot(rec: { inputSnapshot?: string | null }): number[] {
  const raw = rec.inputSnapshot
  if (raw == null || String(raw).trim() === '') return []
  try {
    const o = JSON.parse(String(raw).trim()) as Record<string, unknown>
    const pick = (v: unknown): number[] => {
      if (!Array.isArray(v)) return []
      return v.map((x) => Number(x)).filter((n) => Number.isFinite(n) && n > 0)
    }
    const a = pick(o.formIds ?? o.form_ids)
    if (a.length) return a
    const body = o.body
    if (body && typeof body === 'object' && !Array.isArray(body)) {
      const b = body as Record<string, unknown>
      return pick(b.formIds ?? b.form_ids)
    }
  } catch {
    /* ignore */
  }
  return []
}

/** form_generate_batch：inputSnapshot 内 assetIds 列表（待生成形态小卡片） */
export function parseAssetIdsFromInputSnapshotRecord(rec: { inputSnapshot?: string | null }): number[] {
  const raw = rec.inputSnapshot
  if (raw == null || String(raw).trim() === '') return []
  try {
    const o = JSON.parse(String(raw).trim()) as Record<string, unknown>
    const pick = (v: unknown): number[] => {
      if (!Array.isArray(v)) return []
      return v.map((x) => Number(x)).filter((n) => Number.isFinite(n) && n > 0)
    }
    const a = pick(o.assetIds ?? o.asset_ids)
    if (a.length) return a
    const body = o.body
    if (body && typeof body === 'object' && !Array.isArray(body)) {
      const b = body as Record<string, unknown>
      return pick(b.assetIds ?? b.asset_ids)
    }
    const single = pickFinitePositiveNumber(o.assetId ?? o.asset_id)
    return single != null ? [single] : []
  } catch {
    /* ignore */
  }
  return []
}

const EXTRACT_TYPE_SET = new Set<string>(['scene', 'character', 'prop'])

export function normalizeExtractTypeToken(tok: string): AssetExtractType | null {
  let k = String(tok ?? '')
    .trim()
    .toLowerCase()
  if ((k.startsWith('"') && k.endsWith('"')) || (k.startsWith("'") && k.endsWith("'"))) {
    k = k.slice(1, -1).trim().toLowerCase()
  }
  if (!EXTRACT_TYPE_SET.has(k)) return null
  return k as AssetExtractType
}

/** 后端常见非 JSON 快照：`character,scene` 或 `character|scene`（接口文档摘要示例） */
export function parseExtractTypesFromCsvLike(s: string): AssetExtractType[] | null {
  const parts = s
    .split(/[,，|;\s]+/)
    .map((x) => x.trim())
    .filter(Boolean)
  if (!parts.length) return null
  const out: AssetExtractType[] = []
  for (const p of parts) {
    const t = normalizeExtractTypeToken(p)
    if (t && !out.includes(t)) out.push(t)
  }
  return out.length ? out : null
}

export function collectExtractTypesFromUnknownList(list: unknown[]): AssetExtractType[] | null {
  const out: AssetExtractType[] = []
  for (const x of list) {
    const t = normalizeExtractTypeToken(String(x ?? ''))
    if (t && !out.includes(t)) out.push(t)
  }
  return out.length ? out : null
}

/**
 * 从提取任务 inputSnapshot 解析 extractTypes（与 /extract/parallel 入参一致）。
 * 兼容：① JSON 对象含 extractTypes 数组或逗号分隔字符串；② 顶层 JSON 数组；③ 非 JSON 的逗号分隔纯字符串（文档示例 `inputSnapshot":"character,scene,prop"`）。
 * 解析不到时返回 null，由调用方回退为「全类型」。
 */
export function parseExtractTypesFromInputSnapshotRecord(rec: {
  inputSnapshot?: string | null
}): AssetExtractType[] | null {
  const raw = rec.inputSnapshot
  if (raw == null || String(raw).trim() === '') return null
  const s = String(raw).trim()

  try {
    const parsed = JSON.parse(s) as unknown
    if (Array.isArray(parsed)) {
      return collectExtractTypesFromUnknownList(parsed)
    }
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const o = parsed as Record<string, unknown>

      const pickList = (v: unknown): unknown[] | null => {
        if (!Array.isArray(v) || v.length === 0) return null
        return v
      }

      const fromStringField = (v: unknown): AssetExtractType[] | null => {
        if (typeof v !== 'string' || !v.trim()) return null
        return parseExtractTypesFromCsvLike(v.trim())
      }

      const strTypes =
        fromStringField(o.extractTypes) ||
        fromStringField(o.extract_types) ||
        (o.body && typeof o.body === 'object' && !Array.isArray(o.body)
          ? fromStringField((o.body as Record<string, unknown>).extractTypes) ||
            fromStringField((o.body as Record<string, unknown>).extract_types)
          : null)
      if (strTypes?.length) return strTypes

      const list =
        pickList(o.extractTypes) ||
        pickList(o.extract_types) ||
        (o.body && typeof o.body === 'object' && !Array.isArray(o.body)
          ? pickList((o.body as Record<string, unknown>).extractTypes) ||
            pickList((o.body as Record<string, unknown>).extract_types)
          : null)
      if (list) {
        const arr = collectExtractTypesFromUnknownList(list)
        if (arr?.length) return arr
      }

      const nested = o.inputSnapshot ?? o.input_snapshot
      if (typeof nested === 'string' && nested.trim() && nested.trim() !== s) {
        return parseExtractTypesFromInputSnapshotRecord({ inputSnapshot: nested })
      }
    }
  } catch {
    /* 非 JSON：走 CSV */
  }

  return parseExtractTypesFromCsvLike(s)
}

/** 刷新/切 Tab/关页导致 fetch/SSE 中断，不应记为「生成失败」或弹 toast */
export function isBenignStep3TaskAbortMessage(msg: string): boolean {
  const lower = String(msg ?? '').trim().toLowerCase()
  if (!lower) return false
  return (
    lower.includes('abort') ||
    lower.includes('superseded') ||
    lower.includes('cancel') ||
    lower.includes('user aborted') ||
    lower.includes('signal is aborted') ||
    lower.includes('ended unexpectedly') ||
    lower.includes('sse aborted') ||
    lower.includes('rate limited') ||
    lower.includes('networkerror') ||
    lower.includes('failed to fetch') ||
    lower.includes('load failed') ||
    lower.includes('network error') ||
    lower.includes('err_network') ||
    lower.includes('connection') ||
    lower.includes('断开') ||
    lower.includes('body stream') ||
    lower.includes('任务连接中断') ||
    lower.includes('任务连接异常') ||
    (lower.includes('fetch') && lower.includes('failed'))
  )
}

export function isBenignStep3TaskAbortError(e: unknown): boolean {
  const err = e as { name?: string; message?: string }
  if (err?.name === 'AbortError') return true
  return isBenignStep3TaskAbortMessage(String((e as Error)?.message ?? e ?? ''))
}

export function parseFormImageSuccessItemsFromComplete(data: unknown): FormImageSuccessItem[] {
  if (data == null) return []
  let o: Record<string, unknown>
  if (typeof data === 'string') {
    const text = data.trim()
    if (!text) return []
    try {
      o = JSON.parse(text) as Record<string, unknown>
    } catch {
      return []
    }
  } else if (typeof data === 'object' && !Array.isArray(data)) {
    o = data as Record<string, unknown>
  } else {
    return []
  }

  const pickItem = (raw: unknown): FormImageSuccessItem | null => {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
    const rec = raw as Record<string, unknown>
    const formId = Number(rec.formId ?? rec.form_id)
    if (!Number.isFinite(formId) || formId <= 0) return null
    const imageIdRaw =
      rec.cardImageId ?? rec.card_image_id ?? rec.imageId ?? rec.image_id ?? rec.id
    const imageId =
      imageIdRaw != null && Number.isFinite(Number(imageIdRaw)) && Number(imageIdRaw) > 0
        ? Number(imageIdRaw)
        : undefined
    const imageUrl =
      String(rec.cardImageUrl ?? rec.card_image_url ?? rec.imageUrl ?? rec.image_url ?? rec.url ?? '')
        .trim() || undefined
    return { formId, imageId, imageUrl }
  }

  const fromList = Array.isArray(o.successItems)
    ? o.successItems.map(pickItem).filter((x): x is FormImageSuccessItem => x != null)
    : []
  if (fromList.length) return fromList

  const single = pickItem(o)
  return single ? [single] : []
}

export function parseFormImageFailedFormIdsFromComplete(data: unknown): number[] {
  if (data == null) return []
  let o: Record<string, unknown>
  if (typeof data === 'string') {
    const text = data.trim()
    if (!text) return []
    try {
      o = JSON.parse(text) as Record<string, unknown>
    } catch {
      return []
    }
  } else if (typeof data === 'object' && !Array.isArray(data)) {
    o = data as Record<string, unknown>
  } else {
    return []
  }
  const failedItems = Array.isArray(o.failedItems)
    ? o.failedItems
    : Array.isArray(o.failed_items)
      ? o.failed_items
      : []
  const ids: number[] = []
  for (const raw of failedItems) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue
    const rec = raw as Record<string, unknown>
    const formId = Number(rec.formId ?? rec.form_id)
    if (Number.isFinite(formId) && formId > 0) ids.push(formId)
  }
  return ids
}

export function parseFormGenerateBatchCompleteOutcome(data: unknown): FormGenerateBatchOutcome | null {
  if (data == null) return null
  let o: Record<string, unknown>
  if (typeof data === 'string') {
    const text = data.trim()
    if (!text) return null
    try {
      o = JSON.parse(text) as Record<string, unknown>
    } catch {
      return null
    }
  } else if (typeof data === 'object' && !Array.isArray(data)) {
    o = data as Record<string, unknown>
  } else {
    return null
  }
  const successCount = Number(o.successCount ?? o.success_count)
  const failCount = Number(o.failCount ?? o.fail_count)
  if (!Number.isFinite(successCount) && !Number.isFinite(failCount)) return null
  const failedMessages: string[] = []
  const failedItems = Array.isArray(o.failedItems)
    ? o.failedItems
    : Array.isArray(o.failed_items)
      ? o.failed_items
      : []
  for (const raw of failedItems) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue
    const rec = raw as Record<string, unknown>
    const msg = String(rec.message ?? rec.msg ?? '').trim()
    if (msg) failedMessages.push(msg)
  }
  return {
    successCount: Number.isFinite(successCount) ? successCount : 0,
    failCount: Number.isFinite(failCount) ? failCount : 0,
    failedMessages
  }
}

export function extractFormGenerateImageSubmitTaskId(
  submit: UserAssetExtractFormGenerateImageData
): number | null {
  const tid = parseTaskId(submit.taskId)
  if (tid != null) return tid
  const oneTask = submit.tasks?.length === 1 ? submit.tasks[0] : undefined
  const t2 = parseTaskId(oneTask?.taskId)
  if (t2 != null) return t2
  if (submit.taskIds?.length === 1) return parseTaskId(submit.taskIds[0])
  return null
}

export function extractFormGenerateTextSubmitTaskId(
  submit: UserAssetExtractFormGenerateData
): number | null {
  const tid = parseTaskId(submit.taskId)
  if (tid != null) return tid
  const oneTask = submit.tasks?.length === 1 ? submit.tasks[0] : undefined
  const t2 = parseTaskId(oneTask?.taskId)
  if (t2 != null) return t2
  if (submit.taskIds?.length === 1) return parseTaskId(submit.taskIds[0])
  return null
}

export function normalizeTaskStreamToUserOutcome(
  r: Awaited<TaskStreamHandle['done']>
): UserTaskSseOutcome {
  if (r.type === 'error') return { type: 'error', errorMessage: r.errorMessage || '任务失败' }
  if (r.type === 'cancelled') return { type: 'error', errorMessage: r.message || '任务已取消' }
  if (r.type === 'partial_failed') {
    return {
      type: 'partial_failed',
      data: r.data,
      errorMessage: formatPartialFailedMessage(r.data, '部分生成失败，可续生')
    }
  }
  return { type: 'complete', data: r.data }
}

export function emptyStep3TabTaskProgress(): Step3TabTaskProgress {
  return { percent: 0, stepTitle: '', message: '', stepIndex: null, stepTotal: null }
}

function containsProgressCount(text: string, stepIndex: number, stepTotal: number): boolean {
  const escapedIndex = String(stepIndex).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const escapedTotal = String(stepTotal).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`(^|\\D)${escapedIndex}\\s*[/／]\\s*${escapedTotal}(\\D|$)`).test(text)
}

function mergeDistinctProgressCopy(title: string, message: string): string {
  if (!title) return message
  if (!message) return title
  const normalizedTitle = title.replace(/\s+/g, '')
  const normalizedMessage = message.replace(/\s+/g, '')
  if (normalizedTitle === normalizedMessage) return title
  if (normalizedTitle.includes(normalizedMessage)) return title
  if (normalizedMessage.includes(normalizedTitle)) return message
  return `${title}：${message}`
}

export function formatStep3TabTaskProgressText(p: Step3TabTaskProgress): string {
  const percent = Number(p?.percent ?? 0)
  const title = (p?.stepTitle || '').trim()
  const msg = (p?.message || '').trim()
  const stepIndex = p?.stepIndex
  const stepTotal = p?.stepTotal

  if (!title && !msg) return ''
  const pct = Math.round(Math.min(100, Math.max(0, percent)))
  const showPct = Number.isFinite(percent) && percent > 0
  const percentText = showPct ? `${pct}% ` : ''
  const liveCopy = mergeDistinctProgressCopy(title, msg)
  const stepText =
    stepIndex != null &&
    stepTotal != null &&
    !containsProgressCount(liveCopy, stepIndex, stepTotal)
      ? `（${stepIndex}/${stepTotal}）`
      : ''
  return `${percentText}${liveCopy}${stepText}`
}

/** 本页可受理的跟进指令：智能提取 + 第三步形态/形态图相关任务 */
export function acceptsStep3TrackCommand(payload?: {
  taskId?: number
  taskType?: string | null
}): boolean {
  const taskId = Number(payload?.taskId)
  if (!Number.isFinite(taskId) || taskId <= 0) return false
  const ty = normUserTaskType(payload?.taskType)
  return ty === 'asset_extract' || isStep3FormRelatedTaskType(ty)
}

export function isStep3FormRelatedTaskType(ty: unknown): boolean {
  const n = normUserTaskType(ty)
  return (
    isStep3FormGenerateTaskType(ty) ||
    isFormImageOrCardUserTaskType(ty) ||
    n === 'image_upscale' ||
    n === 'form_edit_chat' ||
    n === 'form_multi_view'
  )
}

export function isFormImageOrCardUserTaskType(ty: unknown): boolean {
  return isFormImageUserTaskType(ty) || isFormCardImageTaskType(ty)
}

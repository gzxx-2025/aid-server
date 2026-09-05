import type { SceneModalSseTaskKind } from '~/stores/creation'
import { isFormCardImageTaskType } from '~/utils/formImageAutoUse'

export function isSinglePrimaryImageType(imageType: unknown): boolean {
  return imageType === 'character' || imageType === 'prop'
}

export function mapSourceLabelToRpsType(source?: string): 'upload' | 'official' | 'ai' {
  if (!source) return 'upload'
  if (/自动|对话|生成|\bAI\b|ai/i.test(source)) return 'ai'
  if (/本地|本地上传/i.test(source)) return 'upload'
  return 'official'
}

export function resolveRpsSourceType(img: any): 'upload' | 'official' | 'ai' {
  const t = img?._rpsSourceType
  if (t === 'upload' || t === 'official' || t === 'ai') return t
  return mapSourceLabelToRpsType(img?.source)
}

export function resolveRpsImageIdFromLocalImage(img: { rpsImageId?: number; id?: string } | null | undefined): number | null {
  const direct = Number(img?.rpsImageId)
  if (Number.isFinite(direct) && direct > 0) return direct
  const idStr = String(img?.id || '')
  const m = idStr.match(/^img-(\d+)$/)
  if (m?.[1]) {
    const parsed = Number(m[1])
    if (Number.isFinite(parsed) && parsed > 0) return parsed
  }
  return null
}

/** 与父级传入的 id 统一为字符串，避免接口 number / 本地 string 导致 Set.has 失败 */
export function normalizeImageId(id: unknown): string | null {
  if (id === undefined || id === null || id === '') return null
  return String(id)
}

export function isTerminalUserTaskStatus(status: unknown): boolean {
  const st = String(status ?? '').trim().toUpperCase()
  return st === 'SUCCEEDED' || st === 'FAILED' || st === 'CANCELLED' || st === 'PARTIAL_FAILED'
}

export function normModalTaskType(ty: unknown): string {
  return String(ty ?? '')
    .trim()
    .toLowerCase()
    .replace(/-/g, '_')
}

export function parseFormIdFromTaskSnapshot(raw: unknown): number | null {
  if (raw == null) return null
  const s = typeof raw === 'string' ? raw.trim() : ''
  if (!s) return null
  try {
    const o = JSON.parse(s) as Record<string, unknown>
    const pick = (v: unknown): number | null => {
      const n = Number(v)
      return Number.isFinite(n) && n > 0 ? n : null
    }
    const direct = pick(o.formId ?? o.form_id)
    if (direct != null) return direct
    const body = o.body
    if (body && typeof body === 'object' && !Array.isArray(body)) {
      const b = body as Record<string, unknown>
      return pick(b.formId ?? b.form_id)
    }
  } catch {
    /* ignore */
  }
  const m = s.match(/"formId"\s*:\s*(\d+)/)
  if (m?.[1]) return Number(m[1])
  return null
}

export function parseFormIdsFromTaskSnapshot(raw: unknown): number[] {
  if (raw == null) return []
  const s = typeof raw === 'string' ? raw.trim() : ''
  if (!s) return []
  try {
    const o = JSON.parse(s) as Record<string, unknown>
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
    const single = parseFormIdFromTaskSnapshot(s)
    return single != null ? [single] : []
  } catch {
    const single = parseFormIdFromTaskSnapshot(s)
    return single != null ? [single] : []
  }
}

export function parseImageIdFromTaskSnapshot(raw: unknown): number | null {
  if (raw == null) return null
  const s = typeof raw === 'string' ? raw.trim() : ''
  if (!s) return null
  try {
    const o = JSON.parse(s) as Record<string, unknown>
    const pick = (v: unknown): number | null => {
      const n = Number(v)
      return Number.isFinite(n) && n > 0 ? n : null
    }
    const direct = pick(o.imageId ?? o.image_id)
    if (direct != null) return direct
    const body = o.body
    if (body && typeof body === 'object' && !Array.isArray(body)) {
      const b = body as Record<string, unknown>
      return pick(b.imageId ?? b.image_id)
    }
  } catch {
    /* ignore */
  }
  const m = s.match(/"imageId"\s*:\s*(\d+)/)
  if (m?.[1]) return Number(m[1])
  return null
}

export function mapTaskTypeToModalKind(ty: string): SceneModalSseTaskKind {
  if (ty === 'form_edit_chat') return 'edit-image'
  if (ty === 'form_multi_view') return 'multi-view'
  if (ty === 'form_image' || ty === 'form_image_batch') return 'form-image'
  if (ty === 'image_upscale') return 'upscale'
  if (isFormCardImageTaskType(ty)) return 'setting-card'
  return 'edit-image'
}

export function taskSnapshotMatchesModalFormIds(
  rec: { inputSnapshot?: string | null; taskType?: string | null },
  formIds: Set<number>
): boolean {
  const multi = parseFormIdsFromTaskSnapshot(rec.inputSnapshot)
  if (multi.some((id) => formIds.has(id))) return true
  const single = parseFormIdFromTaskSnapshot(rec.inputSnapshot)
  return single != null && formIds.has(single)
}

export function mapSessionTaskKind(raw: string | undefined): SceneModalSseTaskKind {
  if (raw === 'dialogue') return 'dialogue'
  if (raw === 'upscale') return 'upscale'
  if (raw === 'multi-view') return 'multi-view'
  if (raw === 'setting-card') return 'setting-card'
  if (raw === 'form-image') return 'form-image'
  return 'edit-image'
}

export function defaultProgressTextForTaskKind(taskKind: SceneModalSseTaskKind): string {
  if (taskKind === 'upscale') return '高清处理中…'
  if (taskKind === 'multi-view') return '多机位生图中...'
  if (taskKind === 'form-image') return '正在生成形态图…'
  if (taskKind === 'setting-card') return '设定卡生成中…'
  if (taskKind === 'dialogue') return '对话作图中…'
  return '生图中…'
}

export function removeLocalGeneratingPlaceholders(images: any[]): any[] {
  return images.filter((img) => !img?._localGeneratingPlaceholder)
}

export function isDeferredModalFollowResult(result: unknown): boolean {
  return (
    !!result &&
    typeof result === 'object' &&
    (result as { ok?: unknown }).ok === false &&
    (result as { deferred?: unknown }).deferred === true
  )
}

export function notifyFormCardBatchSettled(sourceImageIds: number[]) {
  if (typeof window === 'undefined') return
  window.dispatchEvent(
    new CustomEvent('create-flow-form-card-batch-settled', {
      detail: { sourceImageIds }
    })
  )
}

// 格式化日期
export const formatDate = (dateString: string) => {
  if (!dateString) return ''
  const date = new Date(dateString)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

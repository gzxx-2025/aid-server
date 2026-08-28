/**
 * POST /api/user/asset/rps/update-main（接口.md 2.5）请求体规范化。
 * - auto：expectedAppearances 必须是真实 JSON 数组，元素含 id / name / change_reason
 * - manual：仅允许 id + name / aliases / aliasesName
 */
import type { UserAssetRpsUpdateMainRequest } from '~/types/business-api'

export type RpsExpectedAppearanceUpdateItem = {
  id: number
  name: string
  change_reason: string
}

function asAppearanceItem(raw: unknown, fallbackId: number): RpsExpectedAppearanceUpdateItem | null {
  if (raw == null) return null
  if (typeof raw === 'string') {
    const name = raw.trim()
    if (!name) return null
    return { id: fallbackId, name, change_reason: name }
  }
  if (typeof raw !== 'object') return null
  const o = raw as Record<string, unknown>
  const idRaw = o.id
  const id =
    idRaw != null && Number.isFinite(Number(idRaw)) && Number(idRaw) >= 0
      ? Number(idRaw)
      : fallbackId
  const name = String(o.name ?? o.label ?? '').trim()
  const changeReason = String(o.change_reason ?? o.changeReason ?? name).trim() || name
  if (!name) return null
  return { id, name, change_reason: changeReason }
}

/** 设定弹窗「子形象列表」纯文本 → update-main 数组（非 JSON 字符串） */
export function expectedAppearancesFromPlain(text: string): RpsExpectedAppearanceUpdateItem[] | undefined {
  const lines = (text || '')
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
  if (!lines.length) return undefined

  const arr: RpsExpectedAppearanceUpdateItem[] = []
  let autoId = 0
  for (const line of lines) {
    const m = line.match(/^#?\s*(\d+)\s+(.+)$/)
    if (m) {
      const name = m[2]!.trim()
      arr.push({ id: Number(m[1]), name, change_reason: name })
      continue
    }
    const m2 = line.match(/^#(\d+)\s*$/)
    if (m2) {
      const id = Number(m2[1])
      const name = `形象${id}`
      arr.push({ id, name, change_reason: name })
      continue
    }
    arr.push({ id: autoId++, name: line, change_reason: line })
  }
  return arr
}

/** 兼容历史 string / label 形态，统一成文档 2.5.1 要求的数组 */
export function normalizeExpectedAppearancesForUpdateMain(
  value: unknown
): RpsExpectedAppearanceUpdateItem[] | undefined {
  if (value == null) return undefined

  let list: unknown = value
  if (typeof value === 'string') {
    const t = value.trim()
    if (!t) return undefined
    try {
      list = JSON.parse(t) as unknown
    } catch {
      return expectedAppearancesFromPlain(t)
    }
  }

  if (!Array.isArray(list)) return undefined

  const out: RpsExpectedAppearanceUpdateItem[] = []
  list.forEach((item, index) => {
    const normalized = asAppearanceItem(item, index)
    if (normalized) out.push(normalized)
  })
  return out
}

/** createSource=manual 时只保留文档允许的轻量字段 */
export function pickManualUpdateMainFields(
  body: UserAssetRpsUpdateMainRequest
): UserAssetRpsUpdateMainRequest {
  const next: UserAssetRpsUpdateMainRequest = { id: body.id }
  if (body.name !== undefined) next.name = body.name
  if (body.aliases !== undefined) next.aliases = body.aliases
  if (body.aliasesName !== undefined) next.aliasesName = body.aliasesName
  return next
}

/** 提交前规范化：纠正 expectedAppearances；manual 时裁剪字段 */
export function normalizeUpdateMainRequest(
  body: UserAssetRpsUpdateMainRequest,
  options?: { createSource?: string | null; isManual?: boolean }
): UserAssetRpsUpdateMainRequest {
  const isManual =
    options?.isManual === true || String(options?.createSource || '').toLowerCase() === 'manual'

  if (isManual) return pickManualUpdateMainFields(body)

  const next: UserAssetRpsUpdateMainRequest = { ...body }
  if ('expectedAppearances' in next) {
    const normalized = normalizeExpectedAppearancesForUpdateMain(next.expectedAppearances)
    if (normalized === undefined) {
      delete next.expectedAppearances
    } else {
      next.expectedAppearances = normalized
    }
  }
  return next
}

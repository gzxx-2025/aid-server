import type {
UserAssetRow,
UserAssetRpsFormImageRow,
UserAssetRpsFormRow,
UserAssetRpsRow
} from '~/types/business-api'
import type { CharacterFormItem,FormGenStatus } from './types'
import { settingEditorStateFromRpsForm } from './scpSettingPromptUtils'

/** 形态是否允许「自动生成」配图（手动新增形态 / 接口 canAutoGenerateImage=false 时禁用） */
export function formAllowsAutoGenerateImage(form?: {
  canAutoGenerateImage?: boolean | null
  createSource?: string | null
} | null): boolean {
  if (!form) return false
  if (form.canAutoGenerateImage === false) return false
  if (form.canAutoGenerateImage === true) return true
  return String(form.createSource || '').toLowerCase() !== 'manual'
}

export function mapRpsFormToLocalFormFields(
  f: UserAssetRpsFormRow,
  idx: number,
  variant: 'character' | 'prop'
): Pick<CharacterFormItem, 'name' | 'canAutoGenerateImage' | 'createSource' | 'setting'> {
  const createSource = f.createSource != null ? String(f.createSource) : undefined
  return {
    name: f.name?.trim() ? f.name : `形态${idx + 1}: 未命名`,
    createSource,
    setting: settingEditorStateFromRpsForm(f, variant),
    canAutoGenerateImage: formAllowsAutoGenerateImage({
      canAutoGenerateImage: f.canAutoGenerateImage,
      createSource
    })
  }
}

/** 后端旧字段 sourceType=1 表示手动添加的主资产 */
export function isRpsManualSourceType(sourceType: unknown): boolean {
  return sourceType === 1 || sourceType === '1'
}

/** 后端 createSource=manual（或旧 sourceType=1 / 形态全为 manual）表示手动添加 */
export function isRpsManualAsset(raw: {
  createSource?: string | null
  sourceType?: number | string | null
  forms?: Array<{ createSource?: string | null; canAutoGenerateImage?: boolean | null }> | null
}): boolean {
  if (String(raw.createSource || '').toLowerCase() === 'manual') return true
  if (isRpsManualSourceType(raw.sourceType)) return true
  const forms = raw.forms ?? []
  // 形态层：手动壳 createSource=manual，且 canAutoGenerateImage=false
  if (
    forms.length > 0 &&
    forms.every(
      (f) =>
        String(f?.createSource || '').toLowerCase() === 'manual' ||
        f?.canAutoGenerateImage === false
    )
  ) {
    return true
  }
  return false
}

export function buildManualIndexListFromRps(
  sortedRps: UserAssetRpsRow[],
  options: {
    persistedIndices: Set<number>
    prevManualAssetIds: Set<number>
    persistedManualAssetIds?: Set<number>
  }
): number[] {
  const { persistedIndices, prevManualAssetIds, persistedManualAssetIds } = options
  return sortedRps
    .map((raw, i) => {
      const aid = raw.id != null && Number.isFinite(Number(raw.id)) ? Number(raw.id) : null
      if (isRpsManualAsset(raw)) return i
      if (persistedIndices.has(i)) return i
      if (aid != null && prevManualAssetIds.has(aid)) return i
      if (aid != null && persistedManualAssetIds?.has(aid)) return i
      return -1
    })
    .filter((i) => i >= 0)
}

export function splitExtraImageUrls(extra: string | null | undefined): string[] {
  if (!extra?.trim()) return []
  return extra
    .split(/[;,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

export function buildImagesFromAssetRow(row: UserAssetRow): Array<{
  id: string
  title: string
  url: string
  thumbnail: string
  source: string
  importDate: string
}> {
  const list: Array<{
    id: string
    title: string
    url: string
    thumbnail: string
    source: string
    importDate: string
  }> = []
  const date = row.updateTime || row.createTime || ''
  if (row.refImageUrl) {
    list.push({
      id: String(row.id),
      title: row.assetName || '主图',
      url: row.refImageUrl,
      thumbnail: row.refImageUrl,
      source: 'server',
      importDate: date
    })
  }
  let n = 0
  for (const url of splitExtraImageUrls(row.extraImages)) {
    n += 1
    list.push({
      id: `${row.id}-ex-${n}`,
      title: `参考图${n}`,
      url,
      thumbnail: url,
      source: 'server',
      importDate: date
    })
  }
  return list
}

export function reindexAssetIdMap(
  map: Record<number, number>,
  removedIdx: number
): Record<number, number> {
  const next: Record<number, number> = {}
  for (const key of Object.keys(map)) {
    const i = Number(key)
    if (!Number.isFinite(i)) continue
    if (i === removedIdx) continue
    if (i < removedIdx) next[i] = map[i]!
    else next[i - 1] = map[i]!
  }
  return next
}

export function reindexFormIdsByIndexMap(
  map: Record<number, number[]>,
  removedIdx: number
): Record<number, number[]> {
  const next: Record<number, number[]> = {}
  for (const key of Object.keys(map)) {
    const i = Number(key)
    if (!Number.isFinite(i)) continue
    if (i === removedIdx) continue
    if (i < removedIdx) next[i] = map[i]!
    else next[i - 1] = map[i]!
  }
  return next
}

export function reindexSceneGenerationStatusMap(
  map: Record<number, 'idle' | 'generating' | 'success' | 'failed'>,
  removedIdx: number
): Record<number, 'idle' | 'generating' | 'success' | 'failed'> {
  const next: Record<number, 'idle' | 'generating' | 'success' | 'failed'> = {}
  for (const key of Object.keys(map)) {
    const i = Number(key)
    if (!Number.isFinite(i)) continue
    if (i === removedIdx) continue
    if (i < removedIdx) next[i] = map[i]!
    else next[i - 1] = map[i]!
  }
  return next
}

export function reindexFormGenerationStatusMap(
  map: Record<string, FormGenStatus>,
  removedAssetIdx: number
): Record<string, FormGenStatus> {
  const next: Record<string, FormGenStatus> = {}
  for (const key of Object.keys(map)) {
    const [assetIdxRaw, formIdxRaw] = key.split('-')
    const assetIdx = Number(assetIdxRaw)
    const formIdx = Number(formIdxRaw)
    if (!Number.isFinite(assetIdx) || !Number.isFinite(formIdx)) continue
    if (assetIdx === removedAssetIdx) continue
    const nextKey =
      assetIdx < removedAssetIdx ? `${assetIdx}-${formIdx}` : `${assetIdx - 1}-${formIdx}`
    next[nextKey] = map[key]!
  }
  return next
}

export function mapImportSourceType(source: string): 'upload' | 'official' {
  return source === '本地上传' ? 'upload' : 'official'
}

/** rps/list 的 forms[*].images：列表仅展示 is_use=1 且有效 URL 的图片 */
export function isRpsFormImageInUse(img: UserAssetRpsFormImageRow | null | undefined): boolean {
  return Number(img?.isUse) === 1
}

export function inUseImagesFromRpsForm(f: UserAssetRpsFormRow): UserAssetRpsFormImageRow[] {
  return (f.images ?? []).filter(
    (img) => isRpsFormImageInUse(img) && String(img?.imageUrl || '').trim()
  )
}

/**
 * form.imageUrl 可能是「最新一张」而非使用中主图。
 * 后端已返回 images（含空数组）时禁止回退，否则取消主图后再拉 list 会把图复活。
 */
export function resolveLegacyFormImageUrlFallback(f: UserAssetRpsFormRow): string {
  if (Array.isArray(f.images)) return ''
  const url = String(f.imageUrl || '').trim()
  if (!url) return ''
  const hasCurrent =
    Number(f.isUse) === 1 ||
    (f.currentImageId != null && Number.isFinite(Number(f.currentImageId)) && Number(f.currentImageId) > 0)
  return hasCurrent ? url : ''
}

/** 同形态已有 rpsImageId 图片时，去掉 form.imageUrl 旧字段回退产生的占位卡（刷新恢复常见） */
export function sanitizeSceneImageList(imgs: any[]): any[] {
  if (!Array.isArray(imgs) || !imgs.length) return []
  const byForm = new Map<number, { withId: any[]; fallback: any[]; other: any[] }>()
  const noForm: any[] = []
  for (const img of imgs) {
    const fid = Number(img?.rpsFormId)
    if (!Number.isFinite(fid) || fid <= 0) {
      noForm.push(img)
      continue
    }
    let bucket = byForm.get(fid)
    if (!bucket) {
      bucket = { withId: [], fallback: [], other: [] }
      byForm.set(fid, bucket)
    }
    const hasRpsImageId = img?.rpsImageId != null && Number.isFinite(Number(img.rpsImageId))
    const idStr = String(img?.id ?? '')
    if (hasRpsImageId) bucket.withId.push(img)
    else if (idStr.startsWith('form-')) bucket.fallback.push(img)
    else bucket.other.push(img)
  }
  const out: any[] = [...noForm]
  for (const bucket of byForm.values()) {
    if (bucket.withId.length > 0) out.push(...bucket.withId, ...bucket.other)
    else out.push(...bucket.fallback, ...bucket.other)
  }
  return out
}

/** 同一场景资产：已有 images[] 主图时去掉 legacy form.imageUrl / 「主图」占位 */
export function sanitizeSceneImageListForForms(imgs: any[], forms: UserAssetRpsFormRow[]): any[] {
  const result = sanitizeSceneImageList(imgs)
  if (result.length <= 1) return result

  const formById = new Map<number, UserAssetRpsFormRow>()
  for (const f of forms) {
    const id = Number(f?.id)
    if (Number.isFinite(id) && id > 0) formById.set(id, f)
  }

  const byForm = new Map<number, any[]>()
  const noForm: any[] = []
  for (const img of result) {
    const fid = Number(img?.rpsFormId)
    if (!Number.isFinite(fid) || fid <= 0) {
      noForm.push(img)
      continue
    }
    if (!byForm.has(fid)) byForm.set(fid, [])
    byForm.get(fid)!.push(img)
  }

  const out: any[] = [...noForm]
  for (const [formId, formImgs] of byForm) {
    let kept = [...formImgs]
    if (kept.length <= 1) {
      out.push(...kept)
      continue
    }
    const legacyUrl = String(formById.get(formId)?.imageUrl || '').trim()
    if (legacyUrl) {
      // form.imageUrl 现为「当前使用中图片 URL」，与同 URL 的真实 form_image 不是重复占位
      const withoutLegacyPlaceholder = kept.filter((x) => {
        const url = String(x?.url ?? '').trim()
        if (url !== legacyUrl) return true
        const hasRpsImageId =
          x?.rpsImageId != null && Number.isFinite(Number(x.rpsImageId))
        return hasRpsImageId
      })
      if (withoutLegacyPlaceholder.length > 0) kept = withoutLegacyPlaceholder
    }
    const nonGenericTitle = kept.filter((x) => {
      const t = String(x?.title ?? '').trim()
      return t && t !== '主图'
    })
    if (nonGenericTitle.length > 0) {
      kept = kept.filter((x) => String(x?.title ?? '').trim() !== '主图')
    }
    out.push(...kept)
  }
  return out
}


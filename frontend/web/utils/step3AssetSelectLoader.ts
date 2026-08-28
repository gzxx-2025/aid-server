import type { RouteLikeLocation } from '~/types/routeLike'
import type {
  UserAssetRpsFormImageRow,
  UserAssetRpsFormRow,
  UserAssetRpsRow
} from '~/types/business-api'
import { useCreationStore } from '~/stores/creation'
import { sortUserAssetRpsRows, userAssetRpsList } from '~/utils/businessApi'
import { roleVoiceBindingToFormFields } from '~/utils/characterVoiceBinding'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'

export type Step3SelectAssetType = 'scene' | 'character' | 'prop'

type CreationStore = ReturnType<typeof useCreationStore>

function getFormsForRpsRow(raw: UserAssetRpsRow): UserAssetRpsFormRow[] {
  return raw.forms ?? []
}

function isRpsFormImageInUse(img: UserAssetRpsFormImageRow | null | undefined): boolean {
  return Number(img?.isUse) === 1
}

function inUseImagesFromRpsForm(f: UserAssetRpsFormRow): UserAssetRpsFormImageRow[] {
  return (f.images ?? []).filter(
    (img) => isRpsFormImageInUse(img) && String(img?.imageUrl || '').trim()
  )
}

/** 后端已返回 images 时禁止用 form.imageUrl 回退，避免取消主图后 list 刷新复活 */
function resolveLegacyFormImageUrlFallback(f: UserAssetRpsFormRow): string {
  if (Array.isArray(f.images)) return ''
  const url = String(f.imageUrl || '').trim()
  if (!url) return ''
  const hasCurrent =
    Number(f.isUse) === 1 ||
    (f.currentImageId != null && Number.isFinite(Number(f.currentImageId)) && Number(f.currentImageId) > 0)
  return hasCurrent ? url : ''
}

function sanitizeSceneImageList(imgs: any[]): any[] {
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

function sanitizeSceneImageListForForms(imgs: any[], forms: UserAssetRpsFormRow[]): any[] {
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

function namesForType(store: CreationStore, type: Step3SelectAssetType): string[] {
  const sc = store.formData.sceneCharacter
  if (type === 'scene') return sc?.scenes ?? []
  if (type === 'character') return sc?.characters ?? []
  return sc?.props ?? []
}

function imagesRecordForType(store: CreationStore, type: Step3SelectAssetType): Record<number, any[]> {
  if (type === 'scene') return store.sceneImages
  if (type === 'character') return store.characterImages
  return store.propImages
}

function hasAnyImages(record: Record<number, any[]>): boolean {
  return Object.values(record).some((arr) => Array.isArray(arr) && arr.length > 0)
}

/** 选择弹窗打开前：第三步未 bootstrap 或该类型名称/图片未写入 Pinia 时需拉接口 */
export function step3SelectNeedsFetch(store: CreationStore, type: Step3SelectAssetType): boolean {
  const names = namesForType(store, type)
  if (!names.length) return true
  if (!store.step3AssetListSyncReady) {
    return !hasAnyImages(imagesRecordForType(store, type))
  }
  return false
}

function parseSceneAssets(sortedRps: UserAssetRpsRow[]) {
  const sceneNames = sortedRps.map((r, i) => r.assetName || `场景${i + 1}`)
  const sceneImagesNext: Record<number, any[]> = {}
  sortedRps.forEach((raw, i) => {
    const date = raw.updateTime || raw.createTime || ''
    let imgs: any[] = []
    const forms = getFormsForRpsRow(raw)

    const pushInUseFormImages = (f: UserAssetRpsFormRow) => {
      const formId = f.id
      if (formId == null || !Number.isFinite(Number(formId))) return
      const list = inUseImagesFromRpsForm(f)
      for (const img of list) {
        const url = String(img?.imageUrl || '').trim()
        if (!url) continue
        const imageId = Number(img?.id)
        if (
          imgs.some(
            (x) =>
              (Number.isFinite(imageId) && Number(x?.rpsImageId) === imageId) ||
              String(x?.url ?? '').trim() === url
          )
        ) {
          continue
        }
        imgs.push({
          id: Number.isFinite(imageId) ? `img-${imageId}` : `form-${formId}`,
          rpsFormId: Number(formId),
          ...(Number.isFinite(imageId) ? { rpsImageId: imageId } : {}),
          title: String(img?.name || f.name || '主图'),
          url,
          thumbnail: url,
          source: 'server',
          importDate: date
        })
      }
    }

    for (const f of forms) pushInUseFormImages(f)
    if (imgs.length === 0) {
      for (const f of forms) {
        const formId = f.id
        if (formId == null || !Number.isFinite(Number(formId))) continue
        const fallbackUrl = resolveLegacyFormImageUrlFallback(f)
        if (!fallbackUrl) continue
        if (imgs.some((x) => String(x?.url ?? '').trim() === fallbackUrl)) continue
        imgs.push({
          id: `form-${formId}`,
          rpsFormId: Number(formId),
          title: f.name?.trim() || '主图',
          url: fallbackUrl,
          thumbnail: fallbackUrl,
          source: 'server',
          importDate: date
        })
      }
    }
    if (imgs.length > 1) {
      const nonGenericTitle = imgs.filter((x) => String(x?.title ?? '').trim() !== '主图')
      if (nonGenericTitle.length > 0) imgs = nonGenericTitle
    }
    if (imgs.length) sceneImagesNext[i] = sanitizeSceneImageListForForms(imgs, forms)
  })
  return { sceneNames, sceneImagesNext }
}

function parseFormBasedAssets(sortedRps: UserAssetRpsRow[], type: 'character' | 'prop') {
  const names = sortedRps.map((r, i) => r.assetName || `${type === 'character' ? '角色' : '道具'}${i + 1}`)
  const imagesNext: Record<number, any[]> = {}
  const formImagesNext: Record<string, any[]> = {}
  const formsNext: Record<
    number,
    Array<{ name: string; voiceover?: string; voiceoverId?: string; voiceoverAvatarUrl?: string; voiceoverPreviewUrl?: string }>
  > = {}

  sortedRps.forEach((raw, i) => {
    const rawForms = getFormsForRpsRow(raw)
    const date = raw.updateTime || raw.createTime || ''
    const assetImages: any[] = []

    for (let fi = 0; fi < rawForms.length; fi++) {
      const f = rawForms[fi]
      if (f.id == null || !Number.isFinite(Number(f.id))) continue
      const formId = Number(f.id)
      const inUse = inUseImagesFromRpsForm(f)
      const mapped = inUse
        .map((img) => {
          const url = String(img?.imageUrl || '').trim()
          if (!url) return null
          const imageId = Number(img?.id)
          return {
            id: Number.isFinite(imageId) ? `img-${imageId}` : `form-${formId}`,
            rpsFormId: formId,
            ...(Number.isFinite(imageId) ? { rpsImageId: imageId } : {}),
            title: String(img?.name || f.name || `形态图${fi + 1}`),
            url,
            thumbnail: url,
            source: 'server',
            importDate: date
          }
        })
        .filter(Boolean) as any[]

      if (mapped.length > 0) {
        formImagesNext[`${i}-${fi}`] = mapped
        assetImages.push(...mapped)
        continue
      }

      const fallbackUrl = resolveLegacyFormImageUrlFallback(f)
      if (!fallbackUrl) continue
      const fallback = {
        id: `form-${formId}`,
        rpsFormId: formId,
        title: f.name?.trim() || `形态图${fi + 1}`,
        url: fallbackUrl,
        thumbnail: fallbackUrl,
        source: 'server',
        importDate: date
      }
      formImagesNext[`${i}-${fi}`] = [fallback]
      assetImages.push(fallback)
    }

    if (assetImages.length) imagesNext[i] = assetImages
  })

  for (let i = 0; i < sortedRps.length; i++) {
    const rawForms = getFormsForRpsRow(sortedRps[i])
    const voiceFields = type === 'character' ? roleVoiceBindingToFormFields(sortedRps[i].voiceBinding) : {}
    if (rawForms.length > 0) {
      formsNext[i] = rawForms.map((f, idx) => ({
        name: f.name?.trim() ? f.name : `形态${idx + 1}: 未命名`,
        ...voiceFields
      }))
    } else {
      formsNext[i] = [{ name: '形态1: 未命名', ...voiceFields }]
    }
  }

  return { names, imagesNext, formImagesNext, formsNext }
}

export type Step3SelectScope = { projectId: number; episodeId: number }

export function step3SelectScopeKey(scope: Step3SelectScope): string {
  return `${scope.projectId}:${scope.episodeId}`
}

async function isStep3SelectScopeStillValid(
  store: CreationStore,
  route: RouteLikeLocation,
  expected: Step3SelectScope
): Promise<boolean> {
  const ctx = await resolveStoryScriptSaveContext(store, route)
  return ctx != null && ctx.projectId === expected.projectId && ctx.episodeId === expected.episodeId
}

export type EnsureStep3AssetsOptions = {
  /** 返回 true 时丢弃本次写入（作品切换、弹窗关闭、并发加载被新请求取代） */
  isStale?: () => boolean
}

/**
 * 灵感空间 / 分镜图选择弹窗：第三步组件未挂载时主动拉 rps/list 并写入 Pinia，
 * 避免仅依赖 SceneCharacterProp bootstrap 后才有缓存。
 */
export async function ensureStep3AssetsForSelect(
  store: CreationStore,
  route: RouteLikeLocation,
  type: Step3SelectAssetType,
  options?: EnsureStep3AssetsOptions
): Promise<boolean> {
  if (!step3SelectNeedsFetch(store, type)) return true

  const ctx = await resolveStoryScriptSaveContext(store, route)
  if (!ctx) return false
  if (options?.isStale?.()) return false

  try {
    const { rows } = await userAssetRpsList({
      projectId: ctx.projectId,
      episodeId: ctx.episodeId,
      assetType: type
    })
    if (options?.isStale?.()) return false
    if (!(await isStep3SelectScopeStillValid(store, route, ctx))) return false

    const sortedRps = sortUserAssetRpsRows(rows ?? [])

    if (type === 'scene') {
      const { sceneNames, sceneImagesNext } = parseSceneAssets(sortedRps)
      store.updateSceneCharacterData({ scenes: sceneNames })
      // 对齐原 Pinia $patch 语义：顶层 record 合并、数组值整体替换
      useCreationStore.setState((prev) => ({
        sceneImages: { ...prev.sceneImages, ...sceneImagesNext }
      }))
      return true
    }

    const parsed = parseFormBasedAssets(sortedRps, type)
    if (type === 'character') {
      store.updateSceneCharacterData({ characters: parsed.names })
      // 对齐原 Pinia $patch 语义：顶层 record 合并、数组值整体替换
      useCreationStore.setState((prev) => ({
        characterImages: { ...prev.characterImages, ...parsed.imagesNext },
        characterFormImages: { ...prev.characterFormImages, ...parsed.formImagesNext },
        characterForms: { ...prev.characterForms, ...parsed.formsNext }
      }))
    } else {
      store.updateSceneCharacterData({ props: parsed.names })
      // 对齐原 Pinia $patch 语义：顶层 record 合并、数组值整体替换
      useCreationStore.setState((prev) => ({
        propImages: { ...prev.propImages, ...parsed.imagesNext },
        propFormImages: { ...prev.propFormImages, ...parsed.formImagesNext },
        propForms: { ...prev.propForms, ...parsed.formsNext }
      }))
    }
    return true
  } catch {
    return false
  }
}

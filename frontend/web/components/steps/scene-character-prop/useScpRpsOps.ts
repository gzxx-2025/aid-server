'use client'

import { message } from 'antd'
import type { SceneCharacterData } from '~/types'
import type { UserAssetRpsFormRow,UserAssetRpsRow } from '~/types/business-api'
import {
userAssetRpsDelete,
userAssetRpsFormImageList,
userAssetRpsFormImageUpdate,
userAssetRpsList
} from '~/utils/businessApi'
import { setFormImageInUse,unsetFormImageInUse } from '~/utils/formImageAutoUse'
import { findAlignedFormIndexByFormId,mapRpsFormsToAlignedFormIds } from '~/utils/rpsFormIdsAlign'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
formsNumClone,
normalizeFormRowFromApi,
numRecordClone,
sanitizeSceneImageList,
strRecordClone
} from './scpRowUtils'
import type { ScpCtx,TabKey } from './types'

/**
 * POST /api/user/asset/rps/delete：
 * - 删除整条角色/道具：只传 id（后端级联删形态与图），不要带 formId。
 * - 批量删除主资产：传 ids（见 userAssetRpsDeleteBatchByIds）。
 */
export async function rpsDeleteWholeAsset(assetId: number | null | undefined): Promise<void> {
  if (assetId == null || !Number.isFinite(Number(assetId))) return
  try {
    await userAssetRpsDelete({ id: Number(assetId) })
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    const text = `${err?.msg ?? ''} ${err?.message ?? ''}`.toLowerCase()
    if (/不存在|已删除|not found|404/.test(text)) return
    throw e
  }
}

/** 无主资产 id 时的兜底：仅按形态逐个删除（只传 formId） */
export async function rpsDeleteOrphanFormsOnly(formIds: number[]): Promise<void> {
  for (const formId of formIds) {
    if (formId == null || !Number.isFinite(Number(formId))) continue
    try {
      await userAssetRpsDelete({ formId: Number(formId) })
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      const text = `${err?.msg ?? ''} ${err?.message ?? ''}`.toLowerCase()
      if (/不存在|已删除|not found|404/.test(text)) continue
      throw e
    }
  }
}

/** 删除单个形态（与 removeCharacter 中单条逻辑一致） */
export async function rpsDeleteSingleForm(assetId: number, formId: number): Promise<void> {
  try {
    await userAssetRpsDelete({ id: assetId, formId })
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    const text = `${err?.msg ?? ''} ${err?.message ?? ''}`.toLowerCase()
    if (/不存在|已删除|not found|404/.test(text)) return
    throw e
  }
}

export async function resolveInUseImageIdByFormId(formId: number): Promise<number | null> {
  try {
    const list = await userAssetRpsFormImageList({ formId, isUse: 1 })
    const first = list.find((x) => x?.id != null && Number.isFinite(Number(x.id)))
    return first ? Number(first.id) : null
  } catch {
    return null
  }
}

export async function syncImageTitleToRps(imageLike: any, nextTitle: string): Promise<boolean> {
  const imageId =
    imageLike?.rpsImageId != null && Number.isFinite(Number(imageLike.rpsImageId))
      ? Number(imageLike.rpsImageId)
      : null
  if (imageId == null) return true
  try {
    await userAssetRpsFormImageUpdate({ imageId, name: nextTitle })
    return true
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    message.error(err?.msg || err?.message || '图片名称同步失败')
    return false
  }
}

export interface ScpRpsOpsApi {
  update: (next: SceneCharacterData) => void
  /** 将接口返回的主表 VO 中的 forms[].id 写回本地映射（下标与形态列表对齐，无效 id 为 0） */
  applyRpsRowFormIds: (kind: 'scene' | 'character' | 'prop', index: number, row: UserAssetRpsRow) => void
  /** 手动/新增资产后：优先用 create 返回值，否则再拉 rps/list 补齐形态 id */
  syncAssetFormIdsFromServer: (
    kind: 'character' | 'prop',
    index: number,
    assetId: number,
    rowHint?: UserAssetRpsRow | null
  ) => Promise<void>
  resolveFormIdForAssetForm: (
    tab: 'character' | 'prop',
    assetIndex: number,
    formIndex: number
  ) => Promise<number | null>
  tryUseFormImage: (payload: { imageId?: number | null; formId?: number | null }) => Promise<number | null>
  /** 列表「删除」：POST form/unuse，仅取消使用中状态（不删形态记录） */
  tryUnuseFormImage: (payload: { imageId?: number | null; formId?: number | null }) => Promise<boolean>
  /** rps/update 必填 formId；形态创建仅在编辑弹窗内通过上传/资产库/AI 完成，此处不再占位 form/create */
  ensureFormIdForRpsUpdate: (
    kind: 'scene' | 'character' | 'prop',
    assetIndex: number,
    formIndex: number
  ) => number | null
  step3SlotHasImageForFormId: (formId: number) => boolean
  sanitizeStep3SceneImagesState: () => void
  findLocalFormIdsForAsset: (tab: TabKey, assetId: number, sceneIndex?: number) => number[]
  fetchRpsRowByAssetId: (payload: { tab: TabKey; assetId: number }) => Promise<
    (UserAssetRpsRow & { forms: UserAssetRpsFormRow[] }) | null
  >
  fetchRpsRowByAssetIdWithLocalFallback: (payload: {
    tab: TabKey
    assetId: number
    sceneIndex?: number
  }) => Promise<{ id?: number; forms?: UserAssetRpsFormRow[] } | null>
  findSceneIndexByAssetId: (assetId: number) => number
  findCharacterIndexByAssetId: (assetId: number) => number
  findPropIndexByAssetId: (assetId: number) => number
  syncStep3AssetsToCreationStore: () => void
  /** rps/list 拉取成功后写入 Pinia，覆盖内存中可能残留的旧形态/旧图，与接口「forms:[]」等保持一致 */
  syncStep3AfterApiLoad: () => void
}

export function useScpRpsOps(ctx: ScpCtx): ScpRpsOpsApi {
  const update = (next: SceneCharacterData) => {
    ctx.localValue.set(next)
    ctx.emitUpdateModelValue(next)
  }

  /** 将接口返回的主表 VO 中的 forms[].id 写回本地映射（下标与形态列表对齐，无效 id 为 0） */
  function applyRpsRowFormIds(
    kind: 'scene' | 'character' | 'prop',
    index: number,
    row: UserAssetRpsRow
  ) {
    const ids = mapRpsFormsToAlignedFormIds(row.forms)
    if (kind === 'scene') {
      ctx.sceneFormIdsByIndex.set({ ...ctx.sceneFormIdsByIndex.get(), [index]: ids })
    } else if (kind === 'character') {
      ctx.characterFormIdsByIndex.set({ ...ctx.characterFormIdsByIndex.get(), [index]: ids })
    } else {
      ctx.propFormIdsByIndex.set({ ...ctx.propFormIdsByIndex.get(), [index]: ids })
    }
  }

  /** 手动/新增资产后：优先用 create 返回值，否则再拉 rps/list 补齐形态 id */
  async function syncAssetFormIdsFromServer(
    kind: 'character' | 'prop',
    index: number,
    assetId: number,
    rowHint?: UserAssetRpsRow | null
  ) {
    if (rowHint) applyRpsRowFormIds(kind, index, rowHint)
    const cached =
      kind === 'character'
        ? (ctx.characterFormIdsByIndex.get()[index] ?? [])
        : (ctx.propFormIdsByIndex.get()[index] ?? [])
    if (cached.some((id) => Number.isFinite(Number(id)) && Number(id) > 0)) return
    const fetched = await fetchRpsRowByAssetId({ tab: kind, assetId })
    if (fetched) applyRpsRowFormIds(kind, index, fetched)
  }

  async function resolveFormIdForAssetForm(
    tab: 'character' | 'prop',
    assetIndex: number,
    formIndex: number
  ): Promise<number | null> {
    const assetId =
      tab === 'character' ? ctx.characterAssetIds.get()[assetIndex] : ctx.propAssetIds.get()[assetIndex]
    if (assetId == null || !Number.isFinite(Number(assetId))) return null
    await syncAssetFormIdsFromServer(tab, assetIndex, Number(assetId))
    const cached =
      tab === 'character'
        ? (ctx.characterFormIdsByIndex.get()[assetIndex] ?? [])
        : (ctx.propFormIdsByIndex.get()[assetIndex] ?? [])
    const fromCache = cached[formIndex]
    if (fromCache != null && Number.isFinite(Number(fromCache)) && Number(fromCache) > 0) {
      return Number(fromCache)
    }
    const latestRow = await fetchRpsRowByAssetIdWithLocalFallback({ tab, assetId: Number(assetId) })
    const forms = latestRow?.forms ?? []
    const row = forms[formIndex]
    if (row?.id != null && Number.isFinite(Number(row.id))) {
      applyRpsRowFormIds(tab, assetIndex, latestRow as UserAssetRpsRow)
      return Number(row.id)
    }
    return null
  }

  async function tryUseFormImage(payload: {
    imageId?: number | null
    formId?: number | null
  }): Promise<number | null> {
    let targetImageId: number | null =
      payload.imageId != null && Number.isFinite(Number(payload.imageId))
        ? Number(payload.imageId)
        : null
    if (targetImageId == null && payload.formId != null && Number.isFinite(Number(payload.formId))) {
      try {
        const list = await userAssetRpsFormImageList({ formId: Number(payload.formId) })
        const preferred =
          list.find(
            (x) => Number(x?.isUse) === 1 && x?.id != null && Number.isFinite(Number(x.id))
          ) ??
          list.find((x) => x?.id != null && Number.isFinite(Number(x.id))) ??
          null
        targetImageId = preferred ? Number(preferred.id) : null
      } catch {
        targetImageId = null
      }
    }
    if (targetImageId == null) return null
    const id = Number(targetImageId)
    const routeCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    const ok = await setFormImageInUse(id, { projectId: routeCtx?.projectId })
    if (!ok) {
      message.error('设置主图失败')
      return null
    }
    return id
  }

  /** 列表「删除」：POST form/unuse，仅取消使用中状态（不删形态记录） */
  async function tryUnuseFormImage(payload: {
    imageId?: number | null
    formId?: number | null
  }): Promise<boolean> {
    let targetImageId: number | null =
      payload.imageId != null && Number.isFinite(Number(payload.imageId))
        ? Number(payload.imageId)
        : null
    if (targetImageId == null && payload.formId != null && Number.isFinite(Number(payload.formId))) {
      targetImageId = await resolveInUseImageIdByFormId(Number(payload.formId))
    }
    if (targetImageId == null) return true
    const routeCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    const result = await unsetFormImageInUse(Number(targetImageId), { projectId: routeCtx?.projectId })
    if (!result.ok) {
      message.error(result.reason || '取消主图失败')
      return false
    }
    return true
  }

  /** rps/update 必填 formId；形态创建仅在编辑弹窗内通过上传/资产库/AI 完成，此处不再占位 form/create */
  function ensureFormIdForRpsUpdate(
    kind: 'scene' | 'character' | 'prop',
    assetIndex: number,
    formIndex: number
  ): number | null {
    const ids =
      kind === 'scene'
        ? (ctx.sceneFormIdsByIndex.get()[assetIndex] ?? [])
        : kind === 'character'
          ? (ctx.characterFormIdsByIndex.get()[assetIndex] ?? [])
          : (ctx.propFormIdsByIndex.get()[assetIndex] ?? [])
    const id = ids[formIndex]
    return id != null && Number.isFinite(Number(id)) ? Number(id) : null
  }

  function step3SlotHasImageForFormId(formId: number): boolean {
    const fid = Number(formId)
    if (!Number.isFinite(fid) || fid <= 0) return false
    for (const imgs of Object.values(ctx.sceneImages.get())) {
      if (
        (imgs ?? []).some((img) => Number(img?.rpsFormId) === fid && String(img?.url ?? '').trim())
      ) {
        return true
      }
    }
    for (const [k, ids] of Object.entries(ctx.sceneFormIdsByIndex.get())) {
      const si = Number(k)
      if (!Number.isFinite(si)) continue
      if (!(ids ?? []).some((id) => Number(id) === fid)) continue
      const imgs = ctx.sceneImages.get()[si] ?? []
      if (imgs.some((img) => Number(img?.rpsFormId) === fid && String(img?.url ?? '').trim()))
        return true
    }
    for (const [k, ids] of Object.entries(ctx.characterFormIdsByIndex.get())) {
      const ci = Number(k)
      if (!Number.isFinite(ci)) continue
      const fi = findAlignedFormIndexByFormId(ids, fid)
      if (fi < 0) continue
      const slotKey = `${ci}-${fi}`
      const imgs = ctx.characterFormImages.get()[slotKey] ?? []
      if (imgs.some((img) => String(img?.url ?? '').trim())) return true
    }
    for (const [k, ids] of Object.entries(ctx.propFormIdsByIndex.get())) {
      const pi = Number(k)
      if (!Number.isFinite(pi)) continue
      const fi = findAlignedFormIndexByFormId(ids, fid)
      if (fi < 0) continue
      const slotKey = `${pi}-${fi}`
      const imgs = ctx.propFormImages.get()[slotKey] ?? []
      if (imgs.some((img) => String(img?.url ?? '').trim())) return true
    }
    return false
  }

  function sanitizeStep3SceneImagesState() {
    let changed = false
    const next: Record<number, any[]> = { ...ctx.sceneImages.get() }
    for (const key of Object.keys(next)) {
      const si = Number(key)
      if (!Number.isFinite(si)) continue
      let sanitized = sanitizeSceneImageList(next[si] ?? [])
      if (sanitized.length > 1) {
        const nonGenericTitle = sanitized.filter((x: any) => String(x?.title ?? '').trim() !== '主图')
        if (nonGenericTitle.length > 0) sanitized = nonGenericTitle
      }
      if (sanitized.length !== (next[si]?.length ?? 0)) {
        next[si] = sanitized
        changed = true
      }
    }
    if (changed) ctx.sceneImages.set(next)
  }

  function findLocalFormIdsForAsset(tab: TabKey, assetId: number, sceneIndex?: number): number[] {
    const aid = Number(assetId)
    if (tab === 'scene') {
      if (
        sceneIndex != null &&
        Number.isFinite(sceneIndex) &&
        Number(ctx.sceneAssetIds.get()[sceneIndex]) === aid
      ) {
        return ctx.sceneFormIdsByIndex.get()[sceneIndex] ?? []
      }
      for (const [k, v] of Object.entries(ctx.sceneAssetIds.get())) {
        if (Number(v) === aid) return ctx.sceneFormIdsByIndex.get()[Number(k)] ?? []
      }
      return []
    }
    if (tab === 'character') {
      for (const [k, v] of Object.entries(ctx.characterAssetIds.get())) {
        if (Number(v) === aid) return ctx.characterFormIdsByIndex.get()[Number(k)] ?? []
      }
      return []
    }
    for (const [k, v] of Object.entries(ctx.propAssetIds.get())) {
      if (Number(v) === aid) return ctx.propFormIdsByIndex.get()[Number(k)] ?? []
    }
    return []
  }

  async function fetchRpsRowByAssetId(payload: { tab: TabKey; assetId: number }) {
    const routeCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!routeCtx) return null
    const assetType =
      payload.tab === 'scene' ? 'scene' : payload.tab === 'character' ? 'character' : 'prop'
    const { rows } = await userAssetRpsList({
      projectId: routeCtx.projectId,
      episodeId: routeCtx.episodeId,
      assetType
    })
    const found = rows.find((r) => r?.id != null && Number(r.id) === Number(payload.assetId))
    if (!found) return null
    const normalized = (found.forms ?? [])
      .map((f) => normalizeFormRowFromApi(f as UserAssetRpsFormRow & { formId?: number }))
      .filter((x): x is UserAssetRpsFormRow => x != null)
    if (!normalized.length) return null
    return { ...found, forms: normalized }
  }

  async function fetchRpsRowByAssetIdWithLocalFallback(payload: {
    tab: TabKey
    assetId: number
    sceneIndex?: number
  }) {
    const row = await fetchRpsRowByAssetId(payload)
    if (row?.forms?.length) return row
    const localIds = findLocalFormIdsForAsset(
      payload.tab,
      payload.assetId,
      payload.sceneIndex
    ).filter((id) => id != null && Number.isFinite(Number(id)))
    if (!localIds.length) return row
    return {
      id: payload.assetId,
      forms: localIds.map((id) => ({ id: Number(id), name: ' ' }) as UserAssetRpsFormRow)
    }
  }

  function findSceneIndexByAssetId(assetId: number): number {
    for (const [k, v] of Object.entries(ctx.sceneAssetIds.get())) {
      if (Number(v) === Number(assetId)) return Number(k)
    }
    return -1
  }

  function findCharacterIndexByAssetId(assetId: number): number {
    for (const [k, v] of Object.entries(ctx.characterAssetIds.get())) {
      if (Number(v) === Number(assetId)) return Number(k)
    }
    return -1
  }

  function findPropIndexByAssetId(assetId: number): number {
    for (const [k, v] of Object.entries(ctx.propAssetIds.get())) {
      if (Number(v) === Number(assetId)) return Number(k)
    }
    return -1
  }

  function syncStep3AssetsToCreationStore() {
    ctx.patchStore({
      sceneImages: numRecordClone(ctx.sceneImages.get()),
      characterImages: numRecordClone(ctx.characterImages.get()),
      propImages: numRecordClone(ctx.propImages.get()),
      characterForms: formsNumClone(ctx.characterForms.get()),
      propForms: formsNumClone(ctx.propForms.get()),
      characterFormImages: strRecordClone(ctx.characterFormImages.get()),
      propFormImages: strRecordClone(ctx.propFormImages.get())
    })
  }

  /** rps/list 拉取成功后写入 Pinia，覆盖内存中可能残留的旧形态/旧图，与接口「forms:[]」等保持一致 */
  function syncStep3AfterApiLoad() {
    ctx.syncingStep3ToStore = true
    try {
      syncStep3AssetsToCreationStore()
    } finally {
      ctx.syncingStep3ToStore = false
    }
  }

  return {
    update,
    applyRpsRowFormIds,
    syncAssetFormIdsFromServer,
    resolveFormIdForAssetForm,
    tryUseFormImage,
    tryUnuseFormImage,
    ensureFormIdForRpsUpdate,
    step3SlotHasImageForFormId,
    sanitizeStep3SceneImagesState,
    findLocalFormIdsForAsset,
    fetchRpsRowByAssetId,
    fetchRpsRowByAssetIdWithLocalFallback,
    findSceneIndexByAssetId,
    findCharacterIndexByAssetId,
    findPropIndexByAssetId,
    syncStep3AssetsToCreationStore,
    syncStep3AfterApiLoad
  }
}

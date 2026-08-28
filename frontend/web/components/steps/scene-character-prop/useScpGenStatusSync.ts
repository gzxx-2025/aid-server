'use client'

import { findAlignedFormIndexByFormId } from '~/utils/rpsFormIdsAlign'
import {
parseFormImageFailedFormIdsFromComplete,
parseFormImageSuccessItemsFromComplete
} from './scpTaskUtils'
import type { FormGenStatus,ScpCtx } from './types'

export interface ScpGenStatusSyncApi {
  /** SSE complete 后立刻写入图片并清除 generating（避免刷新恢复时列表尚未回填导致卡 loading） */
  applyFormImageCompleteDataToStep3Ui: (data: unknown) => boolean
  markStep3SlotsSuccessFromCompleteData: (data: unknown) => void
  collectGeneratingFormIdsForStep3: () => Set<number>
  /** Pinia 已被弹窗清掉 generating 时，同步本地列表 ref，避免卡片仍转圈 */
  syncLocalStep3GeneratingFromPinia: () => void
}

export function useScpGenStatusSync(ctx: ScpCtx): ScpGenStatusSyncApi {
  /** SSE complete 后立刻写入图片并清除 generating（避免刷新恢复时列表尚未回填导致卡 loading） */
  function applyFormImageCompleteDataToStep3Ui(data: unknown): boolean {
    const items = parseFormImageSuccessItemsFromComplete(data)
    if (!items.length) return false

    let changed = false
    const now = new Date().toISOString()

    for (const item of items) {
      const { formId, imageId, imageUrl } = item
      if (imageUrl) {
        for (const [k, ids] of Object.entries(ctx.sceneFormIdsByIndex.get())) {
          const si = Number(k)
          if (!Number.isFinite(si)) continue
          if (!(ids ?? []).some((id) => Number(id) === formId)) continue
          const prev = ctx.sceneImages.get()[si] ?? []
          // 该场景已有展示图时，刷新恢复不再追加 SSE 占位
          if (prev.some((img) => String(img?.url ?? '').trim())) {
            break
          }
          const alreadyHasFormImage = prev.some(
            (img) => Number(img?.rpsFormId) === formId && String(img?.url ?? '').trim()
          )
          const alreadyHasImage =
            alreadyHasFormImage ||
            (imageId != null && prev.some((img) => Number(img?.rpsImageId) === imageId)) ||
            prev.some((img) => String(img?.url ?? '').trim() === imageUrl)
          if (!alreadyHasImage) {
            const rawSceneName = String(ctx.localValue.get().scenes[si] ?? '').trim()
            const sceneTitle =
              rawSceneName.replace(/^场景\d+:\s*/, '').trim() || rawSceneName || '主图'
            ctx.sceneImages.set({
              ...ctx.sceneImages.get(),
              [si]: [
                ...prev,
                {
                  id: imageId != null ? `img-${imageId}` : `form-${formId}`,
                  rpsFormId: formId,
                  ...(imageId != null ? { rpsImageId: imageId } : {}),
                  title: sceneTitle,
                  url: imageUrl,
                  thumbnail: imageUrl,
                  source: 'server',
                  sourceType: 'ai_auto',
                  _serverSourceType: 'ai_auto',
                  importDate: now
                }
              ]
            })
            changed = true
          }
          break
        }

        for (const [k, ids] of Object.entries(ctx.characterFormIdsByIndex.get())) {
          const ci = Number(k)
          if (!Number.isFinite(ci)) continue
          const fi = findAlignedFormIndexByFormId(ids, formId)
          if (fi < 0) continue
          const slotKey = `${ci}-${fi}`
          const prev = ctx.characterFormImages.get()[slotKey] ?? []
          const alreadyHasFormImage = prev.some(
            (img) => Number(img?.rpsFormId) === formId && String(img?.url ?? '').trim()
          )
          const alreadyHasImage =
            alreadyHasFormImage ||
            (imageId != null && prev.some((img) => Number(img?.rpsImageId) === imageId)) ||
            prev.some((img) => String(img?.url ?? '').trim() === imageUrl)
          if (!alreadyHasImage) {
            const entry = {
              id: imageId != null ? `img-${imageId}` : `form-${formId}`,
              rpsFormId: formId,
              ...(imageId != null ? { rpsImageId: imageId } : {}),
              title: `形态图${fi + 1}`,
              url: imageUrl,
              thumbnail: imageUrl,
              source: 'server',
              sourceType: 'ai_auto',
              _serverSourceType: 'ai_auto',
              importDate: now
            }
            ctx.characterFormImages.set({
              ...ctx.characterFormImages.get(),
              [slotKey]: [...prev, entry]
            })
            const assetImgs = [...(ctx.characterImages.get()[ci] ?? []), entry]
            ctx.characterImages.set({ ...ctx.characterImages.get(), [ci]: assetImgs })
            changed = true
          }
          break
        }

        for (const [k, ids] of Object.entries(ctx.propFormIdsByIndex.get())) {
          const pi = Number(k)
          if (!Number.isFinite(pi)) continue
          const fi = findAlignedFormIndexByFormId(ids, formId)
          if (fi < 0) continue
          const slotKey = `${pi}-${fi}`
          const prev = ctx.propFormImages.get()[slotKey] ?? []
          const alreadyHasFormImage = prev.some(
            (img) => Number(img?.rpsFormId) === formId && String(img?.url ?? '').trim()
          )
          const alreadyHasImage =
            alreadyHasFormImage ||
            (imageId != null && prev.some((img) => Number(img?.rpsImageId) === imageId)) ||
            prev.some((img) => String(img?.url ?? '').trim() === imageUrl)
          if (!alreadyHasImage) {
            const entry = {
              id: imageId != null ? `img-${imageId}` : `form-${formId}`,
              rpsFormId: formId,
              ...(imageId != null ? { rpsImageId: imageId } : {}),
              title: `形态图${fi + 1}`,
              url: imageUrl,
              thumbnail: imageUrl,
              source: 'server',
              sourceType: 'ai_auto',
              _serverSourceType: 'ai_auto',
              importDate: now
            }
            ctx.propFormImages.set({ ...ctx.propFormImages.get(), [slotKey]: [...prev, entry] })
            const assetImgs = [...(ctx.propImages.get()[pi] ?? []), entry]
            ctx.propImages.set({ ...ctx.propImages.get(), [pi]: assetImgs })
            changed = true
          }
          break
        }
      }

      if (ctx.markStep3SlotSuccessByFormId(formId)) changed = true
    }

    if (changed) ctx.store().refreshStep3VisualGeneratingFlag()
    return changed
  }

  function markStep3SlotsSuccessFromCompleteData(data: unknown) {
    for (const item of parseFormImageSuccessItemsFromComplete(data)) {
      ctx.markStep3SlotSuccessByFormId(item.formId)
    }
    for (const fid of parseFormImageFailedFormIdsFromComplete(data)) {
      ctx.resolveFormIdGeneratingSlotAfterCancel(fid, 'failed')
    }
    ctx.reconcileStep3GeneratingWithLoadedImages()
  }

  function collectGeneratingFormIdsForStep3(): Set<number> {
    const formIds = new Set<number>()
    for (const [ks, st] of Object.entries(ctx.sceneGenerationStatus.get())) {
      if (st !== 'generating') continue
      const si = Number(ks)
      if (!Number.isFinite(si)) continue
      for (const fid of ctx.sceneFormIdsByIndex.get()[si] ?? []) {
        const n = Number(fid)
        if (Number.isFinite(n) && n > 0) formIds.add(n)
      }
    }
    for (const [key, st] of Object.entries(ctx.characterFormGenerationStatus.get())) {
      if (st !== 'generating' || key.startsWith('pending-')) continue
      const parts = key.split('-')
      const ci = Number(parts[0])
      const fi = Number(parts[1])
      if (!Number.isFinite(ci) || !Number.isFinite(fi)) continue
      const fid = ctx.characterFormIdsByIndex.get()[ci]?.[fi]
      const n = Number(fid)
      if (Number.isFinite(n) && n > 0) formIds.add(n)
    }
    for (const [key, st] of Object.entries(ctx.propFormGenerationStatus.get())) {
      if (st !== 'generating' || key.startsWith('pending-')) continue
      const parts = key.split('-')
      const pi = Number(parts[0])
      const fi = Number(parts[1])
      if (!Number.isFinite(pi) || !Number.isFinite(fi)) continue
      const fid = ctx.propFormIdsByIndex.get()[pi]?.[fi]
      const n = Number(fid)
      if (Number.isFinite(n) && n > 0) formIds.add(n)
    }
    return formIds
  }

  /** Pinia 已被弹窗清掉 generating 时，同步本地列表 ref，避免卡片仍转圈 */
  function syncLocalStep3GeneratingFromPinia() {
    let changed = false
    for (const [k, st] of Object.entries(ctx.sceneGenerationStatus.get())) {
      const idx = Number(k)
      if (!Number.isFinite(idx) || st !== 'generating') continue
      const piniaSt = ctx.store().sceneGenerationStatus[idx]
      if (piniaSt === 'generating') continue
      const next: FormGenStatus =
        piniaSt === 'success' || piniaSt === 'failed' || piniaSt === 'idle'
          ? piniaSt
          : ctx.sceneSlotHasLoadedImages(idx)
            ? 'success'
            : 'idle'
      ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [idx]: next })
      changed = true
    }
    for (const [key, st] of Object.entries(ctx.characterFormGenerationStatus.get())) {
      if (st !== 'generating') continue
      const piniaSt = ctx.store().characterFormGenerationStatus[key]
      if (piniaSt === 'generating') continue
      const next: FormGenStatus =
        piniaSt === 'success' || piniaSt === 'failed' || piniaSt === 'idle'
          ? piniaSt
          : ctx.characterFormSlotHasLoadedImages(key)
            ? 'success'
            : 'idle'
      ctx.characterFormGenerationStatus.set({
        ...ctx.characterFormGenerationStatus.get(),
        [key]: next
      })
      changed = true
    }
    for (const [key, st] of Object.entries(ctx.propFormGenerationStatus.get())) {
      if (st !== 'generating') continue
      const piniaSt = ctx.store().propFormGenerationStatus[key]
      if (piniaSt === 'generating') continue
      const next: FormGenStatus =
        piniaSt === 'success' || piniaSt === 'failed' || piniaSt === 'idle'
          ? piniaSt
          : ctx.propFormSlotHasLoadedImages(key)
            ? 'success'
            : 'idle'
      ctx.propFormGenerationStatus.set({ ...ctx.propFormGenerationStatus.get(), [key]: next })
      changed = true
    }
    if (changed) ctx.store().refreshStep3VisualGeneratingFlag()
  }

  return {
    applyFormImageCompleteDataToStep3Ui,
    markStep3SlotsSuccessFromCompleteData,
    collectGeneratingFormIdsForStep3,
    syncLocalStep3GeneratingFromPinia
  }
}

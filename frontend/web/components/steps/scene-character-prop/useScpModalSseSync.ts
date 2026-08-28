'use client'

import {
findSceneModalSseTaskInScopes,
resolveCurrentStep3GenVisualScopeBlobs
} from '~/hooks/useCreationStoreHydration'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'
import {
  buildFormSlotKey,
  formSlotKeyToEditorScopeKey,
  isLegacyBareFormSlotScopeKey,
  parseBareFormSlotKey,
  parseFormEditorScopeKey
} from '~/utils/step3FormEditorScopeKey'
import { parseTaskId } from './scpTaskUtils'
import type { ScpCtx } from './types'

export interface ScpModalSseSyncApi {
  hasAnyPersistedModalSseTasks: () => boolean
  /** 任务列表已刷新后：清除 Pinia 中已无进行中任务的 modalSseTasks，避免流程条/Tab loading 误亮 */
  purgeNonOngoingModalSseTasksFromStore: () => void
  sceneIdxHasPersistedModalSseTask: (sceneIdx: number) => boolean
  characterCardHasPersistedModalSseTask: (ci: number) => boolean
  characterSlotHasPersistedModalSseTask: (slotKey: string) => boolean
  propCardHasPersistedModalSseTask: (pi: number) => boolean
  propSlotHasPersistedModalSseTask: (slotKey: string) => boolean
  /** 刷新后：Pinia 中 modalSseTasks 存在但 generating 被误清时，恢复列表卡片 loading */
  syncGeneratingFromPersistedModalSseTasks: () => void
  /**
   * Pinia 持久化了各卡片的 generating；刷新后若任务列表已无对应形态图任务，会与真实状态脱节（无 SSE、任务角标不显示）。
   * 在 reconcile 之后调用：凡本地仍标为 generating、且当前进行中的形态图任务不覆盖其 formId 的 slot，一律回落为 idle。
   */
  clearStalePersistedGeneratingWithoutOngoingStep3Cover: (cover: {
    coverFormIds: Set<number>
    coverAssetIds: Set<number>
    coverImageIds?: Set<number>
  }) => void
  /** @deprecated 兼容旧调用 */
  clearStalePersistedGeneratingWithoutOngoingFormImageCover: (coverFormIds: Set<number>) => void
}

export function useScpModalSseSync(ctx: ScpCtx): ScpModalSseSyncApi {
  function hasAnyPersistedModalSseTasks(): boolean {
    for (const blob of Object.values(ctx.store().step3GenVisualByScope || {})) {
      if (Object.keys(blob?.modalSseTasks || {}).length > 0) return true
    }
    return false
  }

  /** 任务列表已刷新后：清除 Pinia 中已无进行中任务的 modalSseTasks，避免流程条/Tab loading 误亮 */
  function purgeNonOngoingModalSseTasksFromStore() {
    const ongoingTaskIds = new Set(
      ctx.ongoingTasks.get()
        .map((t) => parseTaskId(t.id))
        .filter((id): id is number => id != null)
    )
    for (const { key, blob } of resolveCurrentStep3GenVisualScopeBlobs(ctx.store(), ctx.route())) {
      for (const [scope, snap] of Object.entries(blob?.modalSseTasks || {})) {
        const tid = Number(snap?.taskId)
        if (Number.isFinite(tid) && tid > 0 && ongoingTaskIds.has(tid)) continue
        ctx.store().clearSceneModalSseTask(scope, key)
      }
    }
    ctx.store().refreshStep3VisualGeneratingFlag()
  }

  function sceneIdxHasPersistedModalSseTask(sceneIdx: number): boolean {
    return !!findSceneModalSseTaskInScopes(ctx.store(), `scene-${sceneIdx}`, ctx.route())
  }

  function characterCardHasPersistedModalSseTask(ci: number): boolean {
    return !!findSceneModalSseTaskInScopes(ctx.store(), `character-${ci}`, ctx.route())
  }

  function characterSlotHasPersistedModalSseTask(slotKey: string): boolean {
    const typed = formSlotKeyToEditorScopeKey('character', slotKey)
    if (typed && findSceneModalSseTaskInScopes(ctx.store(), typed, ctx.route())) return true
    // 历史裸键：仅当道具侧没有同槽任务时才认作角色，避免跨 Tab 假阳性
    if (!isLegacyBareFormSlotScopeKey(slotKey)) return false
    if (findSceneModalSseTaskInScopes(ctx.store(), formSlotKeyToEditorScopeKey('prop', slotKey)!, ctx.route())) {
      return false
    }
    const bare = findSceneModalSseTaskInScopes(ctx.store(), slotKey, ctx.route())
    if (!bare) return false
    const parts = parseBareFormSlotKey(slotKey)
    if (!parts) return false
    // 道具也有同槽 formId 映射时，裸键优先归道具（与旧 heuristic 一致但不再污染角色查找）
    if (ctx.propFormIdsByIndex.get()[parts.assetIndex]?.[parts.formIndex] != null) return false
    return true
  }

  function propSlotHasPersistedModalSseTask(slotKey: string): boolean {
    const typed = formSlotKeyToEditorScopeKey('prop', slotKey)
    if (typed && findSceneModalSseTaskInScopes(ctx.store(), typed, ctx.route())) return true
    if (!isLegacyBareFormSlotScopeKey(slotKey)) return false
    // 已有带前缀的角色任务时，裸键不再算道具
    if (findSceneModalSseTaskInScopes(ctx.store(), formSlotKeyToEditorScopeKey('character', slotKey)!, ctx.route())) {
      return false
    }
    return !!findSceneModalSseTaskInScopes(ctx.store(), slotKey, ctx.route())
  }

  function propCardHasPersistedModalSseTask(pi: number): boolean {
    return !!findSceneModalSseTaskInScopes(ctx.store(), `prop-${pi}`, ctx.route())
  }

  /** 刷新后：Pinia 中 modalSseTasks 存在但 generating 被误清时，恢复列表卡片 loading */
  function syncGeneratingFromPersistedModalSseTasks() {
    if (routePathToCreationStep(ctx.route().path) !== 'scene-character') return
    const seen = new Set<string>()
    const ongoingTaskIds = new Set(
      ctx.ongoingTasks.get()
        .map((t) => parseTaskId(t.id))
        .filter((id): id is number => id != null)
    )
    for (const { blob } of resolveCurrentStep3GenVisualScopeBlobs(ctx.store(), ctx.route())) {
      for (const snap of Object.values(blob?.modalSseTasks || {})) {
        const scopeKey = String(snap?.editorScopeKey || '').trim()
        if (!scopeKey || seen.has(scopeKey)) continue
        const snapTaskId = Number(snap?.taskId)
        if (
          Number.isFinite(snapTaskId) &&
          snapTaskId > 0 &&
          !ongoingTaskIds.has(snapTaskId)
        ) {
          continue
        }
        seen.add(scopeKey)

        if (snap.taskKind === 'setting-card') {
          const imageId = Number(snap.imageId)
          if (Number.isFinite(imageId) && imageId > 0) {
            ctx.markSettingCardGenBusy([imageId])
            ctx.applyRpsImageIdToCharacterSettingCardGeneratingSlots(imageId)
          }
          continue
        }

        const formId = Number(snap.formId)
        if (Number.isFinite(formId) && formId > 0) {
          ctx.applyFormIdToStep3GeneratingSlots(formId)
          continue
        }

        const sceneMatch = scopeKey.match(/^scene-(\d+)$/i)
        if (sceneMatch) {
          const idx = Number(sceneMatch[1])
          ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [idx]: 'generating' })
          ctx.store().setSceneGenerationStatus(idx, 'generating')
          continue
        }

        const formTyped = parseFormEditorScopeKey(scopeKey)
        if (formTyped) {
          const slotKey = buildFormSlotKey(formTyped.assetIndex, formTyped.formIndex)
          if (formTyped.tab === 'prop') {
            ctx.propFormGenerationStatus.set({
              ...ctx.propFormGenerationStatus.get(),
              [slotKey]: 'generating'
            })
            ctx.store().setPropFormGenerationStatus(slotKey, 'generating')
          } else {
            ctx.characterFormGenerationStatus.set({
              ...ctx.characterFormGenerationStatus.get(),
              [slotKey]: 'generating'
            })
            ctx.store().setCharacterFormGenerationStatus(slotKey, 'generating')
          }
          continue
        }

        const charMatch = scopeKey.match(/^character-(\d+)$/i)
        if (charMatch) {
          const ci = Number(charMatch[1])
          const slotKey = `${ci}-0`
          ctx.characterFormGenerationStatus.set({
            ...ctx.characterFormGenerationStatus.get(),
            [slotKey]: 'generating'
          })
          ctx.store().setCharacterFormGenerationStatus(slotKey, 'generating')
          continue
        }

        const propMatch = scopeKey.match(/^prop-(\d+)$/i)
        if (propMatch) {
          const pi = Number(propMatch[1])
          const slotKey = `${pi}-0`
          ctx.propFormGenerationStatus.set({
            ...ctx.propFormGenerationStatus.get(),
            [slotKey]: 'generating'
          })
          ctx.store().setPropFormGenerationStatus(slotKey, 'generating')
          continue
        }

        // 历史裸键：有 formId 时已在上面 apply；无 formId 时仅写入明确存在的一侧，禁止两侧都亮
        const bare = parseBareFormSlotKey(scopeKey)
        if (bare) {
          const { assetIndex, formIndex } = bare
          const propHas = ctx.propFormIdsByIndex.get()[assetIndex]?.[formIndex] != null
          const charHas = ctx.characterFormIdsByIndex.get()[assetIndex]?.[formIndex] != null
          if (propHas && !charHas) {
            ctx.propFormGenerationStatus.set({
              ...ctx.propFormGenerationStatus.get(),
              [scopeKey]: 'generating'
            })
            ctx.store().setPropFormGenerationStatus(scopeKey, 'generating')
          } else if (charHas && !propHas) {
            ctx.characterFormGenerationStatus.set({
              ...ctx.characterFormGenerationStatus.get(),
              [scopeKey]: 'generating'
            })
            ctx.store().setCharacterFormGenerationStatus(scopeKey, 'generating')
          }
          // 两侧都有同槽：无法判定归属，跳过（避免污染）；依赖 formId 路径或带前缀新键
        }
      }
    }
    ctx.store().refreshStep3VisualGeneratingFlag()
  }

  /**
   * Pinia 持久化了各卡片的 generating；刷新后若任务列表已无对应形态图任务，会与真实状态脱节（无 SSE、任务角标不显示）。
   * 在 reconcile 之后调用：凡本地仍标为 generating、且当前进行中的形态图任务不覆盖其 formId 的 slot，一律回落为 idle。
   */
  function clearStalePersistedGeneratingWithoutOngoingStep3Cover(cover: {
    coverFormIds: Set<number>
    coverAssetIds: Set<number>
    coverImageIds?: Set<number>
  }) {
    const coverFormIds = cover.coverFormIds
    const coverAssetIds = cover.coverAssetIds
    const coverImageIds = cover.coverImageIds ?? new Set<number>()
    if (routePathToCreationStep(ctx.route().path) !== 'scene-character') return
    const noCover = coverFormIds.size === 0 && coverAssetIds.size === 0 && coverImageIds.size === 0
    let changed = false

    for (const [ks, st] of Object.entries(ctx.sceneGenerationStatus.get())) {
      const idx = Number(ks)
      if (!Number.isFinite(idx) || st !== 'generating') continue
      if (sceneIdxHasPersistedModalSseTask(idx)) continue
      const fids = ctx.sceneFormIdsByIndex.get()[idx] ?? []
      if (fids.some((id) => coverFormIds.has(id))) continue
      if (ctx.sceneIndexHasActiveFormImageGeneration(idx)) continue
      if (ctx.sceneSlotHasLoadedImages(idx)) {
        ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [idx]: 'success' })
        ctx.store().setSceneGenerationStatus(idx, 'success')
        changed = true
        continue
      }
      const aid = ctx.sceneAssetIds.get()[idx]
      const coveredByFormTextAsset =
        aid != null && Number.isFinite(Number(aid)) && coverAssetIds.has(Number(aid))
      if (coveredByFormTextAsset) {
        ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [idx]: 'idle' })
        ctx.store().setSceneGenerationStatus(idx, 'idle')
        changed = true
        continue
      }
      if (!fids.length && !noCover) continue
      const covered = fids.length ? fids.some((id) => coverFormIds.has(id)) : false
      if (!covered) {
        ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [idx]: 'idle' })
        ctx.store().setSceneGenerationStatus(idx, 'idle')
        changed = true
      }
    }

    for (const [key, st] of Object.entries(ctx.characterFormGenerationStatus.get())) {
      if (st !== 'generating') continue
      if (key.startsWith('pending-')) {
        const aid = Number(key.slice('pending-'.length))
        if (Number.isFinite(aid) && coverAssetIds.has(aid)) {
          ctx.characterFormGenerationStatus.set({
            ...ctx.characterFormGenerationStatus.get(),
            [key]: 'idle'
          })
          ctx.store().setCharacterFormGenerationStatus(key, 'idle')
          changed = true
          continue
        }
        if (!noCover) continue
      }
      const slotImageIds = ctx.collectRpsImageIdsForCharacterSlotKey(key)
      if (slotImageIds.some((id) => coverImageIds.has(id))) {
        continue
      }
      if (ctx.isCharacterSlotSettingCardGenerating(key)) {
        continue
      }
      const charParts = key.split('-')
      const charCi = Number(charParts[0])
      const charFi = Number(charParts[1])
      const fidForCover =
        Number.isFinite(charCi) && Number.isFinite(charFi)
          ? ctx.characterFormIdsByIndex.get()[charCi]?.[charFi]
          : undefined
      if (fidForCover != null && coverFormIds.has(fidForCover)) continue
      if (ctx.characterSlotHasActiveFormImageGeneration(key)) continue
      if (ctx.characterFormSlotHasLoadedImages(key)) {
        ctx.characterFormGenerationStatus.set({
          ...ctx.characterFormGenerationStatus.get(),
          [key]: 'success'
        })
        ctx.store().setCharacterFormGenerationStatus(key, 'success')
        changed = true
        continue
      }
      if (Number.isFinite(charCi) && characterCardHasPersistedModalSseTask(charCi)) continue
      if (/^\d+-\d+$/.test(key) && characterSlotHasPersistedModalSseTask(key)) continue
      if (!Number.isFinite(charCi) || !Number.isFinite(charFi)) continue
      const charAssetId = ctx.characterAssetIds.get()[charCi]
      if (
        charAssetId != null &&
        Number.isFinite(Number(charAssetId)) &&
        coverAssetIds.has(Number(charAssetId))
      ) {
        ctx.characterFormGenerationStatus.set({
          ...ctx.characterFormGenerationStatus.get(),
          [key]: 'idle'
        })
        ctx.store().setCharacterFormGenerationStatus(key, 'idle')
        changed = true
        continue
      }
      const fid = ctx.characterFormIdsByIndex.get()[charCi]?.[charFi]
      if (fid == null || !Number.isFinite(fid)) {
        if (!noCover) continue
      } else if (coverFormIds.has(fid)) {
        continue
      }
      ctx.characterFormGenerationStatus.set({ ...ctx.characterFormGenerationStatus.get(), [key]: 'idle' })
      ctx.store().setCharacterFormGenerationStatus(key, 'idle')
      changed = true
    }

    for (const [key, st] of Object.entries(ctx.propFormGenerationStatus.get())) {
      if (st !== 'generating') continue
      if (key.startsWith('pending-')) {
        const aid = Number(key.slice('pending-'.length))
        if (Number.isFinite(aid) && coverAssetIds.has(aid)) {
          ctx.propFormGenerationStatus.set({ ...ctx.propFormGenerationStatus.get(), [key]: 'idle' })
          ctx.store().setPropFormGenerationStatus(key, 'idle')
          changed = true
          continue
        }
        if (!noCover) continue
      }
      const propParts = key.split('-')
      const propPi = Number(propParts[0])
      const propFi = Number(propParts[1])
      const propFidForCover =
        Number.isFinite(propPi) && Number.isFinite(propFi)
          ? ctx.propFormIdsByIndex.get()[propPi]?.[propFi]
          : undefined
      if (propFidForCover != null && coverFormIds.has(propFidForCover)) continue
      if (ctx.propSlotHasActiveFormImageGeneration(key)) continue
      if (ctx.propFormSlotHasLoadedImages(key)) {
        ctx.propFormGenerationStatus.set({ ...ctx.propFormGenerationStatus.get(), [key]: 'success' })
        ctx.store().setPropFormGenerationStatus(key, 'success')
        changed = true
        continue
      }
      if (Number.isFinite(propPi) && propCardHasPersistedModalSseTask(propPi)) continue
      if (/^\d+-\d+$/.test(key) && propSlotHasPersistedModalSseTask(key)) continue
      if (!Number.isFinite(propPi) || !Number.isFinite(propFi)) continue
      const propAssetId = ctx.propAssetIds.get()[propPi]
      if (
        propAssetId != null &&
        Number.isFinite(Number(propAssetId)) &&
        coverAssetIds.has(Number(propAssetId))
      ) {
        ctx.propFormGenerationStatus.set({ ...ctx.propFormGenerationStatus.get(), [key]: 'idle' })
        ctx.store().setPropFormGenerationStatus(key, 'idle')
        changed = true
        continue
      }
      const fid = ctx.propFormIdsByIndex.get()[propPi]?.[propFi]
      if (fid == null || !Number.isFinite(fid)) {
        if (!noCover) continue
      } else if (coverFormIds.has(fid)) {
        continue
      }
      ctx.propFormGenerationStatus.set({ ...ctx.propFormGenerationStatus.get(), [key]: 'idle' })
      ctx.store().setPropFormGenerationStatus(key, 'idle')
      changed = true
    }

    if (
      changed &&
      !Object.values(ctx.sceneGenerationStatus.get()).some((s) => s === 'generating') &&
      !Object.values(ctx.characterFormGenerationStatus.get()).some((s) => s === 'generating') &&
      !Object.values(ctx.propFormGenerationStatus.get()).some((s) => s === 'generating') &&
      !Object.values(ctx.pendingFormGenBusy.get()).some(Boolean) &&
      !ctx.isSettingCardBatchBusy()
    ) {
      ctx.store().refreshStep3VisualGeneratingFlag()
    }
    if (changed) ctx.notifyGlobalGenerateTaskListUpdated()
  }

  /** @deprecated 兼容旧调用 */
  function clearStalePersistedGeneratingWithoutOngoingFormImageCover(coverFormIds: Set<number>) {
    clearStalePersistedGeneratingWithoutOngoingStep3Cover({
      coverFormIds,
      coverAssetIds: new Set(),
      coverImageIds: new Set()
    })
  }

  return {
    hasAnyPersistedModalSseTasks,
    purgeNonOngoingModalSseTasksFromStore,
    sceneIdxHasPersistedModalSseTask,
    characterCardHasPersistedModalSseTask,
    characterSlotHasPersistedModalSseTask,
    propCardHasPersistedModalSseTask,
    propSlotHasPersistedModalSseTask,
    syncGeneratingFromPersistedModalSseTasks,
    clearStalePersistedGeneratingWithoutOngoingStep3Cover,
    clearStalePersistedGeneratingWithoutOngoingFormImageCover
  }
}

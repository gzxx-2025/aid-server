'use client'

import { findAlignedFormIndexByFormId } from '~/utils/rpsFormIdsAlign'
import type { createScpGenerationStatusCore } from './scpGenerationStatusCore'
import type { FormGenStatus, ScpCtx, TabKey } from './types'

type GenerationStatusCore = ReturnType<typeof createScpGenerationStatusCore>

export function createScpGenerationStatusActions(ctx: ScpCtx, core: GenerationStatusCore) {
  function markStep3SlotSuccessByFormId(formId: number): boolean {
    for (const [key, ids] of Object.entries(ctx.propFormIdsByIndex.get())) {
      const propIndex = Number(key)
      if (!Number.isFinite(propIndex)) continue
      const formIndex = findAlignedFormIndexByFormId(ids, formId)
      if (formIndex < 0) continue
      const slotKey = `${propIndex}-${formIndex}`
      ctx.propFormGenerationStatus.set({
        ...ctx.propFormGenerationStatus.get(),
        [slotKey]: 'success'
      })
      ctx.store().setPropFormGenerationStatus(slotKey, 'success')
      return true
    }
    for (const [key, ids] of Object.entries(ctx.characterFormIdsByIndex.get())) {
      const characterIndex = Number(key)
      if (!Number.isFinite(characterIndex)) continue
      const formIndex = findAlignedFormIndexByFormId(ids, formId)
      if (formIndex < 0) continue
      const slotKey = `${characterIndex}-${formIndex}`
      ctx.characterFormGenerationStatus.set({
        ...ctx.characterFormGenerationStatus.get(),
        [slotKey]: 'success'
      })
      ctx.store().setCharacterFormGenerationStatus(slotKey, 'success')
      return true
    }
    for (const [key, ids] of Object.entries(ctx.sceneFormIdsByIndex.get())) {
      const sceneIndex = Number(key)
      if (!Number.isFinite(sceneIndex)) continue
      if (findAlignedFormIndexByFormId(ids, formId) < 0) continue
      ctx.sceneGenerationStatus.set({
        ...ctx.sceneGenerationStatus.get(),
        [sceneIndex]: 'success'
      })
      ctx.store().setSceneGenerationStatus(sceneIndex, 'success')
      return true
    }
    return false
  }

  function applyFormIdToStep3GeneratingSlots(formId: number): boolean {
    const normalizedFormId = Number(formId)
    return Number.isFinite(normalizedFormId) && normalizedFormId > 0
      ? core.setStep3GeneratingSlotForFormId(normalizedFormId)
      : false
  }

  function clearStep3ImageGeneratingSlotsForFormTextAssetId(assetId: number) {
    const normalizedAssetId = Number(assetId)
    if (!Number.isFinite(normalizedAssetId) || normalizedAssetId <= 0) return
    let changed = false
    const pendingKey = `pending-${normalizedAssetId}`
    const sceneIndex = ctx.findSceneIndexByAssetId(normalizedAssetId)
    if (sceneIndex >= 0 && ctx.sceneGenerationStatus.get()[sceneIndex] === 'generating') {
      ctx.sceneGenerationStatus.set({
        ...ctx.sceneGenerationStatus.get(),
        [sceneIndex]: 'idle'
      })
      ctx.store().setSceneGenerationStatus(sceneIndex, 'idle')
      changed = true
    }

    if (ctx.characterFormGenerationStatus.get()[pendingKey] === 'generating') {
      ctx.characterFormGenerationStatus.set({
        ...ctx.characterFormGenerationStatus.get(),
        [pendingKey]: 'idle'
      })
      ctx.store().setCharacterFormGenerationStatus(pendingKey, 'idle')
      changed = true
    }
    if (ctx.propFormGenerationStatus.get()[pendingKey] === 'generating') {
      ctx.propFormGenerationStatus.set({
        ...ctx.propFormGenerationStatus.get(),
        [pendingKey]: 'idle'
      })
      ctx.store().setPropFormGenerationStatus(pendingKey, 'idle')
      changed = true
    }

    const characterIndex = ctx.findCharacterIndexByAssetId(normalizedAssetId)
    if (characterIndex >= 0) {
      const formCount = ctx.characterForms.get()[characterIndex]?.length ?? 1
      for (let formIndex = 0; formIndex < formCount; formIndex++) {
        const key = `${characterIndex}-${formIndex}`
        if (ctx.characterFormGenerationStatus.get()[key] !== 'generating') continue
        ctx.characterFormGenerationStatus.set({
          ...ctx.characterFormGenerationStatus.get(),
          [key]: 'idle'
        })
        ctx.store().setCharacterFormGenerationStatus(key, 'idle')
        changed = true
      }
    }

    const propIndex = ctx.findPropIndexByAssetId(normalizedAssetId)
    if (propIndex >= 0) {
      const formCount = ctx.propForms.get()[propIndex]?.length ?? 1
      for (let formIndex = 0; formIndex < formCount; formIndex++) {
        const key = `${propIndex}-${formIndex}`
        if (ctx.propFormGenerationStatus.get()[key] !== 'generating') continue
        ctx.propFormGenerationStatus.set({
          ...ctx.propFormGenerationStatus.get(),
          [key]: 'idle'
        })
        ctx.store().setPropFormGenerationStatus(key, 'idle')
        changed = true
      }
    }
    if (changed) ctx.store().refreshStep3VisualGeneratingFlag()
  }

  function applyAssetIdToPendingFormTextGeneratingBusy(assetId: number): boolean {
    const normalizedAssetId = Number(assetId)
    if (!Number.isFinite(normalizedAssetId) || normalizedAssetId <= 0) return false
    ctx.pendingFormGenBusy.set({
      ...ctx.pendingFormGenBusy.get(),
      [normalizedAssetId]: true
    })
    clearStep3ImageGeneratingSlotsForFormTextAssetId(normalizedAssetId)
    return true
  }

  function clearPendingFormGenBusyForAssetIds(assetIds: number[]) {
    if (!assetIds.length) return
    const next = { ...ctx.pendingFormGenBusy.get() }
    let changed = false
    for (const assetId of assetIds) {
      if (!next[assetId]) continue
      delete next[assetId]
      changed = true
    }
    if (changed) ctx.pendingFormGenBusy.set(next)
  }

  function resetPendingFormGenerateSlotsForAssetIds(assetIds: number[], _tab: TabKey) {
    clearPendingFormGenBusyForAssetIds(assetIds)
    for (const assetId of assetIds) clearStep3ImageGeneratingSlotsForFormTextAssetId(assetId)
  }

  function hasOngoingStep3VisualWork(): boolean {
    if (ctx.store().isExtractingAssets || ctx.hasActiveTrackedTasks()) return true
    if (Object.values(ctx.pendingFormGenBusy.get()).some(Boolean)) return true
    if (ctx.isSettingCardBatchBusy() || ctx.hasAnyPersistedModalSseTasks()) return true
    if (core.hasPersistedStep3GeneratingWork()) return true
    if (
      core.tabHasStep3FormImageGenerating('scene') ||
      core.tabHasStep3FormImageGenerating('character') ||
      core.tabHasStep3FormImageGenerating('prop') ||
      ctx.hasAnyStep3TabTaskProgress()
    ) {
      return true
    }
    if (!ctx.step3AssetBootstrapReady.get()) return false
    if (ctx.store().isGeneratingStep3Visual) return true
    const progress = ctx.store().extractingTaskProgress
    if (String(progress?.stepTitle || '').trim() || String(progress?.message || '').trim()) {
      return true
    }
    if (typeof progress?.percent === 'number' && progress.percent > 0) return true
    const hasGenerating = (statuses: Record<string | number, FormGenStatus>) =>
      Object.values(statuses).some((status) => status === 'generating')
    return (
      hasGenerating(ctx.sceneGenerationStatus.get()) ||
      hasGenerating(ctx.characterFormGenerationStatus.get()) ||
      hasGenerating(ctx.propFormGenerationStatus.get())
    )
  }

  return {
    markStep3SlotSuccessByFormId,
    applyFormIdToStep3GeneratingSlots,
    applyAssetIdToPendingFormTextGeneratingBusy,
    clearStep3ImageGeneratingSlotsForFormTextAssetId,
    clearPendingFormGenBusyForAssetIds,
    resetPendingFormGenerateSlotsForAssetIds,
    hasOngoingStep3VisualWork
  }
}

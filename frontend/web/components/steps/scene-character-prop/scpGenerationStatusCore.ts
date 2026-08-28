'use client'

import { userTaskDetailCached } from '~/utils/businessApi'
import { findAlignedFormIndexByFormId } from '~/utils/rpsFormIdsAlign'
import { shouldPreserveStep3GeneratingOnAssetListReconcile } from '~/utils/step3AssetListReconcileGate'
import {
clearStep3FormImageTaskRegistry,
getActiveStep3FormImageFormIds,
getStep3FormImageTaskMeta,
hasOngoingStep3FormImageTasks,
hasOngoingStep3FormImageTasksForTab,
isFormIdUnderActiveStep3FormImageTask,
registerStep3FormImageTask
} from '~/utils/step3FormImageTaskRegistry'
import { isStep3FlowStepGenerating } from '~/utils/step3LiveGenRestore'
import {
isFormImageUserTaskType,
parseFormIdFromInputSnapshotRecord,
parseFormIdsFromBatchInputSnapshot
} from './scpTaskUtils'
import type { FormGenStatus,ScpCtx,TabKey } from './types'

export function createScpGenerationStatusCore(ctx: ScpCtx) {
  function purgeStaleStep3FormImageGeneratingMarks(coverFormIds: Set<number>) {
    const activeIds = [...getActiveStep3FormImageFormIds()]
    if (!activeIds.length) return
    const staleFormIds = activeIds.filter((fid) => !coverFormIds.has(fid))
    if (!staleFormIds.length) return
    for (const fid of staleFormIds) {
      if (isFormIdUnderActiveStep3FormImageTask(fid)) continue
      resolveFormIdGeneratingSlotAfterCancel(fid)
    }
  }

  function clearStep3ActiveFormImageGeneratingIds() {
    clearStep3FormImageTaskRegistry()
  }

  function isFormIdUnderActiveFormImageGeneration(formId: number): boolean {
    return isFormIdUnderActiveStep3FormImageTask(formId)
  }

  /** 列表 reconcile：有图/无图时是否仍展示 generating（跨集返回 registry 未就绪时保活） */
  function resolveAssetListReconcileGenStatus(input: {
    hasImage: boolean
    underFormImageGen: boolean
    previousStatus: FormGenStatus | undefined
  }): FormGenStatus {
    const preserve = shouldPreserveStep3GeneratingOnAssetListReconcile({
      underActiveFormImageGen: input.underFormImageGen,
      previousStatus: input.previousStatus,
      assetBootstrapReady: ctx.step3AssetBootstrapReady.get(),
      flowStepGenerating: isStep3FlowStepGenerating(ctx.store(), ctx.route())
    })
    if (input.hasImage) {
      return preserve ? 'generating' : 'success'
    }
    if (preserve) return 'generating'
    if (input.previousStatus === 'failed') return 'failed'
    return 'idle'
  }

  function clearStep3ExtractingTaskProgressIfIdle() {
    if (hasOngoingStep3FormImageTasks()) return
    if (ctx.props().isExtracting || ctx.store().isExtractingAssets) return
    ctx.store().clearExtractingTaskProgress()
  }

  async function ensureStep3FormImageTaskRegistered(payload: {
    taskId: number
    tab: TabKey
    taskType?: string | null
    formIds?: number[]
  }) {
    const taskId = Number(payload.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) return
    if (getStep3FormImageTaskMeta(taskId)) return
    let formIds = (payload.formIds ?? []).map(Number).filter((n) => Number.isFinite(n) && n > 0)
    if (!formIds.length) {
      try {
        const detail = await userTaskDetailCached(taskId)
        if (detail) formIds = collectFormIdsFromFormImageTaskDetail(detail)
      } catch {
        /* ignore */
      }
    }
    registerStep3FormImageTask({
      taskId,
      tab: payload.tab,
      formIds,
      taskType: payload.taskType ?? null
    })
  }

  function resolveFormIdForCharacterSlotKey(slotKey: string): number | null {
    const parts = slotKey.split('-')
    const ci = Number(parts[0])
    const fi = Number(parts[1])
    if (!Number.isFinite(ci) || !Number.isFinite(fi)) return null
    const fid = ctx.characterFormIdsByIndex.get()[ci]?.[fi]
    const n = Number(fid)
    return Number.isFinite(n) && n > 0 ? n : null
  }

  function resolveFormIdForPropSlotKey(slotKey: string): number | null {
    const parts = slotKey.split('-')
    const pi = Number(parts[0])
    const fi = Number(parts[1])
    if (!Number.isFinite(pi) || !Number.isFinite(fi)) return null
    const fid = ctx.propFormIdsByIndex.get()[pi]?.[fi]
    const n = Number(fid)
    return Number.isFinite(n) && n > 0 ? n : null
  }

  function characterSlotHasActiveFormImageGeneration(slotKey: string): boolean {
    const fid = resolveFormIdForCharacterSlotKey(slotKey)
    return fid != null && isFormIdUnderActiveFormImageGeneration(fid)
  }

  function propSlotHasActiveFormImageGeneration(slotKey: string): boolean {
    const fid = resolveFormIdForPropSlotKey(slotKey)
    return fid != null && isFormIdUnderActiveFormImageGeneration(fid)
  }

  function sceneIndexHasActiveFormImageGeneration(sceneIndex: number): boolean {
    const fids = ctx.sceneFormIdsByIndex.get()[sceneIndex] ?? []
    return fids.some((fid) => isFormIdUnderActiveFormImageGeneration(Number(fid)))
  }

  function collectFormIdsFromFormImageTaskDetail(detail: {
    inputSnapshot?: string | null
    taskType?: string | null
  }): number[] {
    if (!isFormImageUserTaskType(detail.taskType)) return []
    const multi = parseFormIdsFromBatchInputSnapshot(detail)
    if (multi.length > 0) return multi
    const single = parseFormIdFromInputSnapshotRecord(detail)
    return single != null ? [single] : []
  }

  function forceSettleTabGeneratingSlots(tab: TabKey, options?: { force?: boolean }) {
    const force = options?.force === true
    if (tab === 'scene') {
      for (const [k, st] of Object.entries(ctx.sceneGenerationStatus.get())) {
        const idx = Number(k)
        if (!Number.isFinite(idx) || st !== 'generating') continue
        if (!force && sceneIndexHasActiveFormImageGeneration(idx)) continue
        const next: FormGenStatus = sceneSlotHasLoadedImages(idx) ? 'success' : 'idle'
        ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [idx]: next })
        ctx.store().setSceneGenerationStatus(idx, next)
      }
    } else if (tab === 'character') {
      for (const [key, st] of Object.entries(ctx.characterFormGenerationStatus.get())) {
        if (st !== 'generating') continue
        if (ctx.isCharacterSlotSettingCardGenerating(key)) continue
        if (!force && characterSlotHasActiveFormImageGeneration(key)) continue
        const next: FormGenStatus = characterFormSlotHasLoadedImages(key) ? 'success' : 'idle'
        ctx.characterFormGenerationStatus.set({
          ...ctx.characterFormGenerationStatus.get(),
          [key]: next
        })
        ctx.store().setCharacterFormGenerationStatus(key, next)
      }
    } else {
      for (const [key, st] of Object.entries(ctx.propFormGenerationStatus.get())) {
        if (st !== 'generating') continue
        if (!force && propSlotHasActiveFormImageGeneration(key)) continue
        const next: FormGenStatus = propFormSlotHasLoadedImages(key) ? 'success' : 'idle'
        ctx.propFormGenerationStatus.set({ ...ctx.propFormGenerationStatus.get(), [key]: next })
        ctx.store().setPropFormGenerationStatus(key, next)
      }
    }
  }

  function tabHasStep3FormImageGenerating(tab: TabKey): boolean {
    return hasOngoingStep3FormImageTasksForTab(tab)
  }

  /** Pinia / 本地 generating 在 bootstrap 完成前即可用于判断其它 Tab 是否有进行中任务 */
  function hasPersistedStep3GeneratingWork(): boolean {
    if (ctx.store().isGeneratingStep3Visual) return true
    const maps = [
      ctx.store().sceneGenerationStatus,
      ctx.store().characterFormGenerationStatus,
      ctx.store().propFormGenerationStatus,
      ctx.sceneGenerationStatus.get(),
      ctx.characterFormGenerationStatus.get(),
      ctx.propFormGenerationStatus.get()
    ]
    return maps.some((m) => Object.values(m).some((s) => s === 'generating'))
  }

  function resolveFormIdGeneratingSlotAfterCancel(formId: number, nextStatus?: FormGenStatus) {
    const fid = Number(formId)
    if (!Number.isFinite(fid) || fid <= 0) return
    for (const [k, ids] of Object.entries(ctx.propFormIdsByIndex.get())) {
      const pi = Number(k)
      if (!Number.isFinite(pi)) continue
      const fi = findAlignedFormIndexByFormId(ids, fid)
      if (fi < 0) continue
      const slotKey = `${pi}-${fi}`
      const next: FormGenStatus =
        nextStatus ?? (propFormSlotHasLoadedImages(slotKey) ? 'success' : 'idle')
      ctx.propFormGenerationStatus.set({ ...ctx.propFormGenerationStatus.get(), [slotKey]: next })
      ctx.store().setPropFormGenerationStatus(slotKey, next)
      return
    }
    for (const [k, ids] of Object.entries(ctx.characterFormIdsByIndex.get())) {
      const ci = Number(k)
      if (!Number.isFinite(ci)) continue
      const fi = findAlignedFormIndexByFormId(ids, fid)
      if (fi < 0) continue
      const slotKey = `${ci}-${fi}`
      const next: FormGenStatus =
        nextStatus ?? (characterFormSlotHasLoadedImages(slotKey) ? 'success' : 'idle')
      ctx.characterFormGenerationStatus.set({
        ...ctx.characterFormGenerationStatus.get(),
        [slotKey]: next
      })
      ctx.store().setCharacterFormGenerationStatus(slotKey, next)
      return
    }
    for (const [k, ids] of Object.entries(ctx.sceneFormIdsByIndex.get())) {
      const si = Number(k)
      if (!Number.isFinite(si)) continue
      if (findAlignedFormIndexByFormId(ids, fid) < 0) continue
      const next: FormGenStatus =
        nextStatus ?? (sceneSlotHasLoadedImages(si) ? 'success' : 'idle')
      ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [si]: next })
      ctx.store().setSceneGenerationStatus(si, next)
      return
    }
  }

  function setStep3GeneratingSlotForFormId(formId: number): boolean {
    const fid = Number(formId)
    if (!Number.isFinite(fid) || fid <= 0) return false
    for (const [k, ids] of Object.entries(ctx.propFormIdsByIndex.get())) {
      const pi = Number(k)
      if (!Number.isFinite(pi)) continue
      const fi = findAlignedFormIndexByFormId(ids, fid)
      if (fi >= 0) {
        const slotKey = `${pi}-${fi}`
        ctx.propFormGenerationStatus.set({
          ...ctx.propFormGenerationStatus.get(),
          [slotKey]: 'generating'
        })
        ctx.store().setPropFormGenerationStatus(slotKey, 'generating')
        return true
      }
    }
    for (const [k, ids] of Object.entries(ctx.characterFormIdsByIndex.get())) {
      const ci = Number(k)
      if (!Number.isFinite(ci)) continue
      const fi = findAlignedFormIndexByFormId(ids, fid)
      if (fi >= 0) {
        const slotKey = `${ci}-${fi}`
        ctx.characterFormGenerationStatus.set({
          ...ctx.characterFormGenerationStatus.get(),
          [slotKey]: 'generating'
        })
        ctx.store().setCharacterFormGenerationStatus(slotKey, 'generating')
        return true
      }
    }
    for (const [k, ids] of Object.entries(ctx.sceneFormIdsByIndex.get())) {
      const si = Number(k)
      if (!Number.isFinite(si)) continue
      if (findAlignedFormIndexByFormId(ids, fid) >= 0) {
        ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [si]: 'generating' })
        ctx.store().setSceneGenerationStatus(si, 'generating')
        return true
      }
    }
    return false
  }

  /** 列表拉取后：按进行中 formId 恢复卡片 generating（已有主图时也保留 loading） */
  function reapplyFormImageGeneratingSlotsFromActiveIds(tab?: TabKey) {
    if (!getActiveStep3FormImageFormIds().size) return
    let changed = false
    for (const fid of getActiveStep3FormImageFormIds()) {
      if (tab != null) {
        const slotTab = ctx.resolveTabKeyForFormId(fid)
        if (slotTab != null && slotTab !== tab) continue
      }
      if (setStep3GeneratingSlotForFormId(fid)) changed = true
    }
    if (changed) ctx.store().refreshStep3VisualGeneratingFlag()
  }

  /** 异步生图/SSE 返回前若已切换作品，禁止写 Pinia 或 toast（避免串状态） */
  function captureStep3RouteContext() {
    return {
      scopeKey: ctx.store().step3GenVisualScopeKey(),
      projectId: ctx.store().currentProjectId,
      episodeId: ctx.store().currentEpisodeId
    }
  }

  function matchesStep3RouteContext(routeCtx: ReturnType<typeof captureStep3RouteContext>) {
    return (
      ctx.store().step3GenVisualScopeKey() === routeCtx.scopeKey &&
      ctx.store().currentProjectId === routeCtx.projectId &&
      ctx.store().currentEpisodeId === routeCtx.episodeId
    )
  }

  function patchSceneGenStatus(
    index: number,
    status: FormGenStatus,
    routeCtx: ReturnType<typeof captureStep3RouteContext>
  ) {
    ctx.store().setSceneGenerationStatus(index, status)
    if (matchesStep3RouteContext(routeCtx)) {
      ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [index]: status })
    }
  }

  function patchCharacterFormGenStatus(
    formKey: string,
    status: FormGenStatus,
    routeCtx: ReturnType<typeof captureStep3RouteContext>
  ) {
    ctx.store().setCharacterFormGenerationStatus(formKey, status)
    if (matchesStep3RouteContext(routeCtx)) {
      ctx.characterFormGenerationStatus.set({
        ...ctx.characterFormGenerationStatus.get(),
        [formKey]: status
      })
    }
  }

  function patchPropFormGenStatus(
    formKey: string,
    status: FormGenStatus,
    routeCtx: ReturnType<typeof captureStep3RouteContext>
  ) {
    ctx.store().setPropFormGenerationStatus(formKey, status)
    if (matchesStep3RouteContext(routeCtx)) {
      ctx.propFormGenerationStatus.set({ ...ctx.propFormGenerationStatus.get(), [formKey]: status })
    }
  }

  function resolveAllStep3GeneratingStatusesIfNoOngoingTasks(target: FormGenStatus) {
    if (hasOngoingStep3FormImageTasks()) return
    ctx.store().resolveAllStep3GeneratingStatuses(target)
  }

  function resolveAllLocalStep3GeneratingTo(status: FormGenStatus) {
    const routeCtx = captureStep3RouteContext()
    for (const [k, st] of Object.entries(ctx.sceneGenerationStatus.get())) {
      if (st === 'generating') patchSceneGenStatus(Number(k), status, routeCtx)
    }
    for (const [key, st] of Object.entries(ctx.characterFormGenerationStatus.get())) {
      if (st === 'generating') patchCharacterFormGenStatus(key, status, routeCtx)
    }
    for (const [key, st] of Object.entries(ctx.propFormGenerationStatus.get())) {
      if (st === 'generating') patchPropFormGenStatus(key, status, routeCtx)
    }
    ctx.store().resolveAllStep3GeneratingStatuses(status)
  }

  function sceneSlotHasLoadedImages(sceneIndex: number): boolean {
    return (ctx.sceneImages.get()[sceneIndex]?.length ?? 0) > 0
  }

  function characterFormSlotHasLoadedImages(formKey: string): boolean {
    return (ctx.characterFormImages.get()[formKey]?.length ?? 0) > 0
  }

  function propFormSlotHasLoadedImages(formKey: string): boolean {
    return (ctx.propFormImages.get()[formKey]?.length ?? 0) > 0
  }

  /** 任务已产出图片但 reconcile/离页恢复仍标 generating 时，回落为 success 以展示图片；
   * 任务已结束且无图时回落 idle，避免 SSE complete（含全失败）后卡片一直转圈。
   * 提交后、registry 尚未挂上 formId 的窗口内：保留 generating，避免「其他形态」编辑按钮被提前放开。 */
  function reconcileStep3GeneratingWithLoadedImages() {
    let changed = false
    const flowStepGenerating = isStep3FlowStepGenerating(ctx.store(), ctx.route())
    for (const [k, st] of Object.entries(ctx.sceneGenerationStatus.get())) {
      const idx = Number(k)
      if (!Number.isFinite(idx) || st !== 'generating') continue
      if (sceneIndexHasActiveFormImageGeneration(idx)) continue
      const preserve = shouldPreserveStep3GeneratingOnAssetListReconcile({
        underActiveFormImageGen: false,
        previousStatus: st,
        assetBootstrapReady: ctx.step3AssetBootstrapReady.get(),
        flowStepGenerating
      })
      if (preserve) continue
      const next: FormGenStatus = sceneSlotHasLoadedImages(idx) ? 'success' : 'idle'
      ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [idx]: next })
      ctx.store().setSceneGenerationStatus(idx, next)
      changed = true
    }
    for (const [key, st] of Object.entries(ctx.characterFormGenerationStatus.get())) {
      if (st !== 'generating') continue
      if (ctx.isCharacterSlotSettingCardGenerating(key)) continue
      if (characterSlotHasActiveFormImageGeneration(key)) continue
      const preserve = shouldPreserveStep3GeneratingOnAssetListReconcile({
        underActiveFormImageGen: false,
        previousStatus: st,
        assetBootstrapReady: ctx.step3AssetBootstrapReady.get(),
        flowStepGenerating
      })
      if (preserve) continue
      const next: FormGenStatus = characterFormSlotHasLoadedImages(key) ? 'success' : 'idle'
      ctx.characterFormGenerationStatus.set({
        ...ctx.characterFormGenerationStatus.get(),
        [key]: next
      })
      ctx.store().setCharacterFormGenerationStatus(key, next)
      changed = true
    }
    for (const [key, st] of Object.entries(ctx.propFormGenerationStatus.get())) {
      if (st !== 'generating') continue
      if (propSlotHasActiveFormImageGeneration(key)) continue
      const preserve = shouldPreserveStep3GeneratingOnAssetListReconcile({
        underActiveFormImageGen: false,
        previousStatus: st,
        assetBootstrapReady: ctx.step3AssetBootstrapReady.get(),
        flowStepGenerating
      })
      if (preserve) continue
      const next: FormGenStatus = propFormSlotHasLoadedImages(key) ? 'success' : 'idle'
      ctx.propFormGenerationStatus.set({ ...ctx.propFormGenerationStatus.get(), [key]: next })
      ctx.store().setPropFormGenerationStatus(key, next)
      changed = true
    }
    if (changed) ctx.store().refreshStep3VisualGeneratingFlag()
  }

  return {
    captureStep3RouteContext,
    characterFormSlotHasLoadedImages,
    characterSlotHasActiveFormImageGeneration,
    clearStep3ActiveFormImageGeneratingIds,
    clearStep3ExtractingTaskProgressIfIdle,
    collectFormIdsFromFormImageTaskDetail,
    ensureStep3FormImageTaskRegistered,
    forceSettleTabGeneratingSlots,
    hasPersistedStep3GeneratingWork,
    isFormIdUnderActiveFormImageGeneration,
    matchesStep3RouteContext,
    patchCharacterFormGenStatus,
    patchPropFormGenStatus,
    patchSceneGenStatus,
    propFormSlotHasLoadedImages,
    propSlotHasActiveFormImageGeneration,
    purgeStaleStep3FormImageGeneratingMarks,
    reapplyFormImageGeneratingSlotsFromActiveIds,
    reconcileStep3GeneratingWithLoadedImages,
    resolveAllLocalStep3GeneratingTo,
    resolveAllStep3GeneratingStatusesIfNoOngoingTasks,
    resolveAssetListReconcileGenStatus,
    resolveFormIdForCharacterSlotKey,
    resolveFormIdForPropSlotKey,
    resolveFormIdGeneratingSlotAfterCancel,
    sceneIndexHasActiveFormImageGeneration,
    sceneSlotHasLoadedImages,
    setStep3GeneratingSlotForFormId,
    tabHasStep3FormImageGenerating,
  }
}

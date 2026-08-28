import { useCreationStore } from '~/stores/creation'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { matchesCreationLiveGenScope, type CreationLiveGenScopeCtx } from '~/composables/useCreationLiveGenScopeGuard'
import { resolveCurrentStep4LiveGenScopeBlobs } from '~/composables/useCreationStoreHydration'
import {
  buildVideoBatchScopePreserveOnContextSwitch,
  shouldTrustPersistedTaskIdOnListMiss
} from '~/utils/storyboardImageBatchRestoreGate'
import { finalizeStoryboardVideoChainFailure } from '~/utils/storyboardVideoChainFailure'
import type { StoryboardPanel, StoryboardVideoPanel } from '~/types'
import {
  applyStoryboardVideoImmediatePanelLoadingRestore,
  applyStoryboardVideoPanelUiFromStore,
  clearVideoBatchTargetIdsSession,
  collectStoryboardVideoPairs,
  getActiveVideoBatchTargetIds,
  parseVideoBatchTaskId,
  writeVideoBatchTargetIdsSession,
  type StoryboardVideoBatchState,
  type StoryboardVideoPair
} from '~/utils/storyboardVideoBatchShared'

type CreationStore = ReturnType<typeof useCreationStore.getState>
type RouteSnapshot = ReturnType<typeof import('~/composables/useRouteLike').getRouteLikeSnapshot>

export function createStoryboardVideoBatchPanelState(opts: {
  state: StoryboardVideoBatchState
  getStore: () => CreationStore
  getRoute: () => RouteSnapshot
  closePromptStream: () => void
  invalidateProjectTaskListCache: () => void
}) {
  const { state, getStore, getRoute, closePromptStream, invalidateProjectTaskListCache } = opts
  const markStoryboardVideoPanelFailed = (storyboardId: number, errorMessage: string) => {
    const id = Number(storyboardId)
    if (!Number.isFinite(id) || id <= 0) return
    getStore().clearStoryboardPanelVideoGenStatus(id)
    getStore().setStoryboardPanelVideoGenStatus(id, 'failed')
    getStore().setStoryboardPanelVideoGenError(id, errorMessage || '视频生成失败')
  }
  const markStoryboardVideoPanelSucceeded = (storyboardId: number) => {
    const id = Number(storyboardId)
    if (!Number.isFinite(id) || id <= 0) return
    getStore().clearStoryboardPanelVideoGenStatus(id)
    getStore().clearStoryboardPanelVideoGenError(id)
  }
  const collectPairs = (scripts: StoryboardPanel[], videos: StoryboardVideoPanel[]) =>
    collectStoryboardVideoPairs(scripts, videos)
  const getActiveBatchTargetIds = () => getActiveVideoBatchTargetIds(getStore(), getRoute())
  const setVideoBatchTargetIds = (storyboardIds: number[]) => {
    getStore().setStoryboardVideoBatchTargetStoryboardIds(storyboardIds)
    writeVideoBatchTargetIdsSession(getStore(), storyboardIds)
  }
  const clearVideoBatchTargetIds = () => {
    clearVideoBatchTargetIdsSession(getStore())
    getStore().clearStoryboardVideoBatchTargetStoryboardIds()
  }
  const stopVideoBatchGeneration = () => {
    clearVideoBatchTargetIdsSession(getStore())
    getStore().finalizeStoryboardVideoBatchGeneration()
  }
  const markPanelsGenerating = (storyboardIds: number[]) => {
    for (const id of storyboardIds) {
      if (getStore().storyboardPanelVideoGenStatusByStoryboardId[String(id)] !== 'generating') {
        getStore().setStoryboardPanelVideoGenStatus(id, 'generating')
      }
    }
  }
  const finishVideoBatchUi = (_storyboardIds: number[]) => {
    clearVideoBatchTargetIdsSession(getStore())
    getStore().finalizeStoryboardVideoBatchGeneration()
    invalidateProjectTaskListCache()
  }
  const abortVideoBatchUi = (_storyboardIds: number[]) => {
    clearVideoBatchTargetIdsSession(getStore())
    getStore().stopStoryboardVideoBatchGeneration()
    invalidateProjectTaskListCache()
  }
  const finalizePromptChainFailureUi = (input: {
    message?: string
    scriptPanels: StoryboardPanel[]
    videoPanels: StoryboardVideoPanel[]
    targetStoryboardIds: number[]
    onPanelsUpdate: (panels: StoryboardVideoPanel[]) => void
  }) => {
    const errorMessage = String(input.message || '').trim() || '视频提交失败'
    state.activePromptTaskId.value = null
    closePromptStream()
    clearVideoBatchTargetIdsSession(getStore())
    input.onPanelsUpdate(
      finalizeStoryboardVideoChainFailure({
        store: getStore(),
        videoPanels: input.videoPanels,
        targets: collectPairs(input.scriptPanels, input.videoPanels),
        targetStoryboardIds: input.targetStoryboardIds,
        message: errorMessage
      })
    )
    invalidateProjectTaskListCache()
  }
  const clearPanelGeneratingStatusIfIdle = (storyboardId: number) => {
    const id = Number(storyboardId)
    if (!Number.isFinite(id) || id <= 0) return
    const key = String(id)
    if (getStore().storyboardPanelVideoGenStatusByStoryboardId[key] === 'failed') return
    for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(getStore(), getRoute())) {
      if (blob.storyboardVideoGenTasksByStoryboardId?.[key]) return
    }
    getStore().clearStoryboardPanelVideoGenStatus(id)
  }
  const clearPanelGeneratingStatuses = (storyboardIds: number[]) =>
    storyboardIds.forEach(clearPanelGeneratingStatusIfIdle)
  const applyImmediatePanelLoadingRestore = (
    scripts: StoryboardPanel[],
    videos: StoryboardVideoPanel[],
    options?: { skipScopeHydrate?: boolean }
  ) => applyStoryboardVideoImmediatePanelLoadingRestore(getStore(), getRoute(), scripts, videos, options)
  const resolveBatchTargetIdSet = (explicitTargetIds?: number[]) =>
    new Set(
      (explicitTargetIds ?? getActiveBatchTargetIds())
        .map(Number)
        .filter((id) => Number.isFinite(id) && id > 0)
    )
  const persistBatchTargetPanelErrors = (
    pairs: StoryboardVideoPair[],
    errorMessage: string,
    explicitTargetIds?: number[]
  ) => {
    const targets = resolveBatchTargetIdSet(explicitTargetIds)
    if (!targets.size) return
    for (const pair of pairs) {
      if (targets.has(pair.storyboardId)) {
        markStoryboardVideoPanelFailed(pair.storyboardId, errorMessage || '视频生成失败')
      }
    }
  }
  const syncPanelsGeneratingUi = (scripts: StoryboardPanel[], videos: StoryboardVideoPanel[]) => {
    const next = applyStoryboardVideoPanelUiFromStore(getStore(), scripts, videos)
    return next.some(
      (panel, index) =>
        panel.generating !== videos[index]?.generating ||
        panel.generateError !== videos[index]?.generateError
    )
      ? next
      : null
  }
  const resolvePersistedTaskIdWhenListMiss = (
    listHitId: number | null,
    preferredId: unknown,
    taskListOk: boolean
  ) => {
    if (listHitId != null) return listHitId
    const preferred = parseVideoBatchTaskId(preferredId)
    if (preferred == null) return null
    const hasPanelGenerating = Object.values(
      getStore().storyboardPanelVideoGenStatusByStoryboardId || {}
    ).some((status) => status === 'generating')
    return shouldTrustPersistedTaskIdOnListMiss({
      taskListOk,
      isGenerating: Boolean(getStore().isGeneratingStoryboardVideo),
      hasPanelGenerating
    })
      ? preferred
      : null
  }
  const keepVideoBatchLoadingForScope = (
    scope: CreationLiveGenScopeCtx,
    taskIds?: { promptTaskId?: number | null; videoTaskId?: number | null }
  ) => {
    const isCurrentScope = matchesCreationLiveGenScope(scope)
    getStore().mergeStep4PlusLiveGenForScopeKey(
      scope.scopeKey,
      buildVideoBatchScopePreserveOnContextSwitch({
        promptTaskId:
          taskIds?.promptTaskId ??
          (isCurrentScope ? getStore().storyboardVideoBatchActivePromptTaskId : undefined),
        videoTaskId:
          taskIds?.videoTaskId ??
          (isCurrentScope ? getStore().storyboardVideoBatchActiveVideoTaskId : undefined)
      })
    )
    if (isCurrentScope && !getStore().isGeneratingStoryboardVideo) {
      getStore().setGeneratingStoryboardVideo(true)
    }
  }
  const isVideoBatchOperationInterrupted = (scope: CreationLiveGenScopeCtx, generation: number) =>
    generation !== state.resumeFollowGeneration || !matchesCreationLiveGenScope(scope)
  const applyPanelsGeneratingToLocal = (
    videos: StoryboardVideoPanel[],
    scripts: StoryboardPanel[],
    generating: boolean
  ): StoryboardVideoPanel[] =>
    videos.map((panel, index) => {
      const storyboardId = parseServerStoryboardId(scripts[index]?.id)
      const storeGenerating =
        storyboardId != null &&
        getStore().storyboardPanelVideoGenStatusByStoryboardId[String(storyboardId)] === 'generating'
      const isBatchTarget = storyboardId != null && getStore().isStoryboardVideoBatchTarget(storyboardId)
      const shouldGenerate = storeGenerating || (generating && isBatchTarget)
      return { ...panel, generating: shouldGenerate, generateError: shouldGenerate ? undefined : panel.generateError }
    })
  const readLatestScriptPanels = (fallback: StoryboardPanel[] = []) => {
    const current = (getStore().formData.storyboardScript.panels as StoryboardPanel[]) || []
    return current.length ? current : fallback
  }
  const readLatestVideoPanels = (fallback: StoryboardVideoPanel[] = []) => {
    const current = (getStore().formData.storyboardVideo.panels as StoryboardVideoPanel[]) || []
    return current.length ? current : fallback
  }
  const emitVideoPanelsUpdateSafe = (
    onUpdate: (panels: StoryboardVideoPanel[]) => void,
    next: StoryboardVideoPanel[],
    fallback: StoryboardVideoPanel[] = []
  ) => {
    if (!next.length && readLatestVideoPanels(fallback).length) return
    onUpdate(next)
  }
  const applyBatchFailureToLocalPanels = (
    videos: StoryboardVideoPanel[],
    scripts: StoryboardPanel[],
    targetIds: number[],
    errorMessage?: string
  ): StoryboardVideoPanel[] => {
    const targets = new Set(targetIds)
    const message = String(errorMessage || '视频生成失败').trim() || '视频生成失败'
    return videos.map((panel, index) => {
      const storyboardId = parseServerStoryboardId(scripts[index]?.id)
      if (storyboardId == null || !targets.has(storyboardId)) {
        return { ...panel, generating: false, generateError: undefined }
      }
      return { ...panel, generating: false, generateError: message, videos: [] }
    })
  }

  return {
    markStoryboardVideoPanelFailed,
    markStoryboardVideoPanelSucceeded,
    collectPairs,
    getActiveBatchTargetIds,
    setVideoBatchTargetIds,
    clearVideoBatchTargetIds,
    stopVideoBatchGeneration,
    markPanelsGenerating,
    finishVideoBatchUi,
    abortVideoBatchUi,
    finalizePromptChainFailureUi,
    clearPanelGeneratingStatuses,
    applyImmediatePanelLoadingRestore,
    resolveBatchTargetIdSet,
    persistBatchTargetPanelErrors,
    syncPanelsGeneratingUi,
    resolvePersistedTaskIdWhenListMiss,
    keepVideoBatchLoadingForScope,
    isVideoBatchOperationInterrupted,
    applyPanelsGeneratingToLocal,
    readLatestScriptPanels,
    readLatestVideoPanels,
    emitVideoPanelsUpdateSafe,
    applyBatchFailureToLocalPanels
  }
}

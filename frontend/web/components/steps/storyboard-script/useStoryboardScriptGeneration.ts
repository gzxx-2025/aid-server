'use client'

/**
 * StoryboardScript 生成链路与后台任务恢复编排（原 StoryboardScript.vue script 内
 * startGeneration / restore 调度 / 批量分镜图恢复 / 全局任务命令 / 生命周期 watch 段原样搬迁）。
 * SSE 铁律：有 taskId 先跟 SSE 再亮 loading；回调过 scope guard；恢复前等 store 水合。
 */

import { useEffect, useRef, useState, type MutableRefObject } from 'react'
import { useCreationStore } from '~/stores/creation'
import { getRouteLikeSnapshot, useRouteLike } from '~/composables/useRouteLike'
import { useStoryboardScriptBatchGenerate } from '~/composables/useStoryboardScriptBatchGenerate'
import { useStoryboardImageBatchGenerate } from '~/composables/useStoryboardImageBatchGenerate'
import { useStoryboardWorkbenchMutations } from '~/composables/useStoryboardWorkbenchMutations'
import { waitForCreationStoreHydrated } from '~/composables/useCreationStoreHydration'
import { useCreateFlowScopeChangedResume } from '~/composables/useCreateFlowLiveGenResume'
import { applyStoryboardScriptPanelsFromApi } from '~/composables/useCreateFlowStoryboardSync'
import {
  getPersistedStoryboardScriptPanels,
  stripStoryboardScriptSkeletonPanels
} from '~/utils/storyboardPanelMap'
import { STORYBOARD_IMAGE_GEN_SSE_TERMINAL_EVENT } from '~/composables/useStoryboardImageGenerateTask'
import { createStoryboardScriptTaskCommandHandlers } from './storyboardScriptTaskCommands'
import { createStoryboardScriptImageModalBridge } from './storyboardScriptImageModalBridge'
import { type StoryboardPanel } from './storyboardScriptShared'
import { useStoryboardScriptGenerationActions } from './useStoryboardScriptGenerationActions'
import { useStoryboardScriptImageBatchRestore } from './useStoryboardScriptImageBatchRestore'

export function useStoryboardScriptGeneration(opts: {
  panelsRef: MutableRefObject<StoryboardPanel[]>
  onChangeRef: MutableRefObject<(panels: StoryboardPanel[]) => void>
  onGenerationCompleteRef: MutableRefObject<(panels: StoryboardPanel[]) => void>
  isImageModalOpenRef: MutableRefObject<boolean>
  currentPanelIndexRef: MutableRefObject<number>
  setCurrentPanelIndex: (v: number) => void
  setIsImageModalOpen: (v: boolean) => void
  setToolbarOpsOpen: (v: boolean) => void
  storyboardListSyncReadyRef: MutableRefObject<boolean>
  storyboardListSyncReady: boolean
  scriptManualAgentModelPickRef: MutableRefObject<boolean>
}) {
  const {
    panelsRef,
    onChangeRef,
    onGenerationCompleteRef,
    isImageModalOpenRef,
    currentPanelIndexRef,
    setCurrentPanelIndex,
    setIsImageModalOpen,
    setToolbarOpsOpen,
    storyboardListSyncReadyRef,
    storyboardListSyncReady,
    scriptManualAgentModelPickRef
  } = opts

  const route = useRouteLike()
  const wb = useStoryboardWorkbenchMutations()
  const storyboardScriptGen = useStoryboardScriptBatchGenerate()
  const storyboardImageBatchGen = useStoryboardImageBatchGenerate()
  const getStore = () => useCreationStore.getState()

  // 订阅 store：与原 Pinia watch 依赖对齐
  const isHydrated = useCreationStore((s) => s.isHydrated)
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const isGeneratingStoryboard = useCreationStore((s) => s.isGeneratingStoryboard)
  const isGeneratingStoryboardImageBatch = useCreationStore(
    (s) => s.isGeneratingStoryboardImageBatch
  )
  const storyboardGenerationError = useCreationStore((s) => s.storyboardGenerationError)
  const storyboardImageBatchActiveTaskId = useCreationStore(
    (s) => s.storyboardImageBatchActiveTaskId
  )
  const storyboardImageBatchActiveImageTaskId = useCreationStore(
    (s) => s.storyboardImageBatchActiveImageTaskId
  )

  /** 切步卸载：先停恢复调度，再丢弃异步收尾（不碰路由时序） */
  const pageDisposedRef = useRef(false)
  const pageMountedRef = useRef(false)
  const generationStoppedRef = useRef(false)
  const storyboardRestoreGenerationRef = useRef(0)
  const panelImageStoreRestoreDepthRef = useRef(0)
  const modalAutoReopenAttemptedRef = useRef(false)
  const generationActions = useStoryboardScriptGenerationActions({
    panelsRef,
    onChangeRef,
    onGenerationCompleteRef,
    scriptManualAgentModelPickRef,
    pageDisposedRef,
    generationStoppedRef,
    storyboardScriptGen,
    storyboardImageBatchGen,
    workbench: wb,
    storyboardListSyncReadyRef
  })
  const {
    isResumingPartialFailed,
    hasOngoingStoryboardScriptGenWork,
    prepareGeneratingProgress,
    clearStoryboardScriptToEmptyState,
    syncStoryboardScriptPanelsFromTaskResult,
    refreshPanelsFromStoryboardListApi,
    startGeneration,
    stopGeneration,
    handleResumePartialFailed,
    handleBatchGenerateStoryboardImages,
    stopImageBatchGeneration
  } = generationActions

  /* ---------- 分镜脚本任务恢复调度 ---------- */

  const storyboardScriptRestoreTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  function scheduleRestoreStoryboardScriptGenerationIfNeeded() {
    if (typeof window === 'undefined' || !pageMountedRef.current || pageDisposedRef.current) return
    if (storyboardScriptRestoreTimerRef.current) {
      clearTimeout(storyboardScriptRestoreTimerRef.current)
    }
    storyboardScriptRestoreTimerRef.current = setTimeout(() => {
      storyboardScriptRestoreTimerRef.current = null
      void restoreStoryboardScriptGenerationIfNeeded()
    }, 48)
  }

  async function restoreStoryboardScriptGenerationIfNeeded() {
    if (typeof window === 'undefined' || !pageMountedRef.current || pageDisposedRef.current) return

    await waitForCreationStoreHydrated(getStore(), getRouteLikeSnapshot())
    if (!pageMountedRef.current || pageDisposedRef.current) return

    const preferredTaskId = Number(getStore().storyboardScriptActiveTaskId)
    const alreadyFollowingSameTask =
      Number.isFinite(preferredTaskId) &&
      preferredTaskId > 0 &&
      storyboardScriptGen.activeTaskId.value === preferredTaskId &&
      getStore().isGeneratingStoryboard
    if (alreadyFollowingSameTask) return

    storyboardScriptGen.cancelResumeFollow()
    const gen = ++storyboardRestoreGenerationRef.current
    await storyboardScriptGen.restoreOngoingGenerationIfNeeded(
      panelsRef.current,
      (next) => {
        if (
          !pageMountedRef.current ||
          pageDisposedRef.current ||
          gen !== storyboardRestoreGenerationRef.current
        ) {
          return
        }
        syncStoryboardScriptPanelsFromTaskResult(next)
      },
      () => {
        if (
          !pageMountedRef.current ||
          pageDisposedRef.current ||
          gen !== storyboardRestoreGenerationRef.current
        ) {
          return
        }
        prepareGeneratingProgress()
      }
    )
  }

  const { restoreStoryboardImageBatchIfNeeded, disposeStoryboardImageBatchRestore } =
    useStoryboardScriptImageBatchRestore({
      panelsRef,
      onChangeRef,
      pageMountedRef,
      pageDisposedRef,
      imageBatchGenerate: storyboardImageBatchGen,
      workbench: wb
    })

  /* ---------- 弹窗自动重开 / 关闭收尾，实现见 storyboardScriptImageModalBridge.ts ---------- */

  const { tryReopenStoryboardImageModalAfterRefresh, handleImageModalClosed } =
    createStoryboardScriptImageModalBridge({
      panelsRef,
      isImageModalOpenRef,
      currentPanelIndexRef,
      modalAutoReopenAttemptedRef,
      setCurrentPanelIndex,
      setIsImageModalOpen,
      refreshPanelsFromStoryboardListApi,
      restoreStoryboardImageBatchIfNeeded: () => restoreStoryboardImageBatchIfNeeded()
    })

  /* ---------- 全局任务命令（任务面板 → 步骤页），实现见 storyboardScriptTaskCommands.ts ---------- */

  const {
    handleGlobalTrackTaskEvent,
    handleGlobalResumeTaskEvent,
    handleGlobalRestartTaskEvent,
    deliverPendingCreateFlowTaskCommands,
    handleGlobalStopTaskEvent
  } = createStoryboardScriptTaskCommandHandlers({
    storyboardScriptGen,
    storyboardImageBatchGen,
    pageDisposedRef,
    generationStoppedRef,
    onChangeRef,
    onGenerationCompleteRef,
    prepareGeneratingProgress,
    syncStoryboardScriptPanelsFromTaskResult,
    handleResumePartialFailed,
    startGeneration,
    setToolbarOpsOpen
  })

  /* ---------- 生命周期与 watch ---------- */

  const startGenerationRef = useRef(startGeneration)
  startGenerationRef.current = startGeneration
  const scheduleRestoreRef = useRef(scheduleRestoreStoryboardScriptGenerationIfNeeded)
  scheduleRestoreRef.current = scheduleRestoreStoryboardScriptGenerationIfNeeded
  const restoreImageBatchRef = useRef(restoreStoryboardImageBatchIfNeeded)
  restoreImageBatchRef.current = restoreStoryboardImageBatchIfNeeded
  const tryReopenRef = useRef(tryReopenStoryboardImageModalAfterRefresh)
  tryReopenRef.current = tryReopenStoryboardImageModalAfterRefresh

  // 原 onMounted / onBeforeUnmount / onUnmounted
  useEffect(() => {
    pageMountedRef.current = true
    pageDisposedRef.current = false
    const stripped = stripStoryboardScriptSkeletonPanels(panelsRef.current)
    const persisted = getPersistedStoryboardScriptPanels(stripped)
    const store = getStore()
    const hasOngoingScriptGen =
      store.isGeneratingStoryboard ||
      !!store.storyboardScriptActiveTaskId ||
      store.isGeneratingStoryboardImageBatch
    if (persisted.length === 0) {
      if (
        !hasOngoingScriptGen &&
        (panelsRef.current.length > 0 || store.storyboardGenerationError)
      ) {
        clearStoryboardScriptToEmptyState()
        onChangeRef.current([])
      }
    } else if (stripped.length !== panelsRef.current.length) {
      applyStoryboardScriptPanelsFromApi(stripped)
    }
    window.addEventListener('create-flow-stop-task', handleGlobalStopTaskEvent)
    window.addEventListener('create-flow-track-task', handleGlobalTrackTaskEvent)
    window.addEventListener('create-flow-restart-task', handleGlobalRestartTaskEvent)
    window.addEventListener('create-flow-resume-task', handleGlobalResumeTaskEvent)
    window.addEventListener(
      STORYBOARD_IMAGE_GEN_SSE_TERMINAL_EVENT,
      storyboardImageBatchGen.onStoryboardImageGenSseTerminal
    )
    /** 先注册任务命令监听，再由挂载后的统一恢复入口接管 SSE。 */
    deliverPendingCreateFlowTaskCommands()
    if (getStore().isHydrated) {
      storyboardImageBatchGen.applyImmediatePanelLoadingRestore(panelsRef.current)
      tryReopenRef.current()
    }
    scheduleRestoreRef.current()
    void restoreImageBatchRef.current({ discoverServerTasks: true })

    return () => {
      pageMountedRef.current = false
      pageDisposedRef.current = true
      disposeStoryboardImageBatchRestore()
      storyboardRestoreGenerationRef.current += 1
      storyboardScriptGen.cancelResumeFollow()
      void storyboardImageBatchGen.cancelResumeFollow()
      if (storyboardScriptRestoreTimerRef.current) {
        clearTimeout(storyboardScriptRestoreTimerRef.current)
        storyboardScriptRestoreTimerRef.current = null
      }
      window.removeEventListener('create-flow-stop-task', handleGlobalStopTaskEvent)
      window.removeEventListener('create-flow-track-task', handleGlobalTrackTaskEvent)
      window.removeEventListener('create-flow-restart-task', handleGlobalRestartTaskEvent)
      window.removeEventListener('create-flow-resume-task', handleGlobalResumeTaskEvent)
      window.removeEventListener(
        STORYBOARD_IMAGE_GEN_SSE_TERMINAL_EVENT,
        storyboardImageBatchGen.onStoryboardImageGenSseTerminal
      )
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 原 watch([persistedCount, error, generating, imageBatchGenerating])：空列表 + 有 error 时清理
  const storyboardEmptyErrorClearingRef = useRef(false)
  const persistedPanelsLength = getPersistedStoryboardScriptPanels(panelsRef.current).length
  useEffect(() => {
    if (persistedPanelsLength > 0 || isGeneratingStoryboard || isGeneratingStoryboardImageBatch) {
      return
    }
    if (!storyboardGenerationError) return
    if (storyboardEmptyErrorClearingRef.current) return
    storyboardEmptyErrorClearingRef.current = true
    try {
      clearStoryboardScriptToEmptyState()
      if (panelsRef.current.length > 0) {
        onChangeRef.current([])
      }
    } finally {
      storyboardEmptyErrorClearingRef.current = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    persistedPanelsLength,
    storyboardGenerationError,
    isGeneratingStoryboard,
    isGeneratingStoryboardImageBatch
  ])

  // 原 watch([isHydrated, projectId, episodeId, route.query...], immediate)：调度分镜脚本恢复
  useEffect(() => {
    scheduleRestoreRef.current()
     
  }, [isHydrated, currentProjectId, currentEpisodeId, route.query.projectId, route.query.episodeId])

  useCreateFlowScopeChangedResume(() => {
    tryReopenRef.current(true)
    scheduleRestoreRef.current()
    return restoreImageBatchRef.current({ discoverServerTasks: true })
  })

  // 原 watch([isHydrated, panels.length], immediate)：刷新后回填卡片 loading + 尝试重开弹窗
  const panelsLength = panelsRef.current.length
  useEffect(() => {
    if (
      !isHydrated ||
      typeof window === 'undefined' ||
      !pageMountedRef.current ||
      pageDisposedRef.current ||
      panelImageStoreRestoreDepthRef.current > 0
    ) {
      return
    }
    panelImageStoreRestoreDepthRef.current += 1
    try {
      storyboardImageBatchGen.applyImmediatePanelLoadingRestore(panelsRef.current)
      tryReopenRef.current()
    } finally {
      panelImageStoreRestoreDepthRef.current -= 1
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isHydrated, panelsLength])

  // 原 watch(storyboardListSyncReady)：列表同步完成后再走完整 restore
  const prevListSyncReadyRef = useRef(storyboardListSyncReady)
  useEffect(() => {
    const prev = prevListSyncReadyRef.current
    prevListSyncReadyRef.current = storyboardListSyncReady
    if (storyboardListSyncReady === prev) return
    if (
      !storyboardListSyncReady ||
      typeof window === 'undefined' ||
      !pageMountedRef.current ||
      pageDisposedRef.current ||
      !getStore().isHydrated
    ) {
      return
    }
    if (panelImageStoreRestoreDepthRef.current > 0) return
    panelImageStoreRestoreDepthRef.current += 1
    try {
      storyboardImageBatchGen.applyImmediatePanelLoadingRestore(panelsRef.current)
    } finally {
      panelImageStoreRestoreDepthRef.current -= 1
    }
    // 列表同步完成后必须再走完整 restore：切集清空 panels 时首轮 restore 可能早退，仅刷 loading 不够
    void restoreImageBatchRef.current({ discoverServerTasks: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storyboardListSyncReady])

  // 原 watch(scope, { flush: 'sync' })：切作品/剧集时断开旧提示词流并交接恢复
  const prevScopeRef = useRef<readonly [unknown, unknown, unknown, unknown] | null>(null)
  useEffect(() => {
    const scope = [
      currentProjectId,
      currentEpisodeId,
      route.query.projectId,
      route.query.episodeId
    ] as const
    const previousScope = prevScopeRef.current
    prevScopeRef.current = scope
    if (!previousScope || scope.every((value, index) => value === previousScope[index])) return
    // 在 scope 事件排队前先断开独立提示词流，确保旧 owner 不会阻塞新作品恢复。
    void storyboardImageBatchGen.cancelResumeFollow()
    void restoreImageBatchRef.current({
      discoverServerTasks: true,
      waitForFollowHandoff: true
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentProjectId, currentEpisodeId, route.query.projectId, route.query.episodeId])

  // 原 watch([...批量任务相关字段], immediate)：内部状态变化时恢复已知任务
  useEffect(() => {
    void restoreImageBatchRef.current()
     
  }, [
    isHydrated,
    currentProjectId,
    currentEpisodeId,
    isGeneratingStoryboardImageBatch,
    storyboardImageBatchActiveTaskId,
    storyboardImageBatchActiveImageTaskId,
    persistedPanelsLength,
    route.query.projectId,
    route.query.episodeId
  ])

  return {
    storyboardScriptGen,
    storyboardImageBatchGen,
    pageMountedRef,
    pageDisposedRef,
    generationStoppedRef,
    isResumingPartialFailed,
    hasOngoingStoryboardScriptGenWork,
    startGeneration,
    stopGeneration,
    handleResumePartialFailed,
    handleBatchGenerateStoryboardImages,
    stopImageBatchGeneration,
    restoreStoryboardImageBatchIfNeeded,
    scheduleRestoreStoryboardScriptGenerationIfNeeded,
    handleImageModalClosed,
    refreshPanelsFromStoryboardListApi
  }
}

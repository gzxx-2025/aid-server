'use client'

import { useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import {
  useStoryboardVideoBatchGenerate,
  applyStoryboardVideoPanelUiFromStore
} from '~/composables/useStoryboardVideoBatchGenerate'
import {
  applyCreationStoreScopeLiveGenFromRoute,
  resolveStoryboardVideoGenEntriesByTaskId,
  waitForCreationStoreHydrated
} from '~/composables/useCreationStoreHydration'
import { useCreateFlowScopeChangedResume } from '~/composables/useCreateFlowLiveGenResume'
import { createCoalescedAsyncRunner } from '~/utils/coalescedAsyncRunner'
import { shouldSilentStoryboardBatchToast } from '~/utils/taskSseSilentDisconnect'
import {
  hasPersistedStoryboardVideoBatchGenWork
} from '~/utils/storyboardListBootstrap'
import {
  shouldDropImageBatchRestoreBecauseFollowing,
  shouldRestoreImageBatchSse
} from '~/utils/storyboardImageBatchRestoreGate'
import { modalGenSessionScopeFromStore } from '~/utils/modalGenSessionScope'
import {
  clearStoryboardVideoModalUserDismissed,
  isStoryboardVideoModalUserDismissed,
  readStoryboardVideoModalGenSession
} from '~/utils/storyboardVideoModalGenSession'
import {
  ackCreateFlowTaskCommand,
  consumePendingCreateFlowTaskCommand,
  createFlowTaskCommandEvent
} from '~/utils/createFlowTaskCommand'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import type { StoryboardVideoStepRestoreOptions } from './storyboardVideoStepRestoreTypes'

/** 分镜视频步骤的后台任务恢复、scope 交接与全局任务事件编排。 */
export function useStoryboardVideoStepRestore(opts: StoryboardVideoStepRestoreOptions) {
  const {
    panelsRef,
    scriptPanelsRef,
    onChangeRef,
    batchVideoSubmittingRef,
    isVideoModalOpenRef,
    resolvePanelStoryboardId,
    openEditVideoModalAt,
    reopenVideoModalAt,
    storyboardListSyncReady,
    isHydrated,
    currentProjectId,
    currentEpisodeId,
    routeProjectId,
    routeEpisodeId,
    panelsLength,
    scriptPanelsLength
  } = opts

  const videoBatchGen = useStoryboardVideoBatchGenerate()
  const pageDisposedRef = useRef(false)
  const pageMountedRef = useRef(false)
  const restoreGenerationRef = useRef(0)
  /** 防止 panels ↔ store 双向同步在同一 tick 内互相触发 */
  const panelVideoUiSyncDepthRef = useRef(0)
  /** 防止 store hydrate ↔ applyImmediatePanelLoadingRestore watcher 同 tick 递归 */
  const panelVideoStoreRestoreDepthRef = useRef(0)
  const serverDiscoveryRequestedRef = useRef(false)
  const followHandoffRequestedRef = useRef(false)
  const videoModalAutoReopenAttemptedRef = useRef(false)

  // 订阅 store：与原 Pinia watch 依赖对齐（JSON 指纹避免深比较硬套）
  const statusById = useCreationStore((s) => s.storyboardPanelVideoGenStatusByStoryboardId)
  const errorById = useCreationStore((s) => s.storyboardPanelVideoGenErrorByStoryboardId)
  const batchTargets = useCreationStore((s) => s.storyboardVideoBatchTargetStoryboardIds)
  const isGeneratingStoryboardVideo = useCreationStore((s) => s.isGeneratingStoryboardVideo)
  const promptTid = useCreationStore((s) => s.storyboardVideoBatchActivePromptTaskId)
  const videoTid = useCreationStore((s) => s.storyboardVideoBatchActiveVideoTaskId)
  const statusSig = JSON.stringify(statusById)
  const errorSig = JSON.stringify(errorById)
  const batchTargetsSig = JSON.stringify(batchTargets)

  function mergeStoryboardVideoPanelUiFromStore(): void {
    if (!pageMountedRef.current || pageDisposedRef.current || batchVideoSubmittingRef.current) {
      return
    }
    const scriptPanels = scriptPanelsRef.current
    const next = applyStoryboardVideoPanelUiFromStore(
      useCreationStore.getState(),
      scriptPanels,
      panelsRef.current
    )
    if (
      next.some(
        (p, i) =>
          p.generating !== panelsRef.current[i]?.generating ||
          p.generateError !== panelsRef.current[i]?.generateError
      )
    ) {
      panelVideoUiSyncDepthRef.current += 1
      try {
        onChangeRef.current(next)
      } finally {
        panelVideoUiSyncDepthRef.current -= 1
      }
    }
  }

  function hasServerStoryboardIdsForVideoRestore(): boolean {
    return scriptPanelsRef.current.some((p) => parseServerStoryboardId(p.id) != null)
  }

  const storyboardListSyncReadyRef = useRef(storyboardListSyncReady)
  storyboardListSyncReadyRef.current = storyboardListSyncReady

  async function runStoryboardVideoBatchRestoreOnce() {
    if (
      typeof window === 'undefined' ||
      !pageMountedRef.current ||
      pageDisposedRef.current ||
      batchVideoSubmittingRef.current
    ) {
      return
    }
    await waitForCreationStoreHydrated(useCreationStore.getState(), getRouteLikeSnapshot())
    if (!pageMountedRef.current || pageDisposedRef.current) return
    const discoverServerTasks = serverDiscoveryRequestedRef.current
    const waitForFollowHandoff = followHandoffRequestedRef.current
    serverDiscoveryRequestedRef.current = false
    followHandoffRequestedRef.current = false

    const scriptPanels = scriptPanelsRef.current
    applyCreationStoreScopeLiveGenFromRoute(useCreationStore.getState(), getRouteLikeSnapshot())
    videoBatchGen.applyImmediatePanelLoadingRestore(scriptPanels, panelsRef.current)
    mergeStoryboardVideoPanelUiFromStore()
    if (shouldDropImageBatchRestoreBecauseFollowing(videoBatchGen.isBatchFollowInFlight())) {
      if (!waitForFollowHandoff) return
      await videoBatchGen.waitForFollowIdle()
      if (!pageMountedRef.current || pageDisposedRef.current) return
      applyCreationStoreScopeLiveGenFromRoute(useCreationStore.getState(), getRouteLikeSnapshot())
      videoBatchGen.applyImmediatePanelLoadingRestore(scriptPanelsRef.current, panelsRef.current)
      mergeStoryboardVideoPanelUiFromStore()
    }

    const store = useCreationStore.getState()
    const hasPersistedVideoWork =
      Boolean(store.isGeneratingStoryboardVideo) ||
      Number(store.storyboardVideoBatchActivePromptTaskId) > 0 ||
      Number(store.storyboardVideoBatchActiveVideoTaskId) > 0 ||
      hasPersistedStoryboardVideoBatchGenWork(store, getRouteLikeSnapshot())
    const hasLocalRestoreIntent = shouldRestoreImageBatchSse({
      isGenerating: hasPersistedVideoWork,
      following: false,
      hasServerStoryboardIds: hasServerStoryboardIdsForVideoRestore(),
      hasActiveTaskId:
        Number(store.storyboardVideoBatchActivePromptTaskId) > 0 ||
        Number(store.storyboardVideoBatchActiveVideoTaskId) > 0
    })
    if (!hasLocalRestoreIntent && !discoverServerTasks) return

    if (!hasPersistedVideoWork) videoBatchGen.cancelResumeFollow()
    const gen = ++restoreGenerationRef.current
    await videoBatchGen.restoreOngoingBatchIfNeeded(
      scriptPanelsRef.current,
      panelsRef.current,
      (next) => {
        if (
          !pageMountedRef.current ||
          pageDisposedRef.current ||
          gen !== restoreGenerationRef.current
        ) {
          return
        }
        // restore 入参可能是 list 同步前的空快照，禁止把已显示列表盖成空态
        if (!next.length && panelsRef.current.length > 0) return
        onChangeRef.current(next)
      },
      { discoverServerTasks }
    )
  }

  const restoreOnceRef = useRef(runStoryboardVideoBatchRestoreOnce)
  restoreOnceRef.current = runStoryboardVideoBatchRestoreOnce
  const [storyboardVideoBatchRestoreRunner] = useState(() =>
    createCoalescedAsyncRunner(() => restoreOnceRef.current())
  )

  function restoreStoryboardVideoBatchIfNeeded(options?: {
    discoverServerTasks?: boolean
    waitForFollowHandoff?: boolean
  }) {
    if (
      typeof window === 'undefined' ||
      !pageMountedRef.current ||
      pageDisposedRef.current ||
      batchVideoSubmittingRef.current
    ) {
      return Promise.resolve()
    }
    if (options?.discoverServerTasks) serverDiscoveryRequestedRef.current = true
    if (options?.waitForFollowHandoff) followHandoffRequestedRef.current = true
    if (
      shouldDropImageBatchRestoreBecauseFollowing(videoBatchGen.isBatchFollowInFlight()) &&
      !followHandoffRequestedRef.current
    ) {
      return Promise.resolve()
    }
    return storyboardVideoBatchRestoreRunner.request()
  }

  /** 刷新或切回原作品后尝试自动重开分镜视频编辑弹窗 */
  function tryReopenStoryboardVideoModalAfterRefresh(fromScopeChange = false) {
    if (typeof window === 'undefined' || isVideoModalOpenRef.current) return
    if (!fromScopeChange && videoModalAutoReopenAttemptedRef.current) return

    const sessionScope = modalGenSessionScopeFromStore(useCreationStore.getState())
    const session = readStoryboardVideoModalGenSession(sessionScope)
    if (!session) return

    const { storyboardId, sceneIdx } = session
    if (isStoryboardVideoModalUserDismissed(storyboardId, sessionScope)) return
    if (sceneIdx < 0 || sceneIdx >= panelsRef.current.length) return

    videoModalAutoReopenAttemptedRef.current = true
    reopenVideoModalAt(sceneIdx)
  }

  function handleGlobalTrackTaskEvent(event: Event) {
    const detail = (event as CustomEvent<{ taskId?: number; taskType?: string | null }>).detail
    const ty = String(detail?.taskType ?? '')
      .trim()
      .toLowerCase()
      .replace(/-/g, '_')
    if (ty !== 'storyboard_video_prompt_batch' && ty !== 'storyboard_video_generate') return
    const taskId = Number(detail?.taskId)
    if (ty === 'storyboard_video_generate' && Number.isFinite(taskId) && taskId > 0) {
      const modalEntries = resolveStoryboardVideoGenEntriesByTaskId(
        useCreationStore.getState(),
        taskId,
        getRouteLikeSnapshot()
      )
      const modalEntry = modalEntries[0]
      if (modalEntry) {
        ackCreateFlowTaskCommand('track', taskId)
        const indexByStoryboard = panelsRef.current.findIndex(
          (_, index) => resolvePanelStoryboardId(index) === modalEntry.storyboardId
        )
        const sceneIdx =
          indexByStoryboard >= 0
            ? indexByStoryboard
            : modalEntry.sceneIdx >= 0 && modalEntry.sceneIdx < panelsRef.current.length
              ? modalEntry.sceneIdx
              : -1
        if (sceneIdx >= 0) {
          clearStoryboardVideoModalUserDismissed(
            modalGenSessionScopeFromStore(useCreationStore.getState())
          )
          openEditVideoModalAt(sceneIdx)
        }
        return
      }
    }
    ackCreateFlowTaskCommand('track', Number(detail?.taskId))
    videoBatchGen.onGlobalTrackTask(
      event,
      scriptPanelsRef.current,
      panelsRef.current,
      (next) => {
        if (pageDisposedRef.current) return
        onChangeRef.current(next)
      },
      (result) => {
        if (pageDisposedRef.current) return
        if (result.ok) {
          message.success('分镜视频批量生成完成')
        } else if (result.message && !shouldSilentStoryboardBatchToast(result.message)) {
          message.error(result.message)
        }
      }
    )
  }

  function handleGlobalStopTaskEvent(event: Event) {
    void videoBatchGen.onGlobalStopTask(event)
  }

  function handleGlobalResumeTaskEvent(event: Event) {
    const detail = (event as CustomEvent<{ taskId?: number; taskType?: string | null }>).detail
    const ty = String(detail?.taskType ?? '')
      .trim()
      .toLowerCase()
      .replace(/-/g, '_')
    if (ty !== 'storyboard_video_prompt_batch' && ty !== 'storyboard_video_generate') return
    ackCreateFlowTaskCommand('resume', Number(detail?.taskId))
    videoBatchGen.onGlobalResumeTask(
      event,
      scriptPanelsRef.current,
      panelsRef.current,
      (next) => {
        if (pageDisposedRef.current) return
        onChangeRef.current(next)
      },
      (result) => {
        if (pageDisposedRef.current) return
        if (result.ok) {
          message.success('分镜视频续生完成')
        } else if (result.message && !shouldSilentStoryboardBatchToast(result.message)) {
          if (result.message.includes('部分') || result.message.includes('续生')) {
            message.warning(result.message)
          } else {
            message.error(result.message)
          }
        }
      }
    )
  }

  /**
   * 全局任务面板先跳步骤再派发指令；本页挂载晚于派发时事件已错过，
   * 挂载完成后补投属于本页的 pending 指令（分镜视频提示词/出片批量任务）。
   */
  function deliverPendingCreateFlowTaskCommands() {
    const acceptsOwnTask = (d: { taskType: string | null }) => {
      const ty = String(d.taskType ?? '')
        .trim()
        .toLowerCase()
        .replace(/-/g, '_')
      return ty === 'storyboard_video_prompt_batch' || ty === 'storyboard_video_generate'
    }
    const resume = consumePendingCreateFlowTaskCommand('resume', acceptsOwnTask)
    if (resume) {
      handleGlobalResumeTaskEvent(createFlowTaskCommandEvent('resume', resume))
    }
    const track = consumePendingCreateFlowTaskCommand('track', acceptsOwnTask)
    if (track) {
      handleGlobalTrackTaskEvent(createFlowTaskCommandEvent('track', track))
    }
  }

  const handleGlobalTrackTaskEventRef = useRef(handleGlobalTrackTaskEvent)
  handleGlobalTrackTaskEventRef.current = handleGlobalTrackTaskEvent
  const handleGlobalStopTaskEventRef = useRef(handleGlobalStopTaskEvent)
  handleGlobalStopTaskEventRef.current = handleGlobalStopTaskEvent
  const handleGlobalResumeTaskEventRef = useRef(handleGlobalResumeTaskEvent)
  handleGlobalResumeTaskEventRef.current = handleGlobalResumeTaskEvent

  // 原 onMounted（恢复部分）+ onBeforeUnmount + onUnmounted
  useEffect(() => {
    pageMountedRef.current = true
    pageDisposedRef.current = false
    if (panelsRef.current.length === 0 && useCreationStore.getState().storyboardGenerationError) {
      useCreationStore.getState().clearStoryboardScriptGenerationOutcome()
    }
    const onTrack = (e: Event) => handleGlobalTrackTaskEventRef.current(e)
    const onStop = (e: Event) => handleGlobalStopTaskEventRef.current(e)
    const onResume = (e: Event) => handleGlobalResumeTaskEventRef.current(e)
    window.addEventListener('create-flow-track-task', onTrack)
    window.addEventListener('create-flow-stop-task', onStop)
    window.addEventListener('create-flow-resume-task', onResume)
    deliverPendingCreateFlowTaskCommands()
    if (useCreationStore.getState().isHydrated) {
      applyCreationStoreScopeLiveGenFromRoute(useCreationStore.getState(), getRouteLikeSnapshot())
      videoBatchGen.applyImmediatePanelLoadingRestore(scriptPanelsRef.current, panelsRef.current)
      mergeStoryboardVideoPanelUiFromStore()
      tryReopenStoryboardVideoModalAfterRefresh()
    }
    void restoreStoryboardVideoBatchIfNeeded({ discoverServerTasks: true })
    return () => {
      pageMountedRef.current = false
      pageDisposedRef.current = true
      storyboardVideoBatchRestoreRunner.dispose()
      restoreGenerationRef.current += 1
      void videoBatchGen.cancelResumeFollow()
      window.removeEventListener('create-flow-track-task', onTrack)
      window.removeEventListener('create-flow-stop-task', onStop)
      window.removeEventListener('create-flow-resume-task', onResume)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 原 watch(statusSig/errorSig/batchTargets/batchVideo)：store 变化后重投影 panels UI
  useEffect(() => {
    if (batchVideoSubmittingRef.current || panelVideoUiSyncDepthRef.current > 0) return
    mergeStoryboardVideoPanelUiFromStore()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusSig, errorSig, batchTargetsSig, isGeneratingStoryboardVideo])

  // 原 watch(creationStore.isHydrated, { immediate: true })
  useEffect(() => {
    if (!isHydrated || !pageMountedRef.current || pageDisposedRef.current) return
    panelVideoStoreRestoreDepthRef.current += 1
    try {
      applyCreationStoreScopeLiveGenFromRoute(useCreationStore.getState(), getRouteLikeSnapshot())
      videoBatchGen.applyImmediatePanelLoadingRestore(scriptPanelsRef.current, panelsRef.current)
      mergeStoryboardVideoPanelUiFromStore()
      tryReopenStoryboardVideoModalAfterRefresh()
    } finally {
      panelVideoStoreRestoreDepthRef.current -= 1
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isHydrated])

  /** 与分镜脚本分镜图一致：hydrate 后立即恢复 store loading，不等待 restore 异步 */
  useEffect(() => {
    if (
      !isHydrated ||
      !pageMountedRef.current ||
      pageDisposedRef.current ||
      batchVideoSubmittingRef.current
    ) {
      return
    }
    if (panelVideoStoreRestoreDepthRef.current > 0 || panelVideoUiSyncDepthRef.current > 0) return
    panelVideoStoreRestoreDepthRef.current += 1
    try {
      videoBatchGen.applyImmediatePanelLoadingRestore(scriptPanelsRef.current, panelsRef.current, {
        skipScopeHydrate: true
      })
    } finally {
      panelVideoStoreRestoreDepthRef.current -= 1
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    isHydrated,
    panelsLength,
    scriptPanelsLength,
    statusSig,
    errorSig,
    isGeneratingStoryboardVideo,
    promptTid,
    videoTid
  ])

  // 原 watch(storyboardListSyncReady)：非 immediate，仅在变为 ready 时执行
  const prevListSyncReadyRef = useRef<boolean | null>(null)
  useEffect(() => {
    const prev = prevListSyncReadyRef.current
    prevListSyncReadyRef.current = storyboardListSyncReady
    if (prev === null || prev === storyboardListSyncReady) return
    if (
      !storyboardListSyncReady ||
      !pageMountedRef.current ||
      pageDisposedRef.current ||
      !useCreationStore.getState().isHydrated
    ) {
      return
    }
    videoBatchGen.applyImmediatePanelLoadingRestore(scriptPanelsRef.current, panelsRef.current)
    mergeStoryboardVideoPanelUiFromStore()
    // 列表同步后必须完整 restore：跨集清空 panels 时首轮可能早退，仅刷 loading 不够
    void restoreStoryboardVideoBatchIfNeeded({ discoverServerTasks: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storyboardListSyncReady])

  // 原 watch(scope, { flush: 'sync' })：项目/剧集切换时交接 SSE 并按新 scope 恢复
  const prevScopeRef = useRef<readonly [unknown, unknown, unknown, unknown] | null>(null)
  useEffect(() => {
    const scope = [currentProjectId, currentEpisodeId, routeProjectId, routeEpisodeId] as const
    const previousScope = prevScopeRef.current
    prevScopeRef.current = scope
    if (!previousScope || scope.every((value, index) => value === previousScope[index])) return
    void videoBatchGen.cancelResumeFollow()
    void restoreStoryboardVideoBatchIfNeeded({
      discoverServerTasks: true,
      waitForFollowHandoff: true
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentProjectId, currentEpisodeId, routeProjectId, routeEpisodeId])

  // 原 watch([isHydrated, scope, taskIds, 面板长度], { immediate: true })：按需请求恢复
  useEffect(() => {
    void restoreStoryboardVideoBatchIfNeeded()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    isHydrated,
    currentProjectId,
    currentEpisodeId,
    promptTid,
    videoTid,
    scriptPanelsLength,
    panelsLength,
    routeProjectId,
    routeEpisodeId
  ])

  useCreateFlowScopeChangedResume(() => {
    tryReopenStoryboardVideoModalAfterRefresh(true)
    return restoreStoryboardVideoBatchIfNeeded({ discoverServerTasks: true })
  })

  return {
    videoBatchGen,
    pageDisposedRef,
    pageMountedRef,
    restoreStoryboardVideoBatchIfNeeded,
    mergeStoryboardVideoPanelUiFromStore
  }
}

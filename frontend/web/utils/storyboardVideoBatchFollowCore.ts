/**
 * 分镜视频批量生成：状态绑定核心助手（原 composables/useStoryboardVideoBatchGenerate.ts
 * 创建器闭包内 loading UI / 任务 id 同步 / 设主视频 / 进度回填等部分拆分；
 * 提示词/出片 follow 链路见 utils/storyboardVideoBatchPromptFollow.ts、storyboardVideoBatchVideoFollow.ts）。
 */

import { useCreationStore } from '~/stores/creation'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import {
  captureCreationLiveGenScope,
  matchesCreationLiveGenScope,
  type CreationLiveGenScopeCtx
} from '~/composables/useCreationLiveGenScopeGuard'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { userTaskDetailCached } from '~/utils/businessApi'
import {
  fetchFlowUserTaskList,
  filterUserTaskRowsForEpisode
} from '~/utils/userTaskListFlowOnce'
import {
  fetchProjectStoryboardRecords,
  fetchStoryboardRecordsForStoryboard,
  groupStoryboardRecordsByStoryboardId,
  type ProjectEpisodeContext
} from '~/utils/storyboardRecordBatch'
import type { TaskSseProgressInput } from '~/utils/taskSseProgressText'
import type { TaskVideoBatchSuccessItem } from '~/utils/taskPartialFailed'
import type { StoryboardPanel, StoryboardVideoPanel } from '~/types'
import type { StoryboardRecordRow, UserTaskRow } from '~/types/business-api'
import {
  buildPanelVideosFromRows,
  clearVideoBatchTargetIdsSession,
  panelHasPersistedVideoFailure,
  parseVideoBatchTaskId as parseTaskId,
  setFinalVideosForStoryboards,
  videoBatchBizErr as bizErr,
  type StoryboardVideoBatchState,
  type StoryboardVideoPair
} from '~/utils/storyboardVideoBatchShared'
import { createStoryboardVideoBatchPanelState } from '~/utils/storyboardVideoBatchPanelState'
import {
  setFinalVideosFromTerminalItems,
  syncScriptFinalVideoFromTerminalItems
} from '~/utils/storyboardVideoBatchTerminalRecords'

const PROJECT_TASK_LIST_CACHE_MS = 5000

export type StoryboardVideoBatchCore = ReturnType<typeof createStoryboardVideoBatchCore>

export function createStoryboardVideoBatchCore(state: StoryboardVideoBatchState) {
  /** 原 Pinia store 为活引用；Zustand 快照不可变，每次使用取最新 state */
  function getStore() {
    return useCreationStore.getState()
  }

  /** 原 vue-router useRoute() 响应式对象；React 侧在调用时取路由快照 */
  function getRoute() {
    return getRouteLikeSnapshot()
  }

  function beginBatchSseFollow() {
    state.batchSseFollowDepth += 1
    state.batchSseFollowInFlight = true
  }

  function endBatchSseFollow() {
    state.batchSseFollowDepth = Math.max(0, state.batchSseFollowDepth - 1)
    state.batchSseFollowInFlight = state.batchSseFollowDepth > 0
    state.followIdleBarrier.notifyStateChange()
  }

  function isVideoBatchFollowBusy(): boolean {
    return (
      state.batchSseFollowInFlight ||
      state.batchRunInFlight ||
      state.promptFollowOwner != null ||
      state.videoFollowOwner != null
    )
  }

  function closePromptStream() {
    const close = state.promptStreamCloser
    state.promptStreamCloser = null
    if (close) {
      try {
        close()
      } catch {
        /* ignore */
      }
    }
  }

  function syncActivePromptTaskIdToStore(taskId: number | null) {
    state.activePromptTaskId.value = taskId
    getStore().setStoryboardVideoBatchActivePromptTaskId(taskId)
  }

  function syncActiveVideoTaskIdToStore(taskId: number | null) {
    getStore().setStoryboardVideoBatchActiveVideoTaskId(taskId)
  }

  async function fetchProjectTaskListCached(
    projectId: number,
    options?: { force?: boolean }
  ): Promise<UserTaskRow[]> {
    const pid = Number(projectId)
    if (!Number.isFinite(pid) || pid <= 0) return []
    const now = Date.now()
    const force = options?.force === true
    if (
      !force &&
      state.cachedProjectTaskList &&
      state.cachedProjectTaskList.projectId === pid &&
      now - state.cachedProjectTaskList.at < PROJECT_TASK_LIST_CACHE_MS
    ) {
      return filterUserTaskRowsForEpisode(
        state.cachedProjectTaskList.rows,
        getStore().currentEpisodeId
      )
    }
    /**
     * restore/发现默认 read：复用壳层 bootstrap 权威 list。
     * force 仅用于提交后写穿（mutate），避免「刚提交的出片任务」被旧缓存挡住。
     */
    const rows = await fetchFlowUserTaskList(pid, {
      intent: force ? 'mutate' : 'read'
    })
    state.cachedProjectTaskList = { projectId: pid, at: now, rows }
    /** 剧集隔离：禁止把其它集的分镜视频任务恢复到本集 */
    return filterUserTaskRowsForEpisode(rows, getStore().currentEpisodeId)
  }

  function invalidateProjectTaskListCache() {
    state.cachedProjectTaskList = null
  }

  function notifyGlobalTasksUpdatedOnce() {
    if (typeof window === 'undefined') return
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }

  function setManualPromptAgentModelPick(value: boolean) {
    state.manualPromptAgentModelPick = value
  }

  function setManualVideoModelPick(value: boolean) {
    state.manualVideoModelPick = value
  }

  const panelState = createStoryboardVideoBatchPanelState({
    state,
    getStore,
    getRoute,
    closePromptStream,
    invalidateProjectTaskListCache
  })
  const {
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
  } = panelState

  function applySseProgress(p: TaskSseProgressInput) {
    getStore().applyStoryboardVideoBatchSseProgress(p)
  }

  async function seedProgressFromTaskDetail(taskId: number, fallbackTotal: number) {
    try {
      const detail = await userTaskDetailCached(taskId)
      if (!detail) return
      if (detail.eta) {
        getStore().applyStoryboardVideoBatchSseProgress({ eta: detail.eta })
      }
      const totalShots = Number((detail as { totalShots?: number }).totalShots)
      const total = Number.isFinite(totalShots) && totalShots > 0 ? totalShots : fallbackTotal
      if (total > 0) {
        const cur = getStore().storyboardVideoBatchProgress
        if (!cur.total || cur.total < total) {
          getStore().setStoryboardVideoBatchProgress(Math.min(cur.completed, total), total)
        }
      }
    } catch {
      /* ignore */
    }
  }

  async function loadVideosForStoryboardPanel(
    storyboardId: number,
    panelTitle: string,
    videoByStoryboardId?: Map<number, StoryboardRecordRow[]>
  ): Promise<NonNullable<StoryboardVideoPanel['videos']>> {
    if (videoByStoryboardId) {
      return buildPanelVideosFromRows(videoByStoryboardId.get(storyboardId) ?? [], panelTitle)
    }
    const ctx = await resolveStoryScriptSaveContext(getStore(), getRoute())
    if (!ctx) return []
    const rows = await fetchStoryboardRecordsForStoryboard(ctx, storyboardId, 'video')
    return buildPanelVideosFromRows(rows, panelTitle)
  }

  async function refreshPanelsVideosForPairs(
    pairs: StoryboardVideoPair[],
    working: StoryboardVideoPanel[],
    options?: { onlyUpToStepIndex?: number; batchTargetIds?: number[] }
  ): Promise<StoryboardVideoPanel[]> {
    const next = [...working]
    const limit = options?.onlyUpToStepIndex
    const targets = resolveBatchTargetIdSet(options?.batchTargetIds)
    if (!targets.size) return next
    /** 项目级 record 列表只拉一次，循环内按分镜分组回填，避免逐镜逐次请求 */
    let videoByStoryboardId: Map<number, StoryboardRecordRow[]> | undefined
    try {
      const ctx = await resolveStoryScriptSaveContext(getStore(), getRoute())
      if (ctx) {
        videoByStoryboardId = groupStoryboardRecordsByStoryboardId(
          await fetchProjectStoryboardRecords(ctx, 'video')
        )
      }
    } catch {
      /* 预取失败退回逐镜路径 */
    }
    for (const pair of pairs) {
      if (!targets.has(pair.storyboardId)) continue
      if (limit != null && pair.index >= limit) continue
      const panelTitle = pair.video?.title || pair.script.title || `分镜视频${pair.index + 1}`
      try {
        // 进度阶段：仅回填已产出的视频，不因暂时无文件误标失败（失败留给终态处理）
        if (panelHasPersistedVideoFailure(getStore(), pair.storyboardId)) {
          continue
        }
        const videos = await loadVideosForStoryboardPanel(
          pair.storyboardId,
          panelTitle,
          videoByStoryboardId
        )
        if (videos.length) {
          next[pair.index] = {
            ...next[pair.index]!,
            generating: true,
            generateError: undefined,
            videos
          }
        }
      } catch {
        /* 单镜刷新失败不阻断 */
      }
    }
    return next
  }

  function applyVideoBatchTerminalItemsToPanels(
    working: StoryboardVideoPanel[],
    pairs: StoryboardVideoPair[],
    items: TaskVideoBatchSuccessItem[]
  ): StoryboardVideoPanel[] {
    if (!items.length) return working
    syncScriptFinalVideoFromTerminalItems(items)
    const next = [...working]
    const bySid = new Map(items.map((it) => [it.storyboardId, it]))
    for (const pair of pairs) {
      const hit = bySid.get(pair.storyboardId)
      if (!hit) continue
      const panelTitle = pair.video?.title || pair.script.title || `分镜视频${pair.index + 1}`
      const videoItem = {
        id: String(hit.recordId),
        url: hit.videoUrl,
        title: panelTitle,
        source: '生成记录',
        isStoryboardVideo: true,
        _fromServer: true as const,
        _serverRow: {
          id: hit.recordId,
          storyboardId: hit.storyboardId,
          fileUrl: hit.videoUrl,
          isSelected: 1,
          genType: 'i2v'
        } as StoryboardRecordRow
      }
      next[pair.index] = {
        ...next[pair.index]!,
        generating: false,
        generateError: undefined,
        finalVideoUrl: hit.videoUrl,
        videos: [videoItem]
      }
      markStoryboardVideoPanelSucceeded(pair.storyboardId)
    }
    return next
  }

  async function refreshPanelsAfterVideoBatch(
    pairs: StoryboardVideoPair[],
    working: StoryboardVideoPanel[],
    failedStoryboardIds?: Set<number>,
    batchTargetIds?: number[]
  ): Promise<StoryboardVideoPanel[]> {
    const next = [...working]
    const targets = resolveBatchTargetIdSet(batchTargetIds)
    if (!targets.size) return next
    const ctx = await resolveStoryScriptSaveContext(getStore(), getRoute())

    for (const pair of pairs) {
      if (!targets.has(pair.storyboardId)) continue
      if (failedStoryboardIds?.has(pair.storyboardId)) {
        const message = '视频生成失败'
        next[pair.index] = {
          ...next[pair.index]!,
          generating: false,
          generateError: message,
          videos: []
        }
        markStoryboardVideoPanelFailed(pair.storyboardId, message)
      }
    }

    const successIds = pairs
      .map((p) => p.storyboardId)
      .filter((sid) => targets.has(sid) && !failedStoryboardIds?.has(sid))

    let videoByStoryboardId = new Map<number, StoryboardRecordRow[]>()
    if (ctx && successIds.length) {
      try {
        const outcome = await setFinalVideosForStoryboards(ctx, successIds)
        videoByStoryboardId = outcome.videoByStoryboardId
        for (const [sid, ok] of outcome.results) {
          if (ok) markStoryboardVideoPanelSucceeded(sid)
          else markStoryboardVideoPanelFailed(sid, '设置主视频失败')
        }
      } catch (e: unknown) {
        const message = bizErr(e) || '设置主视频失败'
        for (const sid of successIds) {
          markStoryboardVideoPanelFailed(sid, message)
        }
        return next.map((p, i) => {
          const pair = pairs[i]
          if (!pair || failedStoryboardIds?.has(pair.storyboardId)) return p
          return {
            ...p,
            generating: false,
            generateError: message,
            videos: p.videos ?? []
          }
        })
      }
    }

    for (const pair of pairs) {
      if (!targets.has(pair.storyboardId)) continue
      if (failedStoryboardIds?.has(pair.storyboardId)) continue
      const panelTitle = pair.video?.title || pair.script.title || `分镜视频${pair.index + 1}`
      const key = String(pair.storyboardId)
      const wasBatchGenerating =
        getStore().storyboardPanelVideoGenStatusByStoryboardId[key] === 'generating' ||
        getStore().storyboardPanelVideoGenStatusByStoryboardId[key] === 'failed' ||
        targets.has(pair.storyboardId)
      const videos = buildPanelVideosFromRows(
        videoByStoryboardId.get(pair.storyboardId) ?? [],
        panelTitle
      )
      // 本地已有成功视频（如 SSE items 已回填）时优先保留，避免被空列表盖成失败
      const localVideos = next[pair.index]?.videos
      const hasLocalSuccess =
        Array.isArray(localVideos) &&
        localVideos.some((v) => v.isStoryboardVideo && String(v.url ?? '').trim())
      const mainUrl =
        videos.find((v) => v.isStoryboardVideo && String(v.url ?? '').trim())?.url ||
        videos.find((v) => String(v.url ?? '').trim())?.url ||
        ''
      if (videos.length && wasBatchGenerating) {
        next[pair.index] = {
          ...next[pair.index]!,
          generating: false,
          generateError: undefined,
          ...(mainUrl ? { finalVideoUrl: String(mainUrl) } : {}),
          videos
        }
        markStoryboardVideoPanelSucceeded(pair.storyboardId)
      } else if (videos.length) {
        next[pair.index] = {
          ...next[pair.index]!,
          generating: false,
          generateError: undefined,
          ...(mainUrl ? { finalVideoUrl: String(mainUrl) } : {}),
          videos
        }
        markStoryboardVideoPanelSucceeded(pair.storyboardId)
      } else if (hasLocalSuccess) {
        next[pair.index] = {
          ...next[pair.index]!,
          generating: false,
          generateError: undefined
        }
        markStoryboardVideoPanelSucceeded(pair.storyboardId)
      } else if (panelHasPersistedVideoFailure(getStore(), pair.storyboardId)) {
        const err =
          String(
            getStore().storyboardPanelVideoGenErrorByStoryboardId[String(pair.storyboardId)] ?? ''
          ).trim() || '视频生成失败'
        next[pair.index] = {
          ...next[pair.index]!,
          generating: false,
          generateError: err,
          videos: []
        }
      } else {
        const message = '视频生成完成，但未获取到视频文件'
        next[pair.index] = {
          ...next[pair.index]!,
          generating: false,
          generateError: message,
          videos: []
        }
        markStoryboardVideoPanelFailed(pair.storyboardId, message)
      }
    }

    return next
  }

  return {
    getStore,
    getRoute,
    beginBatchSseFollow,
    endBatchSseFollow,
    isVideoBatchFollowBusy,
    closePromptStream,
    syncActivePromptTaskIdToStore,
    syncActiveVideoTaskIdToStore,
    fetchProjectTaskListCached,
    invalidateProjectTaskListCache,
    notifyGlobalTasksUpdatedOnce,
    setManualPromptAgentModelPick,
    setManualVideoModelPick,
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
    applyBatchFailureToLocalPanels,
    applySseProgress,
    seedProgressFromTaskDetail,
    refreshPanelsVideosForPairs,
    applyVideoBatchTerminalItemsToPanels,
    setFinalVideosFromTerminalItems,
    refreshPanelsAfterVideoBatch,
    /** 供 restore 阶段捕获 scope（与 Vue captureCreationLiveGenScope 一致） */
    captureScope: captureCreationLiveGenScope,
    matchesScope: matchesCreationLiveGenScope
  }
}

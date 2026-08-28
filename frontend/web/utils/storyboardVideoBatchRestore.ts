/**
 * 分镜视频批量生成：刷新/切集后的任务恢复编排（原 composables/useStoryboardVideoBatchGenerate.ts
 * restoreOngoingBatchIfNeeded 段拆分；全局任务面板事件见 utils/storyboardVideoBatchGlobalTasks.ts）。
 */

import { applyCreationStoreScopeLiveGenFromRoute } from '~/composables/useCreationStoreHydration'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { fetchUserTaskDetailOnce,normalizeTaskStatus } from '~/composables/useTaskSseFollow'
import type { StoryboardPanel,StoryboardVideoPanel } from '~/types'
import type { UserTaskRow } from '~/types/business-api'
import {
shouldKeepVideoBatchLoadingAfterFollowMessage,
shouldRestoreImageBatchSse
} from '~/utils/storyboardImageBatchRestoreGate'
import { hasPersistedStoryboardVideoBatchGenWork } from '~/utils/storyboardListBootstrap'
import type { StoryboardVideoBatchCore } from '~/utils/storyboardVideoBatchFollowCore'
import type { StoryboardVideoBatchPromptFollow } from '~/utils/storyboardVideoBatchPromptFollow'
import { restoreStoryboardVideoPersistedFallback } from '~/utils/storyboardVideoBatchRestorePersisted'
import { restoreStoryboardVideoPromptTarget } from '~/utils/storyboardVideoBatchRestorePrompt'
import { resolveVideoBatchRestoreFollowTarget } from '~/utils/storyboardVideoBatchRestoreTarget'
import {
isOngoingVideoBatchUserTaskStatus,
parseVideoBatchTaskId as parseTaskId,
resolveModalVideoRestoreEntriesForTaskId,
type StoryboardVideoBatchState
} from '~/utils/storyboardVideoBatchShared'
import type { StoryboardVideoBatchVideoFollow } from '~/utils/storyboardVideoBatchVideoFollow'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
beginFlowTaskListQuietWindow,
endFlowTaskListQuietWindow
} from '~/utils/userTaskListFlowOnce'

export type StoryboardVideoBatchRestore = ReturnType<typeof createStoryboardVideoBatchRestore>

export function createStoryboardVideoBatchRestore(
  state: StoryboardVideoBatchState,
  core: StoryboardVideoBatchCore,
  promptFollow: StoryboardVideoBatchPromptFollow,
  videoFollow: StoryboardVideoBatchVideoFollow
) {
  const { getStore } = core

  async function restoreOngoingBatchIfNeeded(
    scriptPanels: StoryboardPanel[],
    videoPanels: StoryboardVideoPanel[],
    onPanelsUpdate: (panels: StoryboardVideoPanel[]) => void,
    options?: { discoverServerTasks?: boolean }
  ): Promise<void> {
    if (typeof window === 'undefined') return

    // 刷新后 list 可能晚于 restore：禁止空快照把已同步列表盖成「暂无分镜视频」
    const safeOnPanelsUpdate = (next: StoryboardVideoPanel[]) => {
      core.emitVideoPanelsUpdateSafe(onPanelsUpdate, next, videoPanels)
    }

    // 刷新后先把 scope 桶灌回扁平字段，再判断 taskId / isGenerating
    applyCreationStoreScopeLiveGenFromRoute(getStore(), core.getRoute())
    core.applyImmediatePanelLoadingRestore(scriptPanels, videoPanels, { skipScopeHydrate: true })
    const synced = core.syncPanelsGeneratingUi(
      core.readLatestScriptPanels(scriptPanels),
      core.readLatestVideoPanels(videoPanels)
    )
    if (synced) safeOnPanelsUpdate(synced)

    const hasServerStoryboardIds = core
      .readLatestScriptPanels(scriptPanels)
      .some((panel) => parseServerStoryboardId(panel.id) != null)
    const hasRestoreIntent = shouldRestoreImageBatchSse({
      isGenerating:
        Boolean(getStore().isGeneratingStoryboardVideo) ||
        hasPersistedStoryboardVideoBatchGenWork(getStore(), core.getRoute()),
      following: false,
      hasServerStoryboardIds,
      hasActiveTaskId:
        parseTaskId(getStore().storyboardVideoBatchActivePromptTaskId) != null ||
        parseTaskId(getStore().storyboardVideoBatchActiveVideoTaskId) != null
    })
    if (!hasRestoreIntent && !options?.discoverServerTasks) return

    if (core.isVideoBatchFollowBusy()) return

    const scopeAtEntry = core.captureScope()
    if (state.restoreSessionInFlight) {
      return state.restoreSessionInFlight
    }

    const run = async () => {
      state.batchRunInFlight = true
      let quietProjectId: number | null = null
      try {
        // 再次灌回：await 期间可能被 list sync / setCurrentProjectContext 冲掉扁平态
        applyCreationStoreScopeLiveGenFromRoute(getStore(), core.getRoute())

        const ctx = await resolveStoryScriptSaveContext(getStore(), core.getRoute())
        if (!ctx) return

        const gen = ++state.resumeFollowGeneration

        const preferredPromptId = getStore().storyboardVideoBatchActivePromptTaskId
        let preferredVideoIdEarly = getStore().storyboardVideoBatchActiveVideoTaskId
        let hasActiveTaskId =
          parseTaskId(preferredPromptId) != null || parseTaskId(preferredVideoIdEarly) != null
        let hasPersistedBatchState =
          getStore().isGeneratingStoryboardVideo ||
          hasActiveTaskId ||
          hasPersistedStoryboardVideoBatchGenWork(getStore(), core.getRoute())

        let pendingVideoTasksEarly = videoFollow.getPendingModalVideoTaskEntries()

        let tasks: UserTaskRow[] = []
        let taskListOk = true
        quietProjectId = ctx.projectId
        beginFlowTaskListQuietWindow(ctx.projectId)
        try {
          // 刷新续跟：read 复用权威 list；SSE 续跟不依赖再 force 打网
          tasks = await core.fetchProjectTaskListCached(ctx.projectId)
        } catch {
          tasks = []
          taskListOk = false
        }
        if (gen !== state.resumeFollowGeneration) return

        // list/detail await 期间 storyboard/list 可能已写入 panels：必须用最新，禁止空入参快照
        let liveScriptPanels = core.readLatestScriptPanels(scriptPanels)
        let liveVideoPanels = core.readLatestVideoPanels(videoPanels)
        let pairs = core.collectPairs(liveScriptPanels, liveVideoPanels)
        let storyboardIds = pairs.map((p) => p.storyboardId)

        if (taskListOk) {
          await videoFollow.reconcileOngoingVideoGenerationTasks(tasks, pairs, scopeAtEntry)
          if (gen !== state.resumeFollowGeneration || !core.matchesScope(scopeAtEntry)) return
          preferredVideoIdEarly = getStore().storyboardVideoBatchActiveVideoTaskId
          hasActiveTaskId =
            parseTaskId(preferredPromptId) != null || parseTaskId(preferredVideoIdEarly) != null
          hasPersistedBatchState =
            getStore().isGeneratingStoryboardVideo ||
            hasActiveTaskId ||
            hasPersistedStoryboardVideoBatchGenWork(getStore(), core.getRoute())
          pendingVideoTasksEarly = videoFollow.getPendingModalVideoTaskEntries()
        }

        const listOngoingVideoId = parseTaskId(
          videoFollow.pickOngoingVideoGenerateTask(tasks, preferredVideoIdEarly)?.id
        )
        const listOngoingPromptId = parseTaskId(
          promptFollow.pickOngoingVideoPromptBatchTask(tasks, preferredPromptId)?.id
        )

        const prefVideo = parseTaskId(preferredVideoIdEarly)
        let storeVideoTaskTrusted = false
        if (prefVideo != null) {
          try {
            const detail = await fetchUserTaskDetailOnce(prefVideo)
            const st = normalizeTaskStatus(detail?.status ?? '')
            storeVideoTaskTrusted =
              Boolean(detail && isOngoingVideoBatchUserTaskStatus(st)) ||
              (!detail && getStore().isGeneratingStoryboardVideo) ||
              (Boolean(detail) && getStore().isGeneratingStoryboardVideo)
          } catch {
            storeVideoTaskTrusted =
              getStore().isGeneratingStoryboardVideo || listOngoingVideoId == null
          }
        }

        const prefPrompt = parseTaskId(preferredPromptId)
        let storePromptTaskTrusted = false
        if (prefPrompt != null) {
          try {
            const detail = await fetchUserTaskDetailOnce(prefPrompt)
            // 提示词即使已终态也要信任：用于解析 chainChildTaskIds 再跟出片
            storePromptTaskTrusted =
              Boolean(detail) || getStore().isGeneratingStoryboardVideo || hasPersistedBatchState
          } catch {
            storePromptTaskTrusted =
              getStore().isGeneratingStoryboardVideo || hasPersistedBatchState
          }
        }
        if (gen !== state.resumeFollowGeneration) return

        const followTarget = resolveVideoBatchRestoreFollowTarget({
          listOngoingVideoTaskId: listOngoingVideoId,
          listOngoingPromptTaskId: listOngoingPromptId,
          storeVideoTaskId: prefVideo,
          storePromptTaskId: prefPrompt,
          storeVideoTaskTrusted,
          storePromptTaskTrusted
        })

        // 跨集会清空 panels：有可跟目标时先跟 SSE，勿只亮 loading
        if (
          !storyboardIds.length &&
          !followTarget &&
          !options?.discoverServerTasks &&
          !shouldRestoreImageBatchSse({
            isGenerating: Boolean(hasPersistedBatchState),
            following: false,
            hasActiveTaskId: hasActiveTaskId || hasPersistedBatchState
          })
        ) {
          return
        }

        // —— 出片优先：任务中心已显示「分镜视频出片」进行中时，必须直接连 SSE ——
        if (followTarget?.kind === 'video') {
          const ongoingVideoId = followTarget.taskId
          if (!videoFollow.shouldRestoreAsListBatchVideoTask(ongoingVideoId)) {
            const entries = resolveModalVideoRestoreEntriesForTaskId(
              ongoingVideoId,
              pairs,
              getStore(),
              core.getRoute()
            )
            if (entries.length) {
              return
            }
            // 无弹窗条目时仍按列表批量续跟，禁止空 return 丢 SSE
          }
          if (getStore().storyboardVideoBatchActiveVideoTaskId !== ongoingVideoId) {
            core.syncActiveVideoTaskIdToStore(ongoingVideoId)
          }
          if (!getStore().isGeneratingStoryboardVideo) {
            getStore().setGeneratingStoryboardVideo(true)
            getStore().setStoryboardVideoBatchError(null)
          }
          liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
          liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
          pairs = core.collectPairs(liveScriptPanels, liveVideoPanels)
          storyboardIds = pairs.map((p) => p.storyboardId)
          core.applyImmediatePanelLoadingRestore(liveScriptPanels, liveVideoPanels, {
            skipScopeHydrate: true
          })
          const working = core.applyPanelsGeneratingToLocal(liveVideoPanels, liveScriptPanels, true)
          safeOnPanelsUpdate(working)
          const batchTargetIds = core.getActiveBatchTargetIds()
          const restorePairs =
            batchTargetIds.length > 0
              ? pairs.filter((p) => batchTargetIds.includes(p.storyboardId))
              : pairs
          const videoFollowOutcome = await videoFollow.followOngoingVideoGenerateTask(
            ongoingVideoId,
            restorePairs,
            safeOnPanelsUpdate,
            working.length ? working : liveVideoPanels
          )
          if (gen !== state.resumeFollowGeneration) {
            core.keepVideoBatchLoadingForScope(scopeAtEntry, { videoTaskId: ongoingVideoId })
            return
          }
          if (
            !videoFollowOutcome.ok &&
            shouldKeepVideoBatchLoadingAfterFollowMessage(videoFollowOutcome.message)
          ) {
            core.keepVideoBatchLoadingForScope(scopeAtEntry, { videoTaskId: ongoingVideoId })
            return
          }
          if (!core.matchesScope(scopeAtEntry)) {
            core.keepVideoBatchLoadingForScope(scopeAtEntry, { videoTaskId: ongoingVideoId })
            return
          }
          if (!videoFollowOutcome.ok) {
            core.abortVideoBatchUi(batchTargetIds.length ? batchTargetIds : storyboardIds)
            return
          }
          core.finishVideoBatchUi(batchTargetIds.length ? batchTargetIds : storyboardIds)
          return
        }

        const ongoingPromptId: number | null =
          followTarget?.kind === 'prompt' ? followTarget.taskId : null

        if (ongoingPromptId != null) {
          return restoreStoryboardVideoPromptTarget({
            state,
            core,
            promptFollow,
            videoFollow,
            ctx,
            gen,
            ongoingPromptId,
            liveScriptPanels,
            liveVideoPanels,
            pairs,
            storyboardIds,
            safeOnPanelsUpdate,
            scopeAtEntry
          })
        }

        const preferredVideoId = getStore().storyboardVideoBatchActiveVideoTaskId
        let ongoingVideoId: number | null = null
        const fallbackPrefVideo = parseTaskId(preferredVideoId)
        if (fallbackPrefVideo != null) {
          try {
            const detail = await fetchUserTaskDetailOnce(fallbackPrefVideo)
            if (detail || getStore().isGeneratingStoryboardVideo) {
              ongoingVideoId = fallbackPrefVideo
            }
          } catch {
            if (getStore().isGeneratingStoryboardVideo) ongoingVideoId = fallbackPrefVideo
          }
        }
        if (ongoingVideoId == null) {
          const ongoingVideoTask = videoFollow.pickOngoingVideoGenerateTask(tasks, preferredVideoId)
          ongoingVideoId = core.resolvePersistedTaskIdWhenListMiss(
            parseTaskId(ongoingVideoTask?.id),
            preferredVideoId,
            taskListOk
          )
        }

        if (ongoingVideoId != null) {
          if (gen !== state.resumeFollowGeneration) return

          liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
          liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
          pairs = core.collectPairs(liveScriptPanels, liveVideoPanels)
          storyboardIds = pairs.map((p) => p.storyboardId)

          if (!videoFollow.shouldRestoreAsListBatchVideoTask(ongoingVideoId)) {
            const entries = resolveModalVideoRestoreEntriesForTaskId(
              ongoingVideoId,
              pairs,
              getStore(),
              core.getRoute()
            )
            if (!entries.length) return
            return
          }

          if (getStore().storyboardVideoBatchActiveVideoTaskId !== ongoingVideoId) {
            core.syncActiveVideoTaskIdToStore(ongoingVideoId)
          }
          if (!getStore().isGeneratingStoryboardVideo) {
            getStore().setGeneratingStoryboardVideo(true)
            getStore().setStoryboardVideoBatchError(null)
          }
          core.applyImmediatePanelLoadingRestore(liveScriptPanels, liveVideoPanels, {
            skipScopeHydrate: true
          })
          const working = core.applyPanelsGeneratingToLocal(liveVideoPanels, liveScriptPanels, true)
          safeOnPanelsUpdate(working)
          const batchTargetIds = core.getActiveBatchTargetIds()
          const restorePairs =
            batchTargetIds.length > 0
              ? pairs.filter((p) => batchTargetIds.includes(p.storyboardId))
              : pairs
          const videoFollowOutcome = await videoFollow.followOngoingVideoGenerateTask(
            ongoingVideoId,
            restorePairs,
            safeOnPanelsUpdate,
            working.length ? working : liveVideoPanels
          )
          if (gen !== state.resumeFollowGeneration) {
            core.keepVideoBatchLoadingForScope(scopeAtEntry, {
              promptTaskId: getStore().storyboardVideoBatchActivePromptTaskId,
              videoTaskId: ongoingVideoId
            })
            return
          }
          if (
            !videoFollowOutcome.ok &&
            shouldKeepVideoBatchLoadingAfterFollowMessage(videoFollowOutcome.message)
          ) {
            core.keepVideoBatchLoadingForScope(scopeAtEntry, {
              promptTaskId: getStore().storyboardVideoBatchActivePromptTaskId,
              videoTaskId: ongoingVideoId
            })
            return
          }
          if (!core.matchesScope(scopeAtEntry)) {
            core.keepVideoBatchLoadingForScope(scopeAtEntry, {
              promptTaskId: getStore().storyboardVideoBatchActivePromptTaskId,
              videoTaskId: ongoingVideoId
            })
            return
          }
          if (!videoFollowOutcome.ok) {
            core.abortVideoBatchUi(batchTargetIds.length ? batchTargetIds : storyboardIds)
            return
          }
          core.finishVideoBatchUi(batchTargetIds.length ? batchTargetIds : storyboardIds)
          return
        }

        return restoreStoryboardVideoPersistedFallback({
          state,
          core,
          promptFollow,
          videoFollow,
          ctx,
          gen,
          taskListOk,
          pendingVideoTasksEarly,
          liveScriptPanels,
          liveVideoPanels,
          pairs,
          storyboardIds,
          safeOnPanelsUpdate,
          scopeAtEntry
        })
      } finally {
        if (quietProjectId != null) endFlowTaskListQuietWindow(quietProjectId)
        state.batchRunInFlight = false
        state.followIdleBarrier.notifyStateChange()
      }
    }

    const pending = run()
    const owner = pending.finally(() => {
      state.restoreSessionInFlight = null
    })
    state.restoreSessionInFlight = owner
    return owner
  }

  return {
    restoreOngoingBatchIfNeeded
  }
}


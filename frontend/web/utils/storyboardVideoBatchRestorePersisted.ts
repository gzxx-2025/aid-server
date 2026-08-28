import type { StoryboardPanel,StoryboardVideoPanel } from '~/types'
import { shouldKeepVideoBatchLoadingAfterFollowMessage } from '~/utils/storyboardImageBatchRestoreGate'
import type { StoryboardVideoBatchCore } from '~/utils/storyboardVideoBatchFollowCore'
import type { StoryboardVideoBatchPromptFollow } from '~/utils/storyboardVideoBatchPromptFollow'
import type { StoryboardVideoBatchState } from '~/utils/storyboardVideoBatchShared'
import { parseVideoBatchTaskId as parseTaskId } from '~/utils/storyboardVideoBatchShared'
import type { StoryboardVideoBatchVideoFollow } from '~/utils/storyboardVideoBatchVideoFollow'
import { excludeCoveredStoryboardIds } from '~/utils/storyboardVideoChainFailure'
interface RestoreSharedInput {
  state: StoryboardVideoBatchState
  core: StoryboardVideoBatchCore
  promptFollow: StoryboardVideoBatchPromptFollow
  videoFollow: StoryboardVideoBatchVideoFollow
  ctx: { projectId: number; episodeId: number }
  gen: number
  liveScriptPanels: StoryboardPanel[]
  liveVideoPanels: StoryboardVideoPanel[]
  pairs: ReturnType<StoryboardVideoBatchCore['collectPairs']>
  storyboardIds: number[]
  safeOnPanelsUpdate: (panels: StoryboardVideoPanel[]) => void
  scopeAtEntry: ReturnType<StoryboardVideoBatchCore['captureScope']>
}

interface RestorePersistedFallbackInput extends RestoreSharedInput {
  taskListOk: boolean
  pendingVideoTasksEarly: ReturnType<
    StoryboardVideoBatchVideoFollow['getPendingModalVideoTaskEntries']
  >
}

export async function restoreStoryboardVideoPersistedFallback(
  input: RestorePersistedFallbackInput
): Promise<void> {
  const {
    state,
    core,
    promptFollow,
    videoFollow,
    ctx,
    gen,
    taskListOk,
    pendingVideoTasksEarly,
    safeOnPanelsUpdate,
    scopeAtEntry
  } = input
  const { getStore } = core
  let {
    liveScriptPanels,
    liveVideoPanels,
    pairs,
    storyboardIds
  } = input

const persistedGenerating = Object.entries(
  getStore().storyboardPanelVideoGenStatusByStoryboardId
).filter(([, st]) => st === 'generating')

const pendingVideoTasks = pendingVideoTasksEarly

const hasPersistedTaskId =
  getStore().storyboardVideoBatchActivePromptTaskId != null ||
  getStore().storyboardVideoBatchActiveVideoTaskId != null

if (!persistedGenerating.length && !pendingVideoTasks.length) {
  if (!getStore().isGeneratingStoryboardVideo && !hasPersistedTaskId) {
    return
  }
  if (!taskListOk) {
    liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
    liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
    core.applyImmediatePanelLoadingRestore(liveScriptPanels, liveVideoPanels, {
      skipScopeHydrate: true
    })
    const retrySynced = core.syncPanelsGeneratingUi(liveScriptPanels, liveVideoPanels)
    if (retrySynced) safeOnPanelsUpdate(retrySynced)
    return
  }
  if (getStore().isGeneratingStoryboardVideo && !hasPersistedTaskId) {
    return
  }
  // 仍有 taskId：强制续跟（对齐分镜图），禁止空 return 丢 SSE
  if (hasPersistedTaskId) {
    const promptTid = parseTaskId(getStore().storyboardVideoBatchActivePromptTaskId)
    const videoTid = parseTaskId(getStore().storyboardVideoBatchActiveVideoTaskId)
    liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
    liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
    pairs = core.collectPairs(liveScriptPanels, liveVideoPanels)
    storyboardIds = pairs.map((p) => p.storyboardId)
    core.applyImmediatePanelLoadingRestore(liveScriptPanels, liveVideoPanels, {
      skipScopeHydrate: true
    })
    if (!getStore().isGeneratingStoryboardVideo) {
      getStore().setGeneratingStoryboardVideo(true)
    }
    if (gen !== state.resumeFollowGeneration) {
      core.keepVideoBatchLoadingForScope(scopeAtEntry, {
        promptTaskId: promptTid,
        videoTaskId: videoTid
      })
      return
    }
    const batchTargetIds = core.getActiveBatchTargetIds()
    const restorePairs =
      batchTargetIds.length > 0
        ? pairs.filter((p) => batchTargetIds.includes(p.storyboardId))
        : pairs
    const promptTargets = batchTargetIds.length ? batchTargetIds : storyboardIds
    if (promptTid != null) {
      const promptOutcome = await promptFollow.followPromptTask(promptTid, promptTargets)
      if (
        !promptOutcome.ok &&
        !promptOutcome.partial &&
        !promptOutcome.chainChildTaskIds?.length &&
        shouldKeepVideoBatchLoadingAfterFollowMessage(promptOutcome.message)
      ) {
        core.keepVideoBatchLoadingForScope(scopeAtEntry, {
          promptTaskId: promptTid,
          videoTaskId: videoTid
        })
        return
      }
      if (
        promptOutcome.ok ||
        promptOutcome.partial ||
        promptOutcome.chainChildTaskIds?.length
      ) {
        const videoId = promptOutcome.chainFailed
          ? null
          : (videoTid ??
            (await videoFollow.resolveOngoingVideoGenerateTaskId(ctx, videoTid)))
        if (
          videoId == null &&
          (promptOutcome.chainChildTaskIds?.length || restorePairs.length)
        ) {
          liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
          liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
          const working = core.applyPanelsGeneratingToLocal(
            liveVideoPanels,
            liveScriptPanels,
            true
          )
          safeOnPanelsUpdate(working)
          const videoOutcome = await videoFollow.followVideoGenerateAfterPrompt(
            restorePairs,
            safeOnPanelsUpdate,
            working.length ? working : liveVideoPanels,
            promptOutcome.chainChildTaskIds,
            { chainSubmissionFailed: promptOutcome.chainFailed }
          )
          if (
            !videoOutcome.ok &&
            shouldKeepVideoBatchLoadingAfterFollowMessage(videoOutcome.message)
          ) {
            core.keepVideoBatchLoadingForScope(scopeAtEntry, {
              promptTaskId: promptTid,
              videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
            })
            return
          }
          if (videoOutcome.ok && core.matchesScope(scopeAtEntry)) {
            if (promptOutcome.chainFailed) {
              core.finalizePromptChainFailureUi({
                message: promptOutcome.message,
                scriptPanels: core.readLatestScriptPanels(liveScriptPanels),
                videoPanels: core.readLatestVideoPanels(working),
                targetStoryboardIds: excludeCoveredStoryboardIds(
                  promptTargets,
                  videoOutcome.coveredStoryboardIds
                ),
                onPanelsUpdate: safeOnPanelsUpdate
              })
            } else {
              core.finishVideoBatchUi(promptTargets)
            }
          }
          return
        }
        if (videoId != null) {
          liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
          liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
          const working = core.applyPanelsGeneratingToLocal(
            liveVideoPanels,
            liveScriptPanels,
            true
          )
          safeOnPanelsUpdate(working)
          const videoFollowOutcome = await videoFollow.followOngoingVideoGenerateTask(
            videoId,
            restorePairs,
            safeOnPanelsUpdate,
            working.length ? working : liveVideoPanels
          )
          if (
            !videoFollowOutcome.ok &&
            shouldKeepVideoBatchLoadingAfterFollowMessage(videoFollowOutcome.message)
          ) {
            core.keepVideoBatchLoadingForScope(scopeAtEntry, {
              promptTaskId: promptTid,
              videoTaskId: videoId
            })
            return
          }
          if (videoFollowOutcome.ok && core.matchesScope(scopeAtEntry)) {
            core.finishVideoBatchUi(promptTargets)
          }
          return
        }
        core.keepVideoBatchLoadingForScope(scopeAtEntry, {
          promptTaskId: promptTid,
          videoTaskId: videoTid
        })
        return
      }
      if (!promptOutcome.ok) {
        if (promptOutcome.chainFailed) {
          core.finalizePromptChainFailureUi({
            message: promptOutcome.message,
            scriptPanels: core.readLatestScriptPanels(liveScriptPanels),
            videoPanels: core.readLatestVideoPanels(liveVideoPanels),
            targetStoryboardIds: promptTargets,
            onPanelsUpdate: safeOnPanelsUpdate
          })
        } else {
          core.abortVideoBatchUi(promptTargets)
        }
      }
      return
    }
    if (videoTid != null) {
      liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
      liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
      const working = core.applyPanelsGeneratingToLocal(
        liveVideoPanels,
        liveScriptPanels,
        true
      )
      safeOnPanelsUpdate(working)
      const videoFollowOutcome = await videoFollow.followOngoingVideoGenerateTask(
        videoTid,
        restorePairs,
        safeOnPanelsUpdate,
        working.length ? working : liveVideoPanels
      )
      if (
        !videoFollowOutcome.ok &&
        shouldKeepVideoBatchLoadingAfterFollowMessage(videoFollowOutcome.message)
      ) {
        core.keepVideoBatchLoadingForScope(scopeAtEntry, {
          videoTaskId: videoTid
        })
        return
      }
      if (videoFollowOutcome.ok && core.matchesScope(scopeAtEntry)) {
        core.finishVideoBatchUi(batchTargetIds.length ? batchTargetIds : storyboardIds)
      }
      return
    }
    return
  }
  core.stopVideoBatchGeneration()
  return
}

if (pendingVideoTasks.length && !getStore().storyboardVideoBatchActiveVideoTaskId) {
  return
}

if (
  persistedGenerating.length &&
  getStore().isGeneratingStoryboardVideo &&
  !hasPersistedTaskId
) {
  if (gen !== state.resumeFollowGeneration) return
  liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
  liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
  core.applyImmediatePanelLoadingRestore(liveScriptPanels, liveVideoPanels, {
    skipScopeHydrate: true
  })
  const synced2 = core.syncPanelsGeneratingUi(liveScriptPanels, liveVideoPanels)
  if (synced2) safeOnPanelsUpdate(synced2)
  return
}

// 只剩弹窗持久化任务时，外层不续跟、不改卡片状态；打开弹窗后由其恢复。
return
}


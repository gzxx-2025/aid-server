import type { StoryboardPanel,StoryboardVideoPanel } from '~/types'
import { shouldKeepVideoBatchLoadingAfterFollowMessage } from '~/utils/storyboardImageBatchRestoreGate'
import type { StoryboardVideoBatchCore } from '~/utils/storyboardVideoBatchFollowCore'
import type { StoryboardVideoBatchPromptFollow } from '~/utils/storyboardVideoBatchPromptFollow'
import type { StoryboardVideoBatchState } from '~/utils/storyboardVideoBatchShared'
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

interface RestorePromptTargetInput extends RestoreSharedInput {
  ongoingPromptId: number
}

export async function restoreStoryboardVideoPromptTarget(
  input: RestorePromptTargetInput
): Promise<void> {
  const {
    state,
    core,
    promptFollow,
    videoFollow,
    ctx,
    gen,
    ongoingPromptId,
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

if (gen !== state.resumeFollowGeneration) return
if (!getStore().isGeneratingStoryboardVideo) {
  getStore().setGeneratingStoryboardVideo(true)
  getStore().setStoryboardVideoBatchError(null)
}

const batchTargetIds = core.getActiveBatchTargetIds()

// 提示词已在服务端完成时，仍需解析 chainChildTaskIds 再跟进出片 SSE
const promptAlreadyTerminal = await promptFollow.isPromptBatchTaskTerminal(
  ongoingPromptId
)
const promptTargets = batchTargetIds.length ? batchTargetIds : storyboardIds
const promptOutcome = await promptFollow.followPromptTask(ongoingPromptId, promptTargets)
if (gen !== state.resumeFollowGeneration) {
  core.keepVideoBatchLoadingForScope(scopeAtEntry, {
    promptTaskId: ongoingPromptId,
    videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
  })
  return
}

if (
  !promptOutcome.ok &&
  !promptOutcome.partial &&
  !promptOutcome.chainChildTaskIds?.length
) {
  if (shouldKeepVideoBatchLoadingAfterFollowMessage(promptOutcome.message)) {
    core.keepVideoBatchLoadingForScope(scopeAtEntry, {
      promptTaskId: ongoingPromptId,
      videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
    })
    return
  }
  if (promptOutcome.chainFailed) {
    liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
    liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
    core.finalizePromptChainFailureUi({
      message: promptOutcome.message,
      scriptPanels: liveScriptPanels,
      videoPanels: liveVideoPanels,
      targetStoryboardIds: promptTargets,
      onPanelsUpdate: safeOnPanelsUpdate
    })
    return
  }
  core.abortVideoBatchUi(batchTargetIds.length ? batchTargetIds : storyboardIds)
  return
}

const ongoingVideoId = promptOutcome.chainFailed
  ? null
  : await videoFollow.resolveOngoingVideoGenerateTaskId(
      ctx,
      getStore().storyboardVideoBatchActiveVideoTaskId
    )

// 提示词已终态且已拿到出片任务后，再清 prompt taskId
if (promptAlreadyTerminal && ongoingVideoId != null) {
  core.syncActivePromptTaskIdToStore(null)
}

const restoreTargetIds = batchTargetIds.length ? batchTargetIds : storyboardIds
let chainCoveredStoryboardIds: Set<number> | undefined
if (ongoingVideoId != null) {
  liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
  liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
  pairs = core.collectPairs(liveScriptPanels, liveVideoPanels)
  storyboardIds = pairs.map((p) => p.storyboardId)
  const working = core.applyPanelsGeneratingToLocal(
    liveVideoPanels,
    liveScriptPanels,
    true
  )
  safeOnPanelsUpdate(working)
  const videoFollowOutcome = await videoFollow.followOngoingVideoGenerateTask(
    ongoingVideoId,
    batchTargetIds.length > 0
      ? pairs.filter((p) => batchTargetIds.includes(p.storyboardId))
      : pairs,
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
  if (!videoFollowOutcome.ok) {
    if (shouldKeepVideoBatchLoadingAfterFollowMessage(videoFollowOutcome.message)) {
      core.keepVideoBatchLoadingForScope(scopeAtEntry, {
        promptTaskId: getStore().storyboardVideoBatchActivePromptTaskId,
        videoTaskId: ongoingVideoId
      })
      return
    }
    if (!core.matchesScope(scopeAtEntry)) {
      core.keepVideoBatchLoadingForScope(scopeAtEntry, { videoTaskId: ongoingVideoId })
      return
    }
    core.abortVideoBatchUi(restoreTargetIds)
    return
  }
} else if (
  promptOutcome.ok ||
  promptOutcome.partial ||
  promptOutcome.chainChildTaskIds?.length
) {
  // 出片任务尚未进 list：有 chain/分镜则继续；否则保活等待 syncReady 重试
  liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
  liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
  pairs = core.collectPairs(liveScriptPanels, liveVideoPanels)
  storyboardIds = pairs.map((p) => p.storyboardId)
  const liveRestorePairs =
    batchTargetIds.length > 0
      ? pairs.filter((p) => batchTargetIds.includes(p.storyboardId))
      : pairs
  const chainIds = promptOutcome.chainChildTaskIds || []
  if (!chainIds.length && !liveRestorePairs.length) {
    core.keepVideoBatchLoadingForScope(scopeAtEntry, {
      promptTaskId: ongoingPromptId,
      videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
    })
    return
  }
  let working = core.applyPanelsGeneratingToLocal(liveVideoPanels, liveScriptPanels, true)
  safeOnPanelsUpdate(working)
  const videoOutcome = await videoFollow.followVideoGenerateAfterPrompt(
    liveRestorePairs,
    safeOnPanelsUpdate,
    working.length ? working : liveVideoPanels,
    promptOutcome.chainChildTaskIds,
    { chainSubmissionFailed: promptOutcome.chainFailed }
  )
  chainCoveredStoryboardIds = videoOutcome.coveredStoryboardIds
  if (gen !== state.resumeFollowGeneration) {
    core.keepVideoBatchLoadingForScope(scopeAtEntry, {
      promptTaskId: ongoingPromptId,
      videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
    })
    return
  }
  if (
    !videoOutcome.ok &&
    shouldKeepVideoBatchLoadingAfterFollowMessage(videoOutcome.message)
  ) {
    core.keepVideoBatchLoadingForScope(scopeAtEntry, {
      promptTaskId: ongoingPromptId,
      videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
    })
    return
  }
  if (videoOutcome.ok) {
    if (promptAlreadyTerminal) {
      core.syncActivePromptTaskIdToStore(null)
    }
    working = await core.refreshPanelsAfterVideoBatch(
      liveRestorePairs,
      working.length ? working : liveVideoPanels,
      undefined,
      restoreTargetIds
    )
    safeOnPanelsUpdate(working)
  } else if (!videoOutcome.ok) {
    if (!core.matchesScope(scopeAtEntry)) {
      core.keepVideoBatchLoadingForScope(scopeAtEntry, {
        promptTaskId: ongoingPromptId
      })
      return
    }
    core.abortVideoBatchUi(restoreTargetIds)
    return
  }
} else {
  core.keepVideoBatchLoadingForScope(scopeAtEntry, {
    promptTaskId: ongoingPromptId,
    videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
  })
  return
}

if (!core.matchesScope(scopeAtEntry)) {
  core.keepVideoBatchLoadingForScope(scopeAtEntry, {
    promptTaskId: ongoingPromptId,
    videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
  })
  return
}

if (promptOutcome.chainFailed) {
  liveScriptPanels = core.readLatestScriptPanels(liveScriptPanels)
  liveVideoPanels = core.readLatestVideoPanels(liveVideoPanels)
  core.finalizePromptChainFailureUi({
    message: promptOutcome.message,
    scriptPanels: liveScriptPanels,
    videoPanels: liveVideoPanels,
    targetStoryboardIds: excludeCoveredStoryboardIds(
      restoreTargetIds,
      chainCoveredStoryboardIds
    ),
    onPanelsUpdate: safeOnPanelsUpdate
  })
  return
}

const finishIds = restoreTargetIds.length
  ? restoreTargetIds
  : core.getActiveBatchTargetIds().length
    ? core.getActiveBatchTargetIds()
    : storyboardIds
core.finishVideoBatchUi(finishIds)
return
}


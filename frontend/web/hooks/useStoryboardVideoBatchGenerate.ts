'use client'

/**
 * 分镜视频批量生成 hook（原 composables/useStoryboardVideoBatchGenerate.ts，4069 行拆分迁移）。
 * 拆分结构（闭包状态经 StoryboardVideoBatchState 显式共享，禁止另建平行状态）：
 * - utils/storyboardVideoBatchShared.ts       —— 模块级纯函数 / session 目标快照 / 卡片 loading 恢复
 * - utils/storyboardVideoBatchFollowCore.ts   —— 状态绑定核心助手（loading UI / 设主视频 / 进度回填）
 * - utils/storyboardVideoBatchPromptFollow.ts —— 提示词任务提交 / SSE 跟随链路
 * - utils/storyboardVideoBatchVideoFollow.ts  —— 出片任务 SSE 跟随 / 任务归属对齐
 * - utils/storyboardVideoBatchRestore.ts      —— 刷新/切集后的任务恢复编排
 * - utils/storyboardVideoBatchGlobalTasks.ts  —— 停止 / 全局任务面板事件
 * 原实现每组件各建一份实例（useRoute/Pinia 随组件生命周期），React 版用惰性 state 保持一致。
 */

import { useState } from 'react'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { shouldKeepVideoBatchLoadingAfterFollowMessage } from '~/utils/storyboardImageBatchRestoreGate'
import { excludeCoveredStoryboardIds } from '~/utils/storyboardVideoChainFailure'
import { createAsyncIdleBarrier } from '~/utils/asyncIdleBarrier'
import type { StoryboardPanel, StoryboardVideoPanel } from '~/types'
import {
  applyStoryboardVideoPanelUiFromStore,
  type StoryboardVideoBatchState
} from '~/utils/storyboardVideoBatchShared'
import { createStoryboardVideoBatchCore } from '~/utils/storyboardVideoBatchFollowCore'
import { createStoryboardVideoBatchPromptFollow } from '~/utils/storyboardVideoBatchPromptFollow'
import { createStoryboardVideoBatchVideoFollow } from '~/utils/storyboardVideoBatchVideoFollow'
import { createStoryboardVideoBatchRestore } from '~/utils/storyboardVideoBatchRestore'
import { createStoryboardVideoBatchGlobalTasks } from '~/utils/storyboardVideoBatchGlobalTasks'

export {
  applyStoryboardVideoPanelUiFromStore,
  applyStoryboardVideoImmediatePanelLoadingRestore,
  getActiveVideoBatchTargetIds
} from '~/utils/storyboardVideoBatchShared'

/** @deprecated 请改用 applyStoryboardVideoPanelUiFromStore */
export { applyStoryboardVideoPanelUiFromStore as applyStoryboardVideoPanelErrorsFromStore } from '~/utils/storyboardVideoBatchShared'

/** 弹窗内单条生视频后台续跟中的 storyboardId，避免与 EditStoryboardVideoModal 重复连 SSE */
export const activeStoryboardVideoModalRestoreFollowIds = new Set<number>()

/** 弹窗内正在跟进 SSE 的分镜（刷新/重开弹窗时由 EditStoryboardVideoModal 写入） */
export const activeStoryboardVideoModalOwnedFollowIds = new Set<number>()

export function isStoryboardVideoModalRestoreFollowing(storyboardId: number): boolean {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return false
  return activeStoryboardVideoModalRestoreFollowIds.has(sid)
}

export function isStoryboardVideoModalOwnedFollow(storyboardId: number): boolean {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return false
  return activeStoryboardVideoModalOwnedFollowIds.has(sid)
}

function createStoryboardVideoBatchGenerate() {
  const state: StoryboardVideoBatchState = {
    activePromptTaskId: { value: null },
    promptStreamCloser: null,
    stopRequested: false,
    manualPromptAgentModelPick: false,
    manualVideoModelPick: false,
    resumeFollowGeneration: 0,
    batchSseFollowInFlight: false,
    batchSseFollowDepth: 0,
    batchRunInFlight: false,
    restoreSessionInFlight: null,
    promptFollowOwner: null,
    videoFollowOwner: null,
    cachedProjectTaskList: null,
    followIdleBarrier: createAsyncIdleBarrier(() => core.isVideoBatchFollowBusy())
  }

  const core = createStoryboardVideoBatchCore(state)
  const promptFollow = createStoryboardVideoBatchPromptFollow(state, core)
  const videoFollow = createStoryboardVideoBatchVideoFollow(state, core)
  const restore = createStoryboardVideoBatchRestore(state, core, promptFollow, videoFollow)
  const globalTasks = createStoryboardVideoBatchGlobalTasks(
    state,
    core,
    promptFollow,
    videoFollow
  )
  const { getStore } = core

  async function runFullAutoGenerate(payload: {
    scriptPanels: StoryboardPanel[]
    videoPanels: StoryboardVideoPanel[]
    overwritePrompt: boolean
    manualPromptAgentModelPick?: boolean
    manualVideoModelPick?: boolean
    onPanelsUpdate: (panels: StoryboardVideoPanel[]) => void
  }): Promise<{ ok: boolean; message?: string }> {
    core.setManualPromptAgentModelPick(payload.manualPromptAgentModelPick === true)
    core.setManualVideoModelPick(payload.manualVideoModelPick === true)
    state.stopRequested = false
    const routeCtx = core.captureScope()

    const pairs = core.collectPairs(payload.scriptPanels, payload.videoPanels)
    if (!pairs.length) {
      return { ok: false, message: '分镜尚未保存到服务器，请先生成分镜脚本' }
    }

    const storyboardIds = pairs.map((p) => p.storyboardId)
    getStore().setGeneratingStoryboardVideo(true)
    getStore().setStoryboardVideoBatchError(null)
    getStore().setStoryboardVideoBatchProgress(0, storyboardIds.length)

    let working = payload.videoPanels.map((p) => ({ ...p }))

    const promptOutcome = await promptFollow.runBatchVideoPrompt(payload.overwritePrompt)
    if (!core.matchesScope(routeCtx)) {
      core.keepVideoBatchLoadingForScope(routeCtx)
      return { ok: false, message: '已切换作品，任务仍在后台进行' }
    }
    if (state.stopRequested) {
      working = working.map((p) => ({ ...p, generating: false }))
      payload.onPanelsUpdate(working)
      core.clearPanelGeneratingStatuses(storyboardIds)
      core.stopVideoBatchGeneration()
      return { ok: false, message: '已停止生成' }
    }
    if (!promptOutcome.ok) {
      if (shouldKeepVideoBatchLoadingAfterFollowMessage(promptOutcome.message)) {
        core.keepVideoBatchLoadingForScope(routeCtx, {
          promptTaskId: getStore().storyboardVideoBatchActivePromptTaskId,
          videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
        })
        return { ok: false, message: promptOutcome.message }
      }
      if (promptOutcome.chainFailed) {
        core.finalizePromptChainFailureUi({
          message: promptOutcome.message,
          scriptPanels: payload.scriptPanels,
          videoPanels: working,
          targetStoryboardIds: storyboardIds,
          onPanelsUpdate: payload.onPanelsUpdate
        })
        return { ok: false, message: promptOutcome.message || '视频提交失败' }
      }
      working = core.applyBatchFailureToLocalPanels(
        working,
        payload.scriptPanels,
        storyboardIds,
        promptOutcome.message || '视频提示词生成失败'
      )
      core.persistBatchTargetPanelErrors(
        pairs,
        promptOutcome.message || '视频提示词生成失败',
        storyboardIds
      )
      payload.onPanelsUpdate(working)
      getStore().setStoryboardVideoBatchError(promptOutcome.message || null)
      core.abortVideoBatchUi(storyboardIds)
      return { ok: false, message: promptOutcome.message || '视频提示词生成失败' }
    }

    if (state.stopRequested) {
      working = working.map((p) => ({ ...p, generating: false }))
      payload.onPanelsUpdate(working)
      core.clearPanelGeneratingStatuses(storyboardIds)
      core.stopVideoBatchGeneration()
      return { ok: false, message: '已停止生成' }
    }
    if (!core.matchesScope(routeCtx)) {
      core.keepVideoBatchLoadingForScope(routeCtx)
      return { ok: false, message: '已切换作品，任务仍在后台进行' }
    }

    const videoOutcome = await videoFollow.followVideoGenerateAfterPrompt(
      pairs,
      payload.onPanelsUpdate,
      working,
      promptOutcome.chainChildTaskIds,
      { chainSubmissionFailed: promptOutcome.chainFailed }
    )
    if (!core.matchesScope(routeCtx)) {
      core.keepVideoBatchLoadingForScope(routeCtx)
      return { ok: false, message: '已切换作品，任务仍在后台进行' }
    }

    if (!videoOutcome.ok) {
      if (shouldKeepVideoBatchLoadingAfterFollowMessage(videoOutcome.message)) {
        core.keepVideoBatchLoadingForScope(routeCtx, {
          promptTaskId: getStore().storyboardVideoBatchActivePromptTaskId,
          videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
        })
        return { ok: false, message: videoOutcome.message }
      }
      working = core.applyBatchFailureToLocalPanels(
        working,
        payload.scriptPanels,
        storyboardIds,
        videoOutcome.message || '视频生成失败'
      )
      core.persistBatchTargetPanelErrors(
        pairs,
        videoOutcome.message || '视频生成失败',
        storyboardIds
      )
      payload.onPanelsUpdate(working)
      getStore().setStoryboardVideoBatchError(videoOutcome.message || null)
      core.abortVideoBatchUi(storyboardIds)
      return { ok: false, message: videoOutcome.message || '视频生成失败' }
    }

    if (promptOutcome.chainFailed) {
      core.finalizePromptChainFailureUi({
        message: promptOutcome.message,
        scriptPanels: core.readLatestScriptPanels(payload.scriptPanels),
        videoPanels: core.readLatestVideoPanels(working),
        targetStoryboardIds: excludeCoveredStoryboardIds(
          storyboardIds,
          videoOutcome.coveredStoryboardIds
        ),
        onPanelsUpdate: payload.onPanelsUpdate
      })
      return { ok: false, message: promptOutcome.message || '视频提交失败' }
    }

    // 列表已在 followOngoingVideoGenerateTask 内用 SSE items / refresh 更新
    core.finishVideoBatchUi(storyboardIds)

    if (state.stopRequested) {
      return { ok: false, message: '已停止生成' }
    }
    if (videoOutcome.partial) {
      return { ok: false, message: '部分分镜视频生成失败，可点击重新生成重试' }
    }
    return { ok: true }
  }

  async function regenerateSinglePanel(payload: {
    scriptPanel: StoryboardPanel
    videoPanel: StoryboardVideoPanel
    panelIndex: number
    videoPanels: StoryboardVideoPanel[]
    manualVideoModelPick?: boolean
    onPanelsUpdate: (panels: StoryboardVideoPanel[]) => void
  }): Promise<{ ok: boolean; message?: string }> {
    const storyboardId = parseServerStoryboardId(payload.scriptPanel.id)
    if (storyboardId == null) {
      return { ok: false, message: '分镜尚未保存到服务器' }
    }

    const scriptPanels =
      (getStore().formData.storyboardScript.panels as StoryboardPanel[]) || []

    // 与列表批量生成一致：POST /api/user/storyboard/generate/video-with-prompt + SSE 跟进
    return runBatchVideosOnly({
      scriptPanels,
      videoPanels: payload.videoPanels,
      manualVideoModelPick: payload.manualVideoModelPick,
      overwrite: true,
      selectedStoryboardIds: [storyboardId],
      onPanelsUpdate: payload.onPanelsUpdate
    })
  }

  async function runBatchVideosOnly(payload: {
    scriptPanels: StoryboardPanel[]
    videoPanels: StoryboardVideoPanel[]
    manualVideoModelPick?: boolean
    selectedStoryboardIds?: number[]
    overwrite?: boolean
    onPanelsUpdate: (panels: StoryboardVideoPanel[]) => void
  }): Promise<{ ok: boolean; message?: string }> {
    core.setManualVideoModelPick(payload.manualVideoModelPick === true)
    state.stopRequested = false
    const routeCtx = core.captureScope()
    const generationAtEntry = state.resumeFollowGeneration
    core.beginBatchSseFollow()
    try {
      let pairs = core.collectPairs(payload.scriptPanels, payload.videoPanels)
      if (payload.selectedStoryboardIds?.length) {
        const selectedSet = new Set(payload.selectedStoryboardIds)
        pairs = pairs.filter((p) => selectedSet.has(p.storyboardId))
      }
      if (!pairs.length) {
        return { ok: false, message: '请选择要生成的分镜' }
      }

      const storyboardIds = pairs.map((p) => p.storyboardId)
      core.setVideoBatchTargetIds(storyboardIds)
      getStore().setGeneratingStoryboardVideo(true)
      getStore().setStoryboardVideoBatchError(null)
      getStore().setStoryboardVideoBatchProgress(0, storyboardIds.length)
      core.markPanelsGenerating(storyboardIds)

      let working = core.applyPanelsGeneratingToLocal(
        payload.videoPanels,
        payload.scriptPanels,
        true
      )
      payload.onPanelsUpdate(working)

      if (core.isVideoBatchOperationInterrupted(routeCtx, generationAtEntry)) {
        core.keepVideoBatchLoadingForScope(routeCtx)
        return { ok: false, message: '已切换作品，任务仍在后台进行' }
      }
      if (state.stopRequested) {
        working = working.map((p) => ({ ...p, generating: false }))
        payload.onPanelsUpdate(working)
        core.clearPanelGeneratingStatuses(storyboardIds)
        core.clearVideoBatchTargetIds()
        getStore().setGeneratingStoryboardVideo(false)
        return { ok: false, message: '已停止生成' }
      }

      const submitOutcome = await promptFollow.submitVideoWithPromptBatch({
        storyboardIds,
        overwrite: payload.overwrite,
        expectedScope: routeCtx,
        expectedGeneration: generationAtEntry
      })
      if (core.isVideoBatchOperationInterrupted(routeCtx, generationAtEntry)) {
        if (submitOutcome.taskId) {
          core.keepVideoBatchLoadingForScope(routeCtx, { promptTaskId: submitOutcome.taskId })
        }
        return { ok: false, message: '已切换作品，任务仍在后台进行' }
      }
      if (!submitOutcome.ok || !submitOutcome.taskId) {
        const errMsg = submitOutcome.message || '视频生成失败'
        core.persistBatchTargetPanelErrors(pairs, errMsg, storyboardIds)
        getStore().syncStep4PlusLiveGenToCurrentScope()
        core.clearVideoBatchTargetIds()
        getStore().setGeneratingStoryboardVideo(false)
        const failedPanels = core.applyBatchFailureToLocalPanels(
          working,
          payload.scriptPanels,
          storyboardIds,
          errMsg
        )
        payload.onPanelsUpdate(failedPanels)
        getStore().setStoryboardVideoBatchError(errMsg)
        getStore().clearStoryboardVideoBatchProgress()
        return { ok: false, message: errMsg }
      }

      if (state.stopRequested) return { ok: false, message: '已停止生成' }

      const promptOutcome = await promptFollow.followPromptTask(
        submitOutcome.taskId,
        storyboardIds,
        {
          progressTotalHint: submitOutcome.totalShots || storyboardIds.length
        }
      )
      if (core.isVideoBatchOperationInterrupted(routeCtx, generationAtEntry)) {
        core.keepVideoBatchLoadingForScope(routeCtx, {
          promptTaskId: submitOutcome.taskId,
          videoTaskId: promptOutcome.chainChildTaskIds?.[0]
        })
        return { ok: false, message: '已切换作品，任务仍在后台进行' }
      }
      if (state.stopRequested) {
        working = working.map((p) => ({ ...p, generating: false }))
        payload.onPanelsUpdate(working)
        core.clearPanelGeneratingStatuses(storyboardIds)
        core.stopVideoBatchGeneration()
        return { ok: false, message: '已停止生成' }
      }
      if (!promptOutcome.ok && !promptOutcome.partial && !promptOutcome.chainChildTaskIds?.length) {
        if (shouldKeepVideoBatchLoadingAfterFollowMessage(promptOutcome.message)) {
          core.keepVideoBatchLoadingForScope(routeCtx, {
            promptTaskId: submitOutcome.taskId,
            videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
          })
          return { ok: false, message: promptOutcome.message }
        }
        if (promptOutcome.chainFailed) {
          core.finalizePromptChainFailureUi({
            message: promptOutcome.message,
            scriptPanels: payload.scriptPanels,
            videoPanels: working,
            targetStoryboardIds: storyboardIds,
            onPanelsUpdate: payload.onPanelsUpdate
          })
          return { ok: false, message: promptOutcome.message || '视频提交失败' }
        }
        working = core.applyBatchFailureToLocalPanels(
          working,
          payload.scriptPanels,
          storyboardIds,
          promptOutcome.message || '视频提示词生成失败'
        )
        core.persistBatchTargetPanelErrors(
          pairs,
          promptOutcome.message || '视频提示词生成失败',
          storyboardIds
        )
        payload.onPanelsUpdate(working)
        getStore().setStoryboardVideoBatchError(promptOutcome.message || null)
        core.abortVideoBatchUi(storyboardIds)
        return { ok: false, message: promptOutcome.message || '视频提示词生成失败' }
      }

      const videoOutcome = await videoFollow.followVideoGenerateAfterPrompt(
        pairs,
        payload.onPanelsUpdate,
        working,
        promptOutcome.chainChildTaskIds,
        { chainSubmissionFailed: promptOutcome.chainFailed }
      )
      if (core.isVideoBatchOperationInterrupted(routeCtx, generationAtEntry)) {
        core.keepVideoBatchLoadingForScope(routeCtx, {
          promptTaskId: submitOutcome.taskId,
          videoTaskId: videoOutcome.taskId ?? promptOutcome.chainChildTaskIds?.[0]
        })
        return { ok: false, message: '已切换作品，任务仍在后台进行' }
      }

      if (!videoOutcome.ok) {
        if (shouldKeepVideoBatchLoadingAfterFollowMessage(videoOutcome.message)) {
          core.keepVideoBatchLoadingForScope(routeCtx, {
            promptTaskId: getStore().storyboardVideoBatchActivePromptTaskId,
            videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
          })
          return { ok: false, message: videoOutcome.message }
        }
        working = core.applyBatchFailureToLocalPanels(
          working,
          payload.scriptPanels,
          storyboardIds,
          videoOutcome.message || '视频生成失败'
        )
        core.persistBatchTargetPanelErrors(
          pairs,
          videoOutcome.message || '视频生成失败',
          storyboardIds
        )
        payload.onPanelsUpdate(working)
        getStore().setStoryboardVideoBatchError(videoOutcome.message || null)
        core.abortVideoBatchUi(storyboardIds)
        return { ok: false, message: videoOutcome.message || '视频生成失败' }
      }

      if (promptOutcome.chainFailed) {
        core.finalizePromptChainFailureUi({
          message: promptOutcome.message,
          scriptPanels: core.readLatestScriptPanels(payload.scriptPanels),
          videoPanels: core.readLatestVideoPanels(working),
          targetStoryboardIds: excludeCoveredStoryboardIds(
            storyboardIds,
            videoOutcome.coveredStoryboardIds
          ),
          onPanelsUpdate: payload.onPanelsUpdate
        })
        return { ok: false, message: promptOutcome.message || '视频提交失败' }
      }

      // 列表已在 followOngoingVideoGenerateTask 内用 SSE items / refresh 更新，避免用过期 working 覆盖成功视频
      core.finishVideoBatchUi(storyboardIds)

      if (state.stopRequested) {
        return { ok: false, message: '已停止生成' }
      }
      if (videoOutcome.partial) {
        return { ok: false, message: '部分分镜视频生成失败，可点击重新生成重试' }
      }
      return { ok: true }
    } finally {
      core.endBatchSseFollow()
      core.notifyGlobalTasksUpdatedOnce()
    }
  }

  function isBatchFollowInFlight(): boolean {
    return core.isVideoBatchFollowBusy()
  }

  return {
    activePromptTaskId: state.activePromptTaskId,
    setManualPromptAgentModelPick: core.setManualPromptAgentModelPick,
    setManualVideoModelPick: core.setManualVideoModelPick,
    runFullAutoGenerate,
    runBatchVideosOnly,
    buildVideoWithPromptSubmitBody: promptFollow.buildVideoWithPromptSubmitBody,
    regenerateSinglePanel,
    requestStop: globalTasks.requestStop,
    restoreOngoingBatchIfNeeded: restore.restoreOngoingBatchIfNeeded,
    applyImmediatePanelLoadingRestore: core.applyImmediatePanelLoadingRestore,
    applyStoryboardVideoPanelUiFromStore,
    syncPanelsGeneratingUi: core.syncPanelsGeneratingUi,
    onGlobalStopTask: globalTasks.onGlobalStopTask,
    onGlobalTrackTask: globalTasks.onGlobalTrackTask,
    onGlobalResumeTask: globalTasks.onGlobalResumeTask,
    cancelResumeFollow: globalTasks.cancelResumeFollow,
    waitForFollowIdle: state.followIdleBarrier.waitForIdle,
    isBatchFollowInFlight
  }
}

/** 供组件在渲染外（模块级）判定使用 */
export type StoryboardVideoBatchGenerate = ReturnType<typeof createStoryboardVideoBatchGenerate>

export function useStoryboardVideoBatchGenerate(): StoryboardVideoBatchGenerate {
  // 原 Vue 每个组件 setup 各创建一份实例；React 侧用惰性 state 保证跨渲染稳定
  const [instance] = useState(() => createStoryboardVideoBatchGenerate())
  return instance
}

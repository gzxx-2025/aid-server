/**
 * 分镜视频批量生成：停止 / 全局任务面板事件链路（原 composables/useStoryboardVideoBatchGenerate.ts
 * requestStop → onGlobalResumeTask 段拆分；恢复编排见 utils/storyboardVideoBatchRestore.ts）。
 */

import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import { requestCancelUserTaskById } from '~/utils/userTaskCancelFlow'
import { resumeUserTask } from '~/utils/taskPartialFailed'
import { excludeCoveredStoryboardIds } from '~/utils/storyboardVideoChainFailure'
import type { StoryboardPanel, StoryboardVideoPanel } from '~/types'
import {
  isStoryboardVideoGenerateTaskType,
  isStoryboardVideoPromptBatchTask,
  parseVideoBatchTaskId as parseTaskId,
  resolveModalVideoRestoreEntriesForTaskId,
  videoBatchBizErr as bizErr,
  type StoryboardVideoBatchState
} from '~/utils/storyboardVideoBatchShared'
import type { StoryboardVideoBatchCore } from '~/utils/storyboardVideoBatchFollowCore'
import type { StoryboardVideoBatchPromptFollow } from '~/utils/storyboardVideoBatchPromptFollow'
import type { StoryboardVideoBatchVideoFollow } from '~/utils/storyboardVideoBatchVideoFollow'

export type StoryboardVideoBatchGlobalTasks = ReturnType<
  typeof createStoryboardVideoBatchGlobalTasks
>

export function createStoryboardVideoBatchGlobalTasks(
  state: StoryboardVideoBatchState,
  core: StoryboardVideoBatchCore,
  promptFollow: StoryboardVideoBatchPromptFollow,
  videoFollow: StoryboardVideoBatchVideoFollow
) {
  const { getStore } = core

  async function requestStop(options?: { alreadyRequestedTaskId?: number | null }) {
    state.stopRequested = true
    core.closePromptStream()
    const promptTaskId =
      state.activePromptTaskId.value ?? getStore().storyboardVideoBatchActivePromptTaskId
    const videoTaskId =
      state.videoFollowOwner?.taskId ?? getStore().storyboardVideoBatchActiveVideoTaskId
    const taskIds = [promptTaskId, videoTaskId]
      .map((id) => parseTaskId(id))
      .filter((id): id is number => id != null)
    let requestedCount = options?.alreadyRequestedTaskId ? 1 : 0
    let attemptedCount = 0
    for (const taskId of [...new Set(taskIds)]) {
      if (taskId === options?.alreadyRequestedTaskId) continue
      attemptedCount++
      try {
        await requestCancelUserTaskById(taskId)
        requestedCount++
      } catch {
        /* ignore */
      }
    }
    core.syncActivePromptTaskIdToStore(null)
    core.syncActiveVideoTaskIdToStore(null)
    core.stopVideoBatchGeneration()
    return { requested: requestedCount > 0, attemptedCount }
  }

  function onGlobalStopTask(event: Event) {
    const detail = (
      event as CustomEvent<{
        taskId?: number
        taskType?: string | null
        remoteCancelRequested?: boolean
      }>
    ).detail
    const id = parseTaskId(detail?.taskId)
    if (!id) return
    if (
      !isStoryboardVideoPromptBatchTask(detail?.taskType) &&
      !isStoryboardVideoGenerateTaskType(detail?.taskType) &&
      state.activePromptTaskId.value !== id &&
      getStore().storyboardVideoBatchActivePromptTaskId !== id &&
      getStore().storyboardVideoBatchActiveVideoTaskId !== id
    ) {
      return
    }
    // 任务中心请求成功才跳过该 taskId；网络失败时允许本页链路补一次真实取消请求。
    void requestStop({
      alreadyRequestedTaskId: detail?.remoteCancelRequested ? id : null
    })
  }

  function onGlobalTrackTask(
    event: Event,
    scriptPanels: StoryboardPanel[],
    videoPanels: StoryboardVideoPanel[],
    onPanelsUpdate: (panels: StoryboardVideoPanel[]) => void,
    onDone?: (result: { ok: boolean; message?: string }) => void
  ) {
    const detail = (event as CustomEvent<{ taskId?: number; taskType?: string | null }>).detail
    const ty = String(detail?.taskType ?? '')
      .trim()
      .toLowerCase()
      .replace(/-/g, '_')
    const id = parseTaskId(detail?.taskId)
    if (!id) return

    const pairs = core.collectPairs(scriptPanels, videoPanels)
    const batchTargetIds = core.getActiveBatchTargetIds()
    const storyboardIds = batchTargetIds.length ? batchTargetIds : pairs.map((p) => p.storyboardId)
    const effectivePairs =
      batchTargetIds.length > 0
        ? pairs.filter((p) => batchTargetIds.includes(p.storyboardId))
        : pairs

    if (ty === 'storyboard_video_prompt_batch') {
      void (async () => {
        if (batchTargetIds.length) {
          core.setVideoBatchTargetIds(batchTargetIds)
        }
        const promptOutcome = await promptFollow.followPromptTask(id, storyboardIds)
        if (promptOutcome.chainFailed && !promptOutcome.chainChildTaskIds?.length) {
          core.finalizePromptChainFailureUi({
            message: promptOutcome.message,
            scriptPanels,
            videoPanels,
            targetStoryboardIds: storyboardIds,
            onPanelsUpdate
          })
          onDone?.({ ok: false, message: promptOutcome.message })
          return
        }
        if (promptOutcome.ok || promptOutcome.partial || promptOutcome.chainChildTaskIds?.length) {
          let working = core.applyPanelsGeneratingToLocal(videoPanels, scriptPanels, true)
          onPanelsUpdate(working)
          const videoOutcome = await videoFollow.followVideoGenerateAfterPrompt(
            effectivePairs,
            onPanelsUpdate,
            working,
            promptOutcome.chainChildTaskIds,
            { chainSubmissionFailed: promptOutcome.chainFailed }
          )
          if (videoOutcome.ok) {
            working = await core.refreshPanelsAfterVideoBatch(
              effectivePairs,
              working,
              undefined,
              storyboardIds
            )
            onPanelsUpdate(working)
            if (promptOutcome.chainFailed) {
              core.finalizePromptChainFailureUi({
                message: promptOutcome.message,
                scriptPanels,
                videoPanels: working,
                targetStoryboardIds: excludeCoveredStoryboardIds(
                  storyboardIds,
                  videoOutcome.coveredStoryboardIds
                ),
                onPanelsUpdate
              })
              onDone?.({ ok: false, message: promptOutcome.message })
              return
            }
            core.finishVideoBatchUi(storyboardIds)
            onDone?.({ ok: true })
            return
          }
        }
        core.abortVideoBatchUi(storyboardIds)
        onDone?.({ ok: false, message: promptOutcome.message })
      })()
      return
    }

    if (ty === 'storyboard_video_generate') {
      void (async () => {
        if (!videoFollow.shouldRestoreAsListBatchVideoTask(id)) {
          const entries = resolveModalVideoRestoreEntriesForTaskId(
            id,
            pairs,
            getStore(),
            core.getRoute()
          )
          if (!entries.length) {
            onDone?.({ ok: false, message: '无法定位进行中的分镜视频任务' })
            return
          }
          onDone?.({ ok: true })
          return
        }

        if (getStore().storyboardVideoBatchActiveVideoTaskId !== id) {
          core.syncActiveVideoTaskIdToStore(id)
        }
        core.applyImmediatePanelLoadingRestore(scriptPanels, videoPanels)
        const working = core.applyPanelsGeneratingToLocal(videoPanels, scriptPanels, true)
        onPanelsUpdate(working)
        const outcome = await videoFollow.followOngoingVideoGenerateTask(
          id,
          effectivePairs,
          onPanelsUpdate,
          working
        )
        if (outcome.ok) {
          core.finishVideoBatchUi(storyboardIds)
        } else {
          core.abortVideoBatchUi(storyboardIds)
        }
        onDone?.({
          ok: outcome.ok,
          message: outcome.message
        })
      })()
    }
  }

  function onGlobalResumeTask(
    event: Event,
    scriptPanels: StoryboardPanel[],
    videoPanels: StoryboardVideoPanel[],
    onPanelsUpdate: (panels: StoryboardVideoPanel[]) => void,
    onDone?: (result: { ok: boolean; message?: string }) => void
  ) {
    const detail = (event as CustomEvent<{ taskId?: number; taskType?: string | null }>).detail
    const ty = String(detail?.taskType ?? '')
      .trim()
      .toLowerCase()
      .replace(/-/g, '_')
    const id = parseTaskId(detail?.taskId)
    if (!id) return

    const pairs = core.collectPairs(scriptPanels, videoPanels)
    const batchTargetIds = core.getActiveBatchTargetIds()
    const storyboardIds = batchTargetIds.length ? batchTargetIds : pairs.map((p) => p.storyboardId)
    const effectivePairs =
      batchTargetIds.length > 0
        ? pairs.filter((p) => batchTargetIds.includes(p.storyboardId))
        : pairs

    if (ty === 'storyboard_video_prompt_batch') {
      void (async () => {
        core.beginBatchSseFollow()
        try {
          try {
            await resumeUserTask(id, 'storyboard_video_prompt_batch')
            getStore().removePausedTaskFollow(id)
            if (typeof window !== 'undefined') {
              window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
            }
            if (batchTargetIds.length) {
              core.setVideoBatchTargetIds(batchTargetIds)
            }
            const promptOutcome = await promptFollow.followPromptTask(id, storyboardIds)
            if (promptOutcome.chainFailed && !promptOutcome.chainChildTaskIds?.length) {
              core.finalizePromptChainFailureUi({
                message: promptOutcome.message,
                scriptPanels,
                videoPanels,
                targetStoryboardIds: storyboardIds,
                onPanelsUpdate
              })
              onDone?.({ ok: false, message: promptOutcome.message })
              return
            }
            if (
              promptOutcome.ok ||
              promptOutcome.partial ||
              promptOutcome.chainChildTaskIds?.length
            ) {
              let working = core.applyPanelsGeneratingToLocal(videoPanels, scriptPanels, true)
              onPanelsUpdate(working)
              const videoOutcome = await videoFollow.followVideoGenerateAfterPrompt(
                effectivePairs,
                onPanelsUpdate,
                working,
                promptOutcome.chainChildTaskIds,
                { chainSubmissionFailed: promptOutcome.chainFailed }
              )
              if (videoOutcome.ok) {
                working = await core.refreshPanelsAfterVideoBatch(
                  effectivePairs,
                  working,
                  undefined,
                  storyboardIds
                )
                onPanelsUpdate(working)
                if (promptOutcome.chainFailed) {
                  core.finalizePromptChainFailureUi({
                    message: promptOutcome.message,
                    scriptPanels,
                    videoPanels: working,
                    targetStoryboardIds: excludeCoveredStoryboardIds(
                      storyboardIds,
                      videoOutcome.coveredStoryboardIds
                    ),
                    onPanelsUpdate
                  })
                  onDone?.({ ok: false, message: promptOutcome.message })
                  return
                }
                core.finishVideoBatchUi(storyboardIds)
                onDone?.({
                  ok: true,
                  message: promptOutcome.partial ? promptOutcome.message : undefined
                })
                return
              }
            }
            core.abortVideoBatchUi(storyboardIds)
            onDone?.({ ok: false, message: promptOutcome.message })
          } catch (e: unknown) {
            core.abortVideoBatchUi(storyboardIds)
            onDone?.({ ok: false, message: bizErr(e) })
          }
        } finally {
          core.endBatchSseFollow()
        }
      })()
      return
    }

    if (ty === 'storyboard_video_generate') {
      void (async () => {
        core.beginBatchSseFollow()
        try {
          try {
            await resumeUserTask(id, 'storyboard_video_generate')
            getStore().removePausedTaskFollow(id)
            if (typeof window !== 'undefined') {
              window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
            }
            core.applyImmediatePanelLoadingRestore(scriptPanels, videoPanels)
            const working = core.applyPanelsGeneratingToLocal(videoPanels, scriptPanels, true)
            onPanelsUpdate(working)
            const outcome = await videoFollow.followOngoingVideoGenerateTask(
              id,
              effectivePairs,
              onPanelsUpdate,
              working
            )
            if (outcome.ok) {
              core.finishVideoBatchUi(storyboardIds)
            } else {
              core.abortVideoBatchUi(storyboardIds)
            }
            onDone?.({ ok: outcome.ok, message: outcome.message })
          } catch (e: unknown) {
            core.abortVideoBatchUi(storyboardIds)
            onDone?.({ ok: false, message: bizErr(e) })
          }
        } finally {
          core.endBatchSseFollow()
        }
      })()
    }
  }

  function cancelResumeFollow(): Promise<void> {
    state.resumeFollowGeneration++
    core.closePromptStream()
    const videoTaskId =
      state.videoFollowOwner?.taskId ?? getStore().storyboardVideoBatchActiveVideoTaskId
    if (videoTaskId != null) suspendTaskSseFollow(videoTaskId)
    return state.followIdleBarrier.waitForIdle()
  }

  return {
    requestStop,
    onGlobalStopTask,
    onGlobalTrackTask,
    onGlobalResumeTask,
    cancelResumeFollow
  }
}

import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import {
  fetchUserTaskDetailOnce,
  normalizeTaskStatus,
  resolveUserTaskTerminalOutcome
} from '~/composables/useTaskSseFollow'
import { followStoryboardImageBatchGenerateTask } from '~/composables/useStoryboardImageGenerateTask'
import {
  captureCreationLiveGenScope,
  matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
  buildImageBatchScopePreserveOnContextSwitch
} from '~/utils/storyboardImageBatchRestoreGate'
import {
  isNavigationOrSuspendBatchMessage,
  isTaskBackgroundRunningMessage,
  shouldKeepImageBatchLoadingAfterFollowMessage
} from '~/utils/taskSseSilentDisconnect'
import {
  TASK_BACKGROUND_RUNNING_MESSAGE,
  imageBatchSleep,
  isOngoingImageBatchTaskStatus,
  parseImageBatchTaskId,
  type StoryboardImageBatchFollowResult,
  type StoryboardImageBatchState
} from '~/utils/storyboardImageBatchShared'
import { pickOngoingStoryboardImageGenerateTask } from '~/utils/storyboardImagePromptTaskPicker'
import type { StoryboardImageBatchCore } from '~/utils/storyboardImageBatchFollowCore'
import type { ProjectEpisodeContext } from '~/utils/storyboardRecordBatch'
import type { StoryboardPanel } from '~/types'
import type { UserTaskRow } from '~/types/business-api'

export function createStoryboardImageGenerateFollow(
  state: StoryboardImageBatchState,
  core: StoryboardImageBatchCore
) {
  const { getStore } = core

  const pickOngoingImageGenerateTask = (
    tasks: Parameters<typeof pickOngoingStoryboardImageGenerateTask>[0],
    preferredTaskId?: number | null
  ) =>
    pickOngoingStoryboardImageGenerateTask(
      tasks,
      preferredTaskId,
      (taskId) => core.isModalOwnedStoryboardImageTaskId(taskId)
    )

  const resolveOngoingImageGenerateTaskId = async (
    context: ProjectEpisodeContext,
    preferredImageId?: number | null
  ): Promise<number | null> => {
    const preferred = parseImageBatchTaskId(
      preferredImageId ?? getStore().storyboardImageBatchActiveImageTaskId
    )
    for (let attempt = 0; attempt < 4; attempt += 1) {
      if (preferred != null) {
        const detail = await fetchUserTaskDetailOnce(preferred)
        if (detail && isOngoingImageBatchTaskStatus(normalizeTaskStatus(detail.status))) {
          return preferred
        }
      }
      let tasks: UserTaskRow[] = []
      let taskListOk = true
      try {
        tasks = await core.fetchRecentProjectTasks(context.projectId)
      } catch {
        taskListOk = false
      }
      const hitId = parseImageBatchTaskId(pickOngoingImageGenerateTask(tasks, preferred)?.id)
      if (hitId != null) return hitId
      if (preferred != null && taskListOk) {
        const detail = await fetchUserTaskDetailOnce(preferred)
        if (detail && isOngoingImageBatchTaskStatus(normalizeTaskStatus(detail.status))) {
          return preferred
        }
      }
      if (attempt < 3) await imageBatchSleep(800)
    }
    return null
  }

  const followOwned = async (
    taskId: number,
    storyboardIds: number[],
    targets: number[]
  ): Promise<StoryboardImageBatchFollowResult> => {
    const routeContext = captureCreationLiveGenScope()
    const total = Math.max(targets.length, storyboardIds.length, 1)
    core.beginBatchSseFollow()
    core.ensureImageBatchLoadingUi(targets)
    core.syncActiveImageTaskIdToStore(taskId)
    if (!getStore().storyboardImageBatchProgress.total) {
      getStore().setStoryboardImageBatchProgress(0, total)
    }
    try {
      await core.seedProgressFromTaskDetail(taskId, total)
      let result: Awaited<ReturnType<typeof followStoryboardImageBatchGenerateTask>>
      const terminal = await resolveUserTaskTerminalOutcome(taskId)
      if (terminal.kind === 'succeeded') result = { ok: true, taskId }
      else if (terminal.kind === 'partial_failed') result = { ok: true, taskId, partial: true }
      else if (terminal.kind === 'cancelled') {
        result = { ok: false, errorMessage: terminal.message || '任务已取消' }
      } else if (terminal.kind === 'failed') {
        result = { ok: false, errorMessage: terminal.errorMessage || '分镜图生成失败' }
      } else {
        result = await followStoryboardImageBatchGenerateTask({
          taskId,
          onProgress: (progress) => {
            if (!matchesCreationLiveGenScope(routeContext)) return
            core.applySseProgress({
              progress: progress.percent,
              message: progress.message,
              stepTitle: progress.stepTitle
            })
          }
        })
      }

      if (!matchesCreationLiveGenScope(routeContext)) {
        getStore().mergeStep4PlusLiveGenForScopeKey(
          routeContext.scopeKey,
          buildImageBatchScopePreserveOnContextSwitch({
            promptTaskId: getStore().storyboardImageBatchActiveTaskId,
            imageTaskId: taskId
          })
        )
        return { ok: false, message: '已切换作品，任务仍在后台进行' }
      }
      const resultMessage = 'errorMessage' in result ? result.errorMessage : '分镜图生成失败'
      const deferred = 'deferred' in result && Boolean(result.deferred)
      if (
        !result.ok &&
        (deferred ||
          isTaskBackgroundRunningMessage(resultMessage) ||
          shouldKeepImageBatchLoadingAfterFollowMessage(resultMessage))
      ) {
        core.ensureImageBatchLoadingUi(targets)
        return {
          ok: false,
          message: isNavigationOrSuspendBatchMessage(resultMessage)
            ? TASK_BACKGROUND_RUNNING_MESSAGE
            : resultMessage
        }
      }
      core.syncActiveImageTaskIdToStore(null)
      if (!result.ok) {
        core.clearPanelGeneratingStatuses(storyboardIds)
        return { ok: false, message: resultMessage }
      }
      const context = await resolveStoryScriptSaveContext(getStore(), getRouteLikeSnapshot())
      let panels: StoryboardPanel[] | undefined
      if (context) panels = await core.finalizeBatchPanelsAfterImageGen(context, targets)
      getStore().setStoryboardImageBatchProgress(total, total)
      return { ok: true, partial: Boolean(result.partial), panels }
    } finally {
      core.endBatchSseFollow()
    }
  }

  const followOngoingImageGenerateTask = async (
    taskId: number,
    storyboardIds: number[],
    targets: number[]
  ): Promise<StoryboardImageBatchFollowResult> => {
    while (state.imageFollowInFlight) {
      if (state.imageFollowInFlight.taskId === taskId) return state.imageFollowInFlight.promise
      try {
        await state.imageFollowInFlight.promise
      } catch {
        // 前一 owner 释放后再抢占新的子任务。
      }
    }
    const promise = followOwned(taskId, storyboardIds, targets)
    const owner = { taskId, promise }
    state.imageFollowInFlight = owner
    try {
      return await promise
    } finally {
      if (state.imageFollowInFlight === owner) state.imageFollowInFlight = null
      state.followIdleBarrier.notifyStateChange()
    }
  }

  return {
    pickOngoingImageGenerateTask,
    resolveOngoingImageGenerateTaskId,
    followOngoingImageGenerateTask
  }
}

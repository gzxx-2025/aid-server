/**
 * 分镜视频批量生成：出片任务 SSE 跟随 / 任务归属对齐链路（原 composables/useStoryboardVideoBatchGenerate.ts
 * followVideoGenerateAfterPrompt → reconcileOngoingVideoGenerationTasks 段拆分；
 * 恢复编排见 utils/storyboardVideoBatchRestore.ts）。
 */

import type { CreationLiveGenScopeCtx } from '~/composables/useCreationLiveGenScopeGuard'
import {
collectStoryboardVideoGenTaskEntriesInScopes,
resolveStoryboardVideoGenEntriesByTaskId
} from '~/composables/useCreationStoreHydration'
import type { UserTaskRow } from '~/types/business-api'
import { userTaskDetailCached } from '~/utils/businessApi'
import { modalGenSessionScopeFromStore } from '~/utils/modalGenSessionScope'
import { discoverOngoingStoryboardGenerationTasks } from '~/utils/storyboardGenerationTaskDiscovery'
import type { StoryboardVideoBatchCore } from '~/utils/storyboardVideoBatchFollowCore'
import {
parseVideoBatchTaskId as parseTaskId,
toModalVideoRestoreEntries,
type ModalVideoRestoreEntry,
type StoryboardVideoBatchState,
type StoryboardVideoPair
} from '~/utils/storyboardVideoBatchShared'
import { readStoryboardVideoModalGenSession } from '~/utils/storyboardVideoModalGenSession'
export function createStoryboardVideoTaskOwnershipOps(
  state: StoryboardVideoBatchState,
  core: StoryboardVideoBatchCore
) {
  const { getStore } = core

  function isBatchVideoGenerateTaskId(taskId: number): boolean {
    const batchId = getStore().storyboardVideoBatchActiveVideoTaskId
    return batchId != null && batchId === taskId
  }

  function isPersistedModalVideoGenerateTaskId(taskId: number): boolean {
    const tid = Number(taskId)
    if (!Number.isFinite(tid) || tid <= 0) return false
    if (resolveStoryboardVideoGenEntriesByTaskId(getStore(), tid, core.getRoute()).length > 0) {
      return true
    }
    const session = readStoryboardVideoModalGenSession(modalGenSessionScopeFromStore(getStore()))
    return Number(session?.taskId) === tid && Number(session?.storyboardId) > 0
  }

  /** Persisted batch target ownership prevents the outer list from clearing card loading. */
  function shouldRestoreAsListBatchVideoTask(taskId: number): boolean {
    if (isPersistedModalVideoGenerateTaskId(taskId)) return false
    if (isBatchVideoGenerateTaskId(taskId)) return true
    if (getStore().isGeneratingStoryboardVideo) return true
    const batchTargets = core.getActiveBatchTargetIds()
    if (batchTargets.length) return true
    if (
      Object.values(getStore().storyboardPanelVideoGenStatusByStoryboardId || {}).some(
        (s) => s === 'generating'
      )
    ) {
      return true
    }
    return false
  }

  function getPendingModalVideoTaskEntries(): ModalVideoRestoreEntry[] {
    return toModalVideoRestoreEntries(
      collectStoryboardVideoGenTaskEntriesInScopes(getStore(), core.getRoute())
    )
  }

  /**
   * Rebuild task ownership from authoritative task snapshots.
   * The task list intentionally omits inputSnapshot, so every ongoing generation task must be
   * inspected before the outer batch owner decides which single SSE it should follow.
   */
  async function reconcileOngoingVideoGenerationTasks(
    tasks: UserTaskRow[],
    pairs: StoryboardVideoPair[],
    scopeAtEntry: CreationLiveGenScopeCtx
  ): Promise<void> {
    const currentStoryboardIds = new Set(pairs.map((pair) => pair.storyboardId))
    if (!currentStoryboardIds.size) return

    const knownModalTaskIds = new Set(
      getPendingModalVideoTaskEntries().map((entry) => Number(entry[1].taskId))
    )
    for (const task of tasks) {
      const taskId = Number(task?.id)
      if (Number.isFinite(taskId) && taskId > 0 && isPersistedModalVideoGenerateTaskId(taskId)) {
        knownModalTaskIds.add(taskId)
      }
    }
    const knownBatchTaskIds = new Set<number>()
    const activeBatchTaskId = parseTaskId(getStore().storyboardVideoBatchActiveVideoTaskId)
    if (activeBatchTaskId != null) knownBatchTaskIds.add(activeBatchTaskId)

    const discovered = await discoverOngoingStoryboardGenerationTasks({
      rows: tasks,
      media: 'video',
      loadDetail: (taskId) => userTaskDetailCached(taskId),
      knownBatchTaskIds,
      knownModalTaskIds
    })
    if (!core.matchesScope(scopeAtEntry)) return

    const visible = discovered
      .map((task) => ({
        ...task,
        storyboardIds: task.storyboardIds.filter((id) => currentStoryboardIds.has(id))
      }))
      .filter((task) => task.storyboardIds.length > 0)

    const restoredStoryboardIds = new Set<number>()
    for (const task of visible.filter((item) => item.owner === 'modal')) {
      for (const storyboardId of task.storyboardIds) {
        if (restoredStoryboardIds.has(storyboardId)) continue
        restoredStoryboardIds.add(storyboardId)
        const pair = pairs.find((item) => item.storyboardId === storyboardId)
        const existing = getStore().getStoryboardVideoGenTask(
          storyboardId,
          scopeAtEntry.scopeKey
        )
        if (existing && existing.taskId > task.taskId) continue
        getStore().setStoryboardVideoGenTask(
          storyboardId,
          {
            taskId: task.taskId,
            sceneIdx: pair?.index ?? 0,
            taskKind: task.videoTaskKind
          },
          scopeAtEntry.scopeKey
        )
      }
    }

    const activeDescriptor = visible.find((task) => task.taskId === activeBatchTaskId)
    const batchTasks = visible.filter((task) => task.owner === 'batch')
    const batchTask =
      batchTasks.find((task) => task.taskId === activeBatchTaskId) ?? batchTasks[0] ?? null

    if (batchTask) {
      if (getStore().storyboardVideoBatchActiveVideoTaskId !== batchTask.taskId) {
        core.syncActiveVideoTaskIdToStore(batchTask.taskId)
      }
      core.setVideoBatchTargetIds(batchTask.storyboardIds)
      getStore().setGeneratingStoryboardVideo(true)
      getStore().setStoryboardVideoBatchError(null)
      core.markPanelsGenerating(batchTask.storyboardIds)
    } else if (
      activeDescriptor?.owner === 'modal' &&
      getStore().storyboardVideoBatchActivePromptTaskId == null
    ) {
      // Undo an old list-only restore that accidentally claimed a modal task as the batch owner.
      core.syncActiveVideoTaskIdToStore(null)
      getStore().setGeneratingStoryboardVideo(false)
      core.clearVideoBatchTargetIds()
    }
  }
  return {
    isBatchVideoGenerateTaskId,
    isPersistedModalVideoGenerateTaskId,
    shouldRestoreAsListBatchVideoTask,
    getPendingModalVideoTaskEntries,
    reconcileOngoingVideoGenerationTasks,
  }
}

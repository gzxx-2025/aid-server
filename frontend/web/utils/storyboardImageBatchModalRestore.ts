import { useCreationStore } from '~/stores/creation'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { userTaskDetailCached } from '~/utils/businessApi'
import { matchesCreationLiveGenScope, type CreationLiveGenScopeCtx } from '~/composables/useCreationLiveGenScopeGuard'
import { resolveCurrentStep4LiveGenScopeBlobs } from '~/composables/useCreationStoreHydration'
import { readModalImageGenSession } from '~/utils/storyboardImageModalGenSession'
import { modalGenSessionScopeFromStore } from '~/utils/modalGenSessionScope'
import {
  discoverOngoingStoryboardGenerationTasks,
  discoverOngoingStoryboardModalImageTasks
} from '~/utils/storyboardGenerationTaskDiscovery'
import { parseImageBatchTaskId } from '~/utils/storyboardImageBatchShared'
import type { StoryboardPanel } from '~/types'
import type { UserTaskRow } from '~/types/business-api'

type CreationStore = ReturnType<typeof useCreationStore.getState>

export function createStoryboardImageBatchModalRestore(opts: {
  getStore: () => CreationStore
  syncActiveImageTaskIdToStore: (taskId: number | null) => void
  setImageBatchTargetIds: (storyboardIds: number[]) => void
  clearImageBatchTargetIds: () => void
}) {
  const { getStore, syncActiveImageTaskIdToStore, setImageBatchTargetIds, clearImageBatchTargetIds } =
    opts

  const isModalOwnedStoryboardImageTaskId = (taskId: number) => {
    const id = Number(taskId)
    if (!Number.isFinite(id) || id <= 0) return false
    for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(getStore(), getRouteLikeSnapshot())) {
      for (const [storyboardId, snapshot] of Object.entries(
        blob.storyboardImageGenTasksByStoryboardId || {}
      )) {
        if (Number(snapshot?.taskId) !== id) continue
        if (Number(storyboardId) > 0) return true
      }
    }
    const session = readModalImageGenSession(modalGenSessionScopeFromStore(getStore()))
    return Number(session?.taskId) === id && session?.storyboardId != null
  }

  const resolveModalImageTaskImageIndex = (
    panel: StoryboardPanel | undefined,
    task: { sourceRecordId: number | null; referenceImageUrl: string | null }
  ) => {
    const images = Array.isArray(panel?.images) ? panel.images : []
    if (!images.length) return 0
    if (task.sourceRecordId != null) {
      const index = images.findIndex((image) => {
        const recordId = Number(
          image?._serverRow?.id ??
            image?.genRecordId ??
            image?.recordId ??
            (image?._fromServer ? image?.id : null)
        )
        return Number.isFinite(recordId) && recordId === task.sourceRecordId
      })
      if (index >= 0) return index
    }
    const normalizeUrl = (value: unknown) => String(value || '').trim().split(/[?#]/, 1)[0]
    const expected = normalizeUrl(task.referenceImageUrl)
    if (!expected) return 0
    const index = images.findIndex((image) => {
      const actual = normalizeUrl(image?.url ?? image?.thumbnail)
      return actual === expected || (!!actual && actual.endsWith(expected))
    })
    return index >= 0 ? index : 0
  }

  const reconcileOngoingImageGenerationTasks = async (
    tasks: UserTaskRow[],
    panels: StoryboardPanel[],
    scopeAtEntry: CreationLiveGenScopeCtx
  ) => {
    const sceneIndexByStoryboardId = new Map<number, number>()
    panels.forEach((panel, index) => {
      const storyboardId = parseServerStoryboardId(panel.id)
      if (storyboardId != null) sceneIndexByStoryboardId.set(storyboardId, index)
    })
    if (!sceneIndexByStoryboardId.size) return

    const knownModalTaskIds = new Set<number>()
    for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(getStore(), getRouteLikeSnapshot())) {
      for (const snapshot of Object.values(blob.storyboardImageGenTasksByStoryboardId || {})) {
        const taskId = Number(snapshot?.taskId)
        if (Number.isFinite(taskId) && taskId > 0) knownModalTaskIds.add(taskId)
      }
    }
    for (const task of tasks) {
      const taskId = Number(task?.id)
      if (taskId > 0 && isModalOwnedStoryboardImageTaskId(taskId)) knownModalTaskIds.add(taskId)
    }
    const activeBatchTaskId = parseImageBatchTaskId(
      getStore().storyboardImageBatchActiveImageTaskId
    )
    const knownBatchTaskIds = new Set<number>()
    if (activeBatchTaskId != null) knownBatchTaskIds.add(activeBatchTaskId)

    const [discovered, modalOnlyTasks] = await Promise.all([
      discoverOngoingStoryboardGenerationTasks({
        rows: tasks,
        media: 'image',
        loadDetail: (taskId) => userTaskDetailCached(taskId),
        knownBatchTaskIds,
        knownModalTaskIds
      }),
      discoverOngoingStoryboardModalImageTasks({
        rows: tasks,
        loadDetail: (taskId) => userTaskDetailCached(taskId)
      })
    ])
    if (!matchesCreationLiveGenScope(scopeAtEntry)) return

    const visible = discovered
      .map((task) => ({
        ...task,
        storyboardIds: task.storyboardIds.filter((id) => sceneIndexByStoryboardId.has(id))
      }))
      .filter((task) => task.storyboardIds.length)
    const restoredStoryboardIds = new Set<number>()
    for (const task of visible.filter((item) => item.owner === 'modal')) {
      for (const storyboardId of task.storyboardIds) {
        if (restoredStoryboardIds.has(storyboardId)) continue
        restoredStoryboardIds.add(storyboardId)
        const existing = getStore().getStoryboardImageGenTask(storyboardId, scopeAtEntry.scopeKey)
        if (existing && existing.taskId > task.taskId) continue
        getStore().setStoryboardImageGenTask(
          storyboardId,
          {
            taskId: task.taskId,
            sceneIdx: sceneIndexByStoryboardId.get(storyboardId) ?? 0,
            kind: 'storyboard'
          },
          scopeAtEntry.scopeKey
        )
      }
    }

    for (const task of modalOnlyTasks) {
      const sceneIdx = sceneIndexByStoryboardId.get(task.storyboardId)
      if (sceneIdx == null) continue
      const existing = getStore().getStoryboardImageGenTask(task.storyboardId, scopeAtEntry.scopeKey)
      if (existing && existing.taskId > task.taskId) continue
      getStore().setStoryboardImageGenTask(
        task.storyboardId,
        {
          taskId: task.taskId,
          sceneIdx,
          kind: task.kind,
          imageIdx: resolveModalImageTaskImageIndex(panels[sceneIdx], task)
        },
        scopeAtEntry.scopeKey
      )
    }

    const activeDescriptor = visible.find((task) => task.taskId === activeBatchTaskId)
    const batchTasks = visible.filter((task) => task.owner === 'batch')
    const batchTask =
      batchTasks.find((task) => task.taskId === activeBatchTaskId) ?? batchTasks[0] ?? null
    if (batchTask) {
      if (getStore().storyboardImageBatchActiveImageTaskId !== batchTask.taskId) {
        syncActiveImageTaskIdToStore(batchTask.taskId)
      }
      setImageBatchTargetIds(batchTask.storyboardIds)
      getStore().setStoryboardImageBatchGenerating(true)
      getStore().setStoryboardImageBatchError(null)
      batchTask.storyboardIds.forEach((storyboardId) =>
        getStore().setStoryboardPanelImageGenStatus(storyboardId, 'generating')
      )
    } else if (activeDescriptor?.owner === 'modal' && getStore().storyboardImageBatchActiveTaskId == null) {
      syncActiveImageTaskIdToStore(null)
      getStore().setStoryboardImageBatchGenerating(false)
      clearImageBatchTargetIds()
    }
  }

  return { isModalOwnedStoryboardImageTaskId, reconcileOngoingImageGenerationTasks }
}

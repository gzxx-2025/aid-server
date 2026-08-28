import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel } from '~/types'
import {
isStoryboardScriptBatchTask,
parseStoryboardScriptTaskId as parseTaskId
} from '~/utils/storyboardScriptBatchTrack'
import { requestCancelUserTaskById } from '~/utils/userTaskCancelFlow'
export interface StoryboardScriptTaskControlRuntime {
  activeTaskId: { value: number | null }
  getStore: () => ReturnType<typeof useCreationStore.getState>
  getFollowInFlight: () => Promise<{
    ok: boolean
    panels: StoryboardPanel[]
    message?: string
  }> | null
  setStopRequested: (value: boolean) => void
  closeStream: () => void
  refreshPanelsFromApi: () => Promise<StoryboardPanel[]>
  syncActiveTaskIdToStore: (taskId: number | null) => void
  resumeTrackFromGlobal: (
    taskId: number,
    currentPanels: StoryboardPanel[]
  ) => Promise<{ ok: boolean; panels: StoryboardPanel[]; message?: string }>
  nextResumeFollowGeneration: () => number
}

export function createStoryboardScriptTaskControls(
  runtime: StoryboardScriptTaskControlRuntime
) {
  const {
    activeTaskId,
    getStore,
    closeStream,
    refreshPanelsFromApi,
    syncActiveTaskIdToStore,
    resumeTrackFromGlobal
  } = runtime

async function requestStop() {
  runtime.setStopRequested(true)
  closeStream()
  const taskId = activeTaskId.value
  if (taskId) {
    try {
      await requestCancelUserTaskById(taskId)
    } catch {
      /* 404 等：仍停止本页展示 */
    }
  }
  if (runtime.getFollowInFlight()) {
    try {
      await runtime.getFollowInFlight()
    } catch {
      /* follow 结束时会刷新列表 */
    }
  } else {
    try {
      await refreshPanelsFromApi()
    } catch {
      /* ignore */
    }
  }
  syncActiveTaskIdToStore(null)
}

function onGlobalStopTask(event: Event) {
  const detail = (event as CustomEvent<{ taskId?: number; taskType?: string | null }>).detail
  const id = parseTaskId(detail?.taskId)
  if (!id) return
  if (!isStoryboardScriptBatchTask(detail?.taskType) && activeTaskId.value !== id) return
  if (activeTaskId.value === id || getStore().storyboardScriptActiveTaskId === id) {
    void requestStop()
  }
}

function onGlobalTrackTask(
  event: Event,
  onDone?: (result: { ok: boolean; panels: StoryboardPanel[]; message?: string }) => void
) {
  const detail = (event as CustomEvent<{ taskId?: number; taskType?: string | null }>).detail
  if (!isStoryboardScriptBatchTask(detail?.taskType)) return
  const id = parseTaskId(detail?.taskId)
  if (!id) return
  void resumeTrackFromGlobal(
    id,
    getStore().formData.storyboardScript.panels as StoryboardPanel[]
  ).then((result) => onDone?.(result))
}

/** 断开 SSE 并作废进行中的 follow；保留持久化 taskId，供切步/刷新后壳层恢复 loading */
function cancelResumeFollow() {
  runtime.nextResumeFollowGeneration()
  closeStream()
}

  return {
    requestStop,
    onGlobalStopTask,
    onGlobalTrackTask,
    cancelResumeFollow
  }
}

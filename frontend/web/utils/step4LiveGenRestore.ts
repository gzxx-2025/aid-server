import type { RouteLikeLocation } from '~/types/routeLike'
import {
  fetchUserTaskDetailOnce,
  isTerminalTaskStatus
} from '~/composables/useTaskSseFollow'
import { resolveCurrentStep4LiveGenScopeBlobs } from '~/composables/useCreationStoreHydration'
import type { useCreationStore } from '~/stores/creation'

type CreationStore = ReturnType<typeof useCreationStore>

function collectPositiveTaskIds(...rawIds: unknown[]): number[] {
  const out: number[] = []
  const seen = new Set<number>()
  for (const raw of rawIds) {
    const n = Number(raw)
    if (!Number.isFinite(n) || n <= 0 || seen.has(n)) continue
    seen.add(n)
    out.push(n)
  }
  return out
}

/** 双阶段批量：当前挂着的 active id 必须全部终态才清，避免 prompt 已结束、下游仍进行中时误停 */
function shouldClearDualPhaseBatch(
  promptTid: unknown,
  childTid: unknown,
  terminalIds: Set<number>
): boolean {
  const ids = collectPositiveTaskIds(promptTid, childTid)
  if (!ids.length) return false
  return ids.every((id) => terminalIds.has(id))
}

function clearPanelGeneratingForTargets(
  store: CreationStore,
  targetIds: number[],
  kind: 'image' | 'video'
) {
  for (const sid of targetIds) {
    if (!Number.isFinite(sid) || sid <= 0) continue
    if (kind === 'image') {
      if (store.storyboardPanelImageGenStatusByStoryboardId[String(sid)] === 'generating') {
        store.clearStoryboardPanelImageGenStatus(sid)
      }
    } else if (store.storyboardPanelVideoGenStatusByStoryboardId[String(sid)] === 'generating') {
      store.clearStoryboardPanelVideoGenStatus(sid)
    }
  }
}

/**
 * 切回作品 / 刷新后：校验 step4 批量与弹窗任务 detail，清除已终态残留，
 * 避免「loading 还在但 SSE 已断、任务早已结束」导致流程条卡死。
 */
export async function purgeTerminalStep4LiveGenTasks(
  store: CreationStore,
  route?: RouteLikeLocation
): Promise<void> {
  const candidates = resolveCurrentStep4LiveGenScopeBlobs(store, route)
  const taskIds = new Set<number>()

  for (const id of collectPositiveTaskIds(
    store.storyboardScriptActiveTaskId,
    store.storyboardImageBatchActiveTaskId,
    store.storyboardImageBatchActiveImageTaskId,
    store.storyboardVideoBatchActivePromptTaskId,
    store.storyboardVideoBatchActiveVideoTaskId
  )) {
    taskIds.add(id)
  }

  for (const { blob } of candidates) {
    for (const id of collectPositiveTaskIds(
      blob.storyboardScriptActiveTaskId,
      blob.storyboardImageBatchActiveTaskId,
      blob.storyboardImageBatchActiveImageTaskId,
      blob.storyboardVideoBatchActivePromptTaskId,
      blob.storyboardVideoBatchActiveVideoTaskId
    )) {
      taskIds.add(id)
    }
    for (const snap of Object.values(blob.storyboardImageGenTasksByStoryboardId || {})) {
      const tid = Number(snap?.taskId)
      if (Number.isFinite(tid) && tid > 0) taskIds.add(tid)
    }
    for (const snap of Object.values(blob.storyboardImagePromptGenTasksByStoryboardId || {})) {
      const tid = Number(snap?.taskId)
      if (Number.isFinite(tid) && tid > 0) taskIds.add(tid)
    }
    for (const snap of Object.values(blob.storyboardVideoGenTasksByStoryboardId || {})) {
      const tid = Number(snap?.taskId)
      if (Number.isFinite(tid) && tid > 0) taskIds.add(tid)
    }
    for (const snap of Object.values(blob.storyboardVideoPromptGenTasksByStoryboardId || {})) {
      const tid = Number(snap?.taskId)
      if (Number.isFinite(tid) && tid > 0) taskIds.add(tid)
    }
  }

  if (!taskIds.size) return

  const terminalIds = new Set<number>()
  for (const tid of taskIds) {
    try {
      const detail = await fetchUserTaskDetailOnce(tid)
      // detail 为空不视为终态（与 isUserTaskTerminal 一致），避免短暂查空误清
      if (detail && isTerminalTaskStatus(detail.status)) {
        terminalIds.add(tid)
      }
    } catch {
      /* 网络失败时保留，避免误清进行中任务 */
    }
  }
  if (!terminalIds.size) return

  if (terminalIds.has(Number(store.storyboardScriptActiveTaskId))) {
    store.stopStoryboardGeneration()
  }

  if (
    shouldClearDualPhaseBatch(
      store.storyboardImageBatchActiveTaskId,
      store.storyboardImageBatchActiveImageTaskId,
      terminalIds
    )
  ) {
    const targets = [...store.storyboardImageBatchTargetStoryboardIds]
    store.setStoryboardImageBatchGenerating(false)
    store.clearStoryboardImageBatchProgress()
    store.clearStoryboardImageBatchTargetStoryboardIds()
    clearPanelGeneratingForTargets(store, targets, 'image')
  }

  if (
    shouldClearDualPhaseBatch(
      store.storyboardVideoBatchActivePromptTaskId,
      store.storyboardVideoBatchActiveVideoTaskId,
      terminalIds
    )
  ) {
    // 终态清理必须覆盖完整 batch 生命周期。仅关闭 isGenerating 会把 targets 留在
    // scope 快照中，而 targets 本身就是刷新恢复的工作凭证，会令流程条再次变为 loading。
    store.finalizeStoryboardVideoBatchGeneration()
  }

  for (const { key, blob } of candidates) {
    for (const [sid, snap] of Object.entries(blob.storyboardImageGenTasksByStoryboardId || {})) {
      const tid = Number(snap?.taskId)
      if (Number.isFinite(tid) && tid > 0 && terminalIds.has(tid)) {
        store.clearStoryboardImageGenTask(Number(sid), key)
        store.clearStoryboardPanelImageGenStatus(Number(sid))
      }
    }
    for (const [sid, snap] of Object.entries(blob.storyboardImagePromptGenTasksByStoryboardId || {})) {
      const tid = Number(snap?.taskId)
      if (Number.isFinite(tid) && tid > 0 && terminalIds.has(tid)) {
        store.clearStoryboardImagePromptGenTask(Number(sid), key)
      }
    }
    for (const [sid, snap] of Object.entries(blob.storyboardVideoGenTasksByStoryboardId || {})) {
      const tid = Number(snap?.taskId)
      if (Number.isFinite(tid) && tid > 0 && terminalIds.has(tid)) {
        store.clearStoryboardVideoGenTask(Number(sid), key)
        if (store.storyboardPanelVideoGenStatusByStoryboardId[String(sid)] === 'generating') {
          store.clearStoryboardPanelVideoGenStatus(Number(sid))
        }
      }
    }
    for (const [sid, snap] of Object.entries(blob.storyboardVideoPromptGenTasksByStoryboardId || {})) {
      const tid = Number(snap?.taskId)
      if (Number.isFinite(tid) && tid > 0 && terminalIds.has(tid)) {
        store.clearStoryboardVideoPromptGenTask(Number(sid), key)
      }
    }

    const patch: Record<string, unknown> = {}
    if (terminalIds.has(Number(blob.storyboardScriptActiveTaskId))) {
      patch.isGeneratingStoryboard = false
      patch.storyboardScriptActiveTaskId = null
    }
    if (
      shouldClearDualPhaseBatch(
        blob.storyboardImageBatchActiveTaskId,
        blob.storyboardImageBatchActiveImageTaskId,
        terminalIds
      )
    ) {
      patch.isGeneratingStoryboardImageBatch = false
      patch.storyboardImageBatchActiveTaskId = null
      patch.storyboardImageBatchActiveImageTaskId = null
      patch.storyboardImageBatchTargetStoryboardIds = []
    }
    if (
      shouldClearDualPhaseBatch(
        blob.storyboardVideoBatchActivePromptTaskId,
        blob.storyboardVideoBatchActiveVideoTaskId,
        terminalIds
      )
    ) {
      patch.isGeneratingStoryboardVideo = false
      patch.storyboardVideoBatchActivePromptTaskId = null
      patch.storyboardVideoBatchActiveVideoTaskId = null
      patch.storyboardVideoBatchTargetStoryboardIds = []
    }
    if (Object.keys(patch).length) {
      store.mergeStep4PlusLiveGenForScopeKey(key, patch as never)
    }
  }

  /**
   * 弹窗单条生视频会置 isGeneratingStoryboardVideo，但终态清理只删 task 快照时不会清该旗标，
   * 导致流程 tab /「视频完成进度」loading 卡到刷新。无批量进行中且无剩余弹窗视频任务时释放。
   */
  const stillHasModalVideoTasks = resolveCurrentStep4LiveGenScopeBlobs(store, route).some(
    ({ blob }) => Object.keys(blob.storyboardVideoGenTasksByStoryboardId || {}).length > 0
  )
  const hasActiveVideoBatchIds =
    store.storyboardVideoBatchActivePromptTaskId != null ||
    store.storyboardVideoBatchActiveVideoTaskId != null
  if (
    store.isGeneratingStoryboardVideo &&
    !stillHasModalVideoTasks &&
    !hasActiveVideoBatchIds
  ) {
    store.setGeneratingStoryboardVideo(false)
  }

  store.syncStep4PlusLiveGenToCurrentScope()
}

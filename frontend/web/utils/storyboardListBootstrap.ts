import { resolveCurrentStep4LiveGenScopeBlobs } from '~/composables/useCreationStoreHydration'
import type { Step4PlusLiveGenSnapshot,useCreationStore } from '~/stores/creation'
import type { RouteLikeLocation } from '~/types/routeLike'
function hasPositiveTaskId(raw: unknown): boolean {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0
}

function collectModalTaskIds(
  tasks?: Record<string, { taskId?: number } | undefined>
): Set<number> {
  return new Set(
    Object.values(tasks || {})
      .map((task) => Number(task?.taskId))
      .filter((taskId) => Number.isFinite(taskId) && taskId > 0)
  )
}

/** 分镜卡片 status 映射中是否存在进行中的 generating（不含 failed） */
export function hasPanelGeneratingStatus(m?: Record<string, string | undefined>): boolean {
  return Object.values(m ?? {}).some((s) => s === 'generating')
}

function hasBatchPanelGeneratingStatus(
  statuses: Record<string, string | undefined> | undefined,
  modalTasks: Record<string, { taskId?: number } | undefined> | undefined
): boolean {
  const modalStoryboardIds = new Set(Object.keys(modalTasks || {}))
  return Object.entries(statuses || {}).some(
    ([storyboardId, status]) => status === 'generating' && !modalStoryboardIds.has(storyboardId)
  )
}

/** scope 桶内是否存在进行中的分镜脚本批量生成（刷新后 flat 字段尚未灌回时） */
export function step4ScopeBlobHasScriptBatchGenWork(blob: Step4PlusLiveGenSnapshot): boolean {
  return Boolean(blob.isGeneratingStoryboard)
}

/** scope 桶内是否存在进行中的分镜图批量生成 */
export function step4ScopeBlobHasImageBatchGenWork(blob: Step4PlusLiveGenSnapshot): boolean {
  const modalTaskIds = collectModalTaskIds(blob.storyboardImageGenTasksByStoryboardId)
  if (hasPositiveTaskId(blob.storyboardImageBatchActiveTaskId)) return true
  const activeImageTaskId = Number(blob.storyboardImageBatchActiveImageTaskId)
  if (hasPositiveTaskId(activeImageTaskId) && !modalTaskIds.has(activeImageTaskId)) return true
  if (
    hasBatchPanelGeneratingStatus(
      blob.storyboardPanelImageGenStatusByStoryboardId,
      blob.storyboardImageGenTasksByStoryboardId
    )
  ) {
    return true
  }
  if ((blob.storyboardImageBatchTargetStoryboardIds?.length ?? 0) > 0) return true
  return false
}

/** scope 桶内是否存在进行中的分镜视频批量生成（不含已失败/仅错误快照） */
export function step4ScopeBlobHasVideoBatchGenWork(blob: Step4PlusLiveGenSnapshot): boolean {
  const modalTaskIds = collectModalTaskIds(blob.storyboardVideoGenTasksByStoryboardId)
  if (hasPositiveTaskId(blob.storyboardVideoBatchActivePromptTaskId)) return true
  const activeVideoTaskId = Number(blob.storyboardVideoBatchActiveVideoTaskId)
  if (hasPositiveTaskId(activeVideoTaskId) && !modalTaskIds.has(activeVideoTaskId)) return true
  if (
    hasBatchPanelGeneratingStatus(
      blob.storyboardPanelVideoGenStatusByStoryboardId,
      blob.storyboardVideoGenTasksByStoryboardId
    )
  ) {
    return true
  }
  if ((blob.storyboardVideoBatchTargetStoryboardIds?.length ?? 0) > 0) return true
  return false
}

export function hasPersistedStoryboardScriptBatchGenWork(
  store: ReturnType<typeof useCreationStore>,
  route?: RouteLikeLocation
): boolean {
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(store, route)) {
    if (step4ScopeBlobHasScriptBatchGenWork(blob)) return true
  }
  return false
}

export function hasPersistedStoryboardImageBatchGenWork(
  store: ReturnType<typeof useCreationStore>,
  route?: RouteLikeLocation
): boolean {
  /** 剧集隔离：只看当前 scope（含 null/0 别名），禁止跨 episode 桶判定，避免他集任务把本集判成生成中 */
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(store, route)) {
    if (step4ScopeBlobHasImageBatchGenWork(blob)) return true
  }
  return false
}

export function hasPersistedStoryboardVideoBatchGenWork(
  store: ReturnType<typeof useCreationStore>,
  route?: RouteLikeLocation
): boolean {
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(store, route)) {
    if (step4ScopeBlobHasVideoBatchGenWork(blob)) return true
  }
  return false
}

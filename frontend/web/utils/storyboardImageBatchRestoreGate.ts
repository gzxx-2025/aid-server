import {
isContextSwitchKeepAliveMessage,
isNavigationOrSuspendBatchMessage,
isTaskBackgroundRunningMessage
} from '~/utils/taskSseSilentDisconnect'

/**
 * 分镜图/分镜视频批量 restore 闸门（跨集切回时防「有 loading 无 SSE / 清掉原集状态」）。
 *
 * 三条硬规则：
 * 1. 已有真实 SSE follow → 勿再开一轮
 * 2. 仍 generating 且（有分镜 id 或有 taskId）→ 允许/需要 restore
 * 3. 切集收尾只保活原 scope，禁止 merge 空 statuses
 */

export function shouldDropImageBatchRestoreBecauseFollowing(following: boolean): boolean {
  return Boolean(following)
}

/** 是否还要再调度 restore / 空 panels 时是否先跟 SSE */
export function shouldRestoreImageBatchSse(input: {
  isGenerating: boolean
  following: boolean
  hasServerStoryboardIds?: boolean
  hasActiveTaskId?: boolean
}): boolean {
  if (input.following) return false
  // taskId is the authoritative server-side recovery handle. The persisted UI flag may hydrate
  // later (or be absent after an older session), so it must never gate a known active task.
  if (input.hasActiveTaskId) return true
  return Boolean(input.isGenerating && input.hasServerStoryboardIds)
}

function positiveIds(ids: number[]): number[] {
  return ids.filter((id) => Number.isFinite(id) && id > 0)
}

/** targets 优先，否则回退 storyboardIds（禁止空 targets 去清全表 generating） */
export function resolveImageBatchLoadingTargetIds(
  targets: number[],
  storyboardIds: number[]
): number[] {
  const explicit = positiveIds(targets)
  return explicit.length ? explicit : positiveIds(storyboardIds)
}

/** 仅当有非空显式 targets 时，才清「非目标」分镜的 generating */
export function shouldClearNonTargetImageBatchPanelStatus(
  explicitTargets: number[],
  storyboardId: number
): boolean {
  const explicit = positiveIds(explicitTargets)
  return explicit.length > 0 && !explicit.includes(storyboardId)
}

/** 切集后写入原 scope：只保活分镜图 generating + taskId，不带空 statuses */
export function buildImageBatchScopePreserveOnContextSwitch(input: {
  promptTaskId?: number | null
  imageTaskId?: number | null
}): {
  isGeneratingStoryboardImageBatch: true
  storyboardImageBatchActiveTaskId?: number
  storyboardImageBatchActiveImageTaskId?: number
} {
  const out: {
    isGeneratingStoryboardImageBatch: true
    storyboardImageBatchActiveTaskId?: number
    storyboardImageBatchActiveImageTaskId?: number
  } = { isGeneratingStoryboardImageBatch: true }
  const promptId = Number(input.promptTaskId)
  if (Number.isFinite(promptId) && promptId > 0) out.storyboardImageBatchActiveTaskId = promptId
  const imageId = Number(input.imageTaskId)
  if (Number.isFinite(imageId) && imageId > 0) out.storyboardImageBatchActiveImageTaskId = imageId
  return out
}

/** 切集后写入原 scope：只保活分镜视频 generating + taskId，不带空 statuses */
export function buildVideoBatchScopePreserveOnContextSwitch(input: {
  promptTaskId?: number | null
  videoTaskId?: number | null
}): {
  isGeneratingStoryboardVideo: true
  storyboardVideoBatchActivePromptTaskId?: number
  storyboardVideoBatchActiveVideoTaskId?: number
} {
  const out: {
    isGeneratingStoryboardVideo: true
    storyboardVideoBatchActivePromptTaskId?: number
    storyboardVideoBatchActiveVideoTaskId?: number
  } = { isGeneratingStoryboardVideo: true }
  const promptId = Number(input.promptTaskId)
  if (Number.isFinite(promptId) && promptId > 0) {
    out.storyboardVideoBatchActivePromptTaskId = promptId
  }
  const videoId = Number(input.videoTaskId)
  if (Number.isFinite(videoId) && videoId > 0) {
    out.storyboardVideoBatchActiveVideoTaskId = videoId
  }
  return out
}

/** 分镜视频：导航/良性断连 + 视频特有未就绪文案 → 保活，禁止 abort/finish */
export function shouldKeepVideoBatchLoadingAfterFollowMessage(message: unknown): boolean {
  const text = String(message ?? '')
  return (
    isNavigationOrSuspendBatchMessage(message) ||
    isTaskBackgroundRunningMessage(message) ||
    isContextSwitchKeepAliveMessage(message) ||
    text.includes('出片任务未就绪') ||
    text.includes('分镜尚未保存')
  )
}

/**
 * 列表未命中进行中任务时是否仍信任持久化 taskId。
 * generating / list 失败 / 有 panel generating 时都应收信任，避免刷新后丢 SSE。
 */
export function shouldTrustPersistedTaskIdOnListMiss(input: {
  taskListOk: boolean
  isGenerating: boolean
  hasPanelGenerating?: boolean
}): boolean {
  return Boolean(input.isGenerating || !input.taskListOk || input.hasPanelGenerating)
}

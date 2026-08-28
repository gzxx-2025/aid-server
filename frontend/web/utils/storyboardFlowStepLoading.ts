import { resolveCurrentStep4LiveGenScopeBlobs } from '~/composables/useCreationStoreHydration'
import type { useCreationStore } from '~/stores/creation'
import { modalGenSessionScopeFromStore } from '~/utils/modalGenSessionScope'
import {
isStoryboardDubbingModalUserDismissed,
readStoryboardDubbingModalGenSession
} from '~/utils/storyboardDubbingModalGenSession'
import { isModalImageGenSessionActive } from '~/utils/storyboardImageModalGenSession'
import { step4ScopeBlobHasVideoBatchGenWork } from '~/utils/storyboardListBootstrap'

type CreationStore = ReturnType<typeof useCreationStore>

function scopeKeysMatch(currentKey: string, sessionKey: string): boolean {
  const current = String(currentKey || '').trim()
  const session = String(sessionKey || '').trim()
  if (!current || !session) return false
  if (current === session) return true
  const normalize = (k: string) => k.replace(/:null$/, ':0').replace(/:0$/, ':0')
  return normalize(current) === normalize(session)
}

function isCurrentScopeDubbingModalSessionActive(store: CreationStore): boolean {
  if (!(typeof window !== 'undefined')) return false
  const sessionScope = modalGenSessionScopeFromStore(store)
  const session = readStoryboardDubbingModalGenSession(sessionScope)
  if (!session) return false
  if (isStoryboardDubbingModalUserDismissed(session.storyboardId, sessionScope)) return false
  const sessionKey = String(session.scopeKey || '').trim()
  if (!sessionKey) return false
  return scopeKeysMatch(store.step3GenVisualScopeKey(), sessionKey)
}

/** 分镜列表中是否有单镜出图 loading（含弹窗生图/对话作图/多机位/变清晰） */
export function hasStoryboardPanelImageGenerating(store: CreationStore): boolean {
  return Object.values(store.storyboardPanelImageGenStatusByStoryboardId).some(
    (status) => status === 'generating'
  )
}

/** Pinia 持久化的弹窗单镜生图任务（刷新后、列表状态恢复前） */
export function hasPersistedStoryboardModalImageGenTask(
  store: CreationStore,
  route?: { query?: Record<string, unknown>; path?: string }
): boolean {
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(store, route)) {
    const tasks = blob.storyboardImageGenTasksByStoryboardId
    if (tasks && Object.keys(tasks).length > 0) return true
  }
  return false
}

/** Pinia 持久化的弹窗单镜视频生成任务；只驱动流程步骤 loading，不属于列表批量任务。 */
export function hasPersistedStoryboardModalVideoGenTask(
  store: CreationStore,
  route?: { query?: Record<string, unknown>; path?: string }
): boolean {
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(store, route)) {
    const tasks = blob.storyboardVideoGenTasksByStoryboardId
    if (tasks && Object.keys(tasks).length > 0) return true
  }
  return false
}

/** Pinia 持久化的分镜脚本批量任务（刷新后、步骤页未挂载时） */
export function hasPersistedStoryboardScriptBatchTask(
  store: CreationStore,
  route?: { query?: Record<string, unknown>; path?: string }
): boolean {
  if (store.isGeneratingStoryboard) return true
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(store, route)) {
    if (blob.isGeneratingStoryboard) return true
  }
  return false
}

/**
 * 头部流程条「分镜设计」步骤是否展示 loading：
 * 分镜脚本批量、分镜图批量、弹窗单镜/对话/多机位/变清晰等。
 */
export function isStoryboardScriptFlowStepGenerating(
  store: CreationStore,
  route?: { query?: Record<string, unknown>; path?: string }
): boolean {
  if (hasPersistedStoryboardScriptBatchTask(store, route)) return true
  if (store.isGeneratingStoryboardImageBatch) return true
  if (hasStoryboardPanelImageGenerating(store)) return true
  if (hasPersistedStoryboardModalImageGenTask(store, route)) return true
  if (
    (typeof window !== 'undefined') &&
    isModalImageGenSessionActive(undefined, modalGenSessionScopeFromStore(store))
  ) {
    return true
  }
  return false
}

/** Pinia 持久化的弹窗单镜配音任务（刷新后、弹窗未打开时） */
export function hasPersistedStoryboardDubbingModalGenTask(
  store: CreationStore,
  route?: { query?: Record<string, unknown>; path?: string }
): boolean {
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(store, route)) {
    const tasks = blob.storyboardDubbingGenTasksByStoryboardId
    if (tasks && Object.keys(tasks).length > 0) return true
  }
  return false
}

/**
 * 分镜脚本批量生成进行中（不含分镜图批量）。
 * 视频/配音步骤依赖脚本同步，流程条需与步骤内 `isSyncGeneratingStoryboard` 一致展示 loading。
 */
export function isStoryboardScriptBatchSyncGenerating(
  store: CreationStore,
  route?: { query?: Record<string, unknown>; path?: string }
): boolean {
  return hasPersistedStoryboardScriptBatchTask(store, route)
}

/**
 * 头部流程条「视频生成」步骤是否展示 loading。
 */
export function isStoryboardVideoFlowStepGenerating(
  store: CreationStore,
  route?: { query?: Record<string, unknown>; path?: string }
): boolean {
  if (isStoryboardScriptBatchSyncGenerating(store, route)) return true
  if (hasPersistedStoryboardModalVideoGenTask(store, route)) return true
  if (store.isGeneratingStoryboardVideo) return true
  if (store.storyboardVideoBatchActivePromptTaskId != null) return true
  if (store.storyboardVideoBatchActiveVideoTaskId != null) return true
  if (
    Object.values(store.storyboardPanelVideoGenStatusByStoryboardId).some(
      (status) => status === 'generating'
    )
  ) {
    return true
  }
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(store, route)) {
    if (step4ScopeBlobHasVideoBatchGenWork(blob)) return true
  }
  return false
}

/**
 * 头部流程条「配音」步骤是否展示 loading：
 * 批量配音、弹窗内单镜配音 SSE 等。
 */
export function isDubbingFlowStepGenerating(
  store: CreationStore,
  route?: { query?: Record<string, unknown>; path?: string }
): boolean {
  if (isStoryboardScriptBatchSyncGenerating(store, route)) return true
  if ((store.dubbingBatchGeneratingIndices?.length ?? 0) > 0) return true
  if (hasPersistedStoryboardDubbingModalGenTask(store, route)) return true
  if (isCurrentScopeDubbingModalSessionActive(store)) return true
  return false
}

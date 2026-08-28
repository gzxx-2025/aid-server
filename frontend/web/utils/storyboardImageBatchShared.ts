/**
 * 分镜图批量生成：模块级共享助手（原 composables/useStoryboardImageBatchGenerate.ts
 * 顶部纯函数 + 批量目标 session 快照 + 卡片 loading 恢复拆分；主体见
 * hooks/useStoryboardImageBatchGenerate.ts，follow 链路见 utils/storyboardImageBatchFollowCore.ts）。
 */

import { useCreationStore } from '~/stores/creation'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import {
  applyCreationStoreScopeLiveGenFromRoute,
  resolveCurrentStep4LiveGenScopeBlobs
} from '~/composables/useCreationStoreHydration'
import { resolveStoryboardPanelCoverImage } from '~/utils/storyboardImageCover'
import {
  isModalImageGenSessionActive,
  readModalImageGenSession,
  syncModalPanelLoadingForActiveSession
} from '~/utils/storyboardImageModalGenSession'
import {
  modalGenSessionScopeFromStore,
  readScopedSessionItem,
  removeScopedSessionItem,
  writeScopedSessionItem
} from '~/utils/modalGenSessionScope'
import { hasPersistedStoryboardImageBatchGenWork } from '~/utils/storyboardListBootstrap'
import type { AsyncIdleBarrier } from '~/utils/asyncIdleBarrier'
import type { RouteLikeLocation } from '~/types/routeLike'
import type { StoryboardPanel } from '~/types'
import type { UserTaskRow } from '~/types/business-api'

export type CreationStoreState = ReturnType<typeof useCreationStore>

export function parseImageBatchTaskId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

export function imageBatchBizErr(e: unknown): string {
  const x = e as { msg?: string; message?: string }
  return x?.msg || x?.message || '操作失败'
}

export type StoryboardPromptBatchFollowResult = {
  ok: boolean
  partial?: boolean
  message?: string
  chainChildTaskIds?: number[]
}

export type StoryboardImageBatchFollowResult = {
  ok: boolean
  message?: string
  partial?: boolean
  panels?: StoryboardPanel[]
}

export function imageBatchSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export const TASK_BACKGROUND_RUNNING_MESSAGE = '任务仍在后台执行，请稍候或刷新页面自动恢复进度'

function normStoryboardImagePromptBatchTaskType(ty: unknown): string {
  return String(ty ?? '')
    .trim()
    .toLowerCase()
    .replace(/-/g, '_')
}

export function isStoryboardImagePromptBatchTask(ty: unknown): boolean {
  return normStoryboardImagePromptBatchTaskType(ty) === 'storyboard_image_prompt_batch'
}

export function isOngoingImageBatchTaskStatus(status: unknown): boolean {
  const s = String(status ?? '')
    .trim()
    .toUpperCase()
  return (
    s === 'PENDING' ||
    s === 'PROCESSING' ||
    s === 'RUNNING' ||
    s === 'QUEUED' ||
    s === 'WAITING' ||
    s === '0'
  )
}

export function panelHasCoverImage(panel: StoryboardPanel): boolean {
  return !!resolveStoryboardPanelCoverImage(panel)?.url
}

const STORYBOARD_IMAGE_BATCH_TARGET_IDS_SESSION_KEY =
  'create-flow:storyboard-image-batch-target-storyboard-ids'

export function normalizeStoryboardBatchTargetIds(raw: unknown): number[] {
  const source = Array.isArray(raw) ? raw : []
  const ids = source.map((id) => Number(id)).filter((id) => Number.isFinite(id) && id > 0)
  return [...new Set(ids)]
}

export function readImageBatchTargetIdsSession(creationStore: CreationStoreState): number[] {
  const raw = readScopedSessionItem(
    STORYBOARD_IMAGE_BATCH_TARGET_IDS_SESSION_KEY,
    modalGenSessionScopeFromStore(creationStore)
  )
  if (!raw) return []
  try {
    return normalizeStoryboardBatchTargetIds(JSON.parse(raw))
  } catch {
    return []
  }
}

export function writeImageBatchTargetIdsSession(
  creationStore: CreationStoreState,
  storyboardIds: number[]
): void {
  const ids = normalizeStoryboardBatchTargetIds(storyboardIds)
  if (!ids.length) {
    removeScopedSessionItem(
      STORYBOARD_IMAGE_BATCH_TARGET_IDS_SESSION_KEY,
      modalGenSessionScopeFromStore(creationStore)
    )
    return
  }
  writeScopedSessionItem(
    STORYBOARD_IMAGE_BATCH_TARGET_IDS_SESSION_KEY,
    JSON.stringify(ids),
    modalGenSessionScopeFromStore(creationStore)
  )
}

export function clearImageBatchTargetIdsSession(creationStore: CreationStoreState): void {
  removeScopedSessionItem(
    STORYBOARD_IMAGE_BATCH_TARGET_IDS_SESSION_KEY,
    modalGenSessionScopeFromStore(creationStore)
  )
}

/** 批量出图目标分镜 id：flat store → scope 桶 → session 目标快照 → status */
export function getActiveImageBatchTargetIds(
  creationStore: CreationStoreState,
  route?: RouteLikeLocation
): number[] {
  const persisted = creationStore.storyboardImageBatchTargetStoryboardIds
  if (persisted.length) return persisted

  const explicitFromBlob = new Set<number>()
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(creationStore, route)) {
    if (!Array.isArray(blob.storyboardImageBatchTargetStoryboardIds)) continue
    for (const id of blob.storyboardImageBatchTargetStoryboardIds) {
      const n = Number(id)
      if (Number.isFinite(n) && n > 0) explicitFromBlob.add(n)
    }
  }
  if (explicitFromBlob.size) return [...explicitFromBlob]

  const fromSession = readImageBatchTargetIdsSession(creationStore)
  if (fromSession.length) return fromSession

  const fromStatus = Object.entries(creationStore.storyboardPanelImageGenStatusByStoryboardId)
    .filter(([, st]) => st === 'generating')
    .map(([k]) => Number(k))
    .filter((id) => Number.isFinite(id) && id > 0)
  if (fromStatus.length) return fromStatus

  const fromBlob = new Set<number>()
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(creationStore, route)) {
    for (const [sidRaw, st] of Object.entries(
      blob.storyboardPanelImageGenStatusByStoryboardId ?? {}
    )) {
      if (st !== 'generating') continue
      const sid = Number(sidRaw)
      if (Number.isFinite(sid) && sid > 0) fromBlob.add(sid)
    }
  }
  return [...fromBlob]
}

export function applyBatchImagePanelLoadingRestore(
  creationStore: CreationStoreState,
  panels: StoryboardPanel[],
  modalTaskSids: Set<number>,
  batchTargetIds: number[]
) {
  const targetSet = new Set(batchTargetIds)
  for (const sid of batchTargetIds) {
    if (modalTaskSids.has(sid)) continue
    if (creationStore.storyboardPanelImageGenStatusByStoryboardId[String(sid)] === 'generating') {
      continue
    }
    creationStore.setStoryboardPanelImageGenStatus(sid, 'generating')
  }
  if (!targetSet.size) return
  for (const panel of panels) {
    const sid = parseServerStoryboardId(panel.id)
    if (sid == null) continue
    if (targetSet.has(sid) || modalTaskSids.has(sid)) continue
    if (creationStore.storyboardPanelImageGenStatusByStoryboardId[String(sid)] === 'generating') {
      creationStore.clearStoryboardPanelImageGenStatus(sid)
    }
  }
}

/**
 * 刷新/拉列表后恢复分镜图卡片 loading 到 store（与分镜脚本 isPanelImageGenerating 数据源一致）。
 * @param options.skipScopeHydrate 为 true 时跳过 scope→flat hydrate（store 变更触发的 watcher 内必须传 true，避免死循环）
 */
export function applyStoryboardImageImmediatePanelLoadingRestore(
  creationStoreAtEntry: CreationStoreState,
  route: RouteLikeLocation,
  panels: StoryboardPanel[],
  options?: { skipScopeHydrate?: boolean }
): void {
  if (!options?.skipScopeHydrate) {
    applyCreationStoreScopeLiveGenFromRoute(creationStoreAtEntry, route)
  }
  // Zustand 快照不可变：hydrate 可能已 setState，后续判断必须基于最新 state
  const creationStore = useCreationStore.getState()

  /** 剧集隔离：仅当前 scope 桶（含 null/0 别名），禁止把他集分镜的 generating 灌进本集扁平 map */
  const scopeCandidates = resolveCurrentStep4LiveGenScopeBlobs(creationStore, route)
  const modalTaskSids = new Set<number>()
  const batchTargetSids = new Set<number>()
  const generatingSids = new Set<number>()

  for (const { blob } of scopeCandidates) {
    for (const sidRaw of Object.keys(blob.storyboardImageGenTasksByStoryboardId ?? {})) {
      const sid = Number(sidRaw)
      if (Number.isFinite(sid) && sid > 0) modalTaskSids.add(sid)
    }
    for (const sidRaw of blob.storyboardImageBatchTargetStoryboardIds ?? []) {
      const sid = Number(sidRaw)
      if (Number.isFinite(sid) && sid > 0) batchTargetSids.add(sid)
    }
    for (const [sidRaw, st] of Object.entries(
      blob.storyboardPanelImageGenStatusByStoryboardId ?? {}
    )) {
      if (st !== 'generating') continue
      const sid = Number(sidRaw)
      if (Number.isFinite(sid) && sid > 0) generatingSids.add(sid)
    }
  }

  if (typeof window !== 'undefined') {
    const sessionScope = modalGenSessionScopeFromStore(creationStore)
    const session = readModalImageGenSession(sessionScope)
    const sessionSid = Number(session?.storyboardId)
    if (
      Number.isFinite(sessionSid) &&
      sessionSid > 0 &&
      isModalImageGenSessionActive(sessionSid, sessionScope)
    ) {
      modalTaskSids.add(sessionSid)
    }
    syncModalPanelLoadingForActiveSession((sid) => {
      modalTaskSids.add(sid)
    }, sessionScope)
  }

  for (const sid of generatingSids) {
    if (modalTaskSids.has(sid) && !batchTargetSids.has(sid)) {
      if (creationStore.storyboardPanelImageGenStatusByStoryboardId[String(sid)] === 'generating') {
        creationStore.clearStoryboardPanelImageGenStatus(sid)
      }
      continue
    }
    if (creationStore.storyboardPanelImageGenStatusByStoryboardId[String(sid)] === 'generating') {
      continue
    }
    creationStore.setStoryboardPanelImageGenStatus(sid, 'generating')
  }

  const latest = useCreationStore.getState()
  if (
    !latest.isGeneratingStoryboardImageBatch &&
    !hasPersistedStoryboardImageBatchGenWork(latest, route)
  ) {
    return
  }

  const batchTargetIds = getActiveImageBatchTargetIds(latest, route)
  const isPromptOnlyImageBatch =
    latest.storyboardImageBatchActiveTaskId != null &&
    latest.storyboardImageBatchActiveImageTaskId == null
  /** 提示词 SSE 已结束、出图任务尚未写入 activeImageTaskId 的间隙，仍需保持卡片 loading */
  const isAwaitingImageGenerateBatch =
    latest.storyboardImageBatchActiveTaskId == null &&
    latest.storyboardImageBatchActiveImageTaskId == null &&
    batchTargetIds.length > 0

  if (isPromptOnlyImageBatch || isAwaitingImageGenerateBatch) {
    applyBatchImagePanelLoadingRestore(latest, panels, modalTaskSids, batchTargetIds)
    return
  }

  if (
    latest.storyboardImageBatchActiveImageTaskId != null ||
    latest.storyboardImageBatchTargetStoryboardIds.length > 0 ||
    batchTargetIds.length > 0
  ) {
    applyBatchImagePanelLoadingRestore(latest, panels, modalTaskSids, batchTargetIds)
  }
}

/** 原创建器闭包内的可变状态：拆分文件后经该对象显式共享（禁止各文件另建平行状态） */
export interface StoryboardImageBatchState {
  activeTaskId: { value: number | null }
  activeImageTaskId: { value: number | null }
  streamCloser: (() => void) | null
  stopRequested: boolean
  resumeFollowGeneration: number
  followInFlight: Promise<StoryboardPromptBatchFollowResult> | null
  promptFollowTaskId: number | null
  imageFollowInFlight: {
    taskId: number
    promise: Promise<StoryboardImageBatchFollowResult>
  } | null
  batchSseFollowInFlight: boolean
  batchSseFollowDepth: number
  restoreSessionInFlight: Promise<void> | null
  batchRunInFlight: boolean
  cachedRecentProjectTasks: { projectId: number; at: number; rows: UserTaskRow[] } | null
  followIdleBarrier: AsyncIdleBarrier
}

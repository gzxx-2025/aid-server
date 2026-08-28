/**
 * 分镜视频批量生成：模块级共享助手（原 composables/useStoryboardVideoBatchGenerate.ts
 * 顶部纯函数 + 批量目标 session 快照 + 卡片 loading 恢复拆分；主体见
 * hooks/useStoryboardVideoBatchGenerate.ts，follow 链路见 utils/storyboardVideoBatchFollowCore.ts）。
 */

import {
applyCreationStoreScopeLiveGenFromRoute,
resolveCurrentStep4LiveGenScopeBlobs
} from '~/composables/useCreationStoreHydration'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel,StoryboardVideoPanel } from '~/types'
import type { UserTaskRow } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import type { AsyncIdleBarrier } from '~/utils/asyncIdleBarrier'
import {
modalGenSessionScopeFromStore,
readScopedSessionItem,
removeScopedSessionItem,
writeScopedSessionItem
} from '~/utils/modalGenSessionScope'
import { collectStoryboardVideoPairs,panelHasStoryboardVideo,type CreationStoreState,type StoryboardVideoGenerateFollowResult,type StoryboardVideoPromptFollowResult } from '~/utils/storyboardVideoBatchPanelShared'

const STORYBOARD_VIDEO_BATCH_TARGET_IDS_SESSION_KEY =
  'create-flow:storyboard-video-batch-target-storyboard-ids'

export function normalizeStoryboardVideoBatchTargetIds(raw: unknown): number[] {
  const source = Array.isArray(raw) ? raw : []
  const ids = source.map((id) => Number(id)).filter((id) => Number.isFinite(id) && id > 0)
  return [...new Set(ids)]
}

function readVideoBatchTargetIdsSession(creationStore: CreationStoreState): number[] {
  const raw = readScopedSessionItem(
    STORYBOARD_VIDEO_BATCH_TARGET_IDS_SESSION_KEY,
    modalGenSessionScopeFromStore(creationStore)
  )
  if (!raw) return []
  try {
    return normalizeStoryboardVideoBatchTargetIds(JSON.parse(raw))
  } catch {
    return []
  }
}

export function writeVideoBatchTargetIdsSession(
  creationStore: CreationStoreState,
  storyboardIds: number[]
): void {
  const ids = normalizeStoryboardVideoBatchTargetIds(storyboardIds)
  if (!ids.length) {
    removeScopedSessionItem(
      STORYBOARD_VIDEO_BATCH_TARGET_IDS_SESSION_KEY,
      modalGenSessionScopeFromStore(creationStore)
    )
    return
  }
  writeScopedSessionItem(
    STORYBOARD_VIDEO_BATCH_TARGET_IDS_SESSION_KEY,
    JSON.stringify(ids),
    modalGenSessionScopeFromStore(creationStore)
  )
}

export function clearVideoBatchTargetIdsSession(creationStore: CreationStoreState): void {
  removeScopedSessionItem(
    STORYBOARD_VIDEO_BATCH_TARGET_IDS_SESSION_KEY,
    modalGenSessionScopeFromStore(creationStore)
  )
}

export function getActiveVideoBatchTargetIds(
  creationStore: CreationStoreState,
  route?: RouteLikeLocation
): number[] {
  const persisted = creationStore.storyboardVideoBatchTargetStoryboardIds
  if (persisted.length) return persisted

  const explicitFromBlob = new Set<number>()
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(creationStore, route)) {
    if (!Array.isArray(blob.storyboardVideoBatchTargetStoryboardIds)) continue
    for (const id of blob.storyboardVideoBatchTargetStoryboardIds) {
      const n = Number(id)
      if (Number.isFinite(n) && n > 0) explicitFromBlob.add(n)
    }
  }
  if (explicitFromBlob.size) return [...explicitFromBlob]

  const fromSession = readVideoBatchTargetIdsSession(creationStore)
  if (fromSession.length) return fromSession

  const fromStatus = Object.entries(creationStore.storyboardPanelVideoGenStatusByStoryboardId)
    .filter(([, st]) => st === 'generating')
    .map(([k]) => Number(k))
    .filter((id) => Number.isFinite(id) && id > 0)
  if (fromStatus.length) return fromStatus

  const fromBlob = new Set<number>()
  for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(creationStore, route)) {
    for (const [sidRaw, st] of Object.entries(
      blob.storyboardPanelVideoGenStatusByStoryboardId ?? {}
    )) {
      if (st !== 'generating') continue
      const sid = Number(sidRaw)
      if (Number.isFinite(sid) && sid > 0) fromBlob.add(sid)
    }
  }
  return [...fromBlob]
}

export function resolveBatchVideoTargetsForRestore(
  scriptPanels: StoryboardPanel[],
  videoPanels: StoryboardVideoPanel[],
  storyboardIds: number[],
  overwrite: boolean
): number[] {
  const pairs = collectStoryboardVideoPairs(scriptPanels, videoPanels)
  return storyboardIds.filter((sid) => {
    const pair = pairs.find((p) => p.storyboardId === sid)
    if (!pair) return false
    if (!pair.video) return true
    if (!overwrite && panelHasStoryboardVideo(pair.video)) return false
    return true
  })
}

/**
 * 刷新/拉列表后恢复分镜视频卡片 loading 到 store（与分镜脚本 isPanelImageGenerating 数据源一致）。
 * @param options.skipScopeHydrate 为 true 时跳过 scope→flat hydrate（由 store 变更触发的 watcher 内必须传 true，避免死循环）
 */
export function applyStoryboardVideoImmediatePanelLoadingRestore(
  creationStoreAtEntry: CreationStoreState,
  route: RouteLikeLocation,
  scriptPanels: StoryboardPanel[],
  videoPanels: StoryboardVideoPanel[],
  options?: { skipScopeHydrate?: boolean }
): void {
  if (!options?.skipScopeHydrate) {
    applyCreationStoreScopeLiveGenFromRoute(creationStoreAtEntry, route)
  }
  // Zustand 快照不可变：hydrate 可能已 setState，后续判断必须基于最新 state
  const creationStore = useCreationStore.getState()

  const resolvedScriptPanels = scriptPanels.length
    ? scriptPanels
    : (creationStore.formData.storyboardScript.panels as StoryboardPanel[]) || []

  const scopeCandidates = resolveCurrentStep4LiveGenScopeBlobs(creationStore, route)
  const pendingModalSids = new Set<number>()
  const generatingSids = new Set<number>()

  for (const { blob } of scopeCandidates) {
    for (const sidRaw of Object.keys(blob.storyboardVideoGenTasksByStoryboardId ?? {})) {
      const sid = Number(sidRaw)
      if (Number.isFinite(sid) && sid > 0) pendingModalSids.add(sid)
    }
    /** 弹窗内单条「生成提示词」进行中：外层列表不同步 batch loading，避免切步骤回来抖动/循环 */
    for (const sidRaw of Object.keys(blob.storyboardVideoPromptGenTasksByStoryboardId ?? {})) {
      const sid = Number(sidRaw)
      if (Number.isFinite(sid) && sid > 0) pendingModalSids.add(sid)
    }
    for (const [sidRaw, st] of Object.entries(
      blob.storyboardPanelVideoGenStatusByStoryboardId ?? {}
    )) {
      if (st !== 'generating') continue
      const sid = Number(sidRaw)
      if (Number.isFinite(sid) && sid > 0) generatingSids.add(sid)
    }
  }

  const isPromptOnlyBatch =
    creationStore.storyboardVideoBatchActivePromptTaskId != null &&
    creationStore.storyboardVideoBatchActiveVideoTaskId == null
  const batchTargetIds = getActiveVideoBatchTargetIds(creationStore, route)

  for (const sid of generatingSids) {
    if (pendingModalSids.has(sid)) continue
    if (creationStore.storyboardPanelVideoGenStatusByStoryboardId[String(sid)] === 'generating') {
      continue
    }
    creationStore.setStoryboardPanelVideoGenStatus(sid, 'generating')
  }

  if (isPromptOnlyBatch) {
    for (const sid of batchTargetIds) {
      if (pendingModalSids.has(sid)) continue
      if (creationStore.storyboardPanelVideoGenStatusByStoryboardId[String(sid)] === 'generating') {
        continue
      }
      creationStore.setStoryboardPanelVideoGenStatus(sid, 'generating')
    }
    const targetSet = new Set(batchTargetIds)
    if (targetSet.size > 0) {
      const pairs = collectStoryboardVideoPairs(resolvedScriptPanels, videoPanels)
      for (const pair of pairs) {
        if (targetSet.has(pair.storyboardId) || pendingModalSids.has(pair.storyboardId)) continue
        const key = String(pair.storyboardId)
        if (creationStore.storyboardPanelVideoGenStatusByStoryboardId[key] === 'generating') {
          creationStore.clearStoryboardPanelVideoGenStatus(pair.storyboardId)
        }
      }
    }
    return
  }

  if (
    creationStore.isGeneratingStoryboardVideo &&
    (creationStore.storyboardVideoBatchActiveVideoTaskId != null ||
      creationStore.storyboardVideoBatchTargetStoryboardIds.length > 0 ||
      batchTargetIds.length > 0)
  ) {
    const storyboardIds = collectStoryboardVideoPairs(resolvedScriptPanels, videoPanels).map(
      (p) => p.storyboardId
    )
    const targets =
      batchTargetIds.length > 0
        ? batchTargetIds
        : resolveBatchVideoTargetsForRestore(
            resolvedScriptPanels,
            videoPanels,
            storyboardIds,
            false
          )
    const targetSet = new Set(targets)
    for (const sid of targets) {
      if (pendingModalSids.has(sid)) continue
      if (creationStore.storyboardPanelVideoGenStatusByStoryboardId[String(sid)] === 'generating') {
        continue
      }
      creationStore.setStoryboardPanelVideoGenStatus(sid, 'generating')
    }
    if (targetSet.size > 0) {
      const pairs = collectStoryboardVideoPairs(resolvedScriptPanels, videoPanels)
      for (const pair of pairs) {
        if (targetSet.has(pair.storyboardId) || pendingModalSids.has(pair.storyboardId)) continue
        const key = String(pair.storyboardId)
        if (creationStore.storyboardPanelVideoGenStatusByStoryboardId[key] === 'generating') {
          creationStore.clearStoryboardPanelVideoGenStatus(pair.storyboardId)
        }
      }
    }
  }
}

/**
 * Zustand 适配（对齐 dubbing/dubbingViewShared 内同名工具）：
 * 原 Vue 直接对 creationStore.formData.*.panels 赋值；formData 为嵌套对象，
 * 写 panels 必须整分支不可变替换（一次 setState 覆盖多个面板字段）。
 */
export function setStoryboardVideoStepFormPanels(next: {
  script?: StoryboardPanel[]
  video?: StoryboardVideoPanel[]
}): void {
  useCreationStore.setState((s) => ({
    formData: {
      ...s.formData,
      ...(next.script !== undefined
        ? { storyboardScript: { ...s.formData.storyboardScript, panels: next.script } }
        : {}),
      ...(next.video !== undefined
        ? { storyboardVideo: { ...s.formData.storyboardVideo, panels: next.video } }
        : {})
    }
  }))
}

/** 原创建器闭包内的可变状态：拆分文件后经该对象显式共享（禁止各文件另建平行状态） */
export interface StoryboardVideoBatchState {
  activePromptTaskId: { value: number | null }
  promptStreamCloser: (() => void) | null
  stopRequested: boolean
  manualPromptAgentModelPick: boolean
  manualVideoModelPick: boolean
  resumeFollowGeneration: number
  batchSseFollowInFlight: boolean
  batchSseFollowDepth: number
  /** 整段 restore / run 进行中（含 detail/list 等待），防止 cancelResumeFollow 误打断 */
  batchRunInFlight: boolean
  restoreSessionInFlight: Promise<void> | null
  promptFollowOwner: {
    taskId: number
    promise: Promise<StoryboardVideoPromptFollowResult>
  } | null
  videoFollowOwner: {
    taskId: number
    promise: Promise<StoryboardVideoGenerateFollowResult>
  } | null
  cachedProjectTaskList: { projectId: number; at: number; rows: UserTaskRow[] } | null
  followIdleBarrier: AsyncIdleBarrier
}

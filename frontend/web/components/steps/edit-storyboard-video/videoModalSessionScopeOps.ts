'use client'

import {
matchesCreationLiveGenScope,
type CreationLiveGenScopeCtx
} from '~/composables/useCreationLiveGenScopeGuard'
import {
findStoryboardVideoGenTaskInScopes
} from '~/composables/useCreationStoreHydration'
import {
activeStoryboardVideoModalOwnedFollowIds,
isStoryboardVideoModalRestoreFollowing
} from '~/composables/useStoryboardVideoBatchGenerate'
import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import {
modalGenSessionScopeFromStore
} from '~/utils/modalGenSessionScope'
import {
isStoryboardVideoModalUserDismissed,
readStoryboardVideoModalGenSession
} from '~/utils/storyboardVideoModalGenSession'
import type { VideoModalCtx,VideoTaskKind } from './types'

/** 对齐 Vue nextTick */
export function createVideoModalSessionScopeOps(ctx: VideoModalCtx) {
  function resolveStoryboardIdForSceneIndex(sceneIdx: number): string {
    const raw = ctx.props().scenes[sceneIdx]?.storyboardId
    const id = Number(raw)
    if (Number.isFinite(id) && id > 0) return String(id)
    return `idx-${sceneIdx}`
  }

  function storyboardVideoModalSessionScope() {
    return modalGenSessionScopeFromStore(ctx.store())
  }

  /** 提交响应晚于项目切换时，任务仍归提交作用域，并在 SSE owner 建立后立即挂起。 */
  function suspendLateModalVideoFollowIfScopeChanged(
    taskId: number,
    taskScope: CreationLiveGenScopeCtx
  ) {
    if (typeof window === 'undefined') return
    queueMicrotask(() => {
      if (!matchesCreationLiveGenScope(taskScope)) suspendTaskSseFollow(taskId)
    })
  }

  function overlayKeyParts(sceneIdx: number, taskKind: string) {
    return {
      editorScopeKey: ctx.props().editorScopeKey,
      sceneIdx,
      entityId: resolveStoryboardIdForSceneIndex(sceneIdx),
      itemIdx: -1,
      taskKind
    }
  }

  function sceneStoryboardIdNum(sceneIdx: number): number | null {
    const raw = ctx.props().scenes[sceneIdx]?.storyboardId
    const id = Number(raw)
    return Number.isFinite(id) && id > 0 ? id : null
  }

  function defaultVideoProgressTextForTaskKind(taskKind?: string): string {
    if (taskKind === 'multi') return '多参视频生成中…'
    if (taskKind === 'edge') return '首尾帧视频生成中…'
    if (taskKind === 'grid') return '宫格视频生成中…'
    return '图生视频生成中…'
  }

  function normalizeModalVideoGenTaskKind(raw: unknown): VideoTaskKind {
    const k = String(raw ?? '')
      .trim()
      .toLowerCase()
    if (k === 'multi') return 'multi'
    if (k === 'edge') return 'edge'
    if (k === 'grid') return 'grid'
    return 'i2v'
  }

  function readSessionForScene(sceneIdx: number) {
    const session = readStoryboardVideoModalGenSession(storyboardVideoModalSessionScope())
    if (!session) return null
    const sid = sceneStoryboardIdNum(sceneIdx)
    if (sid != null && session.storyboardId === sid) return session
    if (session.sceneIdx === sceneIdx) return session
    return null
  }

  function resolveModalVideoGenOwnerSceneIdx(storyboardId: number): number | null {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return null

    const task = findStoryboardVideoGenTaskInScopes(ctx.store(), sid, ctx.route())
    const session = readStoryboardVideoModalGenSession(storyboardVideoModalSessionScope())
    const sessionActive =
      session?.storyboardId === sid &&
      !isStoryboardVideoModalUserDismissed(sid, storyboardVideoModalSessionScope()) &&
      (session.taskId != null || session.taskKind != null)
    const hasActiveGen =
      activeStoryboardVideoModalOwnedFollowIds.has(sid) ||
      isStoryboardVideoModalRestoreFollowing(sid) ||
      !!task ||
      sessionActive
    if (!hasActiveGen) return null

    if (task?.sceneIdx != null && Number.isFinite(task.sceneIdx)) {
      return task.sceneIdx
    }
    if (session?.storyboardId === sid && Number.isFinite(session.sceneIdx)) {
      return session.sceneIdx
    }
    const idx = ctx.props().scenes.findIndex((s) => Number(s?.storyboardId) === sid)
    return idx >= 0 ? idx : null
  }

  function isModalVideoGenOwnerScene(sceneIdx: number): boolean {
    const session = readSessionForScene(sceneIdx)
    if (session?.storyboardId != null) {
      return resolveModalVideoGenOwnerSceneIdx(session.storyboardId) === sceneIdx
    }
    const sid = sceneStoryboardIdNum(sceneIdx)
    if (sid == null) return false
    return resolveModalVideoGenOwnerSceneIdx(sid) === sceneIdx
  }

  function shouldRestoreStoryboardVideoGenerate(sceneIdx: number): boolean {
    const sid = sceneStoryboardIdNum(sceneIdx)
    if (sid == null) return false
    if (ctx.isStoryboardVideoPromptGeneratingForScene(sceneIdx)) return false
    return isModalVideoGenOwnerScene(sceneIdx)
  }

  function resolveVideoGenTaskSnapshotForStoryboard(storyboardId: number, sceneIdx: number) {
    const persisted = findStoryboardVideoGenTaskInScopes(ctx.store(), storyboardId, ctx.route())
    const session = readSessionForScene(sceneIdx)
    const sessionMatches =
      session?.storyboardId === storyboardId &&
      !isStoryboardVideoModalUserDismissed(storyboardId, storyboardVideoModalSessionScope())
    const sessionTaskId =
      sessionMatches && session?.taskId != null && Number(session.taskId) > 0
        ? Number(session.taskId)
        : null
    const sessionTaskKind =
      sessionMatches &&
      session?.taskKind &&
      session.taskKind !== 'video-prompt-gen' &&
      session.taskKind !== 'multi-video-prompt-gen'
        ? normalizeModalVideoGenTaskKind(session.taskKind)
        : null
    return {
      persisted,
      taskId: persisted?.taskId ?? sessionTaskId ?? null,
      taskKind: (persisted?.taskKind ?? sessionTaskKind ?? 'i2v') as VideoTaskKind
    }
  }

  function hasStoryboardVideoPendingState(storyboardId: number): boolean {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return false
    if (activeStoryboardVideoModalOwnedFollowIds.has(sid)) return true
    if (isStoryboardVideoModalRestoreFollowing(sid)) return true
    return !!findStoryboardVideoGenTaskInScopes(ctx.store(), sid, ctx.route())
  }

  function isStoryboardVideoGenerationInProgress(storyboardId: number | null | undefined): boolean {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return false
    return hasStoryboardVideoPendingState(sid)
  }

  function removeLocalGeneratingPlaceholders(videos: any[]): any[] {
    return videos.filter((v) => !v?._localGeneratingPlaceholder)
  }
  return {
    defaultVideoProgressTextForTaskKind,
    hasStoryboardVideoPendingState,
    isModalVideoGenOwnerScene,
    isStoryboardVideoGenerationInProgress,
    normalizeModalVideoGenTaskKind,
    overlayKeyParts,
    readSessionForScene,
    removeLocalGeneratingPlaceholders,
    resolveModalVideoGenOwnerSceneIdx,
    resolveStoryboardIdForSceneIndex,
    resolveVideoGenTaskSnapshotForStoryboard,
    sceneStoryboardIdNum,
    shouldRestoreStoryboardVideoGenerate,
    storyboardVideoModalSessionScope,
    suspendLateModalVideoFollowIfScopeChanged,
  }
}

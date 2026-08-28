'use client'

import {
applyCreationStoreScopeLiveGenFromRoute,
findStoryboardVideoGenTaskInScopes,
waitForCreationStoreHydrated
} from '~/composables/useCreationStoreHydration'
import { buildModalTaskOverlayKey,matchesModalTaskOverlayKey } from '~/composables/useModalTaskScope'
import {
activeStoryboardVideoModalOwnedFollowIds,
isStoryboardVideoModalRestoreFollowing
} from '~/composables/useStoryboardVideoBatchGenerate'
import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import { listModalTabFollowsToSuspend } from '~/utils/modalTabSseMutex'
import {
clearStoryboardVideoModalGenSession
} from '~/utils/storyboardVideoModalGenSession'
import { formatTaskSseLiveText } from '~/utils/taskSseProgressText'
import type { VideoModalCtx,VideoModalSessionApi,VideoTaskKind } from './types'

/** 对齐 Vue nextTick */
import { createVideoModalSessionScopeOps } from './videoModalSessionScopeOps'
function nextTick(fn: () => void) {
  setTimeout(fn, 0)
}

/** 会话作用域 / overlay key / 占位 loading / 归属分镜判定（原 setup 前半段逻辑） */
export function useVideoModalSession(ctx: VideoModalCtx): void {
  const { defaultVideoProgressTextForTaskKind, hasStoryboardVideoPendingState, isModalVideoGenOwnerScene, isStoryboardVideoGenerationInProgress, normalizeModalVideoGenTaskKind, overlayKeyParts, readSessionForScene, removeLocalGeneratingPlaceholders, resolveModalVideoGenOwnerSceneIdx, resolveStoryboardIdForSceneIndex, resolveVideoGenTaskSnapshotForStoryboard, sceneStoryboardIdNum, shouldRestoreStoryboardVideoGenerate, storyboardVideoModalSessionScope, suspendLateModalVideoFollowIfScopeChanged } = createVideoModalSessionScopeOps(ctx)

  function ensureGeneratingPlaceholderVideo(sceneIdx: number) {
    const videos = [...(ctx.props().scenes[sceneIdx]?.videos || [])]
    const pendingIdx = videos.findIndex((v) => v?._generating || v?._localGeneratingPlaceholder)
    if (pendingIdx >= 0) {
      if (sceneIdx === ctx.currentSceneIndex.get()) {
        ctx.selectedVideoIdx.set(pendingIdx)
        scrollVideoCanvasToIndex(sceneIdx, pendingIdx)
      }
      return
    }
    const newIdx = videos.length
    videos.push({
      id: `local-generating-${sceneStoryboardIdNum(sceneIdx) ?? sceneIdx}-${Date.now()}`,
      url: '',
      title: '分镜视频',
      source: '生成记录',
      _generating: true,
      _localGeneratingPlaceholder: true
    })
    ctx.emitUpdate(sceneIdx, { videos })
    if (sceneIdx === ctx.currentSceneIndex.get()) {
      ctx.selectedVideoIdx.set(newIdx)
      scrollVideoCanvasToIndex(sceneIdx, newIdx)
    }
  }

  function clearLocalGeneratingPlaceholdersForScene(sceneIdx: number) {
    const videos = ctx.props().scenes[sceneIdx]?.videos || []
    const next = removeLocalGeneratingPlaceholders(videos)
    if (next.length !== videos.length) {
      ctx.emitUpdate(sceneIdx, { videos: next })
      if (sceneIdx === ctx.currentSceneIndex.get() && ctx.selectedVideoIdx.get() >= next.length) {
        ctx.selectedVideoIdx.set(Math.max(0, next.length - 1))
      }
    }
  }

  /** 拉取服务端记录后，若任务仍在进行则保留/补回本地 generating 占位，避免有视频时刷新丢失 loading */
  function finalizeMappedVideosWhileGenerating(sceneIdx: number, mapped: any[]): any[] {
    const next = removeLocalGeneratingPlaceholders(mapped)
    if (next.some((m) => m?._generating)) {
      return next
    }

    const sid = sceneStoryboardIdNum(sceneIdx)
    if (sid == null) return next

    const stillGenerating = isStoryboardVideoGenerationInProgress(sid)
    if (!stillGenerating) return next

    return [
      ...next,
      {
        id: `local-generating-${sid}-${Date.now()}`,
        url: '',
        title: '分镜视频',
        source: '生成记录',
        _generating: true,
        _localGeneratingPlaceholder: true
      }
    ]
  }

  function clearModalStoryboardVideoLoadingUi(
    storyboardId: number,
    sceneIdx: number,
    taskKind?: VideoTaskKind
  ) {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return

    ctx.store().clearStoryboardVideoGenTask(sid)
    activeStoryboardVideoModalOwnedFollowIds.delete(sid)
    clearStoryboardVideoModalGenSession(storyboardVideoModalSessionScope())
    clearVideoGenerateOverlayForScene(sceneIdx, taskKind)
    clearLocalGeneratingPlaceholdersForScene(sceneIdx)
  }

  /** 同步恢复弹窗内生视频 loading UI（不等待 API），避免刷新后打开弹窗时按钮/画布无 loading */
  function primeStoryboardVideoLoadingUi(sceneIdx: number) {
    const storyboardId = sceneStoryboardIdNum(sceneIdx)
    if (storyboardId == null) return

    const task = findStoryboardVideoGenTaskInScopes(ctx.store(), storyboardId, ctx.route())
    const isFollowing =
      activeStoryboardVideoModalOwnedFollowIds.has(storyboardId) ||
      isStoryboardVideoModalRestoreFollowing(storyboardId)
    const targetKey = ctx.videoGenerateTargetKey.get()
    const overlayActive =
      matchesModalTaskOverlayKey(targetKey, overlayKeyParts(sceneIdx, 'i2v')) ||
      matchesModalTaskOverlayKey(targetKey, overlayKeyParts(sceneIdx, 'multi')) ||
      matchesModalTaskOverlayKey(targetKey, overlayKeyParts(sceneIdx, 'edge')) ||
      matchesModalTaskOverlayKey(targetKey, overlayKeyParts(sceneIdx, 'grid'))

    if (!task && !isFollowing && !overlayActive) return

    const taskKind = task?.taskKind ?? 'i2v'
    ctx.videoGenerateTargetKey.set(buildModalTaskOverlayKey(overlayKeyParts(sceneIdx, taskKind)))
    const live = formatTaskSseLiveText(task || {}, '')
    ctx.videoGenerateProgressText.set(live || defaultVideoProgressTextForTaskKind(taskKind))

    const hasGeneratingRow = (ctx.props().scenes[sceneIdx]?.videos || []).some(
      (v: any) => v?._generating || v?._localGeneratingPlaceholder
    )
    if (!hasGeneratingRow) {
      ensureGeneratingPlaceholderVideo(sceneIdx)
    } else if (sceneIdx === ctx.currentSceneIndex.get()) {
      const genIdx = (ctx.props().scenes[sceneIdx]?.videos || []).findIndex(
        (v: any) => v?._generating || v?._localGeneratingPlaceholder
      )
      if (genIdx >= 0) {
        ctx.selectedVideoIdx.set(genIdx)
        scrollVideoCanvasToIndex(sceneIdx, genIdx)
      }
    }
  }

  /** 为所有进行中的分镜补占位 loading，当前分镜同步 overlay / 按钮态 */
  function ensurePendingStoryboardVideoLoadingPlaceholders(focusSceneIdx: number) {
    ctx.props().scenes.forEach((_, idx) => {
      const storyboardId = sceneStoryboardIdNum(idx)
      if (storyboardId == null || !hasStoryboardVideoPendingState(storyboardId)) {
        clearLocalGeneratingPlaceholdersForScene(idx)
        return
      }
      if (!isModalVideoGenOwnerScene(idx)) {
        clearLocalGeneratingPlaceholdersForScene(idx)
        return
      }
      if (idx === focusSceneIdx) return
      const hasGeneratingRow = (ctx.props().scenes[idx]?.videos || []).some(
        (v: any) => v?._generating || v?._localGeneratingPlaceholder
      )
      if (!hasGeneratingRow) ensureGeneratingPlaceholderVideo(idx)
    })
    if (isModalVideoGenOwnerScene(focusSceneIdx)) {
      primeStoryboardVideoLoadingUi(focusSceneIdx)
    }
  }

  /** 等待持久化恢复后，同步还原弹窗内 loading 状态 */
  async function ensureModalVideoLoadingRestored(sceneIdx: number) {
    await waitForCreationStoreHydrated(ctx.store(), ctx.route())
    applyCreationStoreScopeLiveGenFromRoute(ctx.store(), ctx.route())
    ensurePendingStoryboardVideoLoadingPlaceholders(sceneIdx)
  }

  function isSceneVideoGenerating(sceneIdx: number): boolean {
    const targetKey = ctx.videoGenerateTargetKey.get()
    if (
      matchesModalTaskOverlayKey(targetKey, overlayKeyParts(sceneIdx, 'i2v')) ||
      matchesModalTaskOverlayKey(targetKey, overlayKeyParts(sceneIdx, 'multi')) ||
      matchesModalTaskOverlayKey(targetKey, overlayKeyParts(sceneIdx, 'edge')) ||
      matchesModalTaskOverlayKey(targetKey, overlayKeyParts(sceneIdx, 'grid'))
    ) {
      return true
    }
    const storyboardId = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
    if (Number.isFinite(storyboardId) && storyboardId > 0) {
      if (hasStoryboardVideoPendingState(storyboardId)) {
        return isModalVideoGenOwnerScene(sceneIdx)
      }
    }
    const videos = ctx.props().scenes[sceneIdx]?.videos || []
    if (!videos.some((v: { _generating?: boolean }) => !!v._generating)) return false
    return Number.isFinite(storyboardId) && storyboardId > 0 && isModalVideoGenOwnerScene(sceneIdx)
  }

  function clearVideoGenerateOverlayForScene(sceneIdx: number, taskKind?: VideoTaskKind) {
    const kinds = taskKind ? [taskKind] : (['i2v', 'multi', 'edge', 'grid'] as const)
    for (const k of kinds) {
      if (matchesModalTaskOverlayKey(ctx.videoGenerateTargetKey.get(), overlayKeyParts(sceneIdx, k))) {
        ctx.videoGenerateTargetKey.set('')
        ctx.videoGenerateProgressText.set('分镜视频提交中…')
        return
      }
    }
  }

  function resolveGeneratingVideoIndex(): number {
    const list = ctx.currentSceneVideos()
    const pendingIdx = list.findIndex((item: any) => item?._generating)
    if (pendingIdx >= 0) return pendingIdx
    if (!isSceneVideoGenerating(ctx.currentSceneIndex.get())) return -1
    const localIdx = list.findIndex((item: any) => item?._localGeneratingPlaceholder)
    if (localIdx >= 0) return localIdx
    return list.length > 0 ? list.length - 1 : -1
  }

  function isVideoCanvasItemGenerating(videoIndex: number): boolean {
    const v = ctx.currentSceneVideos()[videoIndex]
    if (!isSceneVideoGenerating(ctx.currentSceneIndex.get())) return false
    if (v?._generating) return true
    const genIdx = resolveGeneratingVideoIndex()
    return genIdx >= 0 && videoIndex === genIdx
  }

  function scrollVideoCanvasToIndex(sceneIdx: number, index: number) {
    if (sceneIdx !== ctx.currentSceneIndex.get() || index < 0) return
    nextTick(() => {
      nextTick(() => {
        const body = ctx.videoCanvasBodyRef.current
        if (!body) return
        const target =
          (body.querySelector(`[data-video-canvas-idx="${index}"]`) as HTMLElement | null) ||
          (body.querySelectorAll('.video-card')[index] as HTMLElement | undefined)
        if (target) {
          target.scrollIntoView({ behavior: 'smooth', block: 'center' })
          return
        }
        body.scrollTo({ top: body.scrollHeight, behavior: 'smooth' })
      })
    })
  }

  /** 左侧生成记录点击：选中并滚动中间列表到对应视频 */
  function selectHistoryVideo(idx: number) {
    if (idx < 0 || idx >= ctx.currentSceneVideos().length) return
    ctx.selectedVideoIdx.set(idx)
    scrollVideoCanvasToIndex(ctx.currentSceneIndex.get(), idx)
  }

  function isHistoryVideoMain(videoIndex: number): boolean {
    return !!ctx.currentSceneVideos()[videoIndex]?.isStoryboardVideo
  }

  function canSetMainFromHistory(videoIndex: number): boolean {
    const video = ctx.currentSceneVideos()[videoIndex]
    if (!video?.url || isHistoryVideoItemGenerating(videoIndex) || isHistoryVideoMain(videoIndex)) {
      return false
    }
    return true
  }

  function isHistoryVideoItemGenerating(videoIndex: number): boolean {
    return isVideoCanvasItemGenerating(videoIndex)
  }

  /** 顶部 Tab 互斥：挂起非当前分镜的视频 SSE */
  function suspendOtherStoryboardVideoModalFollows(keepStoryboardId: number | null) {
    const keepKey = keepStoryboardId != null && keepStoryboardId > 0 ? String(keepStoryboardId) : ''
    const activeFollows: Array<{ tabKey: string; taskId: number }> = []
    for (const sid of activeStoryboardVideoModalOwnedFollowIds) {
      const task = findStoryboardVideoGenTaskInScopes(ctx.store(), sid, ctx.route())
      const tid = Number(task?.taskId)
      if (!Number.isFinite(tid) || tid <= 0) continue
      activeFollows.push({ tabKey: String(sid), taskId: tid })
    }
    const toSuspend = listModalTabFollowsToSuspend({
      currentTabKey: keepKey,
      activeFollows
    })
    for (const tid of toSuspend) {
      suspendTaskSseFollow(tid)
    }
    for (const sid of [...activeStoryboardVideoModalOwnedFollowIds]) {
      if (keepStoryboardId != null && sid === keepStoryboardId) continue
      activeStoryboardVideoModalOwnedFollowIds.delete(sid)
    }
  }

  function storyboardVideoBizErr(e: unknown): string {
    const x = e as { msg?: string; message?: string }
    return x?.msg || x?.message || '操作失败'
  }

  // 原 showXxxGenerateLoading computed：按当前分镜 overlay key 匹配
  function showImageToVideoGenerateLoadingGet(): boolean {
    return matchesModalTaskOverlayKey(
      ctx.videoGenerateTargetKey.get(),
      overlayKeyParts(ctx.currentSceneIndex.get(), 'i2v')
    )
  }
  function showMultiParamGenerateLoadingGet(): boolean {
    return matchesModalTaskOverlayKey(
      ctx.videoGenerateTargetKey.get(),
      overlayKeyParts(ctx.currentSceneIndex.get(), 'multi')
    )
  }
  function showEdgeVideoGenerateLoadingGet(): boolean {
    return matchesModalTaskOverlayKey(
      ctx.videoGenerateTargetKey.get(),
      overlayKeyParts(ctx.currentSceneIndex.get(), 'edge')
    )
  }
  function showGridVideoGenerateLoadingGet(): boolean {
    return matchesModalTaskOverlayKey(
      ctx.videoGenerateTargetKey.get(),
      overlayKeyParts(ctx.currentSceneIndex.get(), 'grid')
    )
  }

  const api: VideoModalSessionApi = {
    resolveStoryboardIdForSceneIndex,
    storyboardVideoModalSessionScope,
    suspendLateModalVideoFollowIfScopeChanged,
    overlayKeyParts,
    sceneStoryboardIdNum,
    defaultVideoProgressTextForTaskKind,
    normalizeModalVideoGenTaskKind,
    readSessionForScene,
    resolveModalVideoGenOwnerSceneIdx,
    isModalVideoGenOwnerScene,
    shouldRestoreStoryboardVideoGenerate,
    resolveVideoGenTaskSnapshotForStoryboard,
    hasStoryboardVideoPendingState,
    isStoryboardVideoGenerationInProgress,
    removeLocalGeneratingPlaceholders,
    ensureGeneratingPlaceholderVideo,
    clearLocalGeneratingPlaceholdersForScene,
    finalizeMappedVideosWhileGenerating,
    clearModalStoryboardVideoLoadingUi,
    primeStoryboardVideoLoadingUi,
    ensurePendingStoryboardVideoLoadingPlaceholders,
    ensureModalVideoLoadingRestored,
    isSceneVideoGenerating,
    clearVideoGenerateOverlayForScene,
    isVideoCanvasItemGenerating,
    resolveGeneratingVideoIndex,
    scrollVideoCanvasToIndex,
    selectHistoryVideo,
    isHistoryVideoMain,
    canSetMainFromHistory,
    isHistoryVideoItemGenerating,
    suspendOtherStoryboardVideoModalFollows,
    storyboardVideoBizErr,
    showImageToVideoGenerateLoadingGet,
    showMultiParamGenerateLoadingGet,
    showEdgeVideoGenerateLoadingGet,
    showGridVideoGenerateLoadingGet
  }
  Object.assign(ctx, api)
}

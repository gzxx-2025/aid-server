'use client'

import {
captureCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import {
applyCreationStoreScopeLiveGenFromRoute,
clearStoryboardImageGenTaskInAllScopes,
findStoryboardImageGenTaskInScopes,
waitForCreationStoreHydrated
} from '~/composables/useCreationStoreHydration'
import { buildModalTaskOverlayKey,matchesModalTaskOverlayKey } from '~/composables/useModalTaskScope'
import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import { useCreationStore } from '~/stores/creation'
import {
type ModalGenSessionScope
} from '~/utils/modalGenSessionScope'
import { listModalTabFollowsToSuspend } from '~/utils/modalTabSseMutex'
import {
clearModalImageGenSession,
clearModalImageGenUserDismissed,
isModalImageGenUserDismissed,
markModalImageGenUserDismissed,
persistModalImageGenSession,
readModalImageGenSession,
type ModalImageGenSessionTab
} from '~/utils/storyboardImageModalGenSession'
import {
activeStoryboardImageModalDialogueFollowIds,
activeStoryboardImageModalGenFollowIds,
activeStoryboardImageModalOverlayFollowIds,
releaseStoryboardImageModalLiveOwned
} from '~/utils/storyboardImageModalOwnedFollow'
import {
formatTaskSseLiveText,
formatTaskSseLiveTextWithCounts
} from '~/utils/taskSseProgressText'
import {
appendLocalGeneratingPlaceholder,
isModalOverlaySessionTab,
removeLocalGeneratingPlaceholders
} from './sessionPlaceholders'
import { createStoryboardModalSessionCore } from './storyboardModalSessionCore'
import type { EditStoryboardImageModalCtx } from './types'

const activeStoryboardImageFollowStoryboardIds = activeStoryboardImageModalGenFollowIds
const activeDialogueFollowStoryboardIds = activeStoryboardImageModalDialogueFollowIds
const activeCanvasOverlayFollowStoryboardIds = activeStoryboardImageModalOverlayFollowIds

export interface StoryboardModalSessionStateApi {
  storyboardImageModalSessionScope: () => ModalGenSessionScope | null
  resolveStoryboardIdForSceneIndex: (sceneIdx: number) => string
  overlayKeyParts: (
    sceneIdx: number,
    imgIdx: number,
    taskKind: string
  ) => {
    editorScopeKey: string
    sceneIdx: number
    entityId: string
    itemIdx: number
    taskKind: string
  }
  getModalImageGenTask: (
    storyboardId: number
  ) => ReturnType<typeof findStoryboardImageGenTaskInScopes>
  isDialogueModalTask: (task: ReturnType<typeof findStoryboardImageGenTaskInScopes>) => boolean
  isCanvasOverlayModalTask: (
    task: ReturnType<typeof findStoryboardImageGenTaskInScopes> | null | undefined
  ) => boolean
  sceneHasCompletedGeneratedImage: (sceneIdx: number) => boolean
  hasActiveModalImageGenSession: (storyboardId: number) => boolean
  clearStoryboardPanelImageGenerating: (storyboardId: number) => void
  readSessionForScene: (sceneIdx: number) => ReturnType<typeof readModalImageGenSession>
  isModalOverlaySessionTab: (tab?: ModalImageGenSessionTab) => boolean
  isModalStoryboardGenerateSession: (
    session: ReturnType<typeof readModalImageGenSession>
  ) => boolean
  shouldRestoreStoryboardImageGenerate: (sceneIdx: number) => boolean
  clearStaleStoryboardGenUiForScene: (sceneIdx: number) => void
  isStoryboardImageGenerationInProgress: (storyboardId: number) => boolean
  isDialogueGenerationInProgress: (storyboardId: number) => boolean
  isModalOverlayGenerationInProgress: (storyboardId: number) => boolean
  sceneStoryboardIdNum: (sceneIdx: number) => number | null
  resolveModalImageGenOwnerSceneIdx: (storyboardId: number) => number | null
  isModalImageGenOwnerScene: (sceneIdx: number) => boolean
  hasModalImageGenPendingState: (storyboardId: number) => boolean
  isAnyModalGenerationPendingForScene: (sceneIdx: number) => boolean
  syncModalImageGenSessionTaskId: (
    storyboardId: number,
    sceneIdx: number,
    taskId: number,
    extra?: { tab?: ModalImageGenSessionTab; imageIdx?: number },
    sessionScope?: ModalGenSessionScope | null,
    scopeKey?: string
  ) => void
  suspendLateModalImageFollowIfScopeChanged: (
    taskId: number,
    taskScope: ReturnType<typeof captureCreationLiveGenScope>
  ) => void
  isSceneModalImageGenerating: (sceneIdx: number) => boolean
  clearStaleModalGeneratingPlaceholders: () => void
  removeLocalGeneratingPlaceholders: (images: any[]) => any[]
  appendLocalGeneratingPlaceholder: (next: any[], sid: number) => any[]
  ensureGeneratingPlaceholderImage: (sceneIdx: number) => void
  ensureOverlayGeneratingPlaceholderImage: (sceneIdx: number) => void
  clearLocalGeneratingPlaceholdersForScene: (sceneIdx: number) => void
  finalizeMappedImagesWhileGenerating: (sceneIdx: number, mapped: any[]) => any[]
  isModalStoryboardImageUiActive: (
    storyboardId: number | null | undefined,
    sceneIdx?: number
  ) => boolean
  clearModalStoryboardImageGenTaskEverywhere: (storyboardId: number) => void
  clearModalStoryboardImageLoadingUi: (storyboardId: number, sceneIdx: number) => void
  clearModalDialogueLoadingUi: (storyboardId: number, sceneIdx: number, imageIdx: number) => void
  dismissModalDialogueUi: (storyboardId: number, sceneIdx: number, imageIdx: number) => void
  dismissModalStoryboardImageUi: (storyboardId: number, sceneIdx: number) => void
  primeCanvasOverlayFromSession: (sceneIdx: number) => void
  ensureModalSessionFromStoreTask: (sceneIdx: number) => void
  ensureModalLoadingRestored: (sceneIdx: number) => Promise<void>
  isStoryboardPanelImageGenerating: (storyboardId: number | null | undefined) => boolean
  primeStoryboardImageLoadingUi: (sceneIdx: number) => void
  primeDialogueLoadingUi: (sceneIdx: number) => void
  suspendOtherStoryboardImageModalFollows: (keepStoryboardId: number | null) => void
}

export function useStoryboardModalSessionState(
  ctx: EditStoryboardImageModalCtx
): StoryboardModalSessionStateApi {
  const sessionCore = createStoryboardModalSessionCore(ctx)
  const {
    storyboardImageModalSessionScope,
    resolveStoryboardIdForSceneIndex,
    overlayKeyParts,
    getModalImageGenTask,
    isDialogueModalTask,
    isCanvasOverlayModalTask,
    sceneHasCompletedGeneratedImage,
    hasActiveModalImageGenSession,
    clearStoryboardPanelImageGenerating,
    readSessionForScene,
    isModalStoryboardGenerateSession,
    shouldRestoreStoryboardImageGenerate,
    clearStaleStoryboardGenUiForScene,
    isStoryboardImageGenerationInProgress,
    isDialogueGenerationInProgress,
    isModalOverlayGenerationInProgress,
    sceneStoryboardIdNum,
    resolveModalImageGenOwnerSceneIdx,
    isModalImageGenOwnerScene,
    hasModalImageGenPendingState,
    isAnyModalGenerationPendingForScene,
    syncModalImageGenSessionTaskId,
    suspendLateModalImageFollowIfScopeChanged,
    isSceneModalImageGenerating,
    clearStaleModalGeneratingPlaceholders,
    ensureGeneratingPlaceholderImage,
    ensureOverlayGeneratingPlaceholderImage,
    clearLocalGeneratingPlaceholdersForScene,
    finalizeMappedImagesWhileGenerating
  } = sessionCore
  function isModalStoryboardImageUiActive(
    storyboardId: number | null | undefined,
    sceneIdx?: number
  ): boolean {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return false
    if (isModalImageGenUserDismissed(sid, storyboardImageModalSessionScope())) return false
    const task = getModalImageGenTask(storyboardId as number)
    if (isDialogueModalTask(task) || isCanvasOverlayModalTask(task)) return false
    if (activeStoryboardImageFollowStoryboardIds.has(sid)) return true
    if (task) return true
    if (
      hasActiveModalImageGenSession(storyboardId as number) &&
      readModalImageGenSession(storyboardImageModalSessionScope())?.tab !== 'dialogue' &&
      !isModalOverlaySessionTab(readModalImageGenSession(storyboardImageModalSessionScope())?.tab)
    ) {
      return true
    }
    if (
      sceneIdx != null &&
      matchesModalTaskOverlayKey(
        ctx.storyboardGenerateTargetKey.get(),
        overlayKeyParts(sceneIdx, -1, 'storyboard-gen')
      )
    ) {
      return true
    }
    return false
  }

  function clearModalStoryboardImageGenTaskEverywhere(storyboardId: number) {
    clearStoryboardImageGenTaskInAllScopes(ctx.store(), storyboardId, ctx.route())
  }

  function clearModalStoryboardImageLoadingUi(storyboardId: number, sceneIdx: number) {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return

    clearModalStoryboardImageGenTaskEverywhere(sid)
    clearModalImageGenSession(storyboardImageModalSessionScope())
    clearModalImageGenUserDismissed(storyboardImageModalSessionScope())
    activeStoryboardImageFollowStoryboardIds.delete(sid)
    clearStoryboardPanelImageGenerating(sid)

    if (!activeStoryboardImageFollowStoryboardIds.size) {
      ctx.isGeneratingStoryboardImage.set(false)
    }

    const overlayKey = buildModalTaskOverlayKey(overlayKeyParts(sceneIdx, -1, 'storyboard-gen'))
    if (ctx.storyboardGenerateTargetKey.get() === overlayKey) {
      ctx.storyboardGenerateTargetKey.set('')
    }
    ctx.storyboardGenerateProgressText.set('分镜图生成中…')
    clearLocalGeneratingPlaceholdersForScene(sceneIdx)
  }

  function clearModalDialogueLoadingUi(storyboardId: number, sceneIdx: number, imageIdx: number) {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return

    ctx.store().clearStoryboardImageGenTask(sid)
    clearModalImageGenSession(storyboardImageModalSessionScope())
    clearModalImageGenUserDismissed(storyboardImageModalSessionScope())
    activeDialogueFollowStoryboardIds.delete(sid)
    clearStoryboardPanelImageGenerating(sid)

    ctx.endCanvasTaskOverlay()
    clearLocalGeneratingPlaceholdersForScene(sceneIdx)
    void imageIdx
  }

  /** 用户主动关闭弹窗：仅清除对话作图 UI，后台任务继续在弹窗外执行 */
  function dismissModalDialogueUi(storyboardId: number, sceneIdx: number, imageIdx: number) {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return

    markModalImageGenUserDismissed(sid, storyboardImageModalSessionScope())
    clearModalImageGenSession(storyboardImageModalSessionScope())
    releaseStoryboardImageModalLiveOwned(sid)

    const overlayKey = buildModalTaskOverlayKey(overlayKeyParts(sceneIdx, imageIdx, 'dialogue'))
    if (ctx.upscaleTargetKey.get() === overlayKey) {
      ctx.endCanvasTaskOverlay()
    }
  }

  /** 用户主动关闭弹窗：仅清除弹窗 UI，后台任务继续在弹窗外执行 */
  function dismissModalStoryboardImageUi(storyboardId: number, sceneIdx: number) {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return

    markModalImageGenUserDismissed(sid, storyboardImageModalSessionScope())
    clearModalImageGenSession(storyboardImageModalSessionScope())
    releaseStoryboardImageModalLiveOwned(sid)

    if (!activeStoryboardImageFollowStoryboardIds.size) {
      ctx.isGeneratingStoryboardImage.set(false)
    }

    const overlayKey = buildModalTaskOverlayKey(overlayKeyParts(sceneIdx, -1, 'storyboard-gen'))
    if (ctx.storyboardGenerateTargetKey.get() === overlayKey) {
      ctx.storyboardGenerateTargetKey.set('')
    }
    ctx.storyboardGenerateProgressText.set('分镜图生成中…')
  }

  /** 同步恢复变清晰/多机位/九宫格画布 loading（不等待 API） */
  function primeCanvasOverlayFromSession(sceneIdx: number) {
    const session = readSessionForScene(sceneIdx)
    if (!session || !isModalOverlaySessionTab(session.tab)) return

    const imageIdx = session.imageIdx ?? ctx.currentImageIndex.get()
    const tab = session.tab as Extract<
      ModalImageGenSessionTab,
      'upscale' | 'multiangle' | 'ninegrid'
    >
    const defaultText =
      tab === 'upscale' ? '高清处理中…' : tab === 'ninegrid' ? '九宫格生图中...' : '多机位生图中...'

    if (sceneIdx === ctx.currentSceneIndex.get()) {
      ctx.currentImageIndex.set(imageIdx)
    }

    ctx.beginCanvasTaskOverlay(sceneIdx, imageIdx, tab, defaultText, { persistSession: false })
  }

  function ensureModalSessionFromStoreTask(sceneIdx: number) {
    if (readSessionForScene(sceneIdx)) return
    const storyboardId = sceneStoryboardIdNum(sceneIdx)
    if (storyboardId == null) return
    const task = getModalImageGenTask(storyboardId)
    if (!task) return
    const tab: ModalImageGenSessionTab =
      task.kind === 'dialogue'
        ? 'dialogue'
        : task.kind === 'upscale'
          ? 'upscale'
          : task.kind === 'ninegrid'
            ? 'ninegrid'
            : task.kind === 'multiangle'
              ? 'multiangle'
              : 'generate'
    persistModalImageGenSession(storyboardId, sceneIdx, ctx.store().step3GenVisualScopeKey(), {
      tab,
      imageIdx: task.imageIdx,
      taskId: task.taskId
    })
  }

  /** 等待 Pinia 持久化恢复后，同步还原弹窗内 loading 状态，并联动分镜列表卡片 loading */
  async function ensureModalLoadingRestored(sceneIdx: number) {
    await waitForCreationStoreHydrated(ctx.store(), ctx.route())
    applyCreationStoreScopeLiveGenFromRoute(useCreationStore.getState(), ctx.route())
    ensureModalSessionFromStoreTask(sceneIdx)
    clearStaleModalGeneratingPlaceholders()
    if (!isModalImageGenOwnerScene(sceneIdx)) return
    primeStoryboardImageLoadingUi(sceneIdx)
    primeDialogueLoadingUi(sceneIdx)
    primeCanvasOverlayFromSession(sceneIdx)
  }

  function isStoryboardPanelImageGenerating(storyboardId: number | null | undefined): boolean {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return false
    return ctx.store().storyboardPanelImageGenStatusByStoryboardId[String(sid)] === 'generating'
  }

  /** 同步恢复弹窗内 loading UI（不等待 API），避免刷新后打开弹窗时按钮/画布无 loading */
  function primeStoryboardImageLoadingUi(sceneIdx: number) {
    const storyboardId = sceneStoryboardIdNum(sceneIdx)
    if (storyboardId == null) return
    if (isModalImageGenUserDismissed(storyboardId, storyboardImageModalSessionScope())) return
    if (!isModalImageGenOwnerScene(sceneIdx)) return

    const task = getModalImageGenTask(storyboardId)
    if (isDialogueModalTask(task) || isCanvasOverlayModalTask(task)) return

    const session = readSessionForScene(sceneIdx)
    const hasPendingTask = !!task
    const sessionActive =
      !!session &&
      session.storyboardId === storyboardId &&
      (session.tab === 'generate' || !session.tab) &&
      hasActiveModalImageGenSession(storyboardId)
    const isFollowing = activeStoryboardImageFollowStoryboardIds.has(storyboardId)
    if (!hasPendingTask && !sessionActive && !isFollowing) return

    const overlayKey = buildModalTaskOverlayKey(overlayKeyParts(sceneIdx, -1, 'storyboard-gen'))
    ctx.storyboardGenerateTargetKey.set(overlayKey)
    ctx.isGeneratingStoryboardImage.set(true)
    const creationStore = ctx.store()
    const batchLive = formatTaskSseLiveTextWithCounts(
      creationStore.storyboardImageBatchProgress,
      '分镜图生成中'
    )
    const singleLive = formatTaskSseLiveText(task || {}, '')
    if (singleLive || (creationStore.isGeneratingStoryboardImageBatch && batchLive)) {
      ctx.storyboardGenerateProgressText.set(singleLive || batchLive)
    }

    const hasGeneratingRow = (ctx.props().scenes[sceneIdx]?.images || []).some(
      (img) => img?._generating
    )
    if (!hasGeneratingRow) {
      ensureGeneratingPlaceholderImage(sceneIdx)
    }
  }

  /** 同步恢复对话作图画布 loading（不等待 API） */
  function primeDialogueLoadingUi(sceneIdx: number) {
    const storyboardId = sceneStoryboardIdNum(sceneIdx)
    if (storyboardId == null) return
    if (isModalImageGenUserDismissed(storyboardId, storyboardImageModalSessionScope())) return
    if (!isModalImageGenOwnerScene(sceneIdx)) return

    const task = getModalImageGenTask(storyboardId)
    const session = readSessionForScene(sceneIdx)
    const isDialogue = isDialogueModalTask(task) || session?.tab === 'dialogue'
    if (!isDialogue) return
    const isFollowing = activeDialogueFollowStoryboardIds.has(storyboardId)
    const sessionActive =
      !!session && session.tab === 'dialogue' && hasActiveModalImageGenSession(storyboardId)
    if (!task && !isFollowing && !sessionActive) return

    const imageIdx = task?.imageIdx ?? session?.imageIdx ?? ctx.currentImageIndex.get()
    if (sceneIdx === ctx.currentSceneIndex.get()) {
      ctx.leftActiveTab.set('dialogue')
      ctx.currentImageIndex.set(imageIdx)
    }

    ctx.upscaleTargetKey.set(
      buildModalTaskOverlayKey(overlayKeyParts(sceneIdx, imageIdx, 'dialogue'))
    )
    ctx.upscaleUiPhase.set('running')
    ctx.canvasOverlayTaskKind.set('dialogue')
    const live = formatTaskSseLiveText(task || {}, '')
    ctx.upscaleProgressText.set(live || '对话作图中...')
  }

  /** 顶部 Tab 互斥：只保留目标分镜的浏览器 SSE */
  function suspendOtherStoryboardImageModalFollows(keepStoryboardId: number | null) {
    const keepKey = keepStoryboardId != null && keepStoryboardId > 0 ? String(keepStoryboardId) : ''
    const owned = new Set<number>([
      ...activeStoryboardImageFollowStoryboardIds,
      ...activeDialogueFollowStoryboardIds,
      ...activeCanvasOverlayFollowStoryboardIds
    ])
    const activeFollows: Array<{ tabKey: string; taskId: number }> = []
    for (const sid of owned) {
      const task = getModalImageGenTask(sid)
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
    for (const sid of [...owned]) {
      if (keepStoryboardId != null && sid === keepStoryboardId) continue
      releaseStoryboardImageModalLiveOwned(sid)
    }
  }

  return {
    storyboardImageModalSessionScope,
    resolveStoryboardIdForSceneIndex,
    overlayKeyParts,
    getModalImageGenTask,
    isDialogueModalTask,
    isCanvasOverlayModalTask,
    sceneHasCompletedGeneratedImage,
    hasActiveModalImageGenSession,
    clearStoryboardPanelImageGenerating,
    readSessionForScene,
    isModalOverlaySessionTab,
    isModalStoryboardGenerateSession,
    shouldRestoreStoryboardImageGenerate,
    clearStaleStoryboardGenUiForScene,
    isStoryboardImageGenerationInProgress,
    isDialogueGenerationInProgress,
    isModalOverlayGenerationInProgress,
    sceneStoryboardIdNum,
    resolveModalImageGenOwnerSceneIdx,
    isModalImageGenOwnerScene,
    hasModalImageGenPendingState,
    isAnyModalGenerationPendingForScene,
    syncModalImageGenSessionTaskId,
    suspendLateModalImageFollowIfScopeChanged,
    isSceneModalImageGenerating,
    clearStaleModalGeneratingPlaceholders,
    removeLocalGeneratingPlaceholders,
    appendLocalGeneratingPlaceholder,
    ensureGeneratingPlaceholderImage,
    ensureOverlayGeneratingPlaceholderImage,
    clearLocalGeneratingPlaceholdersForScene,
    finalizeMappedImagesWhileGenerating,
    isModalStoryboardImageUiActive,
    clearModalStoryboardImageGenTaskEverywhere,
    clearModalStoryboardImageLoadingUi,
    clearModalDialogueLoadingUi,
    dismissModalDialogueUi,
    dismissModalStoryboardImageUi,
    primeCanvasOverlayFromSession,
    ensureModalSessionFromStoreTask,
    ensureModalLoadingRestored,
    isStoryboardPanelImageGenerating,
    primeStoryboardImageLoadingUi,
    primeDialogueLoadingUi,
    suspendOtherStoryboardImageModalFollows
  }
}

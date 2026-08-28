'use client'

import {
applyStep3GenVisualFromRoute,
waitForCreationStoreHydrated
} from '~/composables/useCreationStoreHydration'
import type { SceneModalSseTaskKind,SceneModalSseTaskSnapshot } from '~/stores/creation'
import {
buildModalFollowLockKey,
shouldApplyModalTaskProgressToCanvas
} from '~/utils/liveGenScopeIsolation'
import {
nextModalSseRestoreDelayMs,
resolveDeferredModalFollowHandling
} from '~/utils/modalSseFollowReconnectPolicy'
import {
isSceneImageModalUserDismissed,
persistSceneImageModalGenSession,
readSceneImageModalGenSession
} from '~/utils/sceneImageModalGenSession'
import { shouldRelinquishModalFollowOnDeferred } from '~/utils/sceneModalOuterFollowHandoff'
import { legacyBareFormSlotAliasOf } from '~/utils/step3FormEditorScopeKey'
import { formatTaskSseLiveText } from '~/utils/taskSseProgressText'
import {
defaultProgressTextForTaskKind,
removeLocalGeneratingPlaceholders
} from './sceneModalTaskParsers'
import type {
EditSceneImageModalCtx,
SceneModalTaskStateApi
} from './types'
import { useSceneModalTaskStateCore } from './useSceneModalTaskStateCore'

export type { SceneModalTaskStateApi }

export function useSceneModalTaskState(ctx: EditSceneImageModalCtx): SceneModalTaskStateApi {
  const stateCore = useSceneModalTaskStateCore(ctx, {
    clearSceneModalGeneratingUi,
    isHistoryItemGenerating,
    readSessionForScene,
    isSceneModalImageGenerating,
    clearLocalGeneratingPlaceholders
  })
  const {
    activeSceneModalFollowScopeKeys,
    sceneModalDeferredRestoreAttempts,
    sceneModalDeferredRestoreTimers,
    sceneModalSessionScope,
    currentModalLiveGenScopeKey,
    sceneModalTaskCleanupDecision,
    canClearSceneModalTaskUi,
    clearSceneModalTaskStateIfOwned,
    claimFormImagesForModal,
    suspendSceneModalFollowsExceptEditorScope,
    showUpscaleFailedOverlay,
    isCanvasTaskOverlayActive,
    showCanvasTaskRunningOverlay,
    sceneGenerateOverlayText,
    showSettingCardToolbarLoading,
    showUpscaleToolbarLoading,
    showMultiViewToolbarLoading,
    showCurrentGeneratingPlaceholder,
    resolveActiveSceneModalTaskKind,
    showEditGenerateButtonLoading,
    showDialogueGenerateButtonLoading,
    showGenerateFooterButtonLoading,
    clearUpscaleOverlay,
    resolvePersistedSceneModalSseTask,
    rebuildPersistedFromSession,
    syncExternalGeneratingForModalScope,
    slotHasLoadedImagesForModal,
    markExternalGeneratingCompleteForModalScope,
    clearStaleSceneModalGeneratingState,
    clearExternalGeneratingForModalScope,
    collectModalFormIdsForSceneIndex,
    isEditorScopeGeneratingExternally,
    resolveImageIdxByRpsImageId,
    addModalFollowLock,
    hasModalFollowLock,
    deleteModalFollowLock,
    resetSceneModalDeferredRestoreState
  } = stateCore
  function handleDeferredSceneModalFollow(opts: {
    sceneIdx: number
    editorScopeKey: string
    liveGenScopeKey?: string
    errorMessage?: unknown
  }): boolean {
    const liveGenScopeKey = opts.liveGenScopeKey || currentModalLiveGenScopeKey()
    const attemptKey = buildModalFollowLockKey(liveGenScopeKey, opts.editorScopeKey)
    const attemptCount = attemptKey ? sceneModalDeferredRestoreAttempts.get(attemptKey) ?? 0 : 0
    const decision = resolveDeferredModalFollowHandling({
      errorMessage: opts.errorMessage,
      restoreAttemptCount: attemptCount
    })

    if (decision.kind === 'superseded') {
      // 关窗主动 suspend 也会标 superseded：须 endFollow 交给外层，不能按「继任接管」让出
      return shouldRelinquishModalFollowOnDeferred({
        modalOpen: !!ctx.props().open,
        decisionKind: 'superseded'
      })
    }

    if (opts.editorScopeKey) {
      deleteModalFollowLock(opts.editorScopeKey, liveGenScopeKey)
    }

    if (decision.kind === 'stop' || !attemptKey || typeof window === 'undefined') {
      return false
    }

    sceneModalDeferredRestoreAttempts.set(attemptKey, attemptCount + 1)
    const delayMs = nextModalSseRestoreDelayMs({ attemptCount })
    const gen = ctx.resumeSceneModalFollowGen.current
    const prevTimer = sceneModalDeferredRestoreTimers.get(attemptKey)
    if (prevTimer) clearTimeout(prevTimer)
    const timer = setTimeout(() => {
      sceneModalDeferredRestoreTimers.delete(attemptKey)
      if (!ctx.props().open || gen !== ctx.resumeSceneModalFollowGen.current) return
      void ctx.restoreSceneModalSseIfNeeded(opts.sceneIdx)
    }, delayMs)
    sceneModalDeferredRestoreTimers.set(attemptKey, timer)
    return false
  }

  function applyCanvasProgressIfCurrent(opts: {
    liveGenScopeKey: string
    editorScopeKey: string
    taskId?: number | null
    text: string
  }) {
    const expectedTaskId = Number(opts.taskId)
    const currentTask = ctx.store().getSceneModalSseTask(
      opts.editorScopeKey,
      opts.liveGenScopeKey
    )
    if (Number.isFinite(expectedTaskId) && expectedTaskId > 0) {
      if (Number(currentTask?.taskId) !== expectedTaskId) return
    } else if (currentTask) {
      // 尚未取得 taskId 的提交态不得覆盖同 editorScope 已知任务的进度。
      return
    }
    if (
      !shouldApplyModalTaskProgressToCanvas({
        taskLiveGenScopeKey: opts.liveGenScopeKey,
        currentLiveGenScopeKey: currentModalLiveGenScopeKey(),
        taskEditorScopeKey: opts.editorScopeKey,
        currentEditorScopeKey: ctx.buildEditorScopeKeyForSceneIndex(ctx.currentSceneIndex.get()),
        modalOpen: ctx.props().open
      })
    ) {
      return
    }
    ctx.upscaleProgressText.set(opts.text)
  }

  function persistSceneModalSseTask(
    sceneIdx: number,
    imageIdx: number,
    taskKind: SceneModalSseTaskKind,
    taskId: number,
    extra?: {
      formId?: number | null
      imageId?: number | null
      message?: string
      stepTitle?: string
    }
  ) {
    const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
    if (!editorScopeKey) return
    const liveGenScopeKey = currentModalLiveGenScopeKey()
    addModalFollowLock(editorScopeKey, liveGenScopeKey)
    const prev = resolvePersistedSceneModalSseTask(editorScopeKey)
    ctx.store().setSceneModalSseTask(
      editorScopeKey,
      {
        taskId,
        taskKind,
        sceneIdx,
        imageIdx,
        editorScopeKey,
        formId: extra?.formId ?? ctx.resolveFormIdForSceneIndex(sceneIdx),
        imageId: extra?.imageId ?? null,
        ...(extra?.message ? { message: extra.message } : prev?.message ? { message: prev.message } : {}),
        ...(extra?.stepTitle
          ? { stepTitle: extra.stepTitle }
          : prev?.stepTitle
            ? { stepTitle: prev.stepTitle }
            : {})
      },
      liveGenScopeKey
    )
    // 写入带类型前缀键后清掉同槽历史裸键，杜绝角色/道具继续串读
    const bareAlias = legacyBareFormSlotAliasOf(editorScopeKey)
    if (bareAlias && bareAlias !== editorScopeKey) {
      ctx.store().clearSceneModalSseTask(bareAlias, liveGenScopeKey)
    }
    persistSceneImageModalGenSession(
      editorScopeKey,
      sceneIdx,
      {
        taskKind,
        imageIdx,
        taskId
      },
      sceneModalSessionScope()
    )
    syncExternalGeneratingForModalScope(sceneIdx)
  }

  function beginCanvasTaskOverlay(
    sceneIdx: number,
    imgIdx: number,
    progressText: string,
    taskKind: SceneModalSseTaskKind | null = null
  ) {
    ctx.upscaleTargetKey.set(ctx.buildCanvasOverlayKey(sceneIdx, imgIdx))
    ctx.upscaleUiPhase.set('running')
    ctx.upscaleProgressText.set(progressText)
    ctx.canvasOverlayTaskKind.set(taskKind)
  }

  function endCanvasTaskOverlay(sceneIdx: number, imgIdx: number) {
    if (ctx.upscaleTargetKey.get() === ctx.buildCanvasOverlayKey(sceneIdx, imgIdx)) {
      ctx.upscaleUiPhase.set('idle')
      ctx.upscaleTargetKey.set('')
      ctx.upscaleProgressText.set('高清处理中…')
      ctx.canvasOverlayTaskKind.set(null)
    }
  }

  function readSessionForScene(sceneIdx: number) {
    const session = readSceneImageModalGenSession(sceneModalSessionScope())
    const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
    if (!session || session.editorScopeKey !== editorScopeKey) return null
    return session
  }

  function clearLocalGeneratingPlaceholders() {
    const next = removeLocalGeneratingPlaceholders(ctx.localSceneImages.get())
    if (next.length !== ctx.localSceneImages.get().length) {
      ctx.localSceneImages.set(next)
      if (ctx.currentImageIndex.get() >= next.length) {
        ctx.currentImageIndex.set(Math.max(0, next.length - 1))
      }
    }
  }

  function clearSceneModalGeneratingUi(sceneIdx: number) {
    const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
    if (editorScopeKey) deleteModalFollowLock(editorScopeKey)
    clearExternalGeneratingForModalScope(sceneIdx)
    if (sceneIdx !== ctx.currentSceneIndex.get()) return
    clearLocalGeneratingPlaceholders()
    ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent(), sceneIdx)
  }

  function ensureGeneratingPlaceholderImage(sceneIdx: number) {
    if (sceneIdx !== ctx.currentSceneIndex.get()) return
    const images = [...ctx.localSceneImages.get()]
    const pendingIdx = images.findIndex((img) => img?._generating)
    if (pendingIdx >= 0) {
      ctx.currentImageIndex.set(pendingIdx)
      return
    }
    images.push({
      id: `local-generating-${ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)}-${Date.now()}`,
      url: '',
      thumbnail: '',
      title: '生成中',
      _generating: true,
      _localGeneratingPlaceholder: true
    })
    ctx.localSceneImages.set(images)
    ctx.currentImageIndex.set(images.length - 1)
  }

  function finalizeLocalImagesWhileGenerating(mapped: any[]): any[] {
    const next = removeLocalGeneratingPlaceholders(mapped)
    if (next.some((m) => m?._generating)) {
      return next
    }

    const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(ctx.currentSceneIndex.get())
    if (!editorScopeKey || isSceneImageModalUserDismissed(editorScopeKey, sceneModalSessionScope())) {
      return next
    }

    if (!isSceneModalImageGenerating(ctx.currentSceneIndex.get())) {
      return next
    }

    return [
      ...next,
      {
        id: `local-generating-${editorScopeKey}-${Date.now()}`,
        url: '',
        thumbnail: '',
        title: '生成中',
        _generating: true,
        _localGeneratingPlaceholder: true
      }
    ]
  }

  function isSceneModalImageGenerating(sceneIdx: number): boolean {
    const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
    if (!editorScopeKey) return false
    if (isSceneImageModalUserDismissed(editorScopeKey, sceneModalSessionScope())) return false

    if (hasModalFollowLock(editorScopeKey)) return true

    if (readSessionForScene(sceneIdx)?.taskId) return true

    const persisted = resolvePersistedSceneModalSseTask(editorScopeKey)
    if (persisted) return true

    if (isEditorScopeGeneratingExternally(sceneIdx)) return true

    if (sceneIdx === ctx.currentSceneIndex.get()) {
      return ctx.localSceneImages.get().some((img) => img?._generating)
    }
    return false
  }

  function isHistoryItemGenerating(imageIndex: number): boolean {
    const sceneIdx = ctx.currentSceneIndex.get()
    if (!isSceneModalImageGenerating(sceneIdx)) return false

    const pendingIdx = ctx.currentSceneImages().findIndex((item) => item?._generating)
    if (pendingIdx >= 0) return imageIndex === pendingIdx

    const img = ctx.currentSceneImages()[imageIndex]
    if (img?._generating) return true
    if (isCanvasTaskOverlayActive(sceneIdx, imageIndex)) return true

    const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
    const task = editorScopeKey ? resolvePersistedSceneModalSseTask(editorScopeKey) : null
    if (task) {
      const targetIdx = task.imageIdx ?? ctx.currentImageIndex.get()
      return imageIndex === targetIdx
    }

    if (isEditorScopeGeneratingExternally(sceneIdx)) {
      return imageIndex === ctx.currentImageIndex.get()
    }
    return false
  }

  function syncSceneModalSseProgress(
    snap: SceneModalSseTaskSnapshot,
    p: { message?: string; stepTitle?: string },
    liveGenScopeKey: string = currentModalLiveGenScopeKey()
  ) {
    if (!snap.editorScopeKey) return
    const current = ctx.store().getSceneModalSseTask(snap.editorScopeKey, liveGenScopeKey)
    if (Number(current?.taskId) !== Number(snap.taskId)) return
    ctx.store().setSceneModalSseTask(
      snap.editorScopeKey,
      {
        ...snap,
        ...(String(p.message ?? '').trim() ? { message: String(p.message).trim() } : {}),
        ...(String(p.stepTitle ?? '').trim() ? { stepTitle: String(p.stepTitle).trim() } : {})
      },
      liveGenScopeKey
    )
  }

  function primeSceneModalLoadingUi(sceneIdx: number) {
    const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
    if (!editorScopeKey || isSceneImageModalUserDismissed(editorScopeKey, sceneModalSessionScope())) return

    const persisted = resolvePersistedSceneModalSseTask(editorScopeKey)
    const session = readSessionForScene(sceneIdx)
    const external = isEditorScopeGeneratingExternally(sceneIdx)
    const hasSessionTask = !!(session?.taskId)

    if (
      !persisted &&
      !external &&
      !hasSessionTask &&
      !hasModalFollowLock(editorScopeKey)
    ) {
      return
    }

    if (persisted) {
      if (persisted.taskKind === 'dialogue') {
        ctx.leftActiveTab.set('dialogue')
      } else if (persisted.taskKind === 'edit-image') {
        ctx.leftActiveTab.set('generate')
      }
      const imageIdx = persisted.imageIdx ?? session?.imageIdx ?? ctx.currentImageIndex.get()
      if (sceneIdx === ctx.currentSceneIndex.get()) {
        ctx.currentImageIndex.set(imageIdx)
      }
      beginCanvasTaskOverlay(
        sceneIdx,
        imageIdx,
        formatTaskSseLiveText(persisted, defaultProgressTextForTaskKind(persisted.taskKind)),
        persisted.taskKind
      )
    } else if (session?.taskKind === 'dialogue') {
      ctx.leftActiveTab.set('dialogue')
    } else if (session?.taskKind === 'edit-image') {
      ctx.leftActiveTab.set('generate')
    }

    if (sceneIdx === ctx.currentSceneIndex.get()) {
      const hasGeneratingRow = ctx.localSceneImages.get().some((img) => img?._generating)
      if (!hasGeneratingRow && (persisted || external)) {
        ensureGeneratingPlaceholderImage(sceneIdx)
      }
    }
  }

  async function ensureModalLoadingRestored(sceneIdx: number, isCurrent: () => boolean = () => true) {
    await waitForCreationStoreHydrated(ctx.store(), ctx.route())
    if (!isCurrent()) return
    applyStep3GenVisualFromRoute(ctx.store(), ctx.route())
    primeSceneModalLoadingUi(sceneIdx)
  }

  return {
    activeSceneModalFollowScopeKeys,
    sceneModalSessionScope,
    sceneModalTaskCleanupDecision,
    canClearSceneModalTaskUi,
    clearSceneModalTaskStateIfOwned,
    claimFormImagesForModal,
    suspendSceneModalFollowsExceptEditorScope,
    showUpscaleFailedOverlay,
    isCanvasTaskOverlayActive,
    showCanvasTaskRunningOverlay,
    sceneGenerateOverlayText,
    showSettingCardToolbarLoading,
    showUpscaleToolbarLoading,
    showMultiViewToolbarLoading,
    showCurrentGeneratingPlaceholder,
    resolveActiveSceneModalTaskKind,
    showEditGenerateButtonLoading,
    showDialogueGenerateButtonLoading,
    showGenerateFooterButtonLoading,
    clearUpscaleOverlay,
    resolvePersistedSceneModalSseTask,
    rebuildPersistedFromSession,
    syncExternalGeneratingForModalScope,
    slotHasLoadedImagesForModal,
    markExternalGeneratingCompleteForModalScope,
    clearStaleSceneModalGeneratingState,
    clearExternalGeneratingForModalScope,
    collectModalFormIdsForSceneIndex,
    isEditorScopeGeneratingExternally,
    resolveImageIdxByRpsImageId,
    currentModalLiveGenScopeKey,
    addModalFollowLock,
    hasModalFollowLock,
    deleteModalFollowLock,
    resetSceneModalDeferredRestoreState,
    handleDeferredSceneModalFollow,
    applyCanvasProgressIfCurrent,
    persistSceneModalSseTask,
    beginCanvasTaskOverlay,
    endCanvasTaskOverlay,
    readSessionForScene,
    clearLocalGeneratingPlaceholders,
    clearSceneModalGeneratingUi,
    ensureGeneratingPlaceholderImage,
    finalizeLocalImagesWhileGenerating,
    isSceneModalImageGenerating,
    isHistoryItemGenerating,
    syncSceneModalSseProgress,
    primeSceneModalLoadingUi,
    ensureModalLoadingRestored
  }
}

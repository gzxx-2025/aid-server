'use client'

import { message } from 'antd'
import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import {
buildModalTaskOverlayKey,
matchesAnyModalTaskOverlayKey,
matchesModalTaskOverlayKey
} from '~/composables/useModalTaskScope'
import { isStoryboardImageTaskOngoing } from '~/composables/useStoryboardImageGenerateTask'
import {
followStoryboardImageUpscaleTask
} from '~/composables/useStoryboardImageUpscaleTask'
import {
followStoryboardMultiViewGridImageTask
} from '~/composables/useStoryboardMultiViewGridImageTask'
import { TASK_SSE_TIMEOUT_MS } from '~/composables/useTaskSseFollow'
import { modalGenSessionScopeFromScopeKey } from '~/utils/modalGenSessionScope'
import type { MultiAngleGeneratePayload } from '~/utils/multiAngleCameraPrompt'
import {
clearModalImageGenSession,
clearModalImageGenUserDismissed,
persistModalImageGenSession,
type ModalImageGenSessionTab
} from '~/utils/storyboardImageModalGenSession'
import { activeStoryboardImageModalOverlayFollowIds } from '~/utils/storyboardImageModalOwnedFollow'
import { formatTaskSseJoinedLiveText } from '~/utils/taskSseProgressText'
import { resolveStoryboardFormImageId } from './storyboardFormImageId'
import { createStoryboardModalCanvasActions } from './storyboardModalCanvasActions'
import type { EditStoryboardImageModalCtx,StoryboardCanvasOverlayTaskKind } from './types'
import { CANVAS_OVERLAY_TASK_KINDS } from './types'

const activeCanvasOverlayFollowStoryboardIds = activeStoryboardImageModalOverlayFollowIds

export interface StoryboardModalCanvasOverlayApi {
  beginCanvasTaskOverlay: (
    sceneIdx: number,
    imgIdx: number,
    taskKind: StoryboardCanvasOverlayTaskKind,
    progressText: string,
    opts?: { persistSession?: boolean }
  ) => void
  endCanvasTaskOverlay: (clearSession?: boolean) => void
  isToolbarLoadingForTaskKind: (taskKind: StoryboardCanvasOverlayTaskKind) => boolean
  showUpscaleToolbarLoading: () => boolean
  showMultiViewToolbarLoading: () => boolean
  showDialogueToolbarLoading: () => boolean
  showUpscaleRunningOverlay: () => boolean
  showUpscaleFailedOverlay: () => boolean
  showGeneratingDialogueButton: () => boolean
  clearUpscaleOverlay: () => void
  resolveCanvasOverlayTaskKind: (
    persisted: ReturnType<EditStoryboardImageModalCtx['getModalImageGenTask']>,
    sessionTab?: ModalImageGenSessionTab
  ) => Extract<StoryboardCanvasOverlayTaskKind, 'upscale' | 'multiangle' | 'ninegrid'> | null
  clearModalCanvasOverlayLoadingUi: (
    storyboardId: number,
    sceneIdx: number,
    imageIdx: number,
    taskKind: Extract<StoryboardCanvasOverlayTaskKind, 'upscale' | 'multiangle' | 'ninegrid'>
  ) => void
  canvasOverlayDefaultProgressText: (
    taskKind: Extract<StoryboardCanvasOverlayTaskKind, 'upscale' | 'multiangle' | 'ninegrid'>
  ) => string
  runStoryboardCanvasOverlayFollowForScene: (
    sceneIdx: number,
    imageIdx: number,
    taskKind: Extract<StoryboardCanvasOverlayTaskKind, 'upscale' | 'multiangle' | 'ninegrid'>,
    opts: {
      resumeTaskId: number
      beforeCount?: number
      silentComplete?: boolean
      fallbackRecordId?: number | null
    }
  ) => Promise<void>
  restoreStoryboardCanvasOverlayGenerateIfNeeded: (sceneIdx: number) => Promise<void>
  resolveStoryboardFormImageId: (payload: {
    formId?: number
    imageId?: number
    imageUrl?: string
    imageTitle?: string
  }) => Promise<number | null>
  handleUpscaleModelSelect: (payload: {
    modelCode: string
    resolution: string
    imageIndex: number
  }) => Promise<void>
  handleMultiAngle: (imageIndex: number) => void
  handleMultiAngleGenerate: (payload: MultiAngleGeneratePayload) => Promise<void>
  handleModifyImage: (imageIndex: number) => void
}

export function useStoryboardModalCanvasOverlay(
  ctx: EditStoryboardImageModalCtx
): StoryboardModalCanvasOverlayApi {
  function beginCanvasTaskOverlay(
    sceneIdx: number,
    imgIdx: number,
    taskKind: StoryboardCanvasOverlayTaskKind,
    progressText: string,
    opts?: { persistSession?: boolean }
  ) {
    ctx.upscaleTargetKey.set(
      buildModalTaskOverlayKey(ctx.overlayKeyParts(sceneIdx, imgIdx, taskKind))
    )
    ctx.upscaleUiPhase.set('running')
    ctx.upscaleProgressText.set(progressText)
    ctx.canvasOverlayTaskKind.set(taskKind)

    const storyboardId = ctx.sceneStoryboardIdNum(sceneIdx)
    if (storyboardId != null) {
      ctx.ensureOverlayGeneratingPlaceholderImage(sceneIdx)
    }

    if (opts?.persistSession === false) return

    if (storyboardId == null) return
    const sessionTab: ModalImageGenSessionTab =
      taskKind === 'dialogue'
        ? 'dialogue'
        : taskKind === 'upscale'
          ? 'upscale'
          : taskKind === 'ninegrid'
            ? 'ninegrid'
            : taskKind === 'multiangle'
              ? 'multiangle'
              : 'generate'
    persistModalImageGenSession(storyboardId, sceneIdx, ctx.store().step3GenVisualScopeKey(), {
      tab: sessionTab,
      imageIdx: imgIdx
    })
  }

  function endCanvasTaskOverlay(clearSession = true) {
    ctx.upscaleUiPhase.set('idle')
    ctx.upscaleTargetKey.set('')
    ctx.upscaleProgressText.set('高清处理中…')
    ctx.canvasOverlayTaskKind.set(null)
    if (clearSession) {
      clearModalImageGenSession(ctx.storyboardImageModalSessionScope())
    }
  }

  function isToolbarLoadingForTaskKind(taskKind: StoryboardCanvasOverlayTaskKind): boolean {
    if (ctx.upscaleUiPhase.get() !== 'running') return false
    return matchesModalTaskOverlayKey(
      ctx.upscaleTargetKey.get(),
      ctx.overlayKeyParts(ctx.currentSceneIndex.get(), ctx.currentImageIndex.get(), taskKind)
    )
  }

  const showUpscaleToolbarLoading = () => isToolbarLoadingForTaskKind('upscale')

  const showMultiViewToolbarLoading = () => {
    if (ctx.upscaleUiPhase.get() !== 'running') return false
    return isToolbarLoadingForTaskKind('multiangle') || isToolbarLoadingForTaskKind('ninegrid')
  }

  const showDialogueToolbarLoading = () => isToolbarLoadingForTaskKind('dialogue')

  const showUpscaleRunningOverlay = () => {
    if (ctx.upscaleUiPhase.get() !== 'running') return false
    return matchesAnyModalTaskOverlayKey(ctx.upscaleTargetKey.get(), {
      editorScopeKey: ctx.props().editorScopeKey,
      sceneIdx: ctx.currentSceneIndex.get(),
      entityId: ctx.resolveStoryboardIdForSceneIndex(ctx.currentSceneIndex.get()),
      itemIdx: ctx.currentImageIndex.get(),
      taskKinds: [...CANVAS_OVERLAY_TASK_KINDS]
    })
  }

  const showUpscaleFailedOverlay = () => {
    if (ctx.upscaleUiPhase.get() !== 'failed') return false
    return matchesAnyModalTaskOverlayKey(ctx.upscaleTargetKey.get(), {
      editorScopeKey: ctx.props().editorScopeKey,
      sceneIdx: ctx.currentSceneIndex.get(),
      entityId: ctx.resolveStoryboardIdForSceneIndex(ctx.currentSceneIndex.get()),
      itemIdx: ctx.currentImageIndex.get(),
      taskKinds: [...CANVAS_OVERLAY_TASK_KINDS]
    })
  }

  const showGeneratingDialogueButton = () => isToolbarLoadingForTaskKind('dialogue')

  function clearUpscaleOverlay() {
    ctx.upscaleUiPhase.set('idle')
    ctx.upscaleTargetKey.set('')
    ctx.upscaleFailedMessage.set('')
    ctx.upscaleProgressText.set('高清处理中…')
    ctx.upscaleContext.current = null
    ctx.canvasOverlayTaskKind.set(null)
  }

  function resolveCanvasOverlayTaskKind(
    persisted: ReturnType<EditStoryboardImageModalCtx['getModalImageGenTask']>,
    sessionTab?: ModalImageGenSessionTab
  ): Extract<StoryboardCanvasOverlayTaskKind, 'upscale' | 'multiangle' | 'ninegrid'> | null {
    if (persisted?.kind === 'upscale' || sessionTab === 'upscale') return 'upscale'
    if (persisted?.kind === 'ninegrid' || sessionTab === 'ninegrid') return 'ninegrid'
    if (persisted?.kind === 'multiangle' || sessionTab === 'multiangle') return 'multiangle'
    return null
  }

  function clearModalCanvasOverlayLoadingUi(
    storyboardId: number,
    sceneIdx: number,
    imageIdx: number,
    taskKind: Extract<StoryboardCanvasOverlayTaskKind, 'upscale' | 'multiangle' | 'ninegrid'>
  ) {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return

    ctx.store().clearStoryboardImageGenTask(sid)
    clearModalImageGenSession(ctx.storyboardImageModalSessionScope())
    clearModalImageGenUserDismissed(ctx.storyboardImageModalSessionScope())
    activeCanvasOverlayFollowStoryboardIds.delete(sid)
    ctx.clearStoryboardPanelImageGenerating(sid)
    endCanvasTaskOverlay()
    ctx.upscaleContext.current = null
    ctx.clearLocalGeneratingPlaceholdersForScene(sceneIdx)
    ctx.clearStaleStoryboardGenUiForScene(sceneIdx)
    void imageIdx
    void taskKind
  }

  function canvasOverlayDefaultProgressText(
    taskKind: Extract<StoryboardCanvasOverlayTaskKind, 'upscale' | 'multiangle' | 'ninegrid'>
  ): string {
    if (taskKind === 'upscale') return '高清处理中…'
    if (taskKind === 'ninegrid') return '九宫格生图中...'
    return '多机位生图中...'
  }

  function canvasOverlaySuccessMessage(
    taskKind: Extract<StoryboardCanvasOverlayTaskKind, 'upscale' | 'multiangle' | 'ninegrid'>
  ): string {
    if (taskKind === 'upscale') return '高清处理完成'
    if (taskKind === 'ninegrid') return '九宫格生图完成'
    return '多机位生图完成'
  }

  function canvasOverlayFailureMessage(
    taskKind: Extract<StoryboardCanvasOverlayTaskKind, 'upscale' | 'multiangle' | 'ninegrid'>
  ): string {
    if (taskKind === 'upscale') return '高清任务失败'
    if (taskKind === 'ninegrid') return '九宫格生图失败'
    return '多机位生图失败'
  }

  async function runStoryboardCanvasOverlayFollowForScene(
    sceneIdx: number,
    imageIdx: number,
    taskKind: Extract<StoryboardCanvasOverlayTaskKind, 'upscale' | 'multiangle' | 'ninegrid'>,
    opts: {
      resumeTaskId: number
      beforeCount?: number
      silentComplete?: boolean
      fallbackRecordId?: number | null
    }
  ) {
    const storyboardId = ctx.sceneStoryboardIdNum(sceneIdx)
    if (storyboardId == null) return

    /** 剧集隔离：任务归属启动时 scope；切集后终态收尾不得写当前集扁平 store、不得 toast */
    const taskScope = captureCreationLiveGenScope()
    const taskSessionScope = modalGenSessionScopeFromScopeKey(taskScope.scopeKey)

    beginCanvasTaskOverlay(
      sceneIdx,
      imageIdx,
      taskKind,
      canvasOverlayDefaultProgressText(taskKind),
      {
        persistSession: false
      }
    )
    ctx.upscaleContext.current = { sceneIndex: sceneIdx, imageIndex: imageIdx }
    activeCanvasOverlayFollowStoryboardIds.add(storyboardId)

    const onProgress = (p: { percent?: number; stepTitle?: string; message?: string }) => {
      ctx.upscaleProgressText.set(
        formatTaskSseJoinedLiveText(p, '') ||
          (typeof p.percent === 'number' ? `${Math.round(p.percent)}%` : '') ||
          canvasOverlayDefaultProgressText(taskKind)
      )
      const task = ctx.getModalImageGenTask(storyboardId)
      if (task?.taskId) {
        ctx.store().setStoryboardImageGenTask(
          storyboardId,
          {
            taskId: task.taskId,
            sceneIdx,
            kind: taskKind,
            imageIdx,
            message: p.message,
            stepTitle: p.stepTitle
          },
          taskScope.scopeKey
        )
      }
    }

    try {
      let result:
        | Awaited<ReturnType<typeof followStoryboardImageUpscaleTask>>
        | Awaited<ReturnType<typeof followStoryboardMultiViewGridImageTask>>

      if (taskKind === 'upscale') {
        result = await followStoryboardImageUpscaleTask({
          taskId: opts.resumeTaskId,
          onProgress
        })
      } else {
        const taskLabel = taskKind === 'ninegrid' ? '九宫格' : '多机位'
        result = await followStoryboardMultiViewGridImageTask({
          taskId: opts.resumeTaskId,
          taskLabel,
          timeoutMs: TASK_SSE_TIMEOUT_MS,
          onProgress
        })
      }

      if (!result.ok && 'deferred' in result && result.deferred) {
        // 被新 SSE 接管或良性断连且任务仍进行中：保留 Pinia，勿 toast / 勿标失败
        return
      }

      /** 剧集隔离：已切集则只清任务所属 scope 桶快照，不写当前集 UI/store、不 toast */
      if (!matchesCreationLiveGenScope(taskScope)) {
        ctx.store().clearStoryboardImageGenTask(storyboardId, taskScope.scopeKey)
        clearModalImageGenSession(taskSessionScope)
        return
      }

      if (!result.ok) {
        if (!opts.silentComplete) {
          message.error(
            'errorMessage' in result
              ? result.errorMessage || canvasOverlayFailureMessage(taskKind)
              : canvasOverlayFailureMessage(taskKind)
          )
        }
        ctx.upscaleUiPhase.set('failed')
        ctx.canvasOverlayTaskKind.set(taskKind)
        ctx.upscaleFailedMessage.set(
          'errorMessage' in result
            ? result.errorMessage || canvasOverlayFailureMessage(taskKind)
            : canvasOverlayFailureMessage(taskKind)
        )
        ctx.store().clearStoryboardImageGenTask(storyboardId)
        ctx.clearStoryboardPanelImageGenerating(storyboardId)
        ctx.clearLocalGeneratingPlaceholdersForScene(sceneIdx)
        clearModalImageGenSession(ctx.storyboardImageModalSessionScope())
        return
      }

      clearModalCanvasOverlayLoadingUi(storyboardId, sceneIdx, imageIdx, taskKind)
      ctx.clearStaleStoryboardGenUiForScene(sceneIdx)
      await ctx.refreshSceneRecords(
        sceneIdx,
        result.recordId ?? opts.fallbackRecordId ?? undefined,
        opts.beforeCount,
        { force: true }
      )

      if (!opts.silentComplete) {
        message.success(canvasOverlaySuccessMessage(taskKind))
      }
    } finally {
      activeCanvasOverlayFollowStoryboardIds.delete(storyboardId)
    }
  }

  /** 刷新或重新打开弹窗后，恢复变清晰/多机位/九宫格 SSE 追踪（按 storyboardId 隔离） */
  async function restoreStoryboardCanvasOverlayGenerateIfNeeded(sceneIdx: number) {
    const storyboardId = ctx.sceneStoryboardIdNum(sceneIdx)
    if (storyboardId == null) return
    if (!ctx.isModalImageGenOwnerScene(sceneIdx)) return

    ctx.primeCanvasOverlayFromSession(sceneIdx)

    if (activeCanvasOverlayFollowStoryboardIds.has(storyboardId)) return

    const gen = ++ctx.resumeCanvasOverlayFollowGen.current
    const persisted = ctx.getModalImageGenTask(storyboardId)
    const session = ctx.readSessionForScene(sceneIdx)
    const taskKind = resolveCanvasOverlayTaskKind(persisted, session?.tab)
    if (!taskKind) return

    const imageIdx = persisted?.imageIdx ?? session?.imageIdx ?? ctx.currentImageIndex.get()
    const taskId = persisted?.taskId ?? session?.taskId ?? null

    if (!taskId) {
      if (ctx.hasModalImageGenPendingState(storyboardId)) {
        ctx.primeCanvasOverlayFromSession(sceneIdx)
      }
      return
    }

    const ongoing = await isStoryboardImageTaskOngoing(taskId)
    if (gen !== ctx.resumeCanvasOverlayFollowGen.current) return

    const beforeCount = (ctx.props().scenes[sceneIdx]?.images || []).length

    if (!ongoing) {
      if (ctx.hasModalImageGenPendingState(storyboardId)) {
        ctx.primeCanvasOverlayFromSession(sceneIdx)
        await runStoryboardCanvasOverlayFollowForScene(sceneIdx, imageIdx, taskKind, {
          resumeTaskId: taskId,
          beforeCount,
          silentComplete: true
        })
      } else {
        clearModalCanvasOverlayLoadingUi(storyboardId, sceneIdx, imageIdx, taskKind)
      }
      return
    }

    await runStoryboardCanvasOverlayFollowForScene(sceneIdx, imageIdx, taskKind, {
      resumeTaskId: taskId,
      beforeCount,
      silentComplete: true
    })
  }

  const {
    handleUpscaleModelSelect,
    handleMultiAngle,
    handleMultiAngleGenerate,
    handleModifyImage
  } = createStoryboardModalCanvasActions(ctx, {
    beginCanvasTaskOverlay,
    endCanvasTaskOverlay,
    clearUpscaleOverlay,
    clearModalCanvasOverlayLoadingUi
  })
  return {
    beginCanvasTaskOverlay,
    endCanvasTaskOverlay,
    isToolbarLoadingForTaskKind,
    showUpscaleToolbarLoading,
    showMultiViewToolbarLoading,
    showDialogueToolbarLoading,
    showUpscaleRunningOverlay,
    showUpscaleFailedOverlay,
    showGeneratingDialogueButton,
    clearUpscaleOverlay,
    resolveCanvasOverlayTaskKind,
    clearModalCanvasOverlayLoadingUi,
    canvasOverlayDefaultProgressText,
    runStoryboardCanvasOverlayFollowForScene,
    restoreStoryboardCanvasOverlayGenerateIfNeeded,
    resolveStoryboardFormImageId,
    handleUpscaleModelSelect,
    handleMultiAngle,
    handleMultiAngleGenerate,
    handleModifyImage
  }
}

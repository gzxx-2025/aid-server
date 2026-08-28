import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import {
findStoryboardImageGenTaskInScopes
} from '~/composables/useCreationStoreHydration'
import { buildModalTaskOverlayKey } from '~/composables/useModalTaskScope'
import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import {
modalGenSessionScopeFromStore,
type ModalGenSessionScope
} from '~/utils/modalGenSessionScope'
import {
isModalImageGenSessionActive,
isModalImageGenUserDismissed,
persistModalImageGenSession,
readModalImageGenSession,
type ModalImageGenSessionTab
} from '~/utils/storyboardImageModalGenSession'
import {
activeStoryboardImageModalDialogueFollowIds,
activeStoryboardImageModalGenFollowIds,
activeStoryboardImageModalOverlayFollowIds
} from '~/utils/storyboardImageModalOwnedFollow'
import {
appendLocalGeneratingPlaceholder,
isModalOverlaySessionTab,
removeLocalGeneratingPlaceholders
} from './sessionPlaceholders'
import type { EditStoryboardImageModalCtx } from './types'

const activeStoryboardImageFollowStoryboardIds = activeStoryboardImageModalGenFollowIds
const activeDialogueFollowStoryboardIds = activeStoryboardImageModalDialogueFollowIds
const activeCanvasOverlayFollowStoryboardIds = activeStoryboardImageModalOverlayFollowIds


export function createStoryboardModalSessionCore(ctx: EditStoryboardImageModalCtx) {
function storyboardImageModalSessionScope() {
  return modalGenSessionScopeFromStore(ctx.store())
}

function resolveStoryboardIdForSceneIndex(sceneIdx: number): string {
  const raw = ctx.props().scenes[sceneIdx]?.storyboardId
  const id = Number(raw)
  if (Number.isFinite(id) && id > 0) return String(id)
  return `idx-${sceneIdx}`
}

function overlayKeyParts(sceneIdx: number, imgIdx: number, taskKind: string) {
  return {
    editorScopeKey: ctx.props().editorScopeKey,
    sceneIdx,
    entityId: resolveStoryboardIdForSceneIndex(sceneIdx),
    itemIdx: imgIdx,
    taskKind
  }
}

function getModalImageGenTask(storyboardId: number) {
  return findStoryboardImageGenTaskInScopes(ctx.store(), storyboardId, ctx.route())
}

function isDialogueModalTask(task: ReturnType<typeof getModalImageGenTask>): boolean {
  return task?.kind === 'dialogue'
}

function isCanvasOverlayModalTask(
  task: ReturnType<typeof getModalImageGenTask> | null | undefined
): boolean {
  return task?.kind === 'upscale' || task?.kind === 'multiangle' || task?.kind === 'ninegrid'
}

function sceneHasCompletedGeneratedImage(sceneIdx: number): boolean {
  const images = ctx.props().scenes[sceneIdx]?.images ?? []
  return images.some((img) => {
    const url = String(img?.url ?? img?.thumbnail ?? '').trim()
    return !!url && !img?._generating
  })
}

function hasActiveModalImageGenSession(storyboardId: number): boolean {
  return isModalImageGenSessionActive(storyboardId, storyboardImageModalSessionScope())
}

function clearStoryboardPanelImageGenerating(storyboardId: number) {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return
  // 弹窗收尾不能误清同一分镜正在进行的列表批量任务。
  const creationStore = ctx.store()
  if (
    creationStore.isGeneratingStoryboardImageBatch ||
    creationStore.storyboardImageBatchActiveTaskId != null ||
    creationStore.storyboardImageBatchActiveImageTaskId != null ||
    creationStore.storyboardImageBatchTargetStoryboardIds.includes(sid)
  ) {
    return
  }
  creationStore.clearStoryboardPanelImageGenStatus(sid)
}

function readSessionForScene(sceneIdx: number) {
  const session = readModalImageGenSession(storyboardImageModalSessionScope())
  if (
    !session ||
    isModalImageGenUserDismissed(session.storyboardId, storyboardImageModalSessionScope())
  )
    return null
  if (session.sceneIdx !== sceneIdx) return null
  return session
}

function isModalStoryboardGenerateSession(
  session: ReturnType<typeof readSessionForScene>
): boolean {
  if (!session) return false
  return session.tab === 'generate' || !session.tab
}

/** 当前分镜是否应走「生成分镜图」恢复（排除对话/变清晰/多机位/九宫格） */
function shouldRestoreStoryboardImageGenerate(sceneIdx: number): boolean {
  const session = readSessionForScene(sceneIdx)
  if (session?.tab === 'dialogue' || isModalOverlaySessionTab(session?.tab)) {
    return false
  }
  const storyboardId = sceneStoryboardIdNum(sceneIdx)
  if (storyboardId == null) return false
  const task = getModalImageGenTask(storyboardId)
  if (isDialogueModalTask(task) || isCanvasOverlayModalTask(task)) return false
  return true
}

function clearStaleStoryboardGenUiForScene(sceneIdx: number) {
  const sid = sceneStoryboardIdNum(sceneIdx)
  if (sid != null && activeStoryboardImageFollowStoryboardIds.has(sid)) return

  const overlayKey = buildModalTaskOverlayKey(overlayKeyParts(sceneIdx, -1, 'storyboard-gen'))
  if (ctx.storyboardGenerateTargetKey.get() === overlayKey) {
    ctx.storyboardGenerateTargetKey.set('')
  }
  if (!activeStoryboardImageFollowStoryboardIds.size) {
    ctx.isGeneratingStoryboardImage.set(false)
  }
  ctx.storyboardGenerateProgressText.set('分镜图生成中…')
}

function isStoryboardImageGenerationInProgress(storyboardId: number): boolean {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return false
  const task = getModalImageGenTask(storyboardId)
  if (isDialogueModalTask(task) || isCanvasOverlayModalTask(task)) return false
  if (activeStoryboardImageFollowStoryboardIds.has(sid)) return true
  if (task) return true
  const session = readModalImageGenSession(storyboardImageModalSessionScope())
  return (
    session?.storyboardId === sid &&
    (session.tab === 'generate' || !session.tab) &&
    hasActiveModalImageGenSession(storyboardId)
  )
}

function isDialogueGenerationInProgress(storyboardId: number): boolean {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return false
  const task = getModalImageGenTask(storyboardId)
  if (isDialogueModalTask(task)) return true
  if (activeDialogueFollowStoryboardIds.has(sid)) return true
  const session = readModalImageGenSession(storyboardImageModalSessionScope())
  return (
    session?.storyboardId === sid &&
    session.tab === 'dialogue' &&
    hasActiveModalImageGenSession(storyboardId)
  )
}

function isModalOverlayGenerationInProgress(storyboardId: number): boolean {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return false
  const task = getModalImageGenTask(storyboardId)
  if (isCanvasOverlayModalTask(task)) return true
  if (activeCanvasOverlayFollowStoryboardIds.has(sid)) return true
  const session = readModalImageGenSession(storyboardImageModalSessionScope())
  return (
    session?.storyboardId === sid &&
    isModalOverlaySessionTab(session.tab) &&
    hasActiveModalImageGenSession(storyboardId)
  )
}

function sceneStoryboardIdNum(sceneIdx: number): number | null {
  const id = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
  if (Number.isFinite(id) && id > 0) return id
  const session = readSessionForScene(sceneIdx)
  if (session) return session.storyboardId
  return null
}

/** 弹窗单镜生图/对话作图任务绑定的 sceneIdx（避免污染其它分镜 tab） */
function resolveModalImageGenOwnerSceneIdx(storyboardId: number): number | null {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return null

  const task = getModalImageGenTask(sid)
  const session = readModalImageGenSession(storyboardImageModalSessionScope())
  const hasActiveGen =
    activeStoryboardImageFollowStoryboardIds.has(sid) ||
    activeDialogueFollowStoryboardIds.has(sid) ||
    activeCanvasOverlayFollowStoryboardIds.has(sid) ||
    !!task ||
    (session?.storyboardId === sid && hasActiveModalImageGenSession(sid))
  if (!hasActiveGen) return null

  if (task?.sceneIdx != null && Number.isFinite(task.sceneIdx)) {
    return task.sceneIdx
  }
  if (session?.storyboardId === sid && Number.isFinite(session.sceneIdx)) {
    return session.sceneIdx
  }
  const idx = ctx.props().scenes.findIndex((_, i) => sceneStoryboardIdNum(i) === sid)
  return idx >= 0 ? idx : null
}

function isModalImageGenOwnerScene(sceneIdx: number): boolean {
  const session = readSessionForScene(sceneIdx)
  if (session) return true
  const sid = sceneStoryboardIdNum(sceneIdx)
  if (sid == null) return false
  return resolveModalImageGenOwnerSceneIdx(sid) === sceneIdx
}

function hasModalImageGenPendingState(storyboardId: number): boolean {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return false
  if (hasActiveModalImageGenSession(sid)) return true
  if (getModalImageGenTask(sid)) return true
  if (activeStoryboardImageFollowStoryboardIds.has(sid)) return true
  if (activeDialogueFollowStoryboardIds.has(sid)) return true
  if (activeCanvasOverlayFollowStoryboardIds.has(sid)) return true
  return false
}

function isAnyModalGenerationPendingForScene(sceneIdx: number): boolean {
  const sid = sceneStoryboardIdNum(sceneIdx)
  if (sid == null) return false
  return (
    isStoryboardImageGenerationInProgress(sid) ||
    isDialogueGenerationInProgress(sid) ||
    isModalOverlayGenerationInProgress(sid) ||
    activeStoryboardImageFollowStoryboardIds.has(sid) ||
    activeDialogueFollowStoryboardIds.has(sid) ||
    activeCanvasOverlayFollowStoryboardIds.has(sid)
  )
}

function syncModalImageGenSessionTaskId(
  storyboardId: number,
  sceneIdx: number,
  taskId: number,
  extra?: { tab?: ModalImageGenSessionTab; imageIdx?: number },
  sessionScope?: ModalGenSessionScope | null,
  scopeKey?: string
) {
  const targetSessionScope = sessionScope ?? storyboardImageModalSessionScope()
  const session = readModalImageGenSession(targetSessionScope)
  persistModalImageGenSession(
    storyboardId,
    sceneIdx,
    scopeKey ?? session?.scopeKey ?? ctx.store().step3GenVisualScopeKey(),
    {
      tab: extra?.tab ?? session?.tab ?? 'generate',
      imageIdx: extra?.imageIdx ?? session?.imageIdx,
      taskId
    },
    targetSessionScope
  )
}

/** 提交响应晚于项目切换时，任务仍归提交作用域，并在 SSE owner 建立后立即挂起。 */
function suspendLateModalImageFollowIfScopeChanged(
  taskId: number,
  taskScope: ReturnType<typeof captureCreationLiveGenScope>
) {
  if (typeof window === 'undefined') return
  queueMicrotask(() => {
    if (!matchesCreationLiveGenScope(taskScope)) suspendTaskSseFollow(taskId)
  })
}

/** 当前分镜是否处于弹窗单镜生图/对话作图中（含刷新恢复态） */
function isSceneModalImageGenerating(sceneIdx: number): boolean {
  if (!isModalImageGenOwnerScene(sceneIdx)) return false
  const sid = sceneStoryboardIdNum(sceneIdx)
  if (sid == null) return false
  if (isModalImageGenUserDismissed(sid, storyboardImageModalSessionScope())) return false
  return (
    isStoryboardImageGenerationInProgress(sid) ||
    isDialogueGenerationInProgress(sid) ||
    isModalOverlayGenerationInProgress(sid)
  )
}

function clearStaleModalGeneratingPlaceholders() {
  for (let i = 0; i < ctx.props().scenes.length; i++) {
    if (!isModalImageGenOwnerScene(i)) {
      clearLocalGeneratingPlaceholdersForScene(i)
    }
  }
}

function ensureGeneratingPlaceholderImage(sceneIdx: number) {
  const images = [...(ctx.props().scenes[sceneIdx]?.images || [])]
  const pendingIdx = images.findIndex((img) => img?._generating)
  if (pendingIdx >= 0) {
    if (sceneIdx === ctx.currentSceneIndex.get()) ctx.currentImageIndex.set(pendingIdx)
    return
  }
  const sid = sceneStoryboardIdNum(sceneIdx)
  if (sid == null) return
  const next = appendLocalGeneratingPlaceholder(images, sid)
  ctx.emitUpdate(sceneIdx, { images: next })
  if (sceneIdx === ctx.currentSceneIndex.get()) {
    ctx.currentImageIndex.set(next.length - 1)
  }
}

/** 变清晰/多机位/九宫格：在生成记录末尾补 loading 占位，不改变当前画布选中图 */
function ensureOverlayGeneratingPlaceholderImage(sceneIdx: number) {
  const images = [...(ctx.props().scenes[sceneIdx]?.images || [])]
  if (images.some((img) => img?._generating)) return
  const sid = sceneStoryboardIdNum(sceneIdx)
  if (sid == null) return
  ctx.emitUpdate(sceneIdx, { images: appendLocalGeneratingPlaceholder(images, sid) })
}

function clearLocalGeneratingPlaceholdersForScene(sceneIdx: number) {
  const images = ctx.props().scenes[sceneIdx]?.images || []
  const next = removeLocalGeneratingPlaceholders(images)
  if (next.length !== images.length) {
    ctx.emitUpdate(sceneIdx, { images: next })
    if (sceneIdx === ctx.currentSceneIndex.get() && ctx.currentImageIndex.get() >= next.length) {
      ctx.currentImageIndex.set(Math.max(0, next.length - 1))
    }
  }
}

/** 拉取服务端记录后，若任务仍在进行则保留/补回本地 generating 占位，避免有图时刷新丢失 loading */
function finalizeMappedImagesWhileGenerating(sceneIdx: number, mapped: any[]): any[] {
  const next = removeLocalGeneratingPlaceholders(mapped)
  if (next.some((m) => m?._generating)) {
    return next
  }

  const sid = sceneStoryboardIdNum(sceneIdx)
  if (sid == null || isModalImageGenUserDismissed(sid, storyboardImageModalSessionScope())) {
    return next
  }

  const stillGenerating =
    isStoryboardImageGenerationInProgress(sid) || isDialogueGenerationInProgress(sid)
  if (!stillGenerating || !isModalImageGenOwnerScene(sceneIdx)) {
    return next
  }

  const task = getModalImageGenTask(sid)
  if (isDialogueModalTask(task)) {
    return next
  }

  const session = readSessionForScene(sceneIdx)
  if (session?.tab === 'dialogue') {
    return next
  }
  if (isModalOverlaySessionTab(session?.tab)) {
    if (next.some((m) => m?._generating)) return next
    if (isModalOverlayGenerationInProgress(sid) && isModalImageGenOwnerScene(sceneIdx)) {
      return appendLocalGeneratingPlaceholder(next, sid)
    }
    return next
  }

  return appendLocalGeneratingPlaceholder(next, sid)
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
  }
}


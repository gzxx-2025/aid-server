'use client'

import { useRef } from 'react'
import {
findSceneModalSseTaskInScopes
} from '~/composables/useCreationStoreHydration'
import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import type { SceneModalSseTaskKind,SceneModalSseTaskSnapshot } from '~/stores/creation'
import { userTaskDetailCached } from '~/utils/businessApi'
import { claimFormImagesFromTaskComplete } from '~/utils/formImageAutoUse'
import {
buildModalFollowLockKey
} from '~/utils/liveGenScopeIsolation'
import {
modalGenSessionScopeFromScopeKey,
modalGenSessionScopeFromStore
} from '~/utils/modalGenSessionScope'
import {
decideModalTaskOwnerCleanup,
listModalTabFollowsToSuspend
} from '~/utils/modalTabSseMutex'
import {
clearSceneImageModalGenSession,
readSceneImageModalGenSession
} from '~/utils/sceneImageModalGenSession'
import {
drainStep3SseQueue,
hasStep3SseSlot,
releaseStep3SseSlot,
requeueStep3SseItemToEnd,
tryAcquireStep3SseSlot
} from '~/utils/step3SseConcurrencyGate'
import { legacyBareFormSlotAliasOf } from '~/utils/step3FormEditorScopeKey'
import {
clearExternalGeneratingForModalScopeImpl,
isEditorScopeGeneratingExternallyImpl,
markExternalGeneratingCompleteForModalScopeImpl,
slotHasLoadedImagesForModalImpl,
syncExternalGeneratingForModalScopeImpl
} from './sceneModalExternalStatus'
import {
isTerminalUserTaskStatus,
mapSessionTaskKind
} from './sceneModalTaskParsers'
import type {
EditSceneImageModalCtx,
SceneModalTaskOwner,
SceneModalTaskStateApi
} from './types'


export function useSceneModalTaskStateCore(
  ctx: EditSceneImageModalCtx,
  lateOps: Pick<
    SceneModalTaskStateApi,
    | 'clearSceneModalGeneratingUi'
    | 'isHistoryItemGenerating'
    | 'readSessionForScene'
    | 'isSceneModalImageGenerating'
    | 'clearLocalGeneratingPlaceholders'
  >
) {
const {
  clearSceneModalGeneratingUi,
  isHistoryItemGenerating,
  readSessionForScene,
  isSceneModalImageGenerating,
  clearLocalGeneratingPlaceholders
} = lateOps
/** 值形如 `${projectId:episodeId}::${editorScopeKey}`，禁止裸 character-0 */
const activeSceneModalFollowScopeKeys = useRef(new Set<string>()).current
/** 本轮弹窗内 deferred 自动续跟次数；关弹窗 / 重开清零，防止 stream 风暴 */
const sceneModalDeferredRestoreAttempts = useRef(new Map<string, number>()).current
const sceneModalDeferredRestoreTimers = useRef(
  new Map<string, ReturnType<typeof setTimeout>>()
).current

function sceneModalSessionScope() {
  return modalGenSessionScopeFromStore(ctx.store())
}

function currentModalLiveGenScopeKey(): string {
  return ctx.store().step3GenVisualScopeKey()
}

/** 任务终态只删除自己的持久化状态；同 editorScope 的新任务已经接管时，不得清它的 loading/UI。 */
function sceneModalTaskCleanupDecision(owner: SceneModalTaskOwner) {
  const sessionScope =
    modalGenSessionScopeFromScopeKey(owner.liveGenScopeKey) ?? sceneModalSessionScope()
  const currentSession = readSceneImageModalGenSession(sessionScope)
  const currentSnapshot = ctx.store().getSceneModalSseTask(
    owner.editorScopeKey,
    owner.liveGenScopeKey
  )
  const decision = decideModalTaskOwnerCleanup({
    expectedEditorScopeKey: owner.editorScopeKey,
    expectedTaskId: owner.taskId,
    currentSession,
    currentSnapshot
  })
  return { decision, sessionScope }
}

function canClearSceneModalTaskUi(owner: SceneModalTaskOwner): boolean {
  return (
    owner.liveGenScopeKey === currentModalLiveGenScopeKey() &&
    sceneModalTaskCleanupDecision(owner).decision.canClearUi
  )
}

function clearSceneModalTaskStateIfOwned(
  owner: SceneModalTaskOwner,
  options?: { sceneIdx?: number }
): boolean {
  const { decision, sessionScope } = sceneModalTaskCleanupDecision(owner)

  if (decision.clearSnapshot) {
    ctx.store().clearSceneModalSseTask(owner.editorScopeKey, owner.liveGenScopeKey)
  }
  if (decision.clearSession) {
    clearSceneImageModalGenSession(sessionScope)
  }
  const canClearTaskUi =
    decision.canClearUi && owner.liveGenScopeKey === currentModalLiveGenScopeKey()
  if (options?.sceneIdx != null && canClearTaskUi) {
    clearSceneModalGeneratingUi(options.sceneIdx)
  }
  return canClearTaskUi
}

function claimFormImagesForModal(taskId: number, taskType: unknown, completeData: unknown) {
  const pid = Number(ctx.store().currentProjectId)
  const options = { projectId: Number.isFinite(pid) && pid > 0 ? pid : undefined }
  const id = Number(taskId)
  return Number.isFinite(id) && id > 0
    ? ctx.sceneModalFormImageClaimOwner.claim(id, taskType, completeData, options)
    : claimFormImagesFromTaskComplete(taskType, completeData, options)
}

/** 顶部 Tab 互斥：挂起非目标 editorScope 的浏览器 SSE，释放并发槽 */
function suspendSceneModalFollowsExceptEditorScope(keepEditorScopeKey: string) {
  const liveGenScopeKey = currentModalLiveGenScopeKey()
  const tasks = ctx.store().step3GenVisualByScope[liveGenScopeKey]?.modalSseTasks || {}
  const activeFollows = Object.entries(tasks)
    .map(([tabKey, snap]) => ({
      tabKey,
      taskId: Number((snap as { taskId?: number } | undefined)?.taskId)
    }))
    .filter((f) => Number.isFinite(f.taskId) && f.taskId > 0)

  const toSuspend = listModalTabFollowsToSuspend({
    currentTabKey: keepEditorScopeKey,
    activeFollows
  })
  for (const tid of toSuspend) {
    suspendTaskSseFollow(tid)
    if (hasStep3SseSlot(tid)) releaseStep3SseSlot(tid)
  }
  for (const f of activeFollows) {
    if (f.tabKey !== keepEditorScopeKey) {
      deleteModalFollowLock(f.tabKey, liveGenScopeKey)
    }
  }
  drainStep3SseQueue((item) => {
    if (item.owner === 'modal') {
      requeueStep3SseItemToEnd(item)
      return { kind: 'skipped' as const }
    }
    const acq = tryAcquireStep3SseSlot({ taskId: item.taskId, owner: 'outer' })
    if (acq.kind !== 'acquired' && acq.kind !== 'already-active') return acq
    if (typeof window !== 'undefined') {
      window.dispatchEvent(
        new CustomEvent('create-flow-track-task', {
          detail: { taskId: item.taskId }
        })
      )
    }
    return acq
  })
}

const showUpscaleFailedOverlay = () => {
  if (ctx.upscaleUiPhase.get() !== 'failed') return false
  return (
    ctx.upscaleTargetKey.get() ===
    ctx.buildCanvasOverlayKey(ctx.currentSceneIndex.get(), ctx.currentImageIndex.get())
  )
}

/** 画布 / 左侧生成记录共用的任务 loading（生图、高清、设定卡等） */
function isCanvasTaskOverlayActive(sceneIdx: number, imgIdx: number): boolean {
  if (ctx.upscaleUiPhase.get() !== 'running') return false
  return ctx.upscaleTargetKey.get() === ctx.buildCanvasOverlayKey(sceneIdx, imgIdx)
}

const showCanvasTaskRunningOverlay = () =>
  isCanvasTaskOverlayActive(ctx.currentSceneIndex.get(), ctx.currentImageIndex.get())

const sceneGenerateOverlayText = () => ctx.upscaleProgressText.get()

const showSettingCardToolbarLoading = () =>
  showCanvasTaskRunningOverlay() && ctx.canvasOverlayTaskKind.get() === 'setting-card'

const showUpscaleToolbarLoading = () =>
  showCanvasTaskRunningOverlay() && ctx.canvasOverlayTaskKind.get() === 'upscale'

const showMultiViewToolbarLoading = () =>
  showCanvasTaskRunningOverlay() && ctx.canvasOverlayTaskKind.get() === 'multi-view'

const showCurrentGeneratingPlaceholder = () =>
  !!ctx.currentImg()?._generating && isHistoryItemGenerating(ctx.currentImageIndex.get())

function resolveActiveSceneModalTaskKind(sceneIdx: number): SceneModalSseTaskKind | null {
  const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
  if (!editorScopeKey) return null
  if (ctx.canvasOverlayTaskKind.get()) return ctx.canvasOverlayTaskKind.get()
  const persisted = resolvePersistedSceneModalSseTask(editorScopeKey)
  if (persisted?.taskKind) return persisted.taskKind
  const session = readSessionForScene(sceneIdx)
  if (session?.taskKind) return mapSessionTaskKind(session.taskKind)
  return null
}

const showEditGenerateButtonLoading = () => {
  if (!isSceneModalImageGenerating(ctx.currentSceneIndex.get())) return false
  return resolveActiveSceneModalTaskKind(ctx.currentSceneIndex.get()) === 'edit-image'
}

const showDialogueGenerateButtonLoading = () => {
  if (!isSceneModalImageGenerating(ctx.currentSceneIndex.get())) return false
  return resolveActiveSceneModalTaskKind(ctx.currentSceneIndex.get()) === 'dialogue'
}

const showGenerateFooterButtonLoading = () =>
  ctx.leftActiveTab.get() === 'generate'
    ? showEditGenerateButtonLoading()
    : showDialogueGenerateButtonLoading()

function clearUpscaleOverlay() {
  ctx.upscaleUiPhase.set('idle')
  ctx.upscaleTargetKey.set('')
  ctx.upscaleFailedMessage.set('')
  ctx.upscaleProgressText.set('高清处理中…')
  ctx.upscaleContext.current = null
  ctx.canvasOverlayTaskKind.set(null)
}

function resolvePersistedSceneModalSseTask(editorScopeKey: string): SceneModalSseTaskSnapshot | null {
  const exact =
    findSceneModalSseTaskInScopes(ctx.store(), editorScopeKey, ctx.route()) ??
    ctx.store().findSceneModalSseTaskAcrossScopes(editorScopeKey) ??
    ctx.store().getSceneModalSseTask(editorScopeKey)
  if (exact) return exact

  // 历史裸键 `0-0`：仅当 formId 与当前形态一致时才认领，避免角色/道具同槽串流
  const bareAlias = legacyBareFormSlotAliasOf(editorScopeKey)
  if (!bareAlias) return null
  const legacy =
    findSceneModalSseTaskInScopes(ctx.store(), bareAlias, ctx.route()) ??
    ctx.store().findSceneModalSseTaskAcrossScopes(bareAlias) ??
    ctx.store().getSceneModalSseTask(bareAlias)
  if (!legacy) return null
  const snapFormId = Number(legacy.formId)
  if (!Number.isFinite(snapFormId) || snapFormId <= 0) return null
  const sceneIdx = Number(legacy.sceneIdx)
  const expectedFormId = Number.isFinite(sceneIdx)
    ? ctx.resolveFormIdForSceneIndex(sceneIdx)
    : ctx.resolveFormIdForSceneIndex(ctx.currentSceneIndex.get())
  if (expectedFormId == null || snapFormId !== expectedFormId) return null
  return legacy
}

function rebuildPersistedFromSession(sceneIdx: number): SceneModalSseTaskSnapshot | null {
  const session = readSessionForScene(sceneIdx)
  if (!session?.taskId) return null
  const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
  if (!editorScopeKey) return null
  return {
    taskId: session.taskId,
    taskKind: mapSessionTaskKind(session.taskKind),
    sceneIdx,
    imageIdx: session.imageIdx ?? ctx.currentImageIndex.get(),
    editorScopeKey,
    formId: ctx.resolveFormIdForSceneIndex(sceneIdx)
  }
}

/** 弹窗内发起任务时同步列表卡片 generating，便于刷新后列表/流程条恢复 loading */
const syncExternalGeneratingForModalScope = (sceneIdx: number) =>
  syncExternalGeneratingForModalScopeImpl(ctx, sceneIdx)

const slotHasLoadedImagesForModal = (sceneIdx: number): boolean =>
  slotHasLoadedImagesForModalImpl(ctx, sceneIdx)

/** 形态图已就绪时，将外层 Pinia generating 回落为 success，避免弹窗 Tab/记录卡误显 loading */
const markExternalGeneratingCompleteForModalScope = (sceneIdx: number) =>
  markExternalGeneratingCompleteForModalScopeImpl(ctx, sceneIdx)

/** 刷新后：任务已终态但 session / Pinia generating 仍残留时，打开弹窗前先对齐 */
async function clearStaleSceneModalGeneratingState(
  sceneIdx: number,
  isCurrent: () => boolean = () => true
) {
  const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
  if (!editorScopeKey) return

  const session = readSessionForScene(sceneIdx)
  if (session?.taskId) {
    const sessionOwner: SceneModalTaskOwner = {
      editorScopeKey: session.editorScopeKey,
      taskId: session.taskId,
      liveGenScopeKey: currentModalLiveGenScopeKey()
    }
    try {
      const detail = await userTaskDetailCached(session.taskId)
      if (!isCurrent()) return
      if (detail && isTerminalUserTaskStatus(detail.status)) {
        clearSceneModalTaskStateIfOwned(sessionOwner)
      }
    } catch {
      /* 网络失败时保留 session，避免误清进行中的 loading */
    }
  }

  if (!isCurrent()) return

  if (
    !resolvePersistedSceneModalSseTask(editorScopeKey) &&
    !hasModalFollowLock(editorScopeKey) &&
    isEditorScopeGeneratingExternally(sceneIdx) &&
    slotHasLoadedImagesForModal(sceneIdx)
  ) {
    markExternalGeneratingCompleteForModalScope(sceneIdx)
    clearLocalGeneratingPlaceholders()
    ctx.store().refreshStep3VisualGeneratingFlag()
  }
}

/** 与 syncExternalGeneratingForModalScope 成对：SSE 失败/取消后清除外层 Tab/列表 generating */
const clearExternalGeneratingForModalScope = (sceneIdx: number) =>
  clearExternalGeneratingForModalScopeImpl(ctx, sceneIdx)

function collectModalFormIdsForSceneIndex(sceneIdx: number): number[] {
  const ids = new Set<number>()
  // 仅当前 Tab 对应形态，禁止并入整份 activeRpsFormIds（否则同角色其它形态的生图任务会串到当前 Tab）
  const fid = ctx.resolveFormIdForSceneIndex(sceneIdx)
  if (fid != null) ids.add(fid)
  for (const img of ctx.localSceneImages.get()) {
    const n = Number((img as { rpsFormId?: number })?.rpsFormId)
    if (Number.isFinite(n) && n > 0) ids.add(n)
  }
  return [...ids]
}

const isEditorScopeGeneratingExternally = (sceneIdx: number): boolean =>
  isEditorScopeGeneratingExternallyImpl(ctx, sceneIdx)

function resolveImageIdxByRpsImageId(imageId: number): number {
  const idx = ctx.localSceneImages.get().findIndex(
    (x: { rpsImageId?: number }) => Number(x?.rpsImageId) === imageId
  )
  return idx >= 0 ? idx : Math.max(0, ctx.currentImageIndex.get())
}

function addModalFollowLock(editorScopeKey: string, liveGenScopeKey = currentModalLiveGenScopeKey()) {
  const key = buildModalFollowLockKey(liveGenScopeKey, editorScopeKey)
  if (key) activeSceneModalFollowScopeKeys.add(key)
}

function hasModalFollowLock(
  editorScopeKey: string,
  liveGenScopeKey = currentModalLiveGenScopeKey()
): boolean {
  const key = buildModalFollowLockKey(liveGenScopeKey, editorScopeKey)
  return !!key && activeSceneModalFollowScopeKeys.has(key)
}

function deleteModalFollowLock(
  editorScopeKey: string,
  liveGenScopeKey = currentModalLiveGenScopeKey()
) {
  const key = buildModalFollowLockKey(liveGenScopeKey, editorScopeKey)
  if (key) activeSceneModalFollowScopeKeys.delete(key)
}

function resetSceneModalDeferredRestoreState() {
  for (const timer of sceneModalDeferredRestoreTimers.values()) {
    clearTimeout(timer)
  }
  sceneModalDeferredRestoreTimers.clear()
  sceneModalDeferredRestoreAttempts.clear()
}

/**
 * deferred 统一收口，杜绝「删锁 + 立刻 restore」与新跟随互抢打爆 `/task/stream`。
 * @returns true = 已被继任跟随接管，调用方 finally 不得 endFollow / 删锁 / 清 overlay
 */

  return {
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
  }
}

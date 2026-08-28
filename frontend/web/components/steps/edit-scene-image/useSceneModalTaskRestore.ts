'use client'

import {
followEditImageTask,
formatCreationImageProgressText
} from '~/composables/useEditImageTask'
import {
followFormImageGenerateCardTask,
resolveFormImageGenerateCardResultFromTaskDetail
} from '~/composables/useFormImageGenerateCardTask'
import { followFormImageUpscaleTask } from '~/composables/useFormImageUpscaleTask'
import { followMultiViewImageTask } from '~/composables/useMultiViewImageTask'
import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import type { SceneModalSseTaskSnapshot } from '~/stores/creation'
import { userTaskDetail,userTaskDetailCached } from '~/utils/businessApi'
import { buildModalFollowLockKey,shouldApplyModalTaskProgressToCanvas } from '~/utils/liveGenScopeIsolation'
import { tryAcquireModalFollowLock } from '~/utils/modalSseFollowReconnectPolicy'
import {
createModalActivationTaskDetailLoader,
runModalTabActivation,
shouldAllowModalTabSseConnect
} from '~/utils/modalTabSseMutex'
import {
drainStep3SseQueue,
hasStep3SseSlot,
releaseStep3SseSlot,
requeueStep3SseItemToEnd,
tryAcquireStep3SseSlot
} from '~/utils/step3SseConcurrencyGate'
import {
formatTaskSseJoinedLiveText
} from '~/utils/taskSseProgressText'
import { resolveFallbackSceneModalSseTask } from './sceneModalFallbackResolve'
import {
isDeferredModalFollowResult,
isTerminalUserTaskStatus,
notifyFormCardBatchSettled
} from './sceneModalTaskParsers'
import { createSceneModalTaskResultOps } from './sceneModalTaskResultOps'
import type {
EditSceneImageModalCtx,
SceneModalTaskDetail,
SceneModalTaskOwner,
SceneModalTaskRestoreApi
} from './types'

const FORM_IMAGE_TASK_SETTLED_EVENT = 'create-flow-form-image-task-settled'

export type { SceneModalTaskRestoreApi }

export function useSceneModalTaskRestore(ctx: EditSceneImageModalCtx): SceneModalTaskRestoreApi {
  const {
    resolveModalStep3Tab,
    notifyFormImageTaskSettledFromModal,
    followGenericExtractTaskForModal,
    sceneModalTaskKindToAutoUseType,
    claimFormImagesFromSceneModalTaskResult,
    applySettingCardGenerateSuccess,
    resolveSettingCardFollowResult,
    applySceneModalSseTaskResult
  } = createSceneModalTaskResultOps(ctx)
  async function runSceneModalSseFollow(
    snap: SceneModalSseTaskSnapshot,
    opts?: { silentComplete?: boolean }
  ) {
    const { sceneIdx, imageIdx, taskKind, taskId, editorScopeKey } = snap
    /** 任务归属作品/集：切项目后仍用此键写 Pinia / 释放锁，避免串桶 */
    const liveGenScopeKey = ctx.currentModalLiveGenScopeKey()
    const taskOwner: SceneModalTaskOwner = { editorScopeKey, taskId, liveGenScopeKey }
    const currentEditorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(ctx.currentSceneIndex.get())
    const isCurrentTaskScope = () => {
      const ownerDecision = ctx.sceneModalTaskCleanupDecision(taskOwner).decision
      if (!ownerDecision.clearSession && !ownerDecision.clearSnapshot) return false
      return shouldApplyModalTaskProgressToCanvas({
        taskLiveGenScopeKey: liveGenScopeKey,
        currentLiveGenScopeKey: ctx.currentModalLiveGenScopeKey(),
        taskEditorScopeKey: editorScopeKey,
        currentEditorScopeKey: ctx.buildEditorScopeKeyForSceneIndex(ctx.currentSceneIndex.get()),
        modalOpen: ctx.props().open
      })
    }
    if (
      !shouldAllowModalTabSseConnect({
        currentTabKey: currentEditorScopeKey,
        targetTabKey: editorScopeKey
      })
    ) {
      return
    }

    const lockKey = buildModalFollowLockKey(liveGenScopeKey, editorScopeKey)
    if (!editorScopeKey || !tryAcquireModalFollowLock(ctx.activeSceneModalFollowScopeKeys, lockKey)) {
      return
    }

    const gate = tryAcquireStep3SseSlot({
      taskId,
      owner: 'modal',
      allowPreemptOuter: true
    })
    if (gate.kind === 'enqueued' || gate.kind === 'rejected') {
      ctx.deleteModalFollowLock(editorScopeKey, liveGenScopeKey)
      return
    }
    if (gate.kind === 'preempt' && typeof window !== 'undefined') {
      suspendTaskSseFollow(gate.releaseTaskId)
      window.dispatchEvent(
        new CustomEvent('create-flow-stop-task', {
          detail: { taskId: gate.releaseTaskId, silent: true }
        })
      )
    }

    ctx.store().beginStep3FormImageTaskFollow(taskId)

    if (typeof window !== 'undefined') {
      window.dispatchEvent(
        new CustomEvent('create-flow-stop-task', { detail: { taskId, silent: true } })
      )
    }

    ctx.beginCanvasTaskOverlay(
      sceneIdx,
      imageIdx,
      taskKind === 'upscale'
        ? '高清处理中…'
        : taskKind === 'multi-view'
          ? '多机位生图中...'
          : taskKind === 'form-image'
            ? '正在生成形态图…'
            : taskKind === 'setting-card'
              ? '设定卡生成中…'
              : '生图中...',
      taskKind
    )

    let result: unknown
    /** superseded 时锁/follow/overlay 归继任跟随，本路 finally 不得回收 */
    let relinquishedToSuccessor = false
    try {
      if (taskKind === 'edit-image' || taskKind === 'dialogue') {
        result = await followEditImageTask({
          taskId,
          onProgress: (p) => {
            ctx.syncSceneModalSseProgress(snap, p, liveGenScopeKey)
            ctx.applyCanvasProgressIfCurrent({
              liveGenScopeKey,
              editorScopeKey,
              taskId,
              text: formatCreationImageProgressText(p)
            })
            if (p.items?.length && isCurrentTaskScope()) {
              void ctx.claimFormImagesForModal(taskId, 'form_edit_chat', { items: p.items }).then(async () => {
                if (!isCurrentTaskScope()) return
                const focusId = p.items![p.items!.length - 1]?.imageId ?? null
                await ctx.initFormImageListOnOpen({ focusImageId: focusId })
                if (isCurrentTaskScope()) ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
              })
            }
          }
        })
      } else if (taskKind === 'form-image') {
        result = await followGenericExtractTaskForModal({
          taskId,
          onProgress: (p) => {
            ctx.syncSceneModalSseProgress(snap, p, liveGenScopeKey)
            ctx.applyCanvasProgressIfCurrent({
              liveGenScopeKey,
              editorScopeKey,
              taskId,
              text: formatTaskSseJoinedLiveText(p, '正在生成形态图…')
            })
          }
        })
      } else if (taskKind === 'upscale') {
        result = await followFormImageUpscaleTask({
          taskId,
          onProgress: (p) => {
            ctx.syncSceneModalSseProgress(snap, p, liveGenScopeKey)
            ctx.applyCanvasProgressIfCurrent({
              liveGenScopeKey,
              editorScopeKey,
              taskId,
              text: formatTaskSseJoinedLiveText(p, '高清处理中…')
            })
          }
        })
      } else if (taskKind === 'multi-view') {
        result = await followMultiViewImageTask({
          taskId,
          onProgress: (p) => {
            ctx.syncSceneModalSseProgress(snap, p, liveGenScopeKey)
            ctx.applyCanvasProgressIfCurrent({
              liveGenScopeKey,
              editorScopeKey,
              taskId,
              text: formatTaskSseJoinedLiveText(p, '多机位生图中...')
            })
          }
        })
      } else if (taskKind === 'setting-card') {
        result = await followFormImageGenerateCardTask({
          taskId,
          onProgress: (p) => {
            ctx.syncSceneModalSseProgress(snap, p, liveGenScopeKey)
            ctx.applyCanvasProgressIfCurrent({
              liveGenScopeKey,
              editorScopeKey,
              taskId,
              text: formatTaskSseJoinedLiveText(p, '设定卡生成中…')
            })
          }
        })
      } else {
        result = { ok: false as const, errorMessage: '未知任务类型' }
      }

      if (isDeferredModalFollowResult(result)) {
        relinquishedToSuccessor = ctx.handleDeferredSceneModalFollow({
          sceneIdx,
          editorScopeKey,
          liveGenScopeKey,
          errorMessage: (result as { errorMessage?: unknown }).errorMessage
        })
        return
      }

      const settingCardSourceIds = await applySceneModalSseTaskResult(snap, result, {
        ...opts,
        isCurrent: isCurrentTaskScope,
        liveGenScopeKey
      })
      if (settingCardSourceIds !== undefined) {
        notifyFormCardBatchSettled(settingCardSourceIds)
        ctx.store().refreshStep3VisualGeneratingFlag()
      }
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }
    } finally {
      if (!relinquishedToSuccessor) {
        ctx.store().endStep3FormImageTaskFollow(taskId)
      }
      if (!isDeferredModalFollowResult(result) && !relinquishedToSuccessor) {
        const canClearCurrentUi = ctx.clearSceneModalTaskStateIfOwned(taskOwner)
        if (ctx.sceneModalTaskCleanupDecision(taskOwner).decision.canClearUi) {
          ctx.deleteModalFollowLock(editorScopeKey, liveGenScopeKey)
        }
        if (canClearCurrentUi) {
          ctx.endCanvasTaskOverlay(sceneIdx, imageIdx)
        }
        if (hasStep3SseSlot(taskId)) {
          releaseStep3SseSlot(taskId)
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
      }
      ctx.store().refreshStep3VisualGeneratingFlag()
    }
  }

  async function applyTerminalSceneModalTaskIfNeeded(
    persisted: SceneModalSseTaskSnapshot,
    editorScopeKey: string,
    isCurrent: () => boolean,
    knownDetail?: SceneModalTaskDetail
  ): Promise<boolean> {
    const taskOwner: SceneModalTaskOwner = {
      editorScopeKey,
      taskId: persisted.taskId,
      liveGenScopeKey: ctx.currentModalLiveGenScopeKey()
    }
    try {
      const detail = knownDetail ?? (await userTaskDetailCached(persisted.taskId))
      if (!isCurrent()) return false
      if (!detail || !isTerminalUserTaskStatus(detail.status)) return false

      const st = String(detail.status || '').toUpperCase()
      let settingCardSourceIds: number[] | undefined
      if (st === 'SUCCEEDED' && persisted.taskKind === 'setting-card') {
        const cardResult = resolveFormImageGenerateCardResultFromTaskDetail(detail)
        if (cardResult?.ok) {
          settingCardSourceIds = await applySceneModalSseTaskResult(
            persisted,
            cardResult,
            {
              silentComplete: true,
              isCurrent,
              taskDetail: detail,
              liveGenScopeKey: taskOwner.liveGenScopeKey
            }
          )
        }
      } else if (st === 'SUCCEEDED' || st === 'PARTIAL_FAILED') {
        // form-image 由 settled event 的外层 finalize 唯一接管；弹窗再 claim 会对同一终态重复 /form/use。
        if (persisted.taskKind !== 'form-image') {
          await ctx.claimFormImagesForModal(
            persisted.taskId,
            sceneModalTaskKindToAutoUseType(persisted.taskKind) ?? detail.taskType,
            detail.resultData
          )
        }
        if (!isCurrent()) return false
        await ctx.initFormImageListOnOpen()
        if (!isCurrent()) return false
        ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
        if (persisted.taskKind === 'form-image') {
          notifyFormImageTaskSettledFromModal({
            taskId: persisted.taskId,
            ok: true,
            completeData: detail.resultData,
            taskType: detail.taskType ?? 'form_image'
          })
        }
      } else {
        if (persisted.taskKind === 'form-image') {
          notifyFormImageTaskSettledFromModal({
            taskId: persisted.taskId,
            ok: false,
            completeData: detail.resultData,
            errorMessage: '生图失败',
            taskType: detail.taskType ?? 'form_image'
          })
        }
      }

      if (!isCurrent()) return false
      const canClearTaskUi = ctx.clearSceneModalTaskStateIfOwned(taskOwner, {
        sceneIdx: persisted.sceneIdx
      })
      if (canClearTaskUi) ctx.endCanvasTaskOverlay(persisted.sceneIdx, persisted.imageIdx)
      if (settingCardSourceIds !== undefined) {
        notifyFormCardBatchSettled(settingCardSourceIds)
        ctx.store().refreshStep3VisualGeneratingFlag()
      }
      return true
    } catch {
      return false
    }
  }

  async function restoreSceneModalSseIfNeeded(
    sceneIdx: number,
    options?: { loadingStateReady?: boolean; isCurrent?: () => boolean }
  ) {
    if (!ctx.props().open) return
    const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
    if (!editorScopeKey) return
    const isCurrent =
      options?.isCurrent ??
      (() =>
        ctx.props().open &&
        ctx.currentSceneIndex.get() === sceneIdx &&
        ctx.buildEditorScopeKeyForSceneIndex(ctx.currentSceneIndex.get()) === editorScopeKey)

    if (!options?.loadingStateReady) await ctx.ensureModalLoadingRestored(sceneIdx, isCurrent)
    if (!isCurrent()) return

    if (ctx.hasModalFollowLock(editorScopeKey)) return

    const gen = ++ctx.resumeSceneModalFollowGen.current

    const fallbackDetailByTaskId = new Map<number, SceneModalTaskDetail>()
    const loadFallbackTaskDetail = createModalActivationTaskDetailLoader(async (taskId: number) => {
      const detail = await userTaskDetail({ taskId })
      fallbackDetailByTaskId.set(taskId, detail)
      return detail
    })

    let persisted =
      ctx.resolvePersistedSceneModalSseTask(editorScopeKey) ??
      ctx.rebuildPersistedFromSession(sceneIdx) ??
      (await resolveFallbackSceneModalSseTask(
        ctx,
        sceneIdx,
        editorScopeKey,
        isCurrent,
        loadFallbackTaskDetail
      ))

    if (gen !== ctx.resumeSceneModalFollowGen.current || !isCurrent()) return
    if (!persisted) {
      await ctx.clearStaleSceneModalGeneratingState(sceneIdx, isCurrent)
      if (!isCurrent()) return
      if (ctx.isEditorScopeGeneratingExternally(sceneIdx)) {
        ctx.primeSceneModalLoadingUi(sceneIdx)
      }
      return
    }

    if (!ctx.store().getSceneModalSseTask(editorScopeKey)) {
      ctx.store().setSceneModalSseTask(editorScopeKey, persisted)
    }
    ctx.syncExternalGeneratingForModalScope(sceneIdx)

    const pendingIdx = ctx.localSceneImages.get().findIndex((img) => img?._generating)
    if (pendingIdx >= 0) {
      persisted = { ...persisted, imageIdx: pendingIdx }
      ctx.store().setSceneModalSseTask(editorScopeKey, persisted)
      if (sceneIdx === ctx.currentSceneIndex.get()) {
        ctx.currentImageIndex.set(pendingIdx)
      }
    } else if (sceneIdx === ctx.currentSceneIndex.get() && persisted.imageIdx >= 0) {
      ctx.currentImageIndex.set(persisted.imageIdx)
    }

    ctx.primeSceneModalLoadingUi(sceneIdx)

    const terminalHandled = await applyTerminalSceneModalTaskIfNeeded(
      persisted,
      editorScopeKey,
      isCurrent,
      fallbackDetailByTaskId.get(persisted.taskId)
    )
    if (gen !== ctx.resumeSceneModalFollowGen.current || !isCurrent()) return
    if (terminalHandled) return

    await runSceneModalSseFollow(persisted, { silentComplete: true })
  }

  async function activateSceneModalTab(sceneIdx: number, options?: { forceImageRefresh?: boolean }) {
    const activationGen = ++ctx.sceneModalTabActivationGen.current
    ctx.resumeSceneModalFollowGen.current++
    const editorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(sceneIdx)
    if (!editorScopeKey) return

    const isCurrent = () =>
      ctx.props().open &&
      activationGen === ctx.sceneModalTabActivationGen.current &&
      ctx.currentSceneIndex.get() === sceneIdx &&
      ctx.buildEditorScopeKeyForSceneIndex(ctx.currentSceneIndex.get()) === editorScopeKey

    try {
      await runModalTabActivation({
        ensureLoadingState: () => ctx.ensureModalLoadingRestored(sceneIdx, isCurrent),
        refreshImages: async () => {
          await new Promise((r) => setTimeout(r, 0))
          const key = ctx.buildInitFormImageListKey()
          if (!options?.forceImageRefresh && key === ctx.lastInitFormImageListKey.current) return
          ctx.lastInitFormImageListKey.current = key
          await ctx.initFormImageListOnOpen()
        },
        primeLoadingUi: () => ctx.primeSceneModalLoadingUi(sceneIdx),
        restoreTask: () =>
          restoreSceneModalSseIfNeeded(sceneIdx, {
            loadingStateReady: true,
            isCurrent
          }),
        isCurrent
      })
    } catch {
      // 网络/恢复异常保留持久化任务快照，等待下一次激活重试；Tab 骨架由独立 ownership 定时释放。
    } finally {
      if (isCurrent()) {
        ctx.scrollActiveSceneTabIntoView()
        ctx.sceneTabBarRef.current?.refresh()
      }
    }
  }

  return {
    restoreSceneModalSseIfNeeded,
    activateSceneModalTab,
    runSceneModalSseFollow,
    applySettingCardGenerateSuccess,
    resolveSettingCardFollowResult
  }
}

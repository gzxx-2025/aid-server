'use client'

import { useEffect,useRef } from 'react'
import {
resolveCurrentStep4LiveGenScopeBlobs
} from '~/composables/useCreationStoreHydration'
import {
isStoryboardImageTaskOngoing,
runStoryboardImageGenerateTask,
STORYBOARD_IMAGE_GEN_SSE_COMPLETE_EVENT,
STORYBOARD_IMAGE_GEN_SSE_TERMINAL_EVENT
} from '~/composables/useStoryboardImageGenerateTask'
import type { StoryboardRecordRow } from '~/types/business-api'
import { sortStoryboardImagesForParent } from '~/utils/storyboardImageCover'
import {
isModalImageGenUserDismissed,
readModalImageGenSession
} from '~/utils/storyboardImageModalGenSession'
import { activeStoryboardImageModalGenFollowIds } from '~/utils/storyboardImageModalOwnedFollow'
import {
findPendingStoryboardRecordId,
findPendingStoryboardRecordTaskId
} from '~/utils/storyboardRecordPending'
import { createStoryboardModalGenerateCore } from './storyboardModalGenerateCore'
import type { EditStoryboardImageModalCtx } from './types'

const activeStoryboardImageFollowStoryboardIds = activeStoryboardImageModalGenFollowIds

export interface StoryboardModalGenerateApi {
  showStoryboardGenerateOverlay: () => boolean
  showCanvasImageGenMask: () => boolean
  sceneImageGenMaskText: () => string
  showStoryboardGenerateButtonLoading: () => boolean
  storyboardGenerateOverlayText: () => string
  handleStartGenerate: () => Promise<void>
  runStoryboardImageGenerateForScene: (
    sceneIdx: number,
    opts: {
      submitBody?: Parameters<typeof runStoryboardImageGenerateTask>[0]['body']
      resumeTaskId?: number
      resumeRecordId?: number | null
      beforeCount?: number
      progressSubmitText?: string
      silentComplete?: boolean
    }
  ) => Promise<void>
  restoreStoryboardImageGenerateIfNeeded: (sceneIdx: number) => Promise<void>
}

export function useStoryboardModalGenerate(
  ctx: EditStoryboardImageModalCtx
): StoryboardModalGenerateApi {
  const { handleStartGenerate, runStoryboardImageGenerateForScene, sceneImageGenMaskText, showCanvasImageGenMask, showStoryboardGenerateButtonLoading, showStoryboardGenerateOverlay, storyboardGenerateOverlayText } = createStoryboardModalGenerateCore(ctx)
  async function restoreStoryboardImageGenerateIfNeeded(sceneIdx: number) {
    const storyboardId = ctx.sceneStoryboardIdNum(sceneIdx)
    if (storyboardId == null) return
    if (!ctx.shouldRestoreStoryboardImageGenerate(sceneIdx)) return
    if (!ctx.isModalImageGenOwnerScene(sceneIdx)) {
      ctx.clearLocalGeneratingPlaceholdersForScene(sceneIdx)
      return
    }

    ctx.primeStoryboardImageLoadingUi(sceneIdx)

    if (activeStoryboardImageFollowStoryboardIds.has(storyboardId)) {
      return
    }

    const gen = ++ctx.resumeStoryboardImageFollowGen.current

    let rows: StoryboardRecordRow[] = []
    try {
      rows = await ctx.fetchImageRecordsForStoryboard(storyboardId)
      const mapped = ctx.finalizeMappedImagesWhileGenerating(
        sceneIdx,
        sortStoryboardImagesForParent(ctx.mapRecordRowsToImageItems(rows, sceneIdx))
      )
      const prevImages = ctx.props().scenes[sceneIdx]?.images
      if (!ctx.isSameStoryboardImageRecordList(mapped, prevImages)) {
        ctx.emitUpdate(sceneIdx, { images: mapped })
      }
      ctx.syncAddedImageIdsFromList(mapped)

      const pendingIdx = mapped.findIndex((m) => m._generating)
      if (pendingIdx >= 0 && sceneIdx === ctx.currentSceneIndex.get()) {
        ctx.currentImageIndex.set(pendingIdx)
      }
    } catch {
      /* 记录拉取失败时仍尝试用 Pinia 中的 taskId 恢复 */
    }

    if (gen !== ctx.resumeStoryboardImageFollowGen.current) return

    if (!ctx.shouldRestoreStoryboardImageGenerate(sceneIdx)) return

    const persisted = ctx.getModalImageGenTask(storyboardId)
    const session = ctx.readSessionForScene(sceneIdx)
    if (persisted?.kind === 'dialogue' || ctx.isCanvasOverlayModalTask(persisted)) return
    if (session?.tab === 'dialogue' || ctx.isModalOverlaySessionTab(session?.tab)) return

    const taskIdFromRecord = findPendingStoryboardRecordTaskId(rows)
    const recordIdFromRecord = findPendingStoryboardRecordId(rows)
    const sessionTaskId = ctx.isModalStoryboardGenerateSession(session)
      ? (session?.taskId ?? null)
      : null
    const taskId = persisted?.taskId ?? sessionTaskId ?? taskIdFromRecord ?? null

    if (!taskId) {
      if (ctx.hasModalImageGenPendingState(storyboardId)) {
        ctx.primeStoryboardImageLoadingUi(sceneIdx)
        return
      }
      // 无进行中任务时清掉可能残留的 panel generating，避免流程 tab loading 卡死
      ctx.clearModalStoryboardImageLoadingUi(storyboardId, sceneIdx)
      return
    }

    const ongoing = await isStoryboardImageTaskOngoing(taskId)
    if (gen !== ctx.resumeStoryboardImageFollowGen.current) return

    if (!ongoing) {
      if (ctx.hasModalImageGenPendingState(storyboardId)) {
        ctx.primeStoryboardImageLoadingUi(sceneIdx)
        await runStoryboardImageGenerateForScene(sceneIdx, {
          resumeTaskId: taskId,
          resumeRecordId: recordIdFromRecord,
          silentComplete: true
        })
        return
      }
      ctx.clearModalStoryboardImageLoadingUi(storyboardId, sceneIdx)
      return
    }

    await runStoryboardImageGenerateForScene(sceneIdx, {
      resumeTaskId: taskId,
      resumeRecordId: recordIdFromRecord,
      silentComplete: true
    })
  }

  /** 任意 SSE 跟进方收到 complete 时同步弹窗记录与 loading（避免批量恢复抢占 SSE 后弹窗无响应） */
  async function applyModalStoryboardImageGenSseComplete(detail: {
    taskId?: number
    storyboardId?: number
    recordId?: number | null
    items?: Array<{ recordId?: number; imageId?: number; storyboardId?: number }>
  }) {
    const taskId = Number(detail?.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) return

    let storyboardId = Number(detail?.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) {
      const fromItem = detail?.items?.find((it) => Number(it?.storyboardId) > 0)
      storyboardId = Number(fromItem?.storyboardId)
    }
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return

    const task = ctx.getModalImageGenTask(storyboardId)
    if (ctx.isDialogueModalTask(task) || ctx.isCanvasOverlayModalTask(task)) return

    const session = readModalImageGenSession(ctx.storyboardImageModalSessionScope())
    const sessionMatches =
      session?.storyboardId === storyboardId &&
      (session.tab === 'generate' || !session.tab) &&
      !isModalImageGenUserDismissed(storyboardId, ctx.storyboardImageModalSessionScope())
    const taskMatches = task?.taskId === taskId && task?.kind !== 'dialogue'
    const sessionTaskMatches = sessionMatches && session?.taskId === taskId
    if (!taskMatches && !sessionTaskMatches) return

    const sceneIdx = ctx.resolveModalImageGenOwnerSceneIdx(storyboardId) ?? session?.sceneIdx
    if (sceneIdx == null || sceneIdx < 0 || !ctx.isModalImageGenOwnerScene(sceneIdx)) return
    if (
      !ctx.hasModalImageGenPendingState(storyboardId) &&
      !activeStoryboardImageFollowStoryboardIds.has(storyboardId)
    ) {
      return
    }

    const recordId =
      detail?.recordId ??
      detail?.items?.[detail.items.length - 1]?.recordId ??
      detail?.items?.[detail.items.length - 1]?.imageId ??
      null

    ctx.clearModalStoryboardImageLoadingUi(storyboardId, sceneIdx)
    await ctx.refreshSceneRecords(
      sceneIdx,
      recordId != null ? Number(recordId) : undefined,
      undefined,
      {
        force: true
      }
    )
  }

  function resolveStoryboardIdForImageGenTask(taskId: number): number | null {
    const tid = Number(taskId)
    if (!Number.isFinite(tid) || tid <= 0) return null
    for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(ctx.store(), ctx.route())) {
      for (const [sidRaw, snap] of Object.entries(
        blob.storyboardImageGenTasksByStoryboardId || {}
      )) {
        if (Number((snap as { taskId?: number }).taskId) === tid) {
          const sid = Number(sidRaw)
          if (Number.isFinite(sid) && sid > 0) return sid
        }
      }
    }
    const session = readModalImageGenSession(ctx.storyboardImageModalSessionScope())
    if (session?.taskId === tid) return session.storyboardId
    return null
  }

  /** SSE 返回 error / cancelled / failed 时同步清除弹窗与分镜列表 loading */
  async function applyModalStoryboardImageGenSseTerminal(detail: {
    taskId?: number
    storyboardId?: number
    ok?: boolean
    errorMessage?: string
  }) {
    if (detail?.ok) return
    const taskId = Number(detail?.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) return

    let storyboardId = Number(detail?.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) {
      storyboardId = Number(resolveStoryboardIdForImageGenTask(taskId) ?? 0)
    }
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return

    const task = ctx.getModalImageGenTask(storyboardId)
    if (ctx.isDialogueModalTask(task) || ctx.isCanvasOverlayModalTask(task)) return

    const session = readModalImageGenSession(ctx.storyboardImageModalSessionScope())
    const sessionMatches =
      session?.storyboardId === storyboardId &&
      (session.tab === 'generate' || !session.tab) &&
      !isModalImageGenUserDismissed(storyboardId, ctx.storyboardImageModalSessionScope())
    const taskMatches = task?.taskId === taskId && task?.kind !== 'dialogue'
    const sessionTaskMatches = sessionMatches && session?.taskId === taskId
    if (
      !taskMatches &&
      !sessionTaskMatches &&
      !activeStoryboardImageFollowStoryboardIds.has(storyboardId) &&
      !ctx.hasModalImageGenPendingState(storyboardId)
    ) {
      ctx.clearStoryboardPanelImageGenerating(storyboardId)
      ctx.clearModalStoryboardImageGenTaskEverywhere(storyboardId)
      return
    }

    const sceneIdx = ctx.resolveModalImageGenOwnerSceneIdx(storyboardId) ?? session?.sceneIdx
    if (sceneIdx == null || sceneIdx < 0) {
      ctx.clearStoryboardPanelImageGenerating(storyboardId)
      ctx.clearModalStoryboardImageGenTaskEverywhere(storyboardId)
      return
    }

    ctx.clearModalStoryboardImageLoadingUi(storyboardId, sceneIdx)
    await ctx.refreshSceneRecords(sceneIdx)
  }

  /** 原 onMounted/onUnmounted：监听任意跟进方派发的分镜图 SSE 终态事件 */
  const applyCompleteRef = useRef(applyModalStoryboardImageGenSseComplete)
  applyCompleteRef.current = applyModalStoryboardImageGenSseComplete
  const applyTerminalRef = useRef(applyModalStoryboardImageGenSseTerminal)
  applyTerminalRef.current = applyModalStoryboardImageGenSseTerminal

  useEffect(() => {
    if (typeof window === 'undefined') return

    function handleStoryboardImageGenSseCompleteEvent(event: Event) {
      if (!ctx.props().open) return
      const detail = (event as CustomEvent).detail as Parameters<
        typeof applyModalStoryboardImageGenSseComplete
      >[0]
      void applyCompleteRef.current(detail)
    }

    function handleStoryboardImageGenSseTerminalEvent(event: Event) {
      const detail = (event as CustomEvent).detail as Parameters<
        typeof applyModalStoryboardImageGenSseTerminal
      >[0]
      void applyTerminalRef.current(detail)
    }

    window.addEventListener(
      STORYBOARD_IMAGE_GEN_SSE_COMPLETE_EVENT,
      handleStoryboardImageGenSseCompleteEvent
    )
    window.addEventListener(
      STORYBOARD_IMAGE_GEN_SSE_TERMINAL_EVENT,
      handleStoryboardImageGenSseTerminalEvent
    )
    return () => {
      window.removeEventListener(
        STORYBOARD_IMAGE_GEN_SSE_COMPLETE_EVENT,
        handleStoryboardImageGenSseCompleteEvent
      )
      window.removeEventListener(
        STORYBOARD_IMAGE_GEN_SSE_TERMINAL_EVENT,
        handleStoryboardImageGenSseTerminalEvent
      )
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return {
    showStoryboardGenerateOverlay,
    showCanvasImageGenMask,
    sceneImageGenMaskText,
    showStoryboardGenerateButtonLoading,
    storyboardGenerateOverlayText,
    handleStartGenerate,
    runStoryboardImageGenerateForScene,
    restoreStoryboardImageGenerateIfNeeded
  }
}

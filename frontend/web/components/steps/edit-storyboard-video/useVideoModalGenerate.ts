'use client'

import { message } from 'antd'
import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import { findStoryboardVideoGenTaskInScopes } from '~/composables/useCreationStoreHydration'
import { buildModalTaskOverlayKey } from '~/composables/useModalTaskScope'
import {
activeStoryboardVideoModalOwnedFollowIds,
isStoryboardVideoModalRestoreFollowing
} from '~/composables/useStoryboardVideoBatchGenerate'
import {
followStoryboardVideoGenerateTask,
isStoryboardVideoTaskOngoing,
runStoryboardEdgeVideoGenerateTask,
runStoryboardGridVideoGenerateTask,
runStoryboardImageVideoGenerateTask,
runStoryboardMultiVideoGenerateTask
} from '~/composables/useStoryboardVideoGenerateTask'
import type { StoryboardRecordRow } from '~/types/business-api'
import {
modalGenSessionScopeFromScopeKey
} from '~/utils/modalGenSessionScope'
import { resolveOngoingTaskId } from '~/utils/modalGenTaskRestore'
import { findPendingStoryboardRecordTaskId,isPendingStoryboardRecord } from '~/utils/storyboardRecordPending'
import {
clearStoryboardVideoModalGenSession,
persistStoryboardVideoModalGenSession
} from '~/utils/storyboardVideoModalGenSession'
import { formatTaskSseLiveText } from '~/utils/taskSseProgressText'
import type { VideoModalCtx,VideoModalGenerateApi,VideoTaskKind } from './types'

/** 出片任务提交 / SSE 跟随 / 刷新恢复（原 setup 生成段逻辑） */
export function useVideoModalGenerate(ctx: VideoModalCtx): void {
  async function runStoryboardVideoGenerateForScene(
    sceneIdx: number,
    opts: {
      taskKind: VideoTaskKind
      submitImageVideoBody?: Parameters<typeof runStoryboardImageVideoGenerateTask>[0]['body']
      submitMultiBody?: Parameters<typeof runStoryboardMultiVideoGenerateTask>[0]['body']
      submitEdgeBody?: Parameters<typeof runStoryboardEdgeVideoGenerateTask>[0]['body']
      submitGridBody?: Parameters<typeof runStoryboardGridVideoGenerateTask>[0]['body']
      resumeTaskId?: number
      progressSubmit?: string
      progressRunning?: string
      silentComplete?: boolean
    }
  ) {
    const storyboardId = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return

    /**
     * 剧集隔离：任务归属于启动时的作品/集（scope 快照）。
     * 任务快照始终写回该快照 scope 桶；切集后终态收尾不得写当前集扁平 store、不得 toast。
     */
    const taskScope = captureCreationLiveGenScope()
    const taskSessionScope = modalGenSessionScopeFromScopeKey(taskScope.scopeKey)

    persistStoryboardVideoModalGenSession(
      storyboardId,
      sceneIdx,
      opts.resumeTaskId
        ? { taskKind: opts.taskKind, taskId: opts.resumeTaskId }
        : { taskKind: opts.taskKind },
      taskSessionScope
    )

    ctx.videoGenerateTargetKey.set(buildModalTaskOverlayKey(ctx.overlayKeyParts(sceneIdx, opts.taskKind)))
    if (opts.resumeTaskId) {
      const persisted = findStoryboardVideoGenTaskInScopes(ctx.store(), storyboardId, ctx.route())
      ctx.videoGenerateProgressText.set(
        formatTaskSseLiveText(persisted || {}, '') ||
          opts.progressRunning ||
          ctx.defaultVideoProgressTextForTaskKind(opts.taskKind)
      )
    } else {
      ctx.videoGenerateProgressText.set(opts.progressSubmit || '分镜视频提交中…')
    }
    ctx.ensureGeneratingPlaceholderVideo(sceneIdx)

    const onProgress = (p: { stepTitle?: string; message?: string; taskId?: number }) => {
      const live = String(p.message || p.stepTitle || '').trim()
      if (live) {
        ctx.videoGenerateProgressText.set(live)
      }
      const tid = Number(
        p.taskId ??
          findStoryboardVideoGenTaskInScopes(ctx.store(), storyboardId, ctx.route())?.taskId
      )
      if (Number.isFinite(tid) && tid > 0) {
        ctx.store().setStoryboardVideoGenTask(
          storyboardId,
          {
            taskId: tid,
            sceneIdx,
            taskKind: opts.taskKind,
            message: p.message,
            stepTitle: p.stepTitle
          },
          taskScope.scopeKey
        )
        persistStoryboardVideoModalGenSession(
          storyboardId,
          sceneIdx,
          {
            taskKind: opts.taskKind,
            taskId: tid
          },
          taskSessionScope
        )
      }
    }

    const overlayParts = ctx.overlayKeyParts(sceneIdx, opts.taskKind)
    activeStoryboardVideoModalOwnedFollowIds.add(storyboardId)
    ctx.modalOwnedVideoRecordsRefreshedIds.delete(storyboardId)
    let keepPendingUi = false

    /** 提交回执统一写快照 + 会话 + 晚到挂起（四类任务同构） */
    const onSubmitted = (taskKind: VideoTaskKind) => ({ taskId }: { taskId: number }) => {
      ctx.store().setStoryboardVideoGenTask(
        storyboardId,
        { taskId, sceneIdx, taskKind },
        taskScope.scopeKey
      )
      persistStoryboardVideoModalGenSession(
        storyboardId,
        sceneIdx,
        {
          taskKind,
          taskId
        },
        taskSessionScope
      )
      ctx.suspendLateModalVideoFollowIfScopeChanged(taskId, taskScope)
    }

    try {
      let result:
        | Awaited<ReturnType<typeof runStoryboardImageVideoGenerateTask>>
        | Awaited<ReturnType<typeof runStoryboardMultiVideoGenerateTask>>
        | Awaited<ReturnType<typeof runStoryboardEdgeVideoGenerateTask>>
        | Awaited<ReturnType<typeof runStoryboardGridVideoGenerateTask>>

      if (opts.resumeTaskId) {
        result = await followStoryboardVideoGenerateTask({
          taskId: opts.resumeTaskId,
          onProgress
        })
      } else if (opts.taskKind === 'i2v' && opts.submitImageVideoBody) {
        result = await runStoryboardImageVideoGenerateTask({
          body: opts.submitImageVideoBody,
          onSubmitted: onSubmitted('i2v'),
          onProgress
        })
      } else if (opts.taskKind === 'multi' && opts.submitMultiBody) {
        result = await runStoryboardMultiVideoGenerateTask({
          body: opts.submitMultiBody,
          onSubmitted: onSubmitted('multi'),
          onProgress
        })
      } else if (opts.taskKind === 'edge' && opts.submitEdgeBody) {
        result = await runStoryboardEdgeVideoGenerateTask({
          body: opts.submitEdgeBody,
          onSubmitted: onSubmitted('edge'),
          onProgress
        })
      } else if (opts.taskKind === 'grid' && opts.submitGridBody) {
        result = await runStoryboardGridVideoGenerateTask({
          body: opts.submitGridBody,
          onSubmitted: onSubmitted('grid'),
          onProgress
        })
      } else {
        return
      }

      /**
       * SSE 被挂起 / 被新跟随抢占 / 任务仍后台进行：不是失败。
       * 保留 scope 桶内任务快照供切回后恢复，不 toast、不清 loading、不刷记录。
       */
      if (!result.ok && 'deferred' in result && result.deferred) {
        keepPendingUi = true
        return
      }

      /**
       * 剧集隔离：终态到达时已切到其它作品/集 → 只清理任务所属 scope 桶的快照，
       * 禁止写当前集扁平 store、禁止 toast、禁止刷新/回写当前集数据。
       */
      if (!matchesCreationLiveGenScope(taskScope)) {
        ctx.store().clearStoryboardVideoGenTask(storyboardId, taskScope.scopeKey)
        clearStoryboardVideoModalGenSession(taskSessionScope)
        if (typeof window !== 'undefined') {
          window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
        }
        return
      }

      if (!result.ok) {
        if (!opts.silentComplete) {
          message.error(
            'errorMessage' in result ? result.errorMessage || '视频生成失败' : '视频生成失败'
          )
        }
        ctx.clearModalStoryboardVideoLoadingUi(storyboardId, sceneIdx, opts.taskKind)
        await ctx.refreshVideoRecordsFresh(sceneIdx)
        ctx.modalOwnedVideoRecordsRefreshedIds.add(storyboardId)
        return
      }

      // 先清 loading/占位（避免随后 clear 读到旧 props 把回填盖空），再强制拉列表，最后用 SSE items 兜底
      ctx.clearModalStoryboardVideoLoadingUi(storyboardId, sceneIdx, opts.taskKind)
      await ctx.refreshVideoRecordsFresh(sceneIdx, { focusLatest: true })
      ctx.modalOwnedVideoRecordsRefreshedIds.add(storyboardId)
      if ('data' in result) {
        ctx.applyTerminalVideoItemsToScene(sceneIdx, result.data)
      }
      if (!opts.silentComplete) {
        message.success('视频生成完成')
      }
      // 全局任务刷新只在 finally 派发一次，避免与 sync 回调叠打 list-by-storyboard
    } catch (e: unknown) {
      if (!matchesCreationLiveGenScope(taskScope)) {
        ctx.store().clearStoryboardVideoGenTask(storyboardId, taskScope.scopeKey)
        clearStoryboardVideoModalGenSession(taskSessionScope)
      } else {
        if (!opts.silentComplete) {
          message.error(ctx.storyboardVideoBizErr(e))
        }
        ctx.clearModalStoryboardVideoLoadingUi(storyboardId, sceneIdx, opts.taskKind)
        await ctx.refreshVideoRecordsFresh(sceneIdx)
        ctx.modalOwnedVideoRecordsRefreshedIds.add(storyboardId)
      }
    } finally {
      activeStoryboardVideoModalOwnedFollowIds.delete(storyboardId)
      if (matchesCreationLiveGenScope(taskScope) && !keepPendingUi) {
        if (ctx.videoGenerateTargetKey.get() === buildModalTaskOverlayKey(overlayParts)) {
          ctx.clearVideoGenerateOverlayForScene(sceneIdx, opts.taskKind)
        }
      }
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }
    }
  }

  async function waitForStoryboardVideoModalRestore(
    storyboardId: number,
    gen: number
  ): Promise<boolean> {
    if (typeof window === 'undefined') return false
    while (isStoryboardVideoModalRestoreFollowing(storyboardId)) {
      if (gen !== ctx.resumeStoryboardVideoFollowGen.current) return false
      await new Promise((resolve) => setTimeout(resolve, 400))
    }
    return gen === ctx.resumeStoryboardVideoFollowGen.current
  }

  async function syncStoryboardVideoGenerateUiAfterSettled(
    sceneIdx: number,
    options?: { forceRefresh?: boolean }
  ) {
    const storyboardId = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return
    if (
      activeStoryboardVideoModalOwnedFollowIds.has(storyboardId) ||
      isStoryboardVideoModalRestoreFollowing(storyboardId)
    ) {
      return
    }

    // 弹窗自己的生成链路已 refresh：忽略随后的 global-tasks-updated
    if (!options?.forceRefresh && ctx.modalOwnedVideoRecordsRefreshedIds.has(storyboardId)) {
      return
    }

    const persisted = findStoryboardVideoGenTaskInScopes(ctx.store(), storyboardId, ctx.route())
    const hasGeneratingRow = (ctx.props().scenes[sceneIdx]?.videos || []).some(
      (v: any) => v?._generating || v?._localGeneratingPlaceholder
    )
    const hadPending =
      options?.forceRefresh === true ||
      !!persisted?.taskId ||
      ctx.hasStoryboardVideoPendingState(storyboardId) ||
      hasGeneratingRow

    if (persisted?.taskId) {
      const ongoing = await isStoryboardVideoTaskOngoing(persisted.taskId)
      if (ongoing) return
    }

    // 弹窗内生成已自行 refresh 后，global-tasks-updated 不应再打 list-by-storyboard
    if (!hadPending) return

    ctx.clearModalStoryboardVideoLoadingUi(storyboardId, sceneIdx, persisted?.taskKind)
    if (!shouldSkipStoryboardVideoRestore(storyboardId)) {
      await ctx.refreshVideoRecordsFresh(sceneIdx, { focusLatest: true })
      ctx.modalOwnedVideoRecordsRefreshedIds.add(storyboardId)
    }
  }

  function handleStoryboardVideoGenSettledEvent(event: Event) {
    if (!ctx.props().open) return
    const detail = (event as CustomEvent<{ storyboardId?: number }>).detail
    const sid = Number(detail?.storyboardId)
    const sceneIdx = ctx.props().scenes.findIndex((s) => Number(s?.storyboardId) === sid)
    if (sceneIdx < 0) return
    void syncStoryboardVideoGenerateUiAfterSettled(sceneIdx, { forceRefresh: true })
  }

  function handleGlobalTasksUpdatedForVideoModal() {
    if (!ctx.props().open) return
    if (
      ctx.isGeneratingVideoPrompt.get() ||
      ctx.isGeneratingMultiParamPrompt.get() ||
      ctx.isStoryboardVideoPromptGeneratingForScene(ctx.currentSceneIndex.get())
    ) {
      return
    }
    void syncStoryboardVideoGenerateUiAfterSettled(ctx.currentSceneIndex.get())
  }

  function shouldSkipStoryboardVideoRestore(storyboardId: number, taskId?: number | null): boolean {
    const sceneIdx = ctx.props().scenes.findIndex((s) => Number(s?.storyboardId) === storyboardId)
    if (sceneIdx >= 0 && ctx.isStoryboardVideoPromptGeneratingForScene(sceneIdx)) return true
    if (ctx.activeStoryboardPromptFollowStoryboardIds.has(storyboardId)) return true
    const tid = Number(taskId)
    if (Number.isFinite(tid) && tid > 0 && ctx.activeStoryboardPromptFollowTaskIds.has(tid)) return true
    return false
  }

  async function restoreStoryboardVideoGenerateIfNeeded(sceneIdx: number) {
    const storyboardId = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return

    if (!ctx.isModalVideoGenOwnerScene(sceneIdx)) {
      ctx.clearLocalGeneratingPlaceholdersForScene(sceneIdx)
      return
    }
    if (!ctx.shouldRestoreStoryboardVideoGenerate(sceneIdx)) return
    if (shouldSkipStoryboardVideoRestore(storyboardId)) return

    ctx.primeStoryboardVideoLoadingUi(sceneIdx)

    if (activeStoryboardVideoModalOwnedFollowIds.has(storyboardId)) {
      return
    }

    const gen = ++ctx.resumeStoryboardVideoFollowGen.current

    let rows: StoryboardRecordRow[] = []
    try {
      rows = await ctx.fetchProjectRecordsForStoryboard(storyboardId, 'video')
      if (gen !== ctx.resumeStoryboardVideoFollowGen.current) return
      if (shouldSkipStoryboardVideoRestore(storyboardId)) return

      const mapped = ctx.finalizeMappedVideosWhileGenerating(
        sceneIdx,
        rows
          .filter((r) => !!String(r?.fileUrl ?? '').trim() || isPendingStoryboardRecord(r))
          .map(ctx.mapRecordRowToVideoItem)
      )
      ctx.emitUpdate(sceneIdx, { videos: mapped })
      const pendingIdx = mapped.findIndex((m: any) => m._generating)
      if (pendingIdx >= 0 && sceneIdx === ctx.currentSceneIndex.get()) {
        ctx.selectedVideoIdx.set(pendingIdx)
        ctx.scrollVideoCanvasToIndex(sceneIdx, pendingIdx)
      }
    } catch {
      /* ignore */
    }

    if (gen !== ctx.resumeStoryboardVideoFollowGen.current) return
    if (shouldSkipStoryboardVideoRestore(storyboardId)) return

    const {
      persisted,
      taskId: persistedTaskId,
      taskKind
    } = ctx.resolveVideoGenTaskSnapshotForStoryboard(storyboardId, sceneIdx)
    const taskIdRaw = findPendingStoryboardRecordTaskId(rows) ?? persistedTaskId ?? null

    if (!taskIdRaw) {
      if (ctx.hasStoryboardVideoPendingState(storyboardId)) {
        ctx.primeStoryboardVideoLoadingUi(sceneIdx)
        return
      }
      // 记录/持久化已无进行中任务，但步骤条可能仍残留弹窗启动时的 isGeneratingStoryboardVideo
      ctx.clearModalStoryboardVideoLoadingUi(storyboardId, sceneIdx, taskKind)
      return
    }
    if (activeStoryboardVideoModalOwnedFollowIds.has(storyboardId)) return
    if (shouldSkipStoryboardVideoRestore(storyboardId, taskIdRaw)) return

    const validatedTaskId = await resolveOngoingTaskId(taskIdRaw)
    if (gen !== ctx.resumeStoryboardVideoFollowGen.current) return
    if (shouldSkipStoryboardVideoRestore(storyboardId, taskIdRaw)) return

    if (!validatedTaskId) {
      ctx.store().clearStoryboardVideoGenTask(storyboardId)
      clearStoryboardVideoModalGenSession(ctx.storyboardVideoModalSessionScope())
      if (ctx.hasStoryboardVideoPendingState(storyboardId)) {
        ctx.primeStoryboardVideoLoadingUi(sceneIdx)
        await runStoryboardVideoGenerateForScene(sceneIdx, {
          taskKind,
          resumeTaskId: taskIdRaw,
          progressRunning: ctx.defaultVideoProgressTextForTaskKind(taskKind),
          silentComplete: true
        })
        return
      }
      ctx.clearModalStoryboardVideoLoadingUi(storyboardId, sceneIdx, taskKind)
      if (!shouldSkipStoryboardVideoRestore(storyboardId)) {
        await ctx.refreshVideoRecordsFresh(sceneIdx, { focusLatest: true })
      }
      return
    }

    const taskId = validatedTaskId

    if (isStoryboardVideoModalRestoreFollowing(storyboardId)) {
      ctx.videoGenerateTargetKey.set(buildModalTaskOverlayKey(ctx.overlayKeyParts(sceneIdx, taskKind)))
      ctx.videoGenerateProgressText.set(
        formatTaskSseLiveText(persisted || {}, '') || ctx.defaultVideoProgressTextForTaskKind(taskKind)
      )

      const settled = await waitForStoryboardVideoModalRestore(storyboardId, gen)
      if (!settled) return

      const stillOngoing = await isStoryboardVideoTaskOngoing(taskId)
      if (gen !== ctx.resumeStoryboardVideoFollowGen.current) return
      if (!stillOngoing) {
        ctx.clearModalStoryboardVideoLoadingUi(storyboardId, sceneIdx, taskKind)
        if (!shouldSkipStoryboardVideoRestore(storyboardId)) {
          await ctx.refreshVideoRecordsFresh(sceneIdx, { focusLatest: true })
        }
        return
      }
    }

    await runStoryboardVideoGenerateForScene(sceneIdx, {
      taskKind,
      resumeTaskId: taskId,
      progressRunning: ctx.defaultVideoProgressTextForTaskKind(taskKind),
      silentComplete: true
    })
  }

  const api: VideoModalGenerateApi = {
    runStoryboardVideoGenerateForScene,
    restoreStoryboardVideoGenerateIfNeeded,
    syncStoryboardVideoGenerateUiAfterSettled,
    handleStoryboardVideoGenSettledEvent,
    handleGlobalTasksUpdatedForVideoModal,
    shouldSkipStoryboardVideoRestore
  }
  Object.assign(ctx, api)
}

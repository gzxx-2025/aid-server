/**
 * 分镜图批量生成：提示词任务 → 出图任务 follow 链路工厂（原
 * composables/useStoryboardImageBatchGenerate.ts trackPromptTaskUntilDone ～
 * followOngoingImageGenerateTask 段拆分）。状态与核心助手经参数注入，
 * 主体见 hooks/useStoryboardImageBatchGenerate.ts。
 */

import { createTaskStream, type TaskStreamResult } from '~/composables/useTaskStream'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
  userStoryboardGenerateImage
} from '~/utils/businessApi'
import {
  STORYBOARD_GEN_CONFIG_SCENE_CODES,
  resolveProjectGenImageSubmitFields
} from '~/utils/projectGenConfig'
import {
  fetchUserTaskDetailOnce,
  normalizeTaskStatus,
  resolveUserTaskTerminalOutcome
} from '~/composables/useTaskSseFollow'
import { resumeStoryboardPromptGenerateTask } from '~/utils/storyboardPromptGenerateFlow'
import { confirmTaskResumeWithBilling } from '~/utils/taskResumeBilling'
import {
  extractChainChildTaskIds,
  extractChainChildTaskIdsFromTaskDetail
} from '~/utils/taskChainChild'
import {
  captureCreationLiveGenScope,
  matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import {
  resolveImageBatchLoadingTargetIds,
  shouldClearNonTargetImageBatchPanelStatus,
  buildImageBatchScopePreserveOnContextSwitch
} from '~/utils/storyboardImageBatchRestoreGate'
import {
  shouldKeepImageBatchLoadingAfterFollowMessage,
  isTaskBackgroundRunningMessage,
  isNavigationOrSuspendBatchMessage
} from '~/utils/taskSseSilentDisconnect'
import {
  TASK_BACKGROUND_RUNNING_MESSAGE,
  imageBatchBizErr as bizErr,
  imageBatchSleep as sleep,
  isOngoingImageBatchTaskStatus,
  isStoryboardImagePromptBatchTask,
  normalizeStoryboardBatchTargetIds,
  parseImageBatchTaskId as parseTaskId,
  type StoryboardImageBatchState,
  type StoryboardImageBatchFollowResult,
  type StoryboardPromptBatchFollowResult
} from '~/utils/storyboardImageBatchShared'
import type { StoryboardImageBatchCore } from '~/utils/storyboardImageBatchFollowCore'
import type { ProjectEpisodeContext } from '~/utils/storyboardRecordBatch'
import type { StoryboardPanel } from '~/types'
import { createStoryboardImageGenerateFollow } from '~/utils/storyboardImageGenerateFollow'
import { pickOngoingStoryboardImagePromptBatchTask } from '~/utils/storyboardImagePromptTaskPicker'
import { submitStoryboardImageWithPromptBatch } from '~/utils/storyboardImageBatchSubmit'

export function createStoryboardImageBatchPromptFollow(
  state: StoryboardImageBatchState,
  core: StoryboardImageBatchCore
) {
  const { getStore } = core
  const {
    pickOngoingImageGenerateTask,
    resolveOngoingImageGenerateTaskId,
    followOngoingImageGenerateTask
  } = createStoryboardImageGenerateFollow(state, core)
  const submitImageWithPromptBatch = submitStoryboardImageWithPromptBatch

  async function trackPromptTaskUntilDone(
    taskId: number,
    stream: ReturnType<typeof createTaskStream>
  ): Promise<{ ok: boolean; partial?: boolean; message?: string; chainChildTaskIds?: number[] }> {
    if (!Number.isFinite(taskId) || taskId <= 0) {
      return { ok: false, message: '任务ID无效' }
    }

    const streamGen = state.resumeFollowGeneration
    try {
      const res = await stream.done
      if (state.stopRequested) {
        return { ok: false, message: '已停止生成' }
      }
      if (res.type === 'cancelled') {
        return { ok: false, message: res.message || '任务已取消' }
      }
      if (res.type === 'error') {
        const errMsg = res.errorMessage || '批量生成分镜图失败'
        if (isNavigationOrSuspendBatchMessage(errMsg)) {
          return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE }
        }
        return { ok: false, message: errMsg }
      }
      if (res.type === 'partial_failed') {
        return {
          ok: false,
          partial: true,
          message: '部分分镜图提示词生成失败，可续生',
          chainChildTaskIds: extractChainChildTaskIds(res.data)
        }
      }
      return { ok: true, chainChildTaskIds: extractChainChildTaskIds(res.data) }
    } catch {
      if (state.stopRequested) {
        return { ok: false, message: '已停止生成' }
      }
      // 切步/suspend/网络断流：一律保活，禁止「连接中断请稍后重试」假失败
      if (streamGen !== state.resumeFollowGeneration) {
        return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE }
      }
      try {
        const terminal = await resolveUserTaskTerminalOutcome(taskId)
        if (terminal.kind === 'succeeded') {
          return {
            ok: true,
            chainChildTaskIds: extractChainChildTaskIdsFromTaskDetail(terminal.detail)
          }
        }
        if (terminal.kind === 'partial_failed') {
          return {
            ok: false,
            partial: true,
            message: '部分分镜图提示词生成失败，可续生',
            chainChildTaskIds: extractChainChildTaskIdsFromTaskDetail(terminal.detail)
          }
        }
        if (terminal.kind === 'cancelled') {
          return { ok: false, message: terminal.message || '任务已取消' }
        }
        if (terminal.kind === 'failed') {
          return { ok: false, message: terminal.errorMessage || '批量生成分镜图失败' }
        }
      } catch {
        /* ignore */
      }
      return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE }
    } finally {
      core.closeStream()
    }
  }

  /** 提示词已终态时补齐 chainChildTaskIds（resultData → SSE 重连补发） */
  async function resolveChainChildTaskIdsForPromptTask(
    taskId: number,
    seed?: number[]
  ): Promise<number[]> {
    if (seed?.length) return [...new Set(seed)]
    const detail = await fetchUserTaskDetailOnce(taskId)
    const fromDetail = extractChainChildTaskIdsFromTaskDetail(detail)
    if (fromDetail.length) return fromDetail

    // 文档：终态 Redis 快照含 chainChildTaskIds，重连会补发 complete/partial_failed
    try {
      const stream = createTaskStream(taskId)
      const raced = await Promise.race([
        stream.done.then((res: TaskStreamResult) => ({ kind: 'sse' as const, res })),
        sleep(10000).then(() => ({ kind: 'timeout' as const }))
      ])
      try {
        stream.close()
      } catch {
        /* ignore */
      }
      if (raced.kind === 'sse') {
        if (raced.res.type === 'complete' || raced.res.type === 'partial_failed') {
          return extractChainChildTaskIds(raced.res.data)
        }
      }
    } catch {
      /* ignore */
    }
    return []
  }

  async function followPromptTask(
    taskId: number,
    storyboardIds: number[],
    options?: { progressTotalHint?: number; freshSubmission?: boolean }
  ): Promise<StoryboardPromptBatchFollowResult> {
    while (state.followInFlight) {
      if (state.promptFollowTaskId === taskId) return state.followInFlight
      try {
        await state.followInFlight
      } catch {
        /* The previous owner releases below; a different task may then take ownership. */
      }
    }

    const run = async (): Promise<StoryboardPromptBatchFollowResult> => {
      const followGen = state.resumeFollowGeneration
      state.stopRequested = false
      const routeCtx = captureCreationLiveGenScope()
      core.beginBatchSseFollow()
      core.syncActiveTaskIdToStore(taskId)
      getStore().setStoryboardImageBatchGenerating(true)
      getStore().setStoryboardImageBatchError(null)
      // 切集/restore 后 batch targets 可能暂时丢失：回退 storyboardIds，保证卡片 loading 不被空跑 ensure 漏掉
      core.ensureImageBatchLoadingUi(
        resolveImageBatchLoadingTargetIds(core.getActiveImageBatchTargetIdsLocal(), storyboardIds)
      )

      try {
        const progressTotal = Math.max(
          options?.progressTotalHint ?? 0,
          getStore().storyboardImageBatchProgress.total || 0,
          storyboardIds.length,
          1
        )
        if (!getStore().storyboardImageBatchProgress.total) {
          getStore().setStoryboardImageBatchProgress(0, progressTotal)
        }
        if (!options?.freshSubmission) {
          await core.seedProgressFromTaskDetail(taskId, progressTotal)
        }

        let outcome: {
          ok: boolean
          partial?: boolean
          message?: string
          chainChildTaskIds?: number[]
        }
        const resolved = options?.freshSubmission
          ? null
          : await resolveUserTaskTerminalOutcome(taskId)
        if (resolved?.kind === 'succeeded') {
          outcome = {
            ok: true,
            chainChildTaskIds: await resolveChainChildTaskIdsForPromptTask(taskId)
          }
        } else if (resolved?.kind === 'partial_failed') {
          outcome = {
            ok: false,
            partial: true,
            message: '部分分镜图提示词生成失败，可续生',
            chainChildTaskIds: await resolveChainChildTaskIdsForPromptTask(taskId)
          }
        } else if (resolved?.kind === 'cancelled') {
          outcome = { ok: false, message: resolved.message || '任务已取消' }
        } else if (resolved?.kind === 'failed') {
          outcome = {
            ok: false,
            message: resolved.errorMessage || '批量生成分镜图失败'
          }
        } else {
          const stream = createTaskStream(taskId)
          state.streamCloser = () => {
            try {
              stream.close()
            } catch {
              /* ignore */
            }
          }
          // 原 watch(stream.lastProgress, cb, { immediate: true })：订阅时已有进度会立即回调一次
          const stopWatchProgress = stream.subscribeProgress((p) => {
            if (!p || !matchesCreationLiveGenScope(routeCtx)) return
            core.applySseProgress(p)
          })

          outcome = await trackPromptTaskUntilDone(taskId, stream)
          stopWatchProgress()
          if ((outcome.ok || outcome.partial) && !outcome.chainChildTaskIds?.length) {
            outcome = {
              ...outcome,
              chainChildTaskIds: await resolveChainChildTaskIdsForPromptTask(
                taskId,
                outcome.chainChildTaskIds
              )
            }
          }
        }

        if (followGen !== state.resumeFollowGeneration) {
          return {
            ok: false,
            message: TASK_BACKGROUND_RUNNING_MESSAGE
          }
        }

        if (typeof window !== 'undefined') {
          window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
        }

        if (!matchesCreationLiveGenScope(routeCtx)) {
          getStore().mergeStep4PlusLiveGenForScopeKey(
            routeCtx.scopeKey,
            buildImageBatchScopePreserveOnContextSwitch({
              promptTaskId: taskId,
              imageTaskId: getStore().storyboardImageBatchActiveImageTaskId
            })
          )
          return { ok: false, message: '已切换作品，任务仍在后台进行' }
        }

        if (!outcome.partial) {
          core.syncActiveTaskIdToStore(null)
        }

        return outcome
      } finally {
        core.endBatchSseFollow()
      }
    }

    const pending = run()
    state.followInFlight = pending
    state.promptFollowTaskId = taskId
    try {
      return await pending
    } finally {
      if (state.followInFlight === pending) {
        state.followInFlight = null
        state.promptFollowTaskId = null
      }
      state.followIdleBarrier.notifyStateChange()
    }
  }

  /** 提示词任务终态后，跟进后端自动触发的 storyboard_image_generate 父任务 */
  async function followImageGenerateAfterPrompt(
    targets: number[],
    storyboardIds: number[],
    panels: StoryboardPanel[],
    chainChildTaskIds?: number[]
  ): Promise<{ ok: boolean; message?: string; partial?: boolean; panels?: StoryboardPanel[] }> {
    const scopeAtEntry = captureCreationLiveGenScope()
    const generationAtEntry = state.resumeFollowGeneration
    const preferredImageTaskIdAtEntry = getStore().storyboardImageBatchActiveImageTaskId
    const ctx = await resolveStoryScriptSaveContext(getStore(), getRouteLikeSnapshot())
    if (!ctx) {
      return { ok: false, message: '缺少项目信息', panels }
    }
    if (core.isBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
      core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, panels)
      return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE, panels }
    }

    if (state.stopRequested) {
      return { ok: false, message: '已停止生成', panels }
    }

    const explicitTargets = targets.filter((id) => Number.isFinite(id) && id > 0)
    const loadingTargets = resolveImageBatchLoadingTargetIds(explicitTargets, storyboardIds)
    core.ensureImageBatchLoadingUi(loadingTargets, panels)

    const preferredChildIds = normalizeStoryboardBatchTargetIds(chainChildTaskIds)
    if (preferredChildIds.length) {
      let working = panels
      let anyPartial = false
      for (const childId of preferredChildIds) {
        if (state.stopRequested) {
          return { ok: false, message: '已停止生成', panels: working }
        }
        const childOutcome = await followOngoingImageGenerateTask(
          childId,
          storyboardIds,
          loadingTargets
        )
        if (!childOutcome.ok) {
          return { ...childOutcome, panels: childOutcome.panels ?? working }
        }
        if (childOutcome.partial) anyPartial = true
        if (childOutcome.panels) working = childOutcome.panels
      }
      return { ok: true, partial: anyPartial, panels: working }
    }

    const ongoingImageId = await resolveOngoingImageGenerateTaskId(ctx, preferredImageTaskIdAtEntry)
    if (core.isBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
      // task/list 返回时可能已经切到另一作品，发现结果不作为旧作用域的所有权凭证。
      core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, panels)
      return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE, panels }
    }
    if (ongoingImageId != null) {
      return followOngoingImageGenerateTask(ongoingImageId, storyboardIds, loadingTargets)
    }

    // 无 chainChildTaskIds 且无进行中出图任务：勿假成功；对仍缺图的分镜补发 /generate/image
    const refreshed = await core.refreshPanelsFromApi()
    if (core.isBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
      core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, panels)
      return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE, panels }
    }
    const stillNeed = core.resolveBatchImageTargets(
      refreshed,
      explicitTargets.length ? explicitTargets : storyboardIds,
      false
    )
    // 空 explicit targets 时禁止清全部卡片 loading（切集 restore 曾因此把 generating 写空并持久化）
    for (const sid of storyboardIds) {
      if (shouldClearNonTargetImageBatchPanelStatus(explicitTargets, sid)) {
        getStore().clearStoryboardPanelImageGenStatus(sid)
      }
    }
    if (!stillNeed.length) {
      return { ok: true, panels: refreshed }
    }

    try {
      const imageGenFields = await resolveProjectGenImageSubmitFields(
        ctx.projectId,
        STORYBOARD_GEN_CONFIG_SCENE_CODES.image
      )
      if (core.isBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
        core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, refreshed)
        return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE, panels: refreshed }
      }
      const submitted = await userStoryboardGenerateImage({
        storyboardIds: stillNeed,
        ...(imageGenFields.agentCode ? { agentCode: imageGenFields.agentCode } : {}),
        ...(imageGenFields.modelCode ? { modelName: imageGenFields.modelCode } : {}),
        ...(imageGenFields.aspectRatio ? { aspectRatio: imageGenFields.aspectRatio } : {}),
        ...(imageGenFields.resolution ? { size: imageGenFields.resolution } : {})
      })
      const fallbackTaskId = parseTaskId(submitted.taskId)
      if (core.isBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
        core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, refreshed, {
          imageTaskId: fallbackTaskId
        })
        return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE, panels: refreshed }
      }
      if (!fallbackTaskId) {
        return {
          ok: false,
          panels: refreshed,
          message: '出图任务未就绪，补发失败：未返回任务ID'
        }
      }
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }
      return followOngoingImageGenerateTask(fallbackTaskId, storyboardIds, stillNeed)
    } catch (e: unknown) {
      return {
        ok: false,
        panels: refreshed,
        message: bizErr(e) || '出图任务未就绪或未触发，请重新批量生成分镜图'
      }
    }
  }

  async function handlePromptPartialResume(
    taskId: number,
    storyboardIds: number[]
  ): Promise<{ ok: boolean; partial?: boolean; message?: string }> {
    const shouldResume = await confirmTaskResumeWithBilling(taskId, {
      title: '部分分镜图提示词生成失败',
      content: '部分镜头提示词生成失败，是否续生？',
      okText: '续生',
      cancelText: '跳过'
    })
    if (shouldResume) {
      const resumeOutcome = await resumeStoryboardPromptGenerateTask(taskId, 'image')
      if (resumeOutcome.ok === false) {
        return { ok: false, message: resumeOutcome.errorMessage }
      }
      return followPromptTask(taskId, storyboardIds)
    }
    core.syncActiveTaskIdToStore(taskId)
    return { ok: true, partial: true, message: '部分分镜图提示词生成失败' }
  }

  return {
    followPromptTask,
    resolveChainChildTaskIdsForPromptTask,
    submitImageWithPromptBatch,
    resolveOngoingImageGenerateTaskId,
    followImageGenerateAfterPrompt,
    handlePromptPartialResume,
    pickOngoingImageGenerateTask,
    followOngoingImageGenerateTask,
    pickOngoingImagePromptBatchTask: pickOngoingStoryboardImagePromptBatchTask
  }
}

export type StoryboardImageBatchPromptFollow = ReturnType<
  typeof createStoryboardImageBatchPromptFollow
>

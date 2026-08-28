/**
 * 分镜视频批量生成：提示词任务提交 / SSE 跟随链路（原 composables/useStoryboardVideoBatchGenerate.ts
 * trackPromptTaskUntilDone → runBatchVideoPrompt 段拆分；出片跟随见 utils/storyboardVideoBatchVideoFollow.ts）。
 */

import { createTaskStream } from '~/composables/useTaskStream'
import {
  fetchUserTaskDetailOnce,
  isUserTaskTerminal,
  resolveUserTaskTerminalOutcome
} from '~/composables/useTaskSseFollow'
import {
  userStoryboardGenerateVideoPromptImage,
  userStoryboardGenerateVideoWithPrompt
} from '~/utils/businessApi'
import { isNavigationOrSuspendBatchMessage } from '~/utils/taskSseSilentDisconnect'
import { resolveStoryboardVideoPromptTerminalResult } from '~/utils/storyboardVideoPromptTerminal'
import {
  resumeStoryboardPromptGenerateTask
} from '~/utils/storyboardPromptGenerateFlow'
import { confirmTaskResumeWithBilling } from '~/utils/taskResumeBilling'
import {
  extractChainChildTaskIds,
  extractChainChildTaskIdsFromTaskDetail
} from '~/utils/taskChainChild'
import { buildVideoBatchScopePreserveOnContextSwitch } from '~/utils/storyboardImageBatchRestoreGate'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import type { CreationLiveGenScopeCtx } from '~/composables/useCreationLiveGenScopeGuard'
import type { StoryboardPanel } from '~/types'
import type { StoryboardGenerateVideoWithPromptRequest } from '~/types/business-api'
import {
  parseVideoBatchTaskId as parseTaskId,
  videoBatchBizErr as bizErr,
  type StoryboardVideoBatchState,
  type StoryboardVideoPromptFollowResult
} from '~/utils/storyboardVideoBatchShared'
import type { StoryboardVideoBatchCore } from '~/utils/storyboardVideoBatchFollowCore'
import { createStoryboardVideoBatchSubmitFields } from './storyboardVideoBatchSubmitFields'
import { pickOngoingStoryboardVideoPromptBatchTask } from './storyboardVideoPromptTaskPicker'

export type StoryboardVideoBatchPromptFollow = ReturnType<
  typeof createStoryboardVideoBatchPromptFollow
>

export function createStoryboardVideoBatchPromptFollow(
  state: StoryboardVideoBatchState,
  core: StoryboardVideoBatchCore
) {
  const { getStore } = core
  const {
    buildVideoPromptSubmitFields,
    buildVideoGenSubmitFields
  } = createStoryboardVideoBatchSubmitFields(state, core)

  async function trackPromptTaskUntilDone(
    taskId: number,
    stream: ReturnType<typeof createTaskStream>
  ): Promise<StoryboardVideoPromptFollowResult> {
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
        const errMsg = res.errorMessage || '视频提示词生成失败'
        if (isNavigationOrSuspendBatchMessage(errMsg)) {
          return {
            ok: false,
            message: '任务仍在后台执行，请稍候或刷新页面自动恢复进度'
          }
        }
        return { ok: false, message: errMsg }
      }
      if (res.type === 'partial_failed') {
        return resolveStoryboardVideoPromptTerminalResult(taskId, 'partial_failed', res.data)
      }
      return resolveStoryboardVideoPromptTerminalResult(taskId, 'succeeded', res.data)
    } catch {
      if (state.stopRequested) {
        return { ok: false, message: '已停止生成' }
      }
      if (streamGen !== state.resumeFollowGeneration) {
        return {
          ok: false,
          message: '任务仍在后台执行，请稍候或刷新页面自动恢复进度'
        }
      }
      const resolved = await resolveUserTaskTerminalOutcome(taskId)
      if (resolved.kind === 'succeeded') {
        return resolveStoryboardVideoPromptTerminalResult(
          taskId,
          'succeeded',
          resolved.detail?.resultData
        )
      }
      if (resolved.kind === 'partial_failed') {
        return resolveStoryboardVideoPromptTerminalResult(
          taskId,
          'partial_failed',
          resolved.detail?.resultData
        )
      }
      if (resolved.kind === 'cancelled') {
        return { ok: false, message: resolved.message || '任务已取消' }
      }
      if (resolved.kind === 'failed') {
        return { ok: false, message: resolved.errorMessage || '视频提示词生成失败' }
      }
      // ongoing / 未知：切步断流保活，禁止「连接中断请稍后重试」
      return {
        ok: false,
        message: '任务仍在后台执行，请稍候或刷新页面自动恢复进度'
      }
    } finally {
      core.closePromptStream()
    }
  }

  async function resolveChainChildTaskIdsForPromptTask(
    taskId: number,
    seed?: number[]
  ): Promise<number[]> {
    if (seed?.length) return [...new Set(seed)]
    const fromDetail = extractChainChildTaskIdsFromTaskDetail(await fetchUserTaskDetailOnce(taskId))
    if (fromDetail.length) return fromDetail
    try {
      const stream = createTaskStream(taskId)
      const raced = await Promise.race([
        stream.done.then((res) => ({ kind: 'sse' as const, res })),
        new Promise<{ kind: 'timeout' }>((resolve) =>
          setTimeout(() => resolve({ kind: 'timeout' }), 10000)
        )
      ])
      try {
        stream.close()
      } catch {
        /* ignore */
      }
      if (
        raced.kind === 'sse' &&
        (raced.res.type === 'complete' || raced.res.type === 'partial_failed')
      ) {
        return extractChainChildTaskIds(raced.res.data)
      }
    } catch {
      /* ignore */
    }
    return []
  }

  async function followPromptTaskOwned(
    taskId: number,
    storyboardIds: number[],
    options?: { progressTotalHint?: number }
  ): Promise<StoryboardVideoPromptFollowResult> {
    state.stopRequested = false
    const routeCtx = core.captureScope()
    core.beginBatchSseFollow()
    core.syncActivePromptTaskIdToStore(taskId)
    getStore().setGeneratingStoryboardVideo(true)
    getStore().setStoryboardVideoBatchError(null)
    try {
      const progressTotal = Math.max(
        options?.progressTotalHint ?? 0,
        getStore().storyboardVideoBatchProgress.total || 0,
        storyboardIds.length,
        1
      )
      if (!getStore().storyboardVideoBatchProgress.total) {
        getStore().setStoryboardVideoBatchProgress(0, progressTotal)
      }
      await core.seedProgressFromTaskDetail(taskId, progressTotal)

      let outcome: StoryboardVideoPromptFollowResult
      const resolved = await resolveUserTaskTerminalOutcome(taskId)
      if (resolved.kind === 'succeeded') {
        outcome = resolveStoryboardVideoPromptTerminalResult(
          taskId,
          'succeeded',
          resolved.detail?.resultData
        )
      } else if (resolved.kind === 'partial_failed') {
        outcome = resolveStoryboardVideoPromptTerminalResult(
          taskId,
          'partial_failed',
          resolved.detail?.resultData
        )
      } else if (resolved.kind === 'cancelled') {
        outcome = { ok: false, message: resolved.message || '任务已取消' }
      } else if (resolved.kind === 'failed') {
        outcome = {
          ok: false,
          message: resolved.errorMessage || '视频提示词生成失败'
        }
      } else {
        const stream = createTaskStream(taskId)
        state.promptStreamCloser = () => {
          try {
            stream.close()
          } catch {
            /* ignore */
          }
        }
        // 原 watch(stream.lastProgress, ..., { immediate: true })：subscribeProgress 有值立即回调
        const stopWatchProgress = stream.subscribeProgress((p) => {
          if (!p || !core.matchesScope(routeCtx)) return
          core.applySseProgress(p)
        })

        outcome = await trackPromptTaskUntilDone(taskId, stream)
        stopWatchProgress()
      }
      if (
        !outcome.chainFailed &&
        (outcome.ok || outcome.partial) &&
        !outcome.chainChildTaskIds?.length
      ) {
        outcome = {
          ...outcome,
          chainChildTaskIds: await resolveChainChildTaskIdsForPromptTask(
            taskId,
            outcome.chainChildTaskIds
          )
        }
      }

      /** 已切集：只保活原 scope，禁止清空 loading/taskId */
      if (!core.matchesScope(routeCtx)) {
        getStore().mergeStep4PlusLiveGenForScopeKey(
          routeCtx.scopeKey,
          buildVideoBatchScopePreserveOnContextSwitch({
            promptTaskId: taskId,
            videoTaskId: getStore().storyboardVideoBatchActiveVideoTaskId
          })
        )
        return { ok: false, message: '已切换作品，任务仍在后台进行' }
      }

      if (!outcome.partial) {
        core.syncActivePromptTaskIdToStore(null)
      }

      return outcome
    } finally {
      core.endBatchSseFollow()
    }
  }

  async function followPromptTask(
    taskId: number,
    storyboardIds: number[],
    options?: { progressTotalHint?: number }
  ): Promise<StoryboardVideoPromptFollowResult> {
    while (state.promptFollowOwner) {
      if (state.promptFollowOwner.taskId === taskId) return state.promptFollowOwner.promise
      try {
        await state.promptFollowOwner.promise
      } catch {
        /* 前一提示词任务释放后再接管下一任务。 */
      }
    }

    const promise = followPromptTaskOwned(taskId, storyboardIds, options)
    const owner = { taskId, promise }
    state.promptFollowOwner = owner
    try {
      return await promise
    } finally {
      if (state.promptFollowOwner === owner) state.promptFollowOwner = null
      state.followIdleBarrier.notifyStateChange()
    }
  }

  async function submitSingleVideoPrompt(storyboardId: number): Promise<{
    ok: boolean
    message?: string
    taskId?: number
  }> {
    const ctx = await resolveStoryScriptSaveContext(getStore(), core.getRoute())
    if (!ctx) {
      return { ok: false, message: '缺少项目信息，请从「我的作品」打开作品后再操作' }
    }

    let submitted: Awaited<ReturnType<typeof userStoryboardGenerateVideoPromptImage>>
    try {
      submitted = await userStoryboardGenerateVideoPromptImage({
        projectId: ctx.projectId,
        episodeId: ctx.episodeId,
        storyboardIds: [storyboardId],
        ...(await buildVideoPromptSubmitFields(ctx.projectId))
      })
    } catch (e: unknown) {
      return { ok: false, message: bizErr(e) }
    }

    const taskId = parseTaskId(submitted.taskId)
    if (!taskId) {
      return { ok: false, message: '提交失败：未返回任务ID' }
    }

    if (typeof window !== 'undefined' && !getStore().isGeneratingStoryboardVideo) {
      window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
    }
    return { ok: true, taskId }
  }

  async function submitVideoWithPromptBatch(options: {
    storyboardIds?: number[]
    overwrite?: boolean
    genDurationSeconds?: number | null
    expectedScope?: CreationLiveGenScopeCtx
    expectedGeneration?: number
  }): Promise<{ ok: boolean; taskId?: number; message?: string; totalShots?: number }> {
    const submitBody = await buildVideoWithPromptSubmitBody(options)
    if (!submitBody) {
      return { ok: false, message: '缺少项目信息，请从「我的作品」打开作品后再操作' }
    }
    if (
      options.expectedScope &&
      core.isVideoBatchOperationInterrupted(
        options.expectedScope,
        options.expectedGeneration ?? state.resumeFollowGeneration
      )
    ) {
      return { ok: false, message: '已切换作品，任务仍在后台进行' }
    }

    try {
      const submitted = await userStoryboardGenerateVideoWithPrompt(submitBody)
      const taskId = parseTaskId(submitted.taskId)
      if (!taskId) {
        return { ok: false, message: '提交失败：未返回任务ID' }
      }
      if (typeof window !== 'undefined' && !getStore().isGeneratingStoryboardVideo) {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }
      return {
        ok: true,
        taskId,
        totalShots:
          Number(submitted.totalShots) > 0
            ? Number(submitted.totalShots)
            : (options.storyboardIds?.length ?? 0)
      }
    } catch (e: unknown) {
      return { ok: false, message: bizErr(e) }
    }
  }

  /** 报价与正式提交共用的 VIDEO_WITH_PROMPT 请求体，仅读取当前项目与生成配置。 */
  async function buildVideoWithPromptSubmitBody(options: {
    storyboardIds?: number[]
    overwrite?: boolean
    genDurationSeconds?: number | null
    manualVideoModelPick?: boolean
  }): Promise<StoryboardGenerateVideoWithPromptRequest | null> {
    const ctx = await resolveStoryScriptSaveContext(getStore(), core.getRoute())
    if (!ctx) return null
    const promptFields = await buildVideoPromptSubmitFields(ctx.projectId)
    return {
      projectId: ctx.projectId,
      episodeId: ctx.episodeId,
      ...(options.storyboardIds?.length ? { storyboardIds: [...options.storyboardIds] } : {}),
      ...(options.overwrite != null ? { overwrite: options.overwrite } : {}),
      ...promptFields,
      ...buildVideoGenSubmitFields({
        genDurationSeconds: options.genDurationSeconds,
        manualVideoModelPick: options.manualVideoModelPick
      })
    }
  }

  async function runBatchVideoPrompt(overwrite: boolean): Promise<{
    ok: boolean
    partial?: boolean
    chainFailed?: boolean
    taskId?: number
    message?: string
    chainChildTaskIds?: number[]
  }> {
    state.stopRequested = false
    const scopeAtEntry = core.captureScope()
    const generationAtEntry = state.resumeFollowGeneration
    const ctx = await resolveStoryScriptSaveContext(getStore(), core.getRoute())
    if (!ctx) {
      return { ok: false, message: '缺少项目信息，请从「我的作品」打开作品后再操作' }
    }
    if (core.isVideoBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
      return { ok: false, message: '已切换作品，任务仍在后台进行' }
    }

    const submitOutcome = await submitVideoWithPromptBatch({
      overwrite,
      expectedScope: scopeAtEntry,
      expectedGeneration: generationAtEntry
    })
    if (core.isVideoBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
      if (submitOutcome.taskId) {
        core.keepVideoBatchLoadingForScope(scopeAtEntry, { promptTaskId: submitOutcome.taskId })
      }
      return {
        ok: false,
        taskId: submitOutcome.taskId,
        message: '已切换作品，任务仍在后台进行'
      }
    }
    if (!submitOutcome.ok || !submitOutcome.taskId) {
      return { ok: false, message: submitOutcome.message || '视频提示词生成失败' }
    }

    const storyboardIds = (getStore().formData.storyboardScript.panels as StoryboardPanel[])
      .map((p) => parseServerStoryboardId(p.id))
      .filter((id): id is number => id != null)

    const progressTotal =
      Number(submitOutcome.totalShots) > 0 ? Number(submitOutcome.totalShots) : storyboardIds.length

    let outcome = await followPromptTask(submitOutcome.taskId, storyboardIds, {
      progressTotalHint: progressTotal
    })

    if (state.stopRequested) {
      return { ok: false, message: '已停止生成' }
    }

    if (!outcome.ok) {
      if (outcome.chainFailed && outcome.chainChildTaskIds?.length) {
        return {
          ok: true,
          chainFailed: true,
          taskId: outcome.taskId,
          message: outcome.message,
          chainChildTaskIds: outcome.chainChildTaskIds
        }
      }
      if (outcome.partial && outcome.taskId) {
        const partialWarning = outcome.message || '部分视频提示词生成失败'
        const shouldResume = await confirmTaskResumeWithBilling(outcome.taskId, {
          title: '部分视频提示词生成失败',
          content: partialWarning,
          okText: '续生',
          cancelText: '跳过'
        })
        if (shouldResume) {
          const resumeOutcome = await resumeStoryboardPromptGenerateTask(outcome.taskId, 'video')
          if (resumeOutcome.ok === false) {
            getStore().setStoryboardVideoBatchError(resumeOutcome.errorMessage)
            return { ok: false, message: resumeOutcome.errorMessage }
          }
          outcome = await followPromptTask(outcome.taskId, storyboardIds, {
            progressTotalHint: progressTotal
          })
        } else {
          core.syncActivePromptTaskIdToStore(outcome.taskId)
          return {
            ok: true,
            partial: true,
            taskId: outcome.taskId,
            message: partialWarning,
            chainChildTaskIds: outcome.chainChildTaskIds
          }
        }
      } else {
        return {
          ok: false,
          ...(outcome.chainFailed ? { chainFailed: true } : {}),
          message: outcome.message || '视频提示词生成失败'
        }
      }
    }

    return {
      ok: true,
      taskId: submitOutcome.taskId,
      chainChildTaskIds: outcome.chainChildTaskIds
    }
  }

  /** 提示词任务是否已在服务端终态（刷新后无需再连 SSE，避免空 EventStream） */
  async function isPromptBatchTaskTerminal(taskId: number): Promise<boolean> {
    return isUserTaskTerminal(taskId)
  }

  return {
    followPromptTask,
    submitSingleVideoPrompt,
    submitVideoWithPromptBatch,
    buildVideoWithPromptSubmitBody,
    runBatchVideoPrompt,
    pickOngoingVideoPromptBatchTask: pickOngoingStoryboardVideoPromptBatchTask,
    isPromptBatchTaskTerminal
  }
}

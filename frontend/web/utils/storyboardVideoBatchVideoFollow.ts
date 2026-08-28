/**
 * 分镜视频批量生成：出片任务 SSE 跟随 / 任务归属对齐链路（原 composables/useStoryboardVideoBatchGenerate.ts
 * followVideoGenerateAfterPrompt → reconcileOngoingVideoGenerationTasks 段拆分；
 * 恢复编排见 utils/storyboardVideoBatchRestore.ts）。
 */

import { followStoryboardVideoGenerateTask } from '~/composables/useStoryboardVideoGenerateTask'
import { fetchUserTaskDetailOnce } from '~/composables/useTaskSseFollow'
import type { StoryboardVideoPanel } from '~/types'
import type { UserTaskRow } from '~/types/business-api'
import {
buildVideoBatchScopePreserveOnContextSwitch,
shouldKeepVideoBatchLoadingAfterFollowMessage
} from '~/utils/storyboardImageBatchRestoreGate'
import type { ProjectEpisodeContext } from '~/utils/storyboardRecordBatch'
import type { StoryboardVideoBatchCore } from '~/utils/storyboardVideoBatchFollowCore'
import {
extractStoryboardIdsFromTaskSnapshot,
isOngoingVideoBatchUserTaskStatus,
isStoryboardVideoGenerateTaskType,
normalizeStoryboardVideoBatchTargetIds,
parseVideoBatchTaskId as parseTaskId,
videoBatchSleep as sleep,
type StoryboardVideoBatchState,
type StoryboardVideoGenerateFollowResult,
type StoryboardVideoPair
} from '~/utils/storyboardVideoBatchShared'
import { createStoryboardVideoTaskOwnershipOps } from '~/utils/storyboardVideoTaskOwnershipOps'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
parseVideoBatchSuccessItems,
resolveVideoBatchFailedStoryboardIds
} from '~/utils/taskPartialFailed'

export type StoryboardVideoBatchVideoFollow = ReturnType<
  typeof createStoryboardVideoBatchVideoFollow
>

export function createStoryboardVideoBatchVideoFollow(
  state: StoryboardVideoBatchState,
  core: StoryboardVideoBatchCore
) {
  const { getStore } = core

  function pickOngoingVideoGenerateTask(
    tasks: UserTaskRow[],
    preferredTaskId?: number | null
  ): UserTaskRow | null {
    const ongoing = tasks
      .filter(
        (t) =>
          t &&
          isStoryboardVideoGenerateTaskType(t.taskType) &&
          isOngoingVideoBatchUserTaskStatus(t.status) &&
          !isPersistedModalVideoGenerateTaskId(Number(t.id))
      )
      .sort((a, b) => Number(b.id || 0) - Number(a.id || 0))

    if (!ongoing.length) return null

    const pref = parseTaskId(preferredTaskId)
    if (pref != null) {
      const hit = ongoing.find((t) => Number(t.id) === pref)
      if (hit) return hit
    }
    return ongoing[0] ?? null
  }

  /** 提示词阶段结束后解析出片任务 id（优先 store 持久化，再拉最新 task/list） */
  async function resolveOngoingVideoGenerateTaskId(
    ctx: ProjectEpisodeContext,
    preferredVideoId?: number | null
  ): Promise<number | null> {
    const pref = parseTaskId(
      preferredVideoId ?? getStore().storyboardVideoBatchActiveVideoTaskId
    )
    let tasks: UserTaskRow[]
    let taskListOk = true
    try {
      tasks = await core.fetchProjectTaskListCached(ctx.projectId)
    } catch {
      tasks = []
      taskListOk = false
    }
    const listHit = pickOngoingVideoGenerateTask(tasks, pref)
    return core.resolvePersistedTaskIdWhenListMiss(parseTaskId(listHit?.id), pref, taskListOk)
  }

  async function followOngoingVideoGenerateTaskOwned(
    taskId: number,
    pairs: StoryboardVideoPair[],
    onPanelsUpdate: (panels: StoryboardVideoPanel[]) => void,
    workingPanels: StoryboardVideoPanel[],
    options?: { targetStoryboardIds?: number[]; deferBatchFinalize?: boolean }
  ): Promise<StoryboardVideoGenerateFollowResult> {
    const routeCtx = core.captureScope()
    const storyboardIds = pairs.map((p) => p.storyboardId)
    const hasExplicitTargetIds = Array.isArray(options?.targetStoryboardIds)
    const explicitTargetIds = normalizeStoryboardVideoBatchTargetIds(options?.targetStoryboardIds)
    const activeBatchTargetIds = core.getActiveBatchTargetIds()
    const targetIds = hasExplicitTargetIds
      ? explicitTargetIds
      : activeBatchTargetIds.length
        ? activeBatchTargetIds
        : storyboardIds
    const videoTotal = targetIds.length || storyboardIds.length

    getStore().setGeneratingStoryboardVideo(true)
    core.markPanelsGenerating(targetIds)
    core.syncActiveVideoTaskIdToStore(taskId)
    if (!getStore().storyboardVideoBatchProgress.total) {
      getStore().setStoryboardVideoBatchProgress(0, videoTotal)
    }
    let acceptsProgressRefresh = true
    core.beginBatchSseFollow()
    try {
      await core.seedProgressFromTaskDetail(taskId, videoTotal)

      let working = core.applyPanelsGeneratingToLocal(
        workingPanels,
        pairs.map((p) => p.script),
        true
      )
      onPanelsUpdate(working)

      let lastRefreshStepIndex = -1
      let latestProgressRefreshSequence = 0
      const result = await followStoryboardVideoGenerateTask({
        taskId,
        onProgress: (p) => {
          if (!core.matchesScope(routeCtx)) return
          core.applySseProgress({
            progress: p.percent,
            stepIndex: (p as { stepIndex?: number }).stepIndex,
            stepTotal: videoTotal,
            message: p.message,
            stepTitle: p.stepTitle
          })
          const stepIndex =
            typeof (p as { stepIndex?: number }).stepIndex === 'number'
              ? Number((p as { stepIndex?: number }).stepIndex)
              : null
          if (stepIndex != null && stepIndex > lastRefreshStepIndex) {
            lastRefreshStepIndex = stepIndex
            const refreshSequence = ++latestProgressRefreshSequence
            void core
              .refreshPanelsVideosForPairs(pairs, working, {
                onlyUpToStepIndex: stepIndex,
                batchTargetIds: targetIds
              })
              .then((next) => {
                // 进度刷新是异步的：终态到达后或有更新的刷新启动后，旧结果不得复活
                // generating UI，也不得用较早进度覆盖较新卡片。
                if (
                  !acceptsProgressRefresh ||
                  refreshSequence !== latestProgressRefreshSequence ||
                  !core.matchesScope(routeCtx)
                ) {
                  return
                }
                working = next
                onPanelsUpdate(next)
              })
              .catch(() => {
                /* 进度增量刷新失败不影响 SSE owner；终态会执行权威列表对账。 */
              })
          }
        }
      })
      acceptsProgressRefresh = false

      /** 已切集：只保活原 scope，禁止清空 loading/taskId */
      if (!core.matchesScope(routeCtx)) {
        getStore().mergeStep4PlusLiveGenForScopeKey(
          routeCtx.scopeKey,
          buildVideoBatchScopePreserveOnContextSwitch({
            promptTaskId: getStore().storyboardVideoBatchActivePromptTaskId,
            videoTaskId: taskId
          })
        )
        return { ok: false, message: '已切换作品，任务仍在后台进行' }
      }

      if (!result.ok) {
        const failMsg =
          'errorMessage' in result ? result.errorMessage || '视频生成失败' : '视频生成失败'
        if (shouldKeepVideoBatchLoadingAfterFollowMessage(failMsg)) {
          core.keepVideoBatchLoadingForScope(routeCtx, {
            promptTaskId: getStore().storyboardVideoBatchActivePromptTaskId,
            videoTaskId: taskId
          })
          return { ok: false, message: failMsg }
        }
        core.syncActiveVideoTaskIdToStore(null)
        core.persistBatchTargetPanelErrors(pairs, failMsg, targetIds)
        working = core.applyBatchFailureToLocalPanels(
          working,
          pairs.map((p) => p.script),
          targetIds,
          failMsg
        )
        onPanelsUpdate(working)
        if (!options?.deferBatchFinalize) core.abortVideoBatchUi(targetIds)
        return {
          ok: false,
          message: failMsg
        }
      }

      core.syncActiveVideoTaskIdToStore(null)

      // partial_failed / complete：优先用 SSE items 立刻展示成功视频，再按失败集刷新其余卡片
      const terminalItems = parseVideoBatchSuccessItems(result.data)
      const failedStoryboardIds = resolveVideoBatchFailedStoryboardIds(
        result.data,
        targetIds,
        terminalItems
      )

      if (terminalItems.length) {
        working = core.applyVideoBatchTerminalItemsToPanels(working, pairs, terminalItems)
        onPanelsUpdate(working)
        const ctx = await resolveStoryScriptSaveContext(getStore(), core.getRoute())
        if (ctx) {
          await core.setFinalVideosFromTerminalItems(ctx, terminalItems)
        }
      }

      working = await core.refreshPanelsAfterVideoBatch(
        pairs,
        working,
        failedStoryboardIds.size ? failedStoryboardIds : undefined,
        targetIds
      )
      onPanelsUpdate(working)
      getStore().setStoryboardVideoBatchProgress(videoTotal, videoTotal)
      return { ok: true, ...(result.partial ? { partial: true } : {}) }
    } finally {
      acceptsProgressRefresh = false
      core.endBatchSseFollow()
    }
  }

  async function followOngoingVideoGenerateTask(
    taskId: number,
    pairs: StoryboardVideoPair[],
    onPanelsUpdate: (panels: StoryboardVideoPanel[]) => void,
    workingPanels: StoryboardVideoPanel[],
    options?: { targetStoryboardIds?: number[]; deferBatchFinalize?: boolean }
  ): Promise<StoryboardVideoGenerateFollowResult> {
    while (state.videoFollowOwner) {
      if (state.videoFollowOwner.taskId === taskId) return state.videoFollowOwner.promise
      try {
        await state.videoFollowOwner.promise
      } catch {
        /* 前一出片任务释放后再接管下一任务。 */
      }
    }

    const promise = followOngoingVideoGenerateTaskOwned(
      taskId,
      pairs,
      onPanelsUpdate,
      workingPanels,
      options
    )
    const owner = { taskId, promise }
    state.videoFollowOwner = owner
    try {
      return await promise
    } finally {
      if (state.videoFollowOwner === owner) state.videoFollowOwner = null
      state.followIdleBarrier.notifyStateChange()
    }
  }

  const { isBatchVideoGenerateTaskId, isPersistedModalVideoGenerateTaskId, shouldRestoreAsListBatchVideoTask, getPendingModalVideoTaskEntries, reconcileOngoingVideoGenerationTasks } = createStoryboardVideoTaskOwnershipOps(state, core)

  async function followVideoGenerateAfterPrompt(
    pairs: StoryboardVideoPair[],
    onPanelsUpdate?: (panels: StoryboardVideoPanel[]) => void,
    workingPanels?: StoryboardVideoPanel[],
    chainChildTaskIds?: number[],
    options?: { chainSubmissionFailed?: boolean }
  ): Promise<{
    ok: boolean
    partial?: boolean
    taskId?: number
    message?: string
    failedStoryboardIds?: Set<number>
    coveredStoryboardIds?: Set<number>
  }> {
    const scopeAtEntry = core.captureScope()
    const generationAtEntry = state.resumeFollowGeneration
    const preferredVideoTaskIdAtEntry = getStore().storyboardVideoBatchActiveVideoTaskId
    const storyboardIds = pairs.map((p) => p.storyboardId)
    if (!storyboardIds.length) {
      return { ok: false, message: '分镜尚未保存到服务器，请先生成分镜脚本' }
    }

    const ctx = await resolveStoryScriptSaveContext(getStore(), core.getRoute())
    if (!ctx) {
      return { ok: false, message: '缺少项目信息' }
    }
    if (core.isVideoBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
      core.keepVideoBatchLoadingForScope(scopeAtEntry)
      return { ok: false, message: '已切换作品，任务仍在后台进行' }
    }

    if (state.stopRequested) {
      return { ok: false, message: '已停止生成' }
    }

    core.setVideoBatchTargetIds(storyboardIds)
    core.markPanelsGenerating(storyboardIds)

    const preferredChildIds = [
      ...new Set(
        (chainChildTaskIds || [])
          .map((id) => Number(id))
          .filter((id) => Number.isFinite(id) && id > 0)
      )
    ]

    let working = workingPanels
      ? [...workingPanels]
      : core.applyPanelsGeneratingToLocal(
          (getStore().formData.storyboardVideo.panels as StoryboardVideoPanel[]) || [],
          pairs.map((p) => p.script),
          true
        )
    onPanelsUpdate?.(working)
    const updateWorkingPanels = (next: StoryboardVideoPanel[]) => {
      working = next
      onPanelsUpdate?.(next)
    }

    if (preferredChildIds.length) {
      let lastTaskId: number | undefined
      let anyPartial = false
      const coveredStoryboardIds = new Set<number>()
      for (const childId of preferredChildIds) {
        if (state.stopRequested) {
          return { ok: false, message: '已停止生成', taskId: lastTaskId }
        }
        const childDetail = await fetchUserTaskDetailOnce(childId).catch(() => null)
        let childTargetIds = extractStoryboardIdsFromTaskSnapshot(childDetail?.inputSnapshot)
        const childPairs = childTargetIds.length
          ? pairs.filter((pair) => childTargetIds.includes(pair.storyboardId))
          : pairs
        const outcome = await followOngoingVideoGenerateTask(
          childId,
          childPairs,
          updateWorkingPanels,
          working,
          {
            ...(childTargetIds.length ? { targetStoryboardIds: childTargetIds } : {}),
            deferBatchFinalize: options?.chainSubmissionFailed === true
          }
        )
        if (!childTargetIds.length) {
          const terminalDetail = await fetchUserTaskDetailOnce(childId).catch(() => null)
          childTargetIds = extractStoryboardIdsFromTaskSnapshot(terminalDetail?.inputSnapshot)
        }
        for (const storyboardId of childTargetIds) coveredStoryboardIds.add(storyboardId)
        if (core.isVideoBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
          core.keepVideoBatchLoadingForScope(scopeAtEntry, { videoTaskId: childId })
          return { ok: false, message: '已切换作品，任务仍在后台进行', taskId: childId }
        }
        lastTaskId = childId
        if (!outcome.ok) {
          if (options?.chainSubmissionFailed) {
            anyPartial = true
            continue
          }
          return {
            ok: false,
            message: outcome.message,
            taskId: childId,
            coveredStoryboardIds
          }
        }
        if (outcome.partial) anyPartial = true
      }
      return {
        ok: true,
        taskId: lastTaskId,
        coveredStoryboardIds,
        ...(anyPartial ? { partial: true } : {})
      }
    }

    let ongoingVideoId = await resolveOngoingVideoGenerateTaskId(ctx, preferredVideoTaskIdAtEntry)
    if (ongoingVideoId == null) {
      for (let attempt = 0; attempt < 4 && ongoingVideoId == null; attempt++) {
        if (attempt > 0) await sleep(800)
        if (core.isVideoBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
          core.keepVideoBatchLoadingForScope(scopeAtEntry)
          return { ok: false, message: '已切换作品，任务仍在后台进行' }
        }
        ongoingVideoId = await resolveOngoingVideoGenerateTaskId(ctx, preferredVideoTaskIdAtEntry)
      }
    }

    if (core.isVideoBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
      // 切换后的 task/list 结果不反写旧 scope；切回后由该 scope 自己重新发现。
      core.keepVideoBatchLoadingForScope(scopeAtEntry)
      return { ok: false, message: '已切换作品，任务仍在后台进行' }
    }

    if (ongoingVideoId != null) {
      const outcome = await followOngoingVideoGenerateTask(
        ongoingVideoId,
        pairs,
        updateWorkingPanels,
        working
      )
      if (core.isVideoBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
        core.keepVideoBatchLoadingForScope(scopeAtEntry, { videoTaskId: ongoingVideoId })
        return { ok: false, message: '已切换作品，任务仍在后台进行' }
      }
      return outcome.ok
        ? {
            ok: true,
            taskId: ongoingVideoId,
            ...(outcome.partial ? { partial: true } : {})
          }
        : { ok: false, message: outcome.message }
    }

    // 刷新/切页竞态：提示词已完但出片任务尚未进 list，保活等待 restore 重试
    return { ok: false, message: '视频出片任务未就绪，请稍后重试' }
  }

  return {
    pickOngoingVideoGenerateTask,
    resolveOngoingVideoGenerateTaskId,
    followOngoingVideoGenerateTask,
    isBatchVideoGenerateTaskId,
    isPersistedModalVideoGenerateTaskId,
    shouldRestoreAsListBatchVideoTask,
    getPendingModalVideoTaskEntries,
    reconcileOngoingVideoGenerationTasks,
    followVideoGenerateAfterPrompt
  }
}


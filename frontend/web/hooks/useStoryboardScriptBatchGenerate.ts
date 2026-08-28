'use client'

/**
 * 分镜脚本批量生成（原 composables/useStoryboardScriptBatchGenerate.ts 平移）。
 * SSE 跟踪与终态判定拆分见 utils/storyboardScriptBatchTrack.ts。
 * 原实现为模块级共享单例（多组件复用同一任务跟随状态），React 版保持一致。
 */

import { message } from 'antd'
import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import {
fetchUserTaskDetailOnce,
} from '~/composables/useTaskSseFollow'
import type { TaskProgressEventData } from '~/composables/useTaskStream'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel } from '~/types'
import type { UserTaskDetailData,UserTaskRow } from '~/types/business-api'
import { userStoryboardGenerateScript } from '~/utils/businessApi'
import { buildStoryboardScriptSubmitBody } from '~/utils/storyboardScriptSubmitBody'
import { stripStoryboardScriptSkeletonPanels } from '~/utils/storyboardPanelMap'
import {
applyStoryboardScriptFailedOutcome,
applyStoryboardScriptPartialFailedOutcome,
applyStoryboardScriptSuccessOutcome,
storyboardScriptBizErr as bizErr,
isStoryboardScriptTaskBusyMessage,
parseStoryboardScriptTaskId as parseTaskId,
pickOngoingStoryboardScriptTask,
refreshStoryboardScriptPanelsFromApi as refreshPanelsFromApi,
resolveScenePlotCountHint,
seedStoryboardScriptProgressFromDetailRecord as seedProgressFromDetailRecord,
trackStoryboardScriptTaskUntilDone,
type StoryboardScriptTrackOutcome
} from '~/utils/storyboardScriptBatchTrack'
import {
resumeUserTask
} from '~/utils/taskPartialFailed'
import { EMPTY_COUNT_PROGRESS } from '~/utils/taskSseProgressText'
import {
fetchFlowUserTaskList,
filterUserTaskRowsForEpisode
} from '~/utils/userTaskListFlowOnce'
import { restoreOngoingGenerationIfNeeded as restoreStoryboardScriptGeneration } from './storyboardScriptGenerationRestore'
import { createStoryboardScriptTaskControls } from './storyboardScriptTaskControls'

let sharedStoryboardScriptBatchGen: ReturnType<
  typeof createStoryboardScriptBatchGenerate
> | null = null

function createStoryboardScriptBatchGenerate() {
  /** 事件回调 / 异步流程一律取最新 store 状态（原 Pinia 实例为响应式，Zustand 需调用时取） */
  const getStore = () => useCreationStore.getState()

  // 原 Vue ref：仅内部/回调读取，不驱动渲染；React 版用普通可变对象保留 `.value` 访问形状
  const activeTaskId = { value: null as number | null }
  const taskProgressMessage = { value: '' }
  let streamCloser: (() => void) | null = null
  let stopRequested = false
  let resumeFollowGeneration = 0
  let restoreSessionInFlight: Promise<void> | null = null
  let followInFlight: Promise<{
    ok: boolean
    panels: StoryboardPanel[]
    message?: string
  }> | null = null

  const trackCtx = {
    isStopRequested: () => stopRequested,
    getResumeFollowGeneration: () => resumeFollowGeneration,
    setStreamCloser: (closer: (() => void) | null) => {
      streamCloser = closer
    }
  }

  function closeStream() {
    const close = streamCloser
    streamCloser = null
    if (close) {
      try {
        close()
      } catch {
        /* ignore */
      }
    }
  }

  function syncActiveTaskIdToStore(taskId: number | null) {
    activeTaskId.value = taskId
    getStore().setStoryboardScriptActiveTaskId(taskId)
  }

  function applySseProgress(p: {
    progress?: number
    stepIndex?: number
    stepTotal?: number
    message?: string
    stepTitle?: string
  }) {
    getStore().applyStoryboardScriptSseProgress(p)
  }

  async function seedProgressFromTaskDetail(taskId: number) {
    const detail = await fetchUserTaskDetailOnce(taskId)
    seedProgressFromDetailRecord(detail)
  }

  async function trackTaskUntilDone(
    taskId: number,
    onSseProgress: (p: TaskProgressEventData) => void,
    options?: { startDetail?: UserTaskDetailData | null }
  ): Promise<StoryboardScriptTrackOutcome> {
    return trackStoryboardScriptTaskUntilDone(taskId, onSseProgress, trackCtx, options)
  }

  async function followExistingTask(
    taskId: number,
    currentPanels: StoryboardPanel[],
    options?: { progressTotalHint?: number; startDetail?: UserTaskDetailData | null }
  ): Promise<{
    ok: boolean
    panels: StoryboardPanel[]
    message?: string
  }> {
    const run = async (): Promise<{
      ok: boolean
      panels: StoryboardPanel[]
      message?: string
    }> => {
      const followGen = resumeFollowGeneration
      stopRequested = false
      const routeCtx = captureCreationLiveGenScope()
      syncActiveTaskIdToStore(taskId)
      getStore().setStoryboardGenerating(true)
      getStore().setStoryboardError(null)

      const progressTotal = Math.max(
        options?.progressTotalHint ?? 0,
        getStore().storyboardGenerationProgress.total || 0,
        resolveScenePlotCountHint(),
        1
      )
      if (!getStore().storyboardGenerationProgress.total) {
        getStore().setStoryboardProgress(0, progressTotal)
      }
      if (options?.startDetail) {
        seedProgressFromDetailRecord(options.startDetail)
      } else {
        await seedProgressFromTaskDetail(taskId)
      }

      // 生成过程中只靠 SSE 更新进度文案，不轮询 storyboard/list（等终态再拉一次）
      const workingPanels = stripStoryboardScriptSkeletonPanels(currentPanels)

      const outcome = await trackTaskUntilDone(taskId, (p) => {
        if (followGen !== resumeFollowGeneration) return
        if (!matchesCreationLiveGenScope(routeCtx)) return
        applySseProgress(p)
      })
      if (followGen !== resumeFollowGeneration) {
        return {
          ok: false,
          panels: workingPanels,
          message: '任务仍在后台执行，请稍候或刷新页面自动恢复进度'
        }
      }
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }

      /** 剧集隔离：已切集时只写任务所属 scope 桶；禁止先动当前集扁平字段（syncActiveTaskIdToStore 写当前 scope） */
      if (!matchesCreationLiveGenScope(routeCtx)) {
        getStore().mergeStep4PlusLiveGenForScopeKey(routeCtx.scopeKey, {
          isGeneratingStoryboard: false,
          storyboardGenerationProgress: { ...EMPTY_COUNT_PROGRESS },
          storyboardGenerationError: null,
          storyboardScriptActiveTaskId: null
        })
        return { ok: false, panels: workingPanels, message: '已切换作品，任务仍在后台进行' }
      }

      if (!outcome.partial && !outcome.ongoing) {
        syncActiveTaskIdToStore(null)
      }

      if (outcome.ongoing) {
        getStore().setStoryboardGenerating(true)
        getStore().setStoryboardError(null)
        syncActiveTaskIdToStore(taskId)
        return {
          ok: false,
          panels: workingPanels,
          message: outcome.message
        }
      }

      // 仅在任务终态（成功 / 部分失败 / 失败）后拉一次分镜列表
      let panels = workingPanels
      try {
        panels = await refreshPanelsFromApi()
      } catch (e: unknown) {
        if (outcome.ok) {
          return { ok: false, panels: workingPanels, message: bizErr(e) || '分镜已生成，但刷新列表失败' }
        }
      }

      if (outcome.ok) {
        const total = Math.max(
          getStore().storyboardGenerationProgress.total || progressTotal,
          progressTotal
        )
        getStore().setStoryboardProgress(total, total)
        applyStoryboardScriptSuccessOutcome()
        return { ok: true, panels }
      }

      if (outcome.partial && taskId) {
        applyStoryboardScriptPartialFailedOutcome(
          taskId,
          outcome.message || '部分场次生成失败，可点击续生重试失败项',
          outcome.partialData ?? null,
          panels
        )
        return {
          ok: false,
          panels,
          message: outcome.message
        }
      }

      applyStoryboardScriptFailedOutcome(
        outcome.message || '分镜生成失败，请稍后重试。',
        panels
      )
      return {
        ok: false,
        panels,
        message: outcome.message
      }
    }

    const pending = run()
    followInFlight = pending
    try {
      return await pending
    } finally {
      if (followInFlight === pending) {
        followInFlight = null
      }
    }
  }

  function isTaskFollowPaused(taskId: number): boolean {
    return getStore().taskIdsWithLocalFollowPaused.includes(taskId)
  }

  /**
   * 刷新或切换作品后：根据任务列表与持久化状态恢复分镜脚本生成 UI 与 SSE。
   */
  function restoreOngoingGenerationIfNeeded(
    currentPanels: StoryboardPanel[],
    onPanelsUpdate: (panels: StoryboardPanel[]) => void,
    onShowGeneratingSkeleton: () => void
  ): Promise<void> {
    return restoreStoryboardScriptGeneration({
      getStore,
      getRestoreSessionInFlight: () => restoreSessionInFlight,
      setRestoreSessionInFlight: (pending) => {
        restoreSessionInFlight = pending
      },
      nextResumeFollowGeneration: () => ++resumeFollowGeneration,
      getResumeFollowGeneration: () => resumeFollowGeneration,
      getFollowInFlight: () => followInFlight,
      activeTaskId,
      isTaskFollowPaused,
      followExistingTask,
      syncActiveTaskIdToStore,
      getStopRequested: () => stopRequested
    }, currentPanels, onPanelsUpdate, onShowGeneratingSkeleton)
  }

  /**
   * 提交批量生成分镜脚本并 SSE 追踪；成功后刷新分镜列表。
   */
  async function runBatchGenerate(
    currentPanels: StoryboardPanel[],
    options?: { manualAgentModelPick?: boolean; sceneIds?: number[] }
  ): Promise<{
    ok: boolean
    panels: StoryboardPanel[]
    message?: string
  }> {
    stopRequested = false
    taskProgressMessage.value = ''
    const submitBody = await buildStoryboardScriptSubmitBody({
      store: getStore(),
      route: getRouteLikeSnapshot(),
      currentPanels,
      sceneIds: options?.sceneIds,
      manualAgentModelPick: options?.manualAgentModelPick
    })
    if (!submitBody) {
      return { ok: false, panels: currentPanels, message: '缺少项目信息，请从「我的作品」打开作品后再操作' }
    }
    const ctx = { projectId: submitBody.projectId, episodeId: submitBody.episodeId }
    const selectiveSceneIds = submitBody.sceneIds ?? []
    const isSelective = selectiveSceneIds.length > 0

    let submitted: Awaited<ReturnType<typeof userStoryboardGenerateScript>>
    try {
      submitted = await userStoryboardGenerateScript(submitBody)
    } catch (e: unknown) {
      const msg = bizErr(e)
      if (isStoryboardScriptTaskBusyMessage(msg)) {
        let tasks: UserTaskRow[] = []
        try {
          /** 剧集隔离：busy 续跟也只认本集任务 */
          tasks = filterUserTaskRowsForEpisode(
            await fetchFlowUserTaskList(ctx.projectId, { intent: 'read' }),
            ctx.episodeId
          )
        } catch {
          tasks = []
        }
        const ongoingTask = pickOngoingStoryboardScriptTask(
          tasks,
          getStore().storyboardScriptActiveTaskId
        )
        const ongoingId = parseTaskId(ongoingTask?.id)
        if (ongoingId) {
          syncActiveTaskIdToStore(ongoingId)
          getStore().setStoryboardGenerating(true)
          getStore().setStoryboardError(null)
          const progressTotalHint =
            Number((ongoingTask as { totalBatches?: number })?.totalBatches) > 0
              ? Number((ongoingTask as { totalBatches?: number }).totalBatches)
              : isSelective
                ? selectiveSceneIds.length
                : resolveScenePlotCountHint()
          return followExistingTask(ongoingId, currentPanels, { progressTotalHint })
        }
      }
      return { ok: false, panels: currentPanels, message: msg }
    }

    const taskId = parseTaskId(submitted.taskId)
    if (!taskId) {
      return { ok: false, panels: currentPanels, message: '提交失败：未返回任务ID' }
    }

    syncActiveTaskIdToStore(taskId)
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
    }

    const warningText = String(submitted.warning || '').trim()
    if (warningText) {
      message.warning(warningText)
    }

    const progressTotal =
      Number(submitted.totalBatches) > 0
        ? Number(submitted.totalBatches)
        : isSelective
          ? selectiveSceneIds.length
          : resolveScenePlotCountHint()

    getStore().setStoryboardProgress(0, Math.max(progressTotal, 1))

    const result = await followExistingTask(taskId, currentPanels, { progressTotalHint: progressTotal })
    return result
  }

  async function resumePartialFailedGenerate(
    taskId: number,
    currentPanels: StoryboardPanel[]
  ): Promise<{ ok: boolean; panels: StoryboardPanel[]; message?: string }> {
    const id = parseTaskId(taskId)
    if (!id) {
      return { ok: false, panels: currentPanels, message: '任务ID无效' }
    }
    stopRequested = false
    taskProgressMessage.value = ''
    let resumeTotalBatches = 0
    try {
      const resumed = await resumeUserTask(id, 'storyboard_script_batch')
      resumeTotalBatches = Number(resumed.totalBatches)
    } catch (e: unknown) {
      return { ok: false, panels: currentPanels, message: bizErr(e) }
    }
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
    }
    syncActiveTaskIdToStore(id)
    getStore().setStoryboardGenerating(true)
    getStore().setStoryboardError(null)
    getStore().setStoryboardScriptPartialFailedData(null)
    if (Number.isFinite(resumeTotalBatches) && resumeTotalBatches > 0) {
      getStore().setStoryboardProgress(0, resumeTotalBatches)
    }
    const result = await followExistingTask(id, currentPanels, {
      progressTotalHint: resumeTotalBatches > 0 ? resumeTotalBatches : undefined
    })
    if (result.ok) {
      applyStoryboardScriptSuccessOutcome()
    } else if (!stopRequested && !result.message?.includes('部分') && !result.message?.includes('续生')) {
      applyStoryboardScriptFailedOutcome(result.message || '分镜续生失败', result.panels)
    }
    return result
  }

  async function resumeTrackFromGlobal(
    taskId: number,
    currentPanels: StoryboardPanel[]
  ): Promise<{ ok: boolean; panels: StoryboardPanel[]; message?: string }> {
    const id = parseTaskId(taskId)
    if (!id) {
      return { ok: false, panels: currentPanels, message: '任务ID无效' }
    }
    getStore().removePausedTaskFollow(id)
    getStore().setStoryboardGenerating(true)
    getStore().setStoryboardError(null)
    const result = await followExistingTask(id, currentPanels)
    if (result.ok) {
      applyStoryboardScriptSuccessOutcome()
    } else if (!stopRequested) {
      if (!result.message?.includes('部分') && !result.message?.includes('续生')) {
        applyStoryboardScriptFailedOutcome(result.message || '分镜生成失败', result.panels)
      }
    }
    return result
  }

  const {
    requestStop,
    onGlobalStopTask,
    onGlobalTrackTask,
    cancelResumeFollow
  } = createStoryboardScriptTaskControls({
    activeTaskId,
    getStore,
    getFollowInFlight: () => followInFlight,
    setStopRequested: (value) => {
      stopRequested = value
    },
    closeStream,
    refreshPanelsFromApi,
    syncActiveTaskIdToStore,
    resumeTrackFromGlobal,
    nextResumeFollowGeneration: () => resumeFollowGeneration++
  })

  return {
    activeTaskId,
    taskProgressMessage,
    runBatchGenerate,
    requestStop,
    refreshPanelsFromApi,
    restoreOngoingGenerationIfNeeded,
    resumePartialFailedGenerate,
    resumeTrackFromGlobal,
    onGlobalStopTask,
    onGlobalTrackTask,
    cancelResumeFollow
  }
}

export function useStoryboardScriptBatchGenerate() {
  if (!sharedStoryboardScriptBatchGen) {
    sharedStoryboardScriptBatchGen = createStoryboardScriptBatchGenerate()
  }
  return sharedStoryboardScriptBatchGen
}

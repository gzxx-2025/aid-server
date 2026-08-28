'use client'

import { message } from 'antd'
import { useState } from 'react'
import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import {
resolveUserTaskTerminalOutcome,
suspendTaskSseFollow
} from '~/composables/useTaskSseFollow'
import { useCreationStore } from '~/stores/creation'
import type { DubbingPanel,StoryboardPanel } from '~/types'
import type { StoryboardAudioBatchRequest } from '~/types/business-api'
import { openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { createAsyncIdleBarrier } from '~/utils/asyncIdleBarrier'
import { userStoryboardGenerateAudioBatch } from '~/utils/businessApi'
import { notifyEpisodeTimelineRebuildRequested } from '~/utils/episodeTimelineRebuildSignal'
import { modalGenSessionScopeFromScopeKey } from '~/utils/modalGenSessionScope'
import {
applyAudioBatchResultToPanels,
bizErr,
clearAudioBatchRestoreSession,
followStoryboardAudioBatchTask,
parseAudioBatchTerminalData,
parseTaskId,
pickOngoingAudioBatchTask,
readAudioBatchRestoreSession,
shouldKeepAudioBatchLoadingAfterFollowMessage,
TASK_BACKGROUND_RUNNING_MESSAGE,
writeAudioBatchRestoreSession,
type StoryboardAudioBatchFollowResult
} from '~/utils/storyboardAudioBatchTask'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
isNavigationOrSuspendBatchMessage,
shouldSilentStoryboardBatchToast
} from '~/utils/taskSseSilentDisconnect'
import {
fetchFlowUserTaskListOnce,
filterUserTaskRowsForEpisode
} from '~/utils/userTaskListFlowOnce'

export {
  isStoryboardAudioBatchTaskType,
  followStoryboardAudioBatchTask,
  runStoryboardAudioBatchTask,
  applyAudioBatchResultToPanels
} from '~/utils/storyboardAudioBatchTask'
export type {
  StoryboardAudioBatchProgress,
  StoryboardAudioBatchFollowResult
} from '~/utils/storyboardAudioBatchTask'

function createStoryboardAudioBatchGenerate() {
  // 原 Vue ref：仅内部读取，不驱动渲染；React 版用普通可变对象保留 `.value` 访问形状
  const activeTaskId = { value: null as number | null }
  let restoreInFlight: Promise<void> | null = null
  let followInFlight: Promise<StoryboardAudioBatchFollowResult> | null = null
  let followTaskId: number | null = null
  let followGeneration = 0
  const followIdleBarrier = createAsyncIdleBarrier(
    () => followInFlight != null || restoreInFlight != null
  )

  /** 事件回调 / 异步流程一律取最新 store 状态（原 Pinia 实例为响应式，Zustand 需调用时取） */
  const getStore = () => useCreationStore.getState()

  function syncActiveTaskId(taskId: number | null) {
    activeTaskId.value = taskId
  }

  async function followTaskWithUi(
    taskId: number,
    panelIndices: number[],
    onPanelsUpdate?: (next: DubbingPanel[]) => void,
    panels?: DubbingPanel[],
    scriptPanels?: StoryboardPanel[],
    fallbackVoiceName?: string,
    ownerScope: ReturnType<typeof captureCreationLiveGenScope> = captureCreationLiveGenScope()
  ): Promise<StoryboardAudioBatchFollowResult> {
    const routeCtx = ownerScope
    const sessionScope = modalGenSessionScopeFromScopeKey(routeCtx.scopeKey)
    const generation = followGeneration
    if (!matchesCreationLiveGenScope(routeCtx)) {
      return { ok: false, errorMessage: TASK_BACKGROUND_RUNNING_MESSAGE }
    }
    syncActiveTaskId(taskId)
    getStore().setDubbingBatchGeneratingIndices(panelIndices)
    writeAudioBatchRestoreSession(
      getStore(),
      {
        taskId,
        storyboardIds: [],
        panelIndices
      },
      sessionScope
    )

    try {
      const resolved = await resolveUserTaskTerminalOutcome(taskId)
      let result: StoryboardAudioBatchFollowResult
      if (resolved.kind === 'succeeded') {
        result = {
          ok: true,
          taskId,
          data: parseAudioBatchTerminalData(resolved.detail?.resultData)
        }
      } else if (resolved.kind === 'partial_failed') {
        result = {
          ok: true,
          taskId,
          partial: true,
          data: parseAudioBatchTerminalData(resolved.detail?.resultData)
        }
      } else if (resolved.kind === 'cancelled') {
        result = { ok: false, errorMessage: resolved.message || '任务已取消' }
      } else if (resolved.kind === 'failed') {
        result = {
          ok: false,
          errorMessage: resolved.errorMessage || '批量配音失败'
        }
      } else {
        result = await followStoryboardAudioBatchTask({
          taskId,
          onProgress: () => {
            if (!matchesCreationLiveGenScope(routeCtx)) return
          }
        })
      }

      if (generation !== followGeneration) {
        return { ok: false, errorMessage: TASK_BACKGROUND_RUNNING_MESSAGE }
      }

      if (!matchesCreationLiveGenScope(routeCtx)) {
        return { ok: false, errorMessage: '已切换作品' }
      }

      if (result.ok === false) {
        if (shouldKeepAudioBatchLoadingAfterFollowMessage(result.errorMessage)) {
          return {
            ...result,
            errorMessage: isNavigationOrSuspendBatchMessage(result.errorMessage)
              ? TASK_BACKGROUND_RUNNING_MESSAGE
              : result.errorMessage
          }
        }
        syncActiveTaskId(null)
        clearAudioBatchRestoreSession(getStore(), sessionScope)
        getStore().setDubbingBatchGeneratingIndices([])
      } else {
        syncActiveTaskId(null)
        clearAudioBatchRestoreSession(getStore(), sessionScope)
        getStore().setDubbingBatchGeneratingIndices([])
      }

      if (result.ok === false) {
        if (!shouldSilentStoryboardBatchToast(result.errorMessage)) {
          message.error(result.errorMessage || '批量配音失败')
        }
        return result
      }

      if (panels && scriptPanels && onPanelsUpdate) {
        const next = applyAudioBatchResultToPanels(
          panels,
          scriptPanels,
          result.data,
          fallbackVoiceName || '无音色'
        )
        onPanelsUpdate(next)

        const successCount = result.data?.successCount
        const failCount = result.data?.failCount
        if (result.partial && failCount != null && failCount > 0) {
          message.warning(
            successCount != null
              ? `批量配音完成：成功 ${successCount} 条，失败 ${failCount} 条`
              : `部分分镜配音失败（${failCount} 条）`
          )
        } else {
          message.success('批量配音已完成')
        }
        notifyEpisodeTimelineRebuildRequested()
      }

      return result
    } catch (e: unknown) {
      if (generation !== followGeneration || !matchesCreationLiveGenScope(routeCtx)) {
        return { ok: false, errorMessage: TASK_BACKGROUND_RUNNING_MESSAGE }
      }
      if (!shouldKeepAudioBatchLoadingAfterFollowMessage((e as Error)?.message)) {
        getStore().setDubbingBatchGeneratingIndices([])
        clearAudioBatchRestoreSession(getStore(), sessionScope)
        syncActiveTaskId(null)
      }
      throw e
    }
  }

  async function followTaskWithUiOwned(
    taskId: number,
    panelIndices: number[],
    onPanelsUpdate?: (next: DubbingPanel[]) => void,
    panels?: DubbingPanel[],
    scriptPanels?: StoryboardPanel[],
    fallbackVoiceName?: string,
    ownerScope?: ReturnType<typeof captureCreationLiveGenScope>
  ): Promise<StoryboardAudioBatchFollowResult> {
    while (followInFlight) {
      if (followTaskId === taskId) return followInFlight
      try {
        await followInFlight
      } catch {
        /* 前一任务释放所有权后，下一任务再开始。 */
      }
    }

    const pending = followTaskWithUi(
      taskId,
      panelIndices,
      onPanelsUpdate,
      panels,
      scriptPanels,
      fallbackVoiceName,
      ownerScope
    )
    followInFlight = pending
    followTaskId = taskId
    try {
      return await pending
    } finally {
      if (followInFlight === pending) {
        followInFlight = null
        followTaskId = null
      }
      followIdleBarrier.notifyStateChange()
    }
  }

  async function runBatchForIndices(opts: {
    panelIndices: number[]
    scriptPanels: StoryboardPanel[]
    panels: DubbingPanel[]
    overwrite: boolean
    voiceLibraryId?: number
    emotion?: string
    onPanelsUpdate: (next: DubbingPanel[]) => void
    onGenerating?: (v: boolean) => void
  }): Promise<{ ok: boolean; message?: string; partial?: boolean }> {
    const {
      panelIndices,
      scriptPanels,
      panels,
      overwrite,
      voiceLibraryId,
      emotion,
      onPanelsUpdate,
      onGenerating
    } = opts

    if (!panelIndices.length) {
      return { ok: false, message: '请选择分镜' }
    }

    const routeCtx = captureCreationLiveGenScope()
    const generationAtEntry = followGeneration
    const sessionScope = modalGenSessionScopeFromScopeKey(routeCtx.scopeKey)
    const isInterrupted = () =>
      generationAtEntry !== followGeneration || !matchesCreationLiveGenScope(routeCtx)

    const ctx = await resolveStoryScriptSaveContext(getStore(), getRouteLikeSnapshot())
    if (!ctx) {
      return { ok: false, message: '缺少项目信息，请从「我的作品」打开作品后再操作' }
    }
    if (isInterrupted()) {
      return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE }
    }

    const storyboardIds = panelIndices
      .map((i) => parseServerStoryboardId(scriptPanels[i]?.id ?? panels[i]?.id ?? ''))
      .filter((id): id is number => id != null)

    if (!storyboardIds.length) {
      return { ok: false, message: '分镜尚未保存到服务端，请稍后再试' }
    }

    const body: StoryboardAudioBatchRequest = {
      projectId: ctx.projectId,
      episodeId: ctx.episodeId,
      storyboardIds,
      overwrite,
      resolution: 'FHD',
      ...(voiceLibraryId != null && voiceLibraryId > 0 ? { voiceLibraryId } : {}),
      ...(emotion ? { emotion } : {})
    }

    const fallbackVoiceName = panels[panelIndices[0]!]?.dubbingVoiceName || '无音色'

    getStore().setDubbingBatchGeneratingIndices(panelIndices)
    onGenerating?.(true)

    writeAudioBatchRestoreSession(
      getStore(),
      {
        taskId: 0,
        storyboardIds,
        panelIndices
      },
      sessionScope
    )

    try {
      const submitted = await userStoryboardGenerateAudioBatch(body)
      const taskId = parseTaskId(submitted?.taskId)
      if (!taskId) {
        if (isInterrupted()) {
          getStore().mergeStep4PlusLiveGenForScopeKey(routeCtx.scopeKey, {
            dubbingBatchGeneratingIndices: panelIndices
          })
          return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE }
        }
        getStore().setDubbingBatchGeneratingIndices([])
        clearAudioBatchRestoreSession(getStore(), sessionScope)
        onGenerating?.(false)
        return { ok: false, message: '提交失败：未返回任务ID' }
      }

      writeAudioBatchRestoreSession(
        getStore(),
        {
          taskId,
          storyboardIds,
          panelIndices
        },
        sessionScope
      )

      if (isInterrupted()) {
        getStore().mergeStep4PlusLiveGenForScopeKey(routeCtx.scopeKey, {
          dubbingBatchGeneratingIndices: panelIndices
        })
        return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE }
      }

      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }

      const result = await followTaskWithUiOwned(
        taskId,
        panelIndices,
        onPanelsUpdate,
        panels,
        scriptPanels,
        fallbackVoiceName,
        routeCtx
      )

      if (!matchesCreationLiveGenScope(routeCtx)) {
        return { ok: false, message: '已切换作品' }
      }

      if (result.ok === false) {
        if (shouldKeepAudioBatchLoadingAfterFollowMessage(result.errorMessage)) {
          return {
            ok: false,
            message: isNavigationOrSuspendBatchMessage(result.errorMessage)
              ? TASK_BACKGROUND_RUNNING_MESSAGE
              : result.errorMessage
          }
        }
        onGenerating?.(false)
        return { ok: false, message: result.errorMessage }
      }

      onGenerating?.(false)

      return { ok: true, partial: result.partial }
    } catch (e: unknown) {
      if (isInterrupted()) {
        getStore().mergeStep4PlusLiveGenForScopeKey(routeCtx.scopeKey, {
          dubbingBatchGeneratingIndices: panelIndices
        })
        return { ok: false, message: TASK_BACKGROUND_RUNNING_MESSAGE }
      }
      getStore().setDubbingBatchGeneratingIndices([])
      clearAudioBatchRestoreSession(getStore(), sessionScope)
      syncActiveTaskId(null)
      onGenerating?.(false)
      const msg = bizErr(e)
      openRechargeModalFromInsufficientBalance(msg)
      message.error(msg)
      return { ok: false, message: msg }
    }
  }

  async function restoreOngoingBatchIfNeeded(opts: {
    panels: DubbingPanel[]
    scriptPanels: StoryboardPanel[]
    onPanelsUpdate: (next: DubbingPanel[]) => void
    onGenerating?: (v: boolean) => void
  }) {
    if (typeof window === 'undefined') return
    if (restoreInFlight || followInFlight) return

    const routeCtx = captureCreationLiveGenScope()
    const generationAtEntry = followGeneration
    const sessionScope = modalGenSessionScopeFromScopeKey(routeCtx.scopeKey)
    const isInterrupted = () =>
      generationAtEntry !== followGeneration || !matchesCreationLiveGenScope(routeCtx)

    const session = readAudioBatchRestoreSession(getStore(), sessionScope)
    const panelIndices = session?.panelIndices?.length
      ? session.panelIndices
      : [...getStore().dubbingBatchGeneratingIndices]

    if (!panelIndices.length) return

    let taskId = parseTaskId(session?.taskId) ?? parseTaskId(activeTaskId.value)

    if (!taskId) {
      try {
        const ctx = await resolveStoryScriptSaveContext(getStore(), getRouteLikeSnapshot())
        if (!ctx) return
        if (isInterrupted()) return
        /** 剧集隔离：禁止把其它集的配音批量任务恢复到本集 */
        const rows = filterUserTaskRowsForEpisode(
          await fetchFlowUserTaskListOnce(ctx.projectId),
          ctx.episodeId
        )
        const hit = pickOngoingAudioBatchTask(rows)
        taskId = parseTaskId(hit?.id)
        if (isInterrupted()) return
      } catch {
        return
      }
    }

    if (!taskId) {
      if (!getStore().dubbingBatchGeneratingIndices.length) return
      getStore().setDubbingBatchGeneratingIndices([])
      clearAudioBatchRestoreSession(getStore(), sessionScope)
      return
    }

    const pending = (async () => {
      opts.onGenerating?.(true)
      try {
        await followTaskWithUiOwned(
          taskId!,
          panelIndices,
          opts.onPanelsUpdate,
          opts.panels,
          opts.scriptPanels,
          undefined,
          routeCtx
        )
      } finally {
        opts.onGenerating?.(false)
      }
    })()
    const owner = pending.finally(() => {
      restoreInFlight = null
      followIdleBarrier.notifyStateChange()
    })
    restoreInFlight = owner
    return owner
  }

  /** Disconnect this page's SSE while preserving its restore session and loading state. */
  function cancelResumeFollow(): Promise<void> {
    followGeneration++
    const taskId = followTaskId ?? activeTaskId.value
    if (taskId != null) suspendTaskSseFollow(taskId)
    activeTaskId.value = null
    return followIdleBarrier.waitForIdle()
  }

  return {
    activeTaskId,
    runBatchForIndices,
    restoreOngoingBatchIfNeeded,
    cancelResumeFollow,
    waitForFollowIdle: followIdleBarrier.waitForIdle
  }
}

export function useStoryboardAudioBatchGenerate() {
  // 原 Vue 每个组件 setup 各创建一份实例；React 侧用惰性 state 保证跨渲染稳定
  const [instance] = useState(() => createStoryboardAudioBatchGenerate())
  return instance
}

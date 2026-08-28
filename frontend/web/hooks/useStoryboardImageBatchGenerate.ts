'use client'

/**
 * 分镜图批量生成（原 composables/useStoryboardImageBatchGenerate.ts，2442 行拆分迁移）。
 * 拆分结构（闭包状态经 StoryboardImageBatchState 显式共享，禁止另建平行状态）：
 * - utils/storyboardImageBatchShared.ts       —— 模块级纯函数 / session 目标快照 / 卡片 loading 恢复
 * - utils/storyboardImageBatchFollowCore.ts   —— 状态绑定核心助手（loading UI / 设主图 / 任务对齐）
 * - utils/storyboardImageBatchPromptFollow.ts —— 提示词任务 → 出图任务 follow 链路
 * - utils/storyboardImageBatchRestore.ts      —— 刷新/切集后的任务恢复编排
 * 原实现每组件各建一份实例（useRoute/Pinia 随组件生命周期），React 版用惰性 state 保持一致。
 */

import { useState } from 'react'
import { captureCreationLiveGenScope } from '~/composables/useCreationLiveGenScopeGuard'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import type { StoryboardPanel } from '~/types'
import { createAsyncIdleBarrier } from '~/utils/asyncIdleBarrier'
import { createStoryboardImageBatchCore } from '~/utils/storyboardImageBatchFollowCore'
import { createStoryboardImageBatchPromptFollow } from '~/utils/storyboardImageBatchPromptFollow'
import { createStoryboardImageBatchRestore } from '~/utils/storyboardImageBatchRestore'
import {
TASK_BACKGROUND_RUNNING_MESSAGE,
isStoryboardImagePromptBatchTask,
parseImageBatchTaskId as parseTaskId,
type StoryboardImageBatchState
} from '~/utils/storyboardImageBatchShared'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { isStoryboardImageGenerateTaskType,resumeUserTask } from '~/utils/taskPartialFailed'
import { shouldKeepImageBatchLoadingAfterFollowMessage } from '~/utils/taskSseSilentDisconnect'
import { requestCancelUserTaskById } from '~/utils/userTaskCancelFlow'

export {
  getActiveImageBatchTargetIds,
  applyStoryboardImageImmediatePanelLoadingRestore
} from '~/utils/storyboardImageBatchShared'

function createStoryboardImageBatchGenerate() {
  const state: StoryboardImageBatchState = {
    activeTaskId: { value: null },
    activeImageTaskId: { value: null },
    streamCloser: null,
    stopRequested: false,
    resumeFollowGeneration: 0,
    followInFlight: null,
    promptFollowTaskId: null,
    imageFollowInFlight: null,
    batchSseFollowInFlight: false,
    batchSseFollowDepth: 0,
    restoreSessionInFlight: null,
    batchRunInFlight: false,
    cachedRecentProjectTasks: null,
    followIdleBarrier: createAsyncIdleBarrier(() => isFollowInFlight())
  }

  const core = createStoryboardImageBatchCore(state)
  const follow = createStoryboardImageBatchPromptFollow(state, core)
  const restore = createStoryboardImageBatchRestore(state, core, follow)
  const { getStore } = core

  async function runBatchForPanels(
    panels: StoryboardPanel[],
    overwrite: boolean,
    options?: {
      manualAgentModelPick?: boolean
      selectedStoryboardIds?: number[]
      agentCode?: string
      modelCode?: string
      genScenario?: string
      genNegativePrompt?: string
    }
  ): Promise<{ ok: boolean; panels: StoryboardPanel[]; message?: string }> {
    state.stopRequested = false
    const scopeAtEntry = captureCreationLiveGenScope()
    const generationAtEntry = state.resumeFollowGeneration
    state.batchRunInFlight = true
    try {
      const ctx = await resolveStoryScriptSaveContext(getStore(), getRouteLikeSnapshot())
      if (!ctx) {
        return { ok: false, panels, message: '缺少项目信息，请从「我的作品」打开作品后再操作' }
      }
      if (core.isBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
        return { ok: false, panels, message: TASK_BACKGROUND_RUNNING_MESSAGE }
      }

      let storyboardIds = panels
        .map((p) => parseServerStoryboardId(p.id))
        .filter((id): id is number => id != null)

      if (options?.selectedStoryboardIds?.length) {
        const selectedSet = new Set(options.selectedStoryboardIds)
        storyboardIds = storyboardIds.filter((id) => selectedSet.has(id))
      }

      if (!storyboardIds.length) {
        return { ok: false, panels, message: '请选择要生成的分镜' }
      }

      const targets = core.resolveBatchImageTargets(panels, storyboardIds, overwrite)
      if (!targets.length) {
        return { ok: false, panels, message: '所选分镜均已有分镜图' }
      }

      core.setImageBatchTargetIds(targets)
      core.markPanelsGenerating(targets)
      getStore().setStoryboardImageBatchGenerating(true)
      getStore().setStoryboardImageBatchError(null)
      getStore().setStoryboardImageBatchProgress(0, targets.length)

      const submitOutcome = await follow.submitImageWithPromptBatch(ctx, targets, overwrite, {
        manualAgentModelPick: options?.manualAgentModelPick,
        agentCode: options?.agentCode,
        modelCode: options?.modelCode,
        genScenario: options?.genScenario,
        genNegativePrompt: options?.genNegativePrompt
      })
      if (core.isBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
        core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, panels, {
          promptTaskId: submitOutcome.taskId
        })
        return { ok: false, panels, message: TASK_BACKGROUND_RUNNING_MESSAGE }
      }
      if (!submitOutcome.ok || !submitOutcome.taskId) {
        getStore().setStoryboardImageBatchGenerating(false)
        core.clearPanelGeneratingStatuses(targets)
        core.clearImageBatchTargetIds()
        return { ok: false, panels, message: submitOutcome.message || '批量生成分镜图失败' }
      }

      let promptOutcome = await follow.followPromptTask(submitOutcome.taskId, storyboardIds, {
        progressTotalHint: submitOutcome.totalShots,
        freshSubmission: true
      })

      if (core.isBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
        core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, panels, {
          promptTaskId: submitOutcome.taskId,
          imageTaskId: promptOutcome.chainChildTaskIds?.[0]
        })
        return { ok: false, panels, message: TASK_BACKGROUND_RUNNING_MESSAGE }
      }

      // 提示词 SSE 结束会清空 activeTaskId 并触发 restore；出图 SSE 接续前显式保持 loading
      core.ensureImageBatchLoadingUi(targets, panels)

      if (state.stopRequested) {
        getStore().setStoryboardImageBatchGenerating(false)
        core.clearPanelGeneratingStatuses(targets)
        core.clearImageBatchTargetIds()
        return { ok: false, panels, message: '已停止生成' }
      }

      if (!promptOutcome.ok) {
        if (promptOutcome.partial && submitOutcome.taskId) {
          promptOutcome = await follow.handlePromptPartialResume(
            submitOutcome.taskId,
            storyboardIds
          )
          if (core.isBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
            core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, panels, {
              promptTaskId: submitOutcome.taskId,
              imageTaskId: (promptOutcome as { chainChildTaskIds?: number[] })
                .chainChildTaskIds?.[0]
            })
            return { ok: false, panels, message: TASK_BACKGROUND_RUNNING_MESSAGE }
          }
        }
        if (!promptOutcome.ok && !promptOutcome.partial) {
          if (shouldKeepImageBatchLoadingAfterFollowMessage(promptOutcome.message)) {
            return { ok: false, panels, message: promptOutcome.message }
          }
          getStore().setStoryboardImageBatchGenerating(false)
          if (promptOutcome.message) {
            getStore().setStoryboardImageBatchError(promptOutcome.message)
          }
          core.clearPanelGeneratingStatuses(targets)
          core.clearImageBatchTargetIds()
          return { ok: false, panels, message: promptOutcome.message }
        }
      }

      let workingPanels = panels
      try {
        workingPanels = await core.refreshPanelsFromApi()
      } catch {
        /* ignore */
      }
      const promptChildIds = (promptOutcome as { chainChildTaskIds?: number[] }).chainChildTaskIds
      if (core.isBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
        core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, workingPanels, {
          promptTaskId: submitOutcome.taskId,
          imageTaskId: promptChildIds?.[0]
        })
        return { ok: false, panels: workingPanels, message: TASK_BACKGROUND_RUNNING_MESSAGE }
      }

      const imageOutcome = await follow.followImageGenerateAfterPrompt(
        targets,
        storyboardIds,
        workingPanels,
        promptChildIds
      )

      if (core.isBatchOperationInterrupted(scopeAtEntry, generationAtEntry)) {
        core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, workingPanels, {
          promptTaskId: submitOutcome.taskId,
          imageTaskId: promptChildIds?.[0]
        })
        return { ok: false, panels: workingPanels, message: TASK_BACKGROUND_RUNNING_MESSAGE }
      }

      if (typeof window !== 'undefined' && imageOutcome.ok) {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }

      workingPanels = imageOutcome.panels ?? workingPanels
      if (!imageOutcome.ok) {
        if (shouldKeepImageBatchLoadingAfterFollowMessage(imageOutcome.message)) {
          core.ensureImageBatchLoadingUi(targets, workingPanels)
          return { ok: false, panels: workingPanels, message: imageOutcome.message }
        }
        getStore().setStoryboardImageBatchGenerating(false)
        if (imageOutcome.message) {
          getStore().setStoryboardImageBatchError(imageOutcome.message)
        }
        core.clearPanelGeneratingStatuses(targets)
        core.clearImageBatchTargetIds()
        return { ok: false, panels: workingPanels, message: imageOutcome.message }
      }

      getStore().setStoryboardImageBatchProgress(targets.length, targets.length)
      getStore().setStoryboardImageBatchGenerating(false)
      core.clearPanelGeneratingStatuses(targets)
      core.clearImageBatchTargetIds()

      return {
        ok: true,
        panels: workingPanels,
        message: promptOutcome.partial || imageOutcome.partial ? '部分分镜图生成失败' : undefined
      }
    } finally {
      state.batchRunInFlight = false
      state.followIdleBarrier.notifyStateChange()
    }
  }

  async function requestStop() {
    state.stopRequested = true
    core.closeStream()
    const promptTaskId = state.activeTaskId.value ?? getStore().storyboardImageBatchActiveTaskId
    const imageTaskId =
      state.imageFollowInFlight?.taskId ??
      state.activeImageTaskId.value ??
      getStore().storyboardImageBatchActiveImageTaskId
    const taskIds = [promptTaskId, imageTaskId]
      .map((id) => parseTaskId(id))
      .filter((id): id is number => id != null)
    for (const taskId of [...new Set(taskIds)]) {
      try {
        await requestCancelUserTaskById(taskId)
      } catch {
        /* ignore */
      }
    }
    core.syncActiveTaskIdToStore(null)
    core.syncActiveImageTaskIdToStore(null)
    core.stopImageBatchGeneration()
  }

  function onGlobalStopTask(event: Event) {
    const detail = (event as CustomEvent<{ taskId?: number; taskType?: string | null }>).detail
    const id = parseTaskId(detail?.taskId)
    if (!id) return
    if (
      !isStoryboardImagePromptBatchTask(detail?.taskType) &&
      !isStoryboardImageGenerateTaskType(detail?.taskType) &&
      state.activeTaskId.value !== id &&
      state.activeImageTaskId.value !== id &&
      getStore().storyboardImageBatchActiveTaskId !== id &&
      getStore().storyboardImageBatchActiveImageTaskId !== id
    ) {
      return
    }
    void requestStop()
  }

  function onGlobalTrackTask(
    event: Event,
    onDone?: (result: { ok: boolean; panels: StoryboardPanel[]; message?: string }) => void
  ) {
    const detail = (event as CustomEvent<{ taskId?: number; taskType?: string | null }>).detail
    if (!isStoryboardImagePromptBatchTask(detail?.taskType)) return
    const id = parseTaskId(detail?.taskId)
    if (!id) return
    const panels = getStore().formData.storyboardScript.panels as StoryboardPanel[]
    const storyboardIds = panels
      .map((p) => parseServerStoryboardId(p.id))
      .filter((sid): sid is number => sid != null)
    void (async () => {
      const promptOutcome = await follow.followPromptTask(id, storyboardIds)
      let workingPanels = panels
      if (promptOutcome.ok || promptOutcome.partial) {
        workingPanels = await core.refreshPanelsFromApi()
        const batchTargetIds = core.getActiveImageBatchTargetIdsLocal()
        const imageTargets = batchTargetIds.length
          ? batchTargetIds
          : core.resolveBatchImageTargets(workingPanels, storyboardIds, false)
        const imageOutcome = await follow.followImageGenerateAfterPrompt(
          imageTargets,
          storyboardIds,
          workingPanels,
          promptOutcome.chainChildTaskIds
        )
        if (imageOutcome.ok) {
          workingPanels = imageOutcome.panels ?? workingPanels
        }
        getStore().setStoryboardImageBatchGenerating(false)
        core.clearPanelGeneratingStatuses(storyboardIds)
        core.clearImageBatchTargetIds()
        onDone?.({
          ok: imageOutcome.ok,
          panels: workingPanels,
          message: imageOutcome.ok
            ? promptOutcome.partial
              ? promptOutcome.message
              : undefined
            : imageOutcome.message
        })
      } else {
        getStore().setStoryboardImageBatchGenerating(false)
        core.clearImageBatchTargetIds()
        onDone?.({ ok: false, panels: workingPanels, message: promptOutcome.message })
      }
    })()
  }

  function onGlobalResumeTask(
    event: Event,
    onPanelsUpdate: (panels: StoryboardPanel[]) => void,
    onDone?: (result: { ok: boolean; panels: StoryboardPanel[]; message?: string }) => void
  ) {
    const detail = (event as CustomEvent<{ taskId?: number; taskType?: string | null }>).detail
    if (!isStoryboardImagePromptBatchTask(detail?.taskType)) return
    const id = parseTaskId(detail?.taskId)
    if (!id) return
    const panels = getStore().formData.storyboardScript.panels as StoryboardPanel[]
    const storyboardIds = panels
      .map((p) => parseServerStoryboardId(p.id))
      .filter((sid): sid is number => sid != null)
    void (async () => {
      core.beginBatchSseFollow()
      try {
        try {
          await resumeUserTask(id, 'storyboard_image_prompt_batch')
          getStore().removePausedTaskFollow(id)
          core.invalidateRecentProjectTasksCache()
          const promptOutcome = await follow.followPromptTask(id, storyboardIds)
          let workingPanels = panels
          if (promptOutcome.ok || promptOutcome.partial) {
            workingPanels = await core.refreshPanelsFromApi()
            onPanelsUpdate(workingPanels)
            const batchTargetIds = core.getActiveImageBatchTargetIdsLocal()
            const imageTargets = batchTargetIds.length
              ? batchTargetIds
              : core.resolveBatchImageTargets(workingPanels, storyboardIds, false)
            const imageOutcome = await follow.followImageGenerateAfterPrompt(
              imageTargets,
              storyboardIds,
              workingPanels,
              promptOutcome.chainChildTaskIds
            )
            if (imageOutcome.ok) {
              workingPanels = imageOutcome.panels ?? workingPanels
              onPanelsUpdate(workingPanels)
            }
            getStore().setStoryboardImageBatchGenerating(false)
            core.clearPanelGeneratingStatuses(storyboardIds)
            core.clearImageBatchTargetIds()
            onDone?.({
              ok: imageOutcome.ok,
              panels: workingPanels,
              message: imageOutcome.ok
                ? promptOutcome.partial
                  ? promptOutcome.message
                  : undefined
                : imageOutcome.message
            })
          } else {
            getStore().setStoryboardImageBatchGenerating(false)
            core.clearImageBatchTargetIds()
            onDone?.({ ok: false, panels: workingPanels, message: promptOutcome.message })
          }
        } catch (e: unknown) {
          core.syncActiveTaskIdToStore(null)
          core.syncActiveImageTaskIdToStore(null)
          getStore().setStoryboardImageBatchGenerating(false)
          core.clearPanelGeneratingStatuses(core.getActiveImageBatchTargetIdsLocal())
          core.clearImageBatchTargetIds()
          const err = e as { msg?: string; message?: string }
          onDone?.({
            ok: false,
            panels,
            message: err?.msg || err?.message || '续生失败'
          })
        }
      } finally {
        core.endBatchSseFollow()
      }
    })()
  }

  /** 断开 SSE 并作废进行中的 restore follow；保留持久化 taskId，供切步/刷新后恢复 loading */
  function cancelResumeFollow(): Promise<void> {
    state.resumeFollowGeneration++
    core.closeStream()
    const imageTaskId =
      state.imageFollowInFlight?.taskId ??
      state.activeImageTaskId.value ??
      getStore().storyboardImageBatchActiveImageTaskId
    if (imageTaskId != null) suspendTaskSseFollow(imageTaskId)
    return state.followIdleBarrier.waitForIdle()
  }

  /**
   * 是否已有真实任务链 owner（不含只负责发现任务的 restoreSession）。
   * 页面恢复协调器单独串行化 restore，任务链状态只用于阻止内部响应式写入反向恢复。
   */
  function isFollowInFlight(): boolean {
    return (
      state.batchRunInFlight ||
      state.batchSseFollowInFlight ||
      state.followInFlight != null ||
      state.imageFollowInFlight != null
    )
  }

  return {
    activeTaskId: state.activeTaskId,
    runBatchForPanels,
    requestStop,
    restoreOngoingBatchIfNeeded: restore.restoreOngoingBatchIfNeeded,
    applyImmediatePanelLoadingRestore: core.applyImmediatePanelLoadingRestore,
    ensureBatchPanelLoadingUi: core.ensureBatchPanelLoadingUi,
    onGlobalStopTask,
    onGlobalTrackTask,
    onGlobalResumeTask,
    onStoryboardImageGenSseTerminal: core.onStoryboardImageGenSseTerminal,
    cancelResumeFollow,
    waitForFollowIdle: state.followIdleBarrier.waitForIdle,
    isFollowInFlight
  }
}

/** 供组件在渲染外（模块级）判定使用 */
export type StoryboardImageBatchGenerate = ReturnType<typeof createStoryboardImageBatchGenerate>

export function useStoryboardImageBatchGenerate(): StoryboardImageBatchGenerate {
  // 原 Vue 每个组件 setup 各创建一份实例；React 侧用惰性 state 保证跨渲染稳定
  const [instance] = useState(() => createStoryboardImageBatchGenerate())
  return instance
}

'use client'

/**
 * StoryboardScript 全局任务命令处理（原 StoryboardScript.vue script 内
 * handleGlobalTrackTask / Resume / Restart / Stop + pending 指令补投 + 引导锚点段原样搬迁）。
 * 依赖经 deps 显式注入，由 useStoryboardScriptGeneration 装配。
 */

import { message } from 'antd'
import type { MutableRefObject } from 'react'
import type { StoryboardImageBatchGenerate } from '~/composables/useStoryboardImageBatchGenerate'
import type { useStoryboardScriptBatchGenerate } from '~/composables/useStoryboardScriptBatchGenerate'
import { useCreationStore } from '~/stores/creation'
import {
ackCreateFlowTaskCommand,
consumePendingCreateFlowTaskCommand,
createFlowTaskCommandEvent,
type CreateFlowTaskCommandDetail
} from '~/utils/createFlowTaskCommand'
import { extractSceneIdsFromPartialFailed,normUserTaskType } from '~/utils/taskPartialFailed'
import { shouldSilentStoryboardBatchToast } from '~/utils/taskSseSilentDisconnect'
import type { StoryboardPanel } from './storyboardScriptShared'
 interface StoryboardScriptTaskCommandDeps {
  storyboardScriptGen: ReturnType<typeof useStoryboardScriptBatchGenerate>
  storyboardImageBatchGen: StoryboardImageBatchGenerate
  pageDisposedRef: MutableRefObject<boolean>
  generationStoppedRef: MutableRefObject<boolean>
  onChangeRef: MutableRefObject<(panels: StoryboardPanel[]) => void>
  onGenerationCompleteRef: MutableRefObject<(panels: StoryboardPanel[]) => void>
  prepareGeneratingProgress: () => void
  syncStoryboardScriptPanelsFromTaskResult: (next: StoryboardPanel[]) => StoryboardPanel[]
  handleResumePartialFailed: () => Promise<void>
  startGeneration: (options?: {
    sceneIds?: number[]
    manualAgentModelPick?: boolean
  }) => Promise<void>
  setToolbarOpsOpen: (v: boolean) => void
}

export function createStoryboardScriptTaskCommandHandlers(deps: StoryboardScriptTaskCommandDeps) {
  const {
    storyboardScriptGen,
    storyboardImageBatchGen,
    pageDisposedRef,
    generationStoppedRef,
    onChangeRef,
    onGenerationCompleteRef,
    prepareGeneratingProgress,
    syncStoryboardScriptPanelsFromTaskResult,
    handleResumePartialFailed,
    startGeneration,
    setToolbarOpsOpen
  } = deps
  const getStore = () => useCreationStore.getState()

  function handleGlobalTrackTaskEvent(event: Event) {
    const detail = (event as CustomEvent<CreateFlowTaskCommandDetail>).detail
    const ty = String(detail?.taskType ?? '').trim().toLowerCase().replace(/-/g, '_')
    if (ty === 'storyboard_script_batch' || ty === 'storyboard_image_prompt_batch') {
      ackCreateFlowTaskCommand('track', Number(detail?.taskId))
    }
    if (ty === 'storyboard_image_prompt_batch') {
      storyboardImageBatchGen.onGlobalTrackTask(event, (result) => {
        if (pageDisposedRef.current) return
        onChangeRef.current(result.panels)
        if (result.ok) {
          message.success('批量生成分镜图完成')
        } else if (result.message && !shouldSilentStoryboardBatchToast(result.message)) {
          message.error(result.message)
        }
      })
      return
    }
    generationStoppedRef.current = false
    prepareGeneratingProgress()
    storyboardScriptGen.onGlobalTrackTask(event, (result) => {
      if (pageDisposedRef.current) return
      syncStoryboardScriptPanelsFromTaskResult(result.panels)
      if (result.ok) {
        message.success('分镜生成完成')
        onGenerationCompleteRef.current(result.panels)
      } else if (result.message && !generationStoppedRef.current) {
        if (shouldSilentStoryboardBatchToast(result.message)) return
        if (result.message.includes('部分') || result.message.includes('续生')) {
          message.warning(result.message)
        } else {
          message.error(result.message)
        }
      }
    })
  }

  function handleGlobalResumeTaskEvent(event: Event) {
    const detail = (event as CustomEvent<{ taskId?: number; taskType?: string | null }>).detail
    const ty = String(detail?.taskType ?? '').trim().toLowerCase().replace(/-/g, '_')
    const taskId = Number(detail?.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) return
    if (ty === 'storyboard_script_batch' || ty === 'storyboard_image_prompt_batch') {
      ackCreateFlowTaskCommand('resume', taskId)
    }
    if (ty === 'storyboard_image_prompt_batch') {
      generationStoppedRef.current = false
      storyboardImageBatchGen.onGlobalResumeTask(
        event,
        (nextPanels) => {
          if (pageDisposedRef.current) return
          onChangeRef.current(nextPanels)
        },
        (result) => {
          if (pageDisposedRef.current) return
          if (result.ok) {
            message.success('分镜图续生完成')
          } else if (result.message && !shouldSilentStoryboardBatchToast(result.message)) {
            if (result.message.includes('部分') || result.message.includes('续生')) {
              message.warning(result.message)
            } else {
              message.error(result.message)
            }
          }
        }
      )
      return
    }
    if (ty !== 'storyboard_script_batch') return
    generationStoppedRef.current = false
    prepareGeneratingProgress()
    getStore().setStoryboardScriptActiveTaskId(taskId)
    void handleResumePartialFailed()
  }

  function handleGlobalRestartTaskEvent(event: Event) {
    const detail = (event as CustomEvent<CreateFlowTaskCommandDetail>).detail
    const ty = String(detail?.taskType ?? '').trim().toLowerCase().replace(/-/g, '_')
    if (ty !== 'storyboard_script_batch') return
    ackCreateFlowTaskCommand('restart', Number(detail?.taskId))
    const sceneIds = (detail?.sceneIds ?? []).filter(
      (id) => Number.isFinite(id) && id > 0
    )
    const fallbackSceneIds = extractSceneIdsFromPartialFailed(
      getStore().storyboardScriptPartialFailedData
    )
    void startGeneration({
      ...((sceneIds.length ? sceneIds : fallbackSceneIds).length
        ? { sceneIds: sceneIds.length ? sceneIds : fallbackSceneIds }
        : {}),
      manualAgentModelPick: detail?.manualAgentModelPick === true
    })
  }

  /**
   * 全局任务面板先跳步骤再派发指令；本页挂载晚于派发时事件已错过，
   * 挂载完成后补投属于本页的 pending 指令（分镜脚本/分镜图提示词批量任务）。
   */
  function deliverPendingCreateFlowTaskCommands() {
    const acceptsOwnTask = (d: { taskType: string | null }) => {
      const ty = normUserTaskType(d.taskType)
      return ty === 'storyboard_script_batch' || ty === 'storyboard_image_prompt_batch'
    }
    const resume = consumePendingCreateFlowTaskCommand('resume', acceptsOwnTask)
    if (resume) {
      handleGlobalResumeTaskEvent(createFlowTaskCommandEvent('resume', resume))
    }
    const restart = consumePendingCreateFlowTaskCommand(
      'restart',
      (d) => normUserTaskType(d.taskType) === 'storyboard_script_batch'
    )
    if (restart) {
      handleGlobalRestartTaskEvent(createFlowTaskCommandEvent('restart', restart))
    }
    const track = consumePendingCreateFlowTaskCommand('track', acceptsOwnTask)
    if (track) {
      handleGlobalTrackTaskEvent(createFlowTaskCommandEvent('track', track))
    }
  }

  async function handleGlobalStopTaskEvent(event: Event) {
    const detail = (event as CustomEvent<{ taskId?: number; taskType?: string | null }>).detail
    const ty = String(detail?.taskType ?? '').trim().toLowerCase().replace(/-/g, '_')
    if (ty === 'storyboard_image_prompt_batch') {
      void storyboardImageBatchGen.onGlobalStopTask(event)
      return
    }
    const isStoryboardTask =
      ty === 'storyboard_script_batch' ||
      storyboardScriptGen.activeTaskId.value === Number(detail?.taskId) ||
      getStore().storyboardScriptActiveTaskId === Number(detail?.taskId)
    if (!isStoryboardTask) return
    generationStoppedRef.current = true
    await storyboardScriptGen.requestStop()
    getStore().stopStoryboardGeneration()
  }

  return {
    handleGlobalTrackTaskEvent,
    handleGlobalResumeTaskEvent,
    handleGlobalRestartTaskEvent,
    deliverPendingCreateFlowTaskCommands,
    handleGlobalStopTaskEvent
  }
}

'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import { useStoryboardImageBatchGenerate } from '@/hooks/useStoryboardImageBatchGenerate'
import { useStoryboardScriptBatchGenerate } from '@/hooks/useStoryboardScriptBatchGenerate'
import { useStoryboardVideoBatchGenerate } from '@/hooks/useStoryboardVideoBatchGenerate'
import { useStoryboardAudioBatchGenerate } from '@/hooks/useStoryboardAudioBatchGenerate'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import { useStudioFlowRuntime } from '@/components/studio/flow/StudioFlowRuntimeProvider'
import {
  runStudioFlowAutoPipeline,
  studioFlowAutoPipelineStageTitle,
  type StudioFlowAutoPipelineProgress,
  type StudioFlowAutoPipelineStage
} from '@/utils/studio/studioFlowAutoPipelineRunner'
import { maybeOpenStudioFlowRechargeFromMessage } from '@/utils/studio/studioFlowAutoPipelineTask'
import {
  clearStudioFlowAutoPipelinePending,
  clearStudioFlowAutoPipelineResume,
  dispatchStudioFlowAutoPipelineStart,
  readStudioFlowAutoPipelinePending,
  readStudioFlowAutoPipelineResume,
  STUDIO_FLOW_AUTO_PIPELINE_PREPARE_EVENT,
  STUDIO_FLOW_AUTO_PIPELINE_START_EVENT,
  writeStudioFlowAutoPipelineResume,
  type StudioFlowAutoPipelineStartDetail
} from '@/utils/studio/studioFlowAutoPipelineEvents'
import { reconcileStudioFlowAutoPipelineResume } from '@/utils/studio/studioFlowAutoPipelineProgress'

export interface StudioFlowAutoPipelineState {
  running: boolean
  awaitingConfirmation: boolean
  progress: StudioFlowAutoPipelineProgress | null
  failedStage: StudioFlowAutoPipelineStage | null
  errorMessage: string | null
  canResume: boolean
  /** 用户点了关闭：收起到紧凑入口，不清除续跑点 */
  dismissed: boolean
}

const idleState: StudioFlowAutoPipelineState = {
  running: false,
  awaitingConfirmation: false,
  progress: null,
  failedStage: null,
  errorMessage: null,
  canResume: false,
  dismissed: false
}

function doneState(): StudioFlowAutoPipelineState {
  return {
    running: false,
    awaitingConfirmation: false,
    progress: {
      stage: 'done',
      title: '创作流水线完成',
      message: '画布节点已就绪；可在顶栏「交付」导出成片'
    },
    failedStage: null,
    errorMessage: null,
    canResume: false,
    dismissed: false
  }
}

export function useStudioFlowAutoPipeline(projectId: number | null, episodeId: number | null) {
  const runtime = useStudioFlowRuntime()
  const nodes = useStudioUiStore((state) => state.nodes)
  const creationMode = useCreationStore((state) => state.formData.globalSetting.creationMode)
  const storyboardScriptBatch = useStoryboardScriptBatchGenerate()
  const storyboardImageBatch = useStoryboardImageBatchGenerate()
  const storyboardVideoBatch = useStoryboardVideoBatchGenerate()
  const storyboardAudioBatch = useStoryboardAudioBatchGenerate()
  const cancelRef = useRef(false)
  const runningRef = useRef(false)
  const [state, setState] = useState<StudioFlowAutoPipelineState>(idleState)

  const applyReconciledResume = useCallback((
    stored: StudioFlowAutoPipelineStage | null,
    options?: { preferKeepMessage?: boolean }
  ) => {
    if (!projectId || !stored) return
    const eid = episodeId ?? 0
    const canvasNodes = useStudioUiStore.getState().nodes
    const mode = useCreationStore.getState().formData.globalSetting.creationMode
    const reconciled = reconcileStudioFlowAutoPipelineResume(stored, {
      nodes: canvasNodes,
      creationMode: mode
    })

    if (reconciled === stored) {
      setState((current) => ({
        ...current,
        awaitingConfirmation: false,
        failedStage: stored,
        canResume: true,
        dismissed: false,
        errorMessage: options?.preferKeepMessage
          ? (current.errorMessage || '上次自动创作因余额或其他原因中断，充值后可继续')
          : current.errorMessage
      }))
      return
    }

    if (!reconciled) {
      clearStudioFlowAutoPipelineResume(projectId, eid)
      setState(doneState())
      return
    }

    writeStudioFlowAutoPipelineResume(projectId, eid, reconciled)
    setState((current) => ({
      ...current,
      running: false,
      awaitingConfirmation: false,
      progress: null,
      failedStage: reconciled,
      canResume: true,
      dismissed: current.dismissed,
      errorMessage: reconciled === stored
        ? (current.errorMessage || '上次自动创作因余额或其他原因中断，充值后可继续')
        : `画布进度已更新，下一步可从「${studioFlowAutoPipelineStageTitle(reconciled)}」继续`
    }))
  }, [episodeId, projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (!projectId) {
        setState(idleState)
        return
      }
      const eid = episodeId ?? 0
      const failedStage = readStudioFlowAutoPipelineResume(projectId, eid)
      if (failedStage) {
        applyReconciledResume(failedStage, { preferKeepMessage: true })
        return
      }
      if (readStudioFlowAutoPipelinePending(projectId, eid)) {
        setState({ ...idleState, awaitingConfirmation: true })
        return
      }
      setState(idleState)
    }, 0)
    return () => window.clearTimeout(timer)
  }, [applyReconciledResume, episodeId, projectId])

  // 画布手动生成后：把中断续跑点推进到真实未完成阶段（或全部完成）
  useEffect(() => {
    if (!projectId || runningRef.current) return
    const timer = window.setTimeout(() => {
      const eid = episodeId ?? 0
      const stored = state.failedStage || readStudioFlowAutoPipelineResume(projectId, eid)
      if (!stored) return

      const reconciled = reconcileStudioFlowAutoPipelineResume(stored, {
        nodes,
        creationMode
      })
      if (reconciled === stored) return

      if (!reconciled) {
        clearStudioFlowAutoPipelineResume(projectId, eid)
        setState(doneState())
        return
      }

      writeStudioFlowAutoPipelineResume(projectId, eid, reconciled)
      setState((current) => ({
        ...current,
        awaitingConfirmation: false,
        failedStage: reconciled,
        canResume: true,
        errorMessage: `画布进度已更新，下一步可从「${studioFlowAutoPipelineStageTitle(reconciled)}」继续`
      }))
    }, 0)
    return () => window.clearTimeout(timer)
  }, [creationMode, episodeId, nodes, projectId, state.failedStage])

  const start = useCallback(async (scope: {
    projectId: number
    episodeId: number
    fromStage?: StudioFlowAutoPipelineStage
  }) => {
    if (runningRef.current) {
      message.warning('自动创作流水线已在运行中')
      return false
    }
    runningRef.current = true
    cancelRef.current = false
    clearStudioFlowAutoPipelinePending(scope.projectId, scope.episodeId)

    const canvasNodes = useStudioUiStore.getState().nodes
    const mode = useCreationStore.getState().formData.globalSetting.creationMode
    const fromStage = scope.fromStage
      ? reconcileStudioFlowAutoPipelineResume(scope.fromStage, {
        nodes: canvasNodes,
        creationMode: mode
      }) ?? undefined
      : undefined
    // 续跑点已全部完成：直接结束，避免重复跑已完成的视频阶段
    if (scope.fromStage && !fromStage) {
      runningRef.current = false
      clearStudioFlowAutoPipelineResume(scope.projectId, scope.episodeId)
      setState(doneState())
      message.success('画布阶段已全部完成，无需续跑')
      return true
    }

    const startStage = fromStage || 'extract'
    setState({
      running: true,
      awaitingConfirmation: false,
      progress: {
        stage: startStage,
        title: fromStage ? '继续自动创作' : studioFlowAutoPipelineStageTitle(startStage),
        message: fromStage
          ? `从「${studioFlowAutoPipelineStageTitle(fromStage)}」阶段续跑…`
          : '准备开始…'
      },
      failedStage: null,
      errorMessage: null,
      canResume: false,
      dismissed: false
    })

    try {
      const result = await runStudioFlowAutoPipeline({
        projectId: scope.projectId,
        episodeId: scope.episodeId,
        fromStage,
        onProgress: (progress) => {
          setState((current) => ({
            ...current,
            running: true,
            progress,
            dismissed: false
          }))
        },
        shouldCancel: () => cancelRef.current,
        onStageComplete: async () => {
          await runtime?.refresh({ taskIntent: 'mutate' })
        },
        // 提交后由当前 SSE 持有实时状态；阶段终态再做一次权威快照刷新。
        // 禁止在运行中用 task/list 轮询覆盖节点的真实 SSE progress。
        onTaskSubmitted: async () => undefined,
        runStoryboardScriptBatch: (panels) =>
          storyboardScriptBatch.runBatchGenerate(panels),
        runStoryboardImageBatch: (panels) =>
          storyboardImageBatch.runBatchForPanels(panels, false),
        runStoryboardVideoBatch: ({ scriptPanels, videoPanels }) =>
          storyboardVideoBatch.runBatchVideosOnly({
            scriptPanels,
            videoPanels,
            overwrite: false,
            onPanelsUpdate: (panels) => {
              useCreationStore.getState().updateFormData({
                storyboardVideo: {
                  ...useCreationStore.getState().formData.storyboardVideo,
                  panels
                }
              })
            }
          }),
        runDubbingBatch: ({ scriptPanels, dubbingPanels, panelIndices }) =>
          storyboardAudioBatch.runBatchForIndices({
            panelIndices,
            scriptPanels,
            panels: dubbingPanels,
            overwrite: false,
            onPanelsUpdate: (panels) => {
              useCreationStore.getState().updateFormData({
                dubbing: {
                  ...useCreationStore.getState().formData.dubbing,
                  panels
                }
              })
            }
          })
      })

      if (result.ok) {
        clearStudioFlowAutoPipelineResume(scope.projectId, scope.episodeId)
        setState(doneState())
        message.success('自动创作流水线已完成')
        return true
      }

      maybeOpenStudioFlowRechargeFromMessage(result.message)
      if (result.failedStage && result.failedStage !== 'done') {
        const reconciled = reconcileStudioFlowAutoPipelineResume(result.failedStage, {
          nodes: useStudioUiStore.getState().nodes,
          creationMode: useCreationStore.getState().formData.globalSetting.creationMode
        })
        if (!reconciled) {
          clearStudioFlowAutoPipelineResume(scope.projectId, scope.episodeId)
          setState(doneState())
          return true
        }
        writeStudioFlowAutoPipelineResume(scope.projectId, scope.episodeId, reconciled)
        setState({
          running: false,
          awaitingConfirmation: false,
          progress: null,
          failedStage: reconciled,
          errorMessage: result.message ?? '自动创作中断',
          canResume: true,
          dismissed: false
        })
      } else {
        setState({
          running: false,
          awaitingConfirmation: false,
          progress: null,
          failedStage: null,
          errorMessage: result.message ?? '自动创作中断',
          canResume: false,
          dismissed: false
        })
      }
      message.warning(result.message || '自动创作流水线已中断')
      return false
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '自动创作失败'
      maybeOpenStudioFlowRechargeFromMessage(errorMessage)
      setState({
        running: false,
        awaitingConfirmation: false,
        progress: null,
        failedStage: null,
        errorMessage,
        canResume: false,
        dismissed: false
      })
      message.error(errorMessage)
      return false
    } finally {
      runningRef.current = false
      cancelRef.current = false
    }
  }, [runtime, storyboardAudioBatch, storyboardImageBatch, storyboardScriptBatch, storyboardVideoBatch])

  const resume = useCallback(() => {
    if (!projectId) return
    const eid = episodeId ?? 0
    const stored = state.failedStage || readStudioFlowAutoPipelineResume(projectId, eid)
    if (!stored) {
      message.info('没有可续跑的阶段')
      return
    }
    const reconciled = reconcileStudioFlowAutoPipelineResume(stored, {
      nodes: useStudioUiStore.getState().nodes,
      creationMode: useCreationStore.getState().formData.globalSetting.creationMode
    })
    if (!reconciled) {
      clearStudioFlowAutoPipelineResume(projectId, eid)
      setState(doneState())
      message.success('画布阶段已全部完成，无需续跑')
      return
    }
    void start({ projectId, episodeId: eid, fromStage: reconciled })
  }, [episodeId, projectId, start, state.failedStage])

  const confirmStart = useCallback(() => {
    if (!projectId || runningRef.current) return
    void start({ projectId, episodeId: episodeId ?? 0 })
  }, [episodeId, projectId, start])

  const cancel = useCallback(() => {
    if (!runningRef.current) return
    cancelRef.current = true
    setState((current) => ({
      ...current,
      progress: current.progress
        ? { ...current.progress, message: '正在取消…' }
        : current.progress
    }))
  }, [])

  /** 收起卡片：保留续跑点，改为紧凑入口 */
  const dismiss = useCallback(() => {
    if (runningRef.current) return
    if (projectId != null && state.awaitingConfirmation) {
      clearStudioFlowAutoPipelinePending(projectId, episodeId ?? 0)
    }
    setState((current) => {
      if (current.canResume && current.failedStage) {
        return { ...current, dismissed: true }
      }
      return idleState
    })
  }, [episodeId, projectId, state.awaitingConfirmation])

  /** 展开完整中断卡 */
  const expand = useCallback(() => {
    setState((current) => ({ ...current, dismissed: false }))
  }, [])

  /** 真正放弃续跑并清除本地记录 */
  const abandon = useCallback(() => {
    if (runningRef.current) return
    if (projectId != null) {
      clearStudioFlowAutoPipelineResume(projectId, episodeId ?? 0)
      clearStudioFlowAutoPipelinePending(projectId, episodeId ?? 0)
    }
    setState(idleState)
  }, [episodeId, projectId])

  useEffect(() => {
    const handlePrepare = (event: Event) => {
      const detail = (event as CustomEvent<StudioFlowAutoPipelineStartDetail>).detail
      if (!detail?.projectId) return
      if (projectId && detail.projectId !== projectId) return
      if (episodeId != null && detail.episodeId !== episodeId) return
      setState({ ...idleState, awaitingConfirmation: true })
    }
    window.addEventListener(STUDIO_FLOW_AUTO_PIPELINE_PREPARE_EVENT, handlePrepare)
    return () => window.removeEventListener(STUDIO_FLOW_AUTO_PIPELINE_PREPARE_EVENT, handlePrepare)
  }, [episodeId, projectId])

  useEffect(() => {
    const handleStart = (event: Event) => {
      const detail = (event as CustomEvent<StudioFlowAutoPipelineStartDetail>).detail
      if (!detail?.projectId) return
      if (projectId && detail.projectId !== projectId) return
      if (episodeId != null && detail.episodeId !== episodeId) return
      void start(detail)
    }
    window.addEventListener(STUDIO_FLOW_AUTO_PIPELINE_START_EVENT, handleStart)
    return () => window.removeEventListener(STUDIO_FLOW_AUTO_PIPELINE_START_EVENT, handleStart)
  }, [episodeId, projectId, start])

  return {
    state,
    start,
    confirmStart,
    resume,
    cancel,
    dismiss,
    expand,
    abandon,
    dispatchStart: dispatchStudioFlowAutoPipelineStart
  }
}

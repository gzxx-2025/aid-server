'use client'

import { useCallback, useRef } from 'react'
import { message } from 'antd'
import type { StudioCanvasNode } from '@/types/studio'
import { useStoryboardImageBatchGenerate } from '@/hooks/useStoryboardImageBatchGenerate'
import { useStoryboardVideoBatchGenerate } from '@/hooks/useStoryboardVideoBatchGenerate'
import { useCreationStore } from '@/stores/creation'
import { createStudioTask, useStudioUiStore } from '@/stores/studioUi'
import { userAssetExtractFormGenerateImage } from '@/utils/businessApi'
import { extractFormGenerateImageSubmitTaskId } from '@/components/steps/scene-character-prop/scpTaskUtils'
import type { StudioFlowBatchIntent } from '@/utils/studio/studioFlowBatchSelection'
import {
  collectFormIdsForRpsFlowNodes,
  collectStoryboardPanelsForFlowNodes,
  collectStoryboardIdsForFlowNodes
} from '@/utils/studio/studioFlowBatchTargets'
import { useStudioFlowRuntime } from '@/components/studio/flow/StudioFlowRuntimeProvider'
import { waitStudioFlowTaskStreamDone } from '@/utils/studio/studioFlowAutoPipelineTask'
import { studioSseTaskProgressPatch } from '@/utils/studio/studioSseTaskProgress'

export function useStudioFlowBatchActions() {
  const runtime = useStudioFlowRuntime()
  const storyboardImageBatch = useStoryboardImageBatchGenerate()
  const storyboardVideoBatch = useStoryboardVideoBatchGenerate()
  const runningRef = useRef(false)

  const runBatch = useCallback(async (
    intent: StudioFlowBatchIntent,
    nodes: StudioCanvasNode[],
    prompt?: string
  ) => {
    if (runningRef.current) {
      message.warning('已有批量任务执行中，请稍候')
      return false
    }
    runningRef.current = true
    try {
      const store = useCreationStore.getState()
      switch (intent) {
        case 'batch-storyboard-image': {
          const panels = collectStoryboardPanelsForFlowNodes(
            nodes,
            store.formData.storyboardScript?.panels ?? []
          )
          if (!panels.length) {
            message.warning('未找到对应的分镜脚本，请先同步分镜数据')
            return false
          }
          const result = await storyboardImageBatch.runBatchForPanels(panels, Boolean(prompt?.trim()), {
            genScenario: prompt?.trim() || undefined
          })
          if (!result.ok) {
            message.warning(result.message || '批量分镜图提交失败')
            return false
          }
          message.success('已完成批量分镜图任务')
          await runtime?.refresh({ taskIntent: 'mutate' })
          return true
        }
        case 'batch-storyboard-video': {
          const scriptPanels = collectStoryboardPanelsForFlowNodes(
            nodes,
            store.formData.storyboardScript?.panels ?? []
          )
          if (!scriptPanels.length) {
            message.warning('未找到对应的分镜脚本，请先同步分镜数据')
            return false
          }
          const videoPanels = [...(store.formData.storyboardVideo?.panels ?? [])]
          const selectedStoryboardIds = collectStoryboardIdsForFlowNodes(nodes)
          const result = await storyboardVideoBatch.runBatchVideosOnly({
            scriptPanels,
            videoPanels,
            selectedStoryboardIds,
            overwrite: Boolean(prompt?.trim()),
            onPanelsUpdate: (panels) => {
              useCreationStore.getState().updateFormData({
                storyboardVideo: {
                  ...useCreationStore.getState().formData.storyboardVideo,
                  panels
                }
              })
            }
          })
          if (!result.ok) {
            message.warning(result.message || '批量分镜视频提交失败')
            return false
          }
          message.success('已完成批量分镜视频任务')
          await runtime?.refresh({ taskIntent: 'mutate' })
          return true
        }
        case 'batch-rps-image': {
          const formIds = collectFormIdsForRpsFlowNodes(nodes, { missingImageOnly: true })
          const fallbackFormIds = formIds.length ? formIds : collectFormIdsForRpsFlowNodes(nodes)
          if (!fallbackFormIds.length) {
            message.warning('所选节点没有可生成的形态，请先完成形态提取')
            return false
          }
          const extractAgents = store.extractAgents
          const agentCode = String(
            extractAgents?.scene || extractAgents?.character || extractAgents?.prop || ''
          ).trim()
          if (!agentCode) {
            message.warning('请先在生成配置中选择形态图 Agent')
            return false
          }
          const submit = await userAssetExtractFormGenerateImage({
            formIds: fallbackFormIds,
            agentCode
          })
          const taskId = extractFormGenerateImageSubmitTaskId(submit)
          if (!taskId) {
            message.error('批量形态图提交失败：未返回任务 ID')
            return false
          }
          const projectId = Number(store.currentProjectId ?? 0)
          const episodeId = Number(store.currentEpisodeId ?? 0)
          if (projectId > 0) {
            const { seedStudioFlowTaskFormIdTargets, markStudioFlowRpsNodesGeneratingByFormIds } =
              await import('@/utils/studio/studioFlowProject')
            seedStudioFlowTaskFormIdTargets({
              projectId,
              projectType: store.currentProjectType === 'series' ? 'series' : 'movie',
              episodeId,
              taskId,
              formIds: fallbackFormIds,
              taskType: 'FORM_IMAGE_BATCH'
            })
            markStudioFlowRpsNodesGeneratingByFormIds(fallbackFormIds)
          }
          const formIdSet = new Set(fallbackFormIds.map(Number))
          const targetNodeIds = nodes.flatMap((node) =>
            (node.data.flowAssetForms ?? []).some((form) => formIdSet.has(Number(form.formId)))
              ? [node.id]
              : [])
          const studioTaskIds = targetNodeIds.map((nodeId) => {
            const id = `studio-flow-${taskId}:${nodeId}`
            useStudioUiStore.getState().upsertTask({
              ...createStudioTask('批量形态图', {
                nodeId,
                remoteTaskId: taskId,
                stage: '任务已提交',
                progress: 0,
                status: 'running'
              }),
              id
            })
            return id
          })
          const outcome = await waitStudioFlowTaskStreamDone(taskId, (progress) => {
            const patch = studioSseTaskProgressPatch(progress)
            const studio = useStudioUiStore.getState()
            studioTaskIds.forEach((id) => studio.patchTask(id, patch))
          })
          const studio = useStudioUiStore.getState()
          studioTaskIds.forEach((id) => studio.patchTask(id, outcome.ok
            ? { status: 'succeeded', stage: '已完成', progress: 100 }
            : { status: 'failed', stage: outcome.message || '执行失败' }))
          if (!outcome.ok) {
            message.warning(outcome.message || '批量形态图生成失败')
            return false
          }
          message.success(`已完成 ${fallbackFormIds.length} 个形态的批量生图任务`)
          await runtime?.refresh({ taskIntent: 'mutate' })
          return true
        }
        case 'batch-dubbing':
          message.info('批量配音将在 Agent 对话中接入现有音画同步批量能力')
          return false
        case 'batch-delete-storyboard':
          message.info('批量删除分镜需二次确认，请先在步骤页或后续 Agent 指令中执行')
          return false
        default:
          return false
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '批量任务提交失败')
      return false
    } finally {
      runningRef.current = false
    }
  }, [runtime, storyboardImageBatch, storyboardVideoBatch])

  return { runBatch }
}

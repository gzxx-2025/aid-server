'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { message } from 'antd'
import { runStoryboardImageGenerateTask } from '@/hooks/useStoryboardImageGenerateTask'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import type { UserModelListItem } from '@/types/business-api'
import { STORYBOARD_IMAGE_FUNC_CODE_FALLBACKS } from '@/utils/aiModelFuncCodes'
import { userModelListByFuncCodes } from '@/utils/businessApi'
import { pickFirstNonEmptyModelPool, uniqueTrimmedCodes } from '@/utils/modelListByFuncBatch'
import { resolveStudioStoryboardImageBinding } from '@/utils/studio/studioImageActionCapabilities'
import { STUDIO_FLOW_GENERATE_STORYBOARD_IMAGE_EVENT, consumePendingStudioFlowStoryboardImageGenerate } from '@/utils/studio/studioFlowStoryboardMedia'
import { resolveStudioComposerModelFromNode } from '@/utils/studio/studioComposerDefaultModel'
import { buildStudioStoryboardImageSubmission } from '@/utils/studio/studioComposerSubmission'
import { useStudioFlowNodeTask } from './useStudioFlowNodeTask'

export function useStudioFlowStoryboardImageActions(node: StudioCanvasNode) {
  const panels = useCreationStore((state) => state.formData.storyboardScript.panels)
  const projectId = useCreationStore((state) => state.currentProjectId)
  const episodeId = useCreationStore((state) => state.currentEpisodeId)
  const [modelsLoading, setModelsLoading] = useState(true)
  const [storyboardModels, setStoryboardModels] = useState<UserModelListItem[]>([])
  const { runOnNode } = useStudioFlowNodeTask(node.id, node.data.title)
  const binding = useMemo(
    () => resolveStudioStoryboardImageBinding(node, panels),
    [node, panels]
  )

  useEffect(() => {
    let active = true
    const codes = uniqueTrimmedCodes([...STORYBOARD_IMAGE_FUNC_CODE_FALLBACKS])
    const pid = Number(projectId)
    const eid = Number(episodeId)
    void userModelListByFuncCodes(codes, Number.isFinite(pid) && pid > 0
      ? { projectId: pid, ...(Number.isFinite(eid) && eid >= 0 ? { episodeId: eid } : {}) }
      : undefined)
      .then((groups) => {
        if (!active) return
        setStoryboardModels(pickFirstNonEmptyModelPool(groups, STORYBOARD_IMAGE_FUNC_CODE_FALLBACKS))
      })
      .catch(() => { if (active) setStoryboardModels([]) })
      .finally(() => { if (active) setModelsLoading(false) })
    return () => { active = false }
  }, [episodeId, projectId])

  const submitDisabledReason = useMemo(() => {
    if (!binding.storyboardId) return '未绑定服务端分镜，请刷新流程画布'
    if (modelsLoading) return '正在读取分镜生图模型池'
    if (!storyboardModels.length) return '分镜生图模型池暂无可用模型'
    return null
  }, [binding.storyboardId, modelsLoading, storyboardModels.length])

  const resolveSubmission = useCallback((prompt: string) => {
    if (submitDisabledReason || !binding.storyboardId) return null
    const latestNode = useStudioUiStore.getState().nodes.find((item) => item.id === node.id) ?? node
    const model = resolveStudioComposerModelFromNode(storyboardModels, latestNode) ?? storyboardModels[0]
    if (!model) return null
    return buildStudioStoryboardImageSubmission({
      node: latestNode,
      model,
      storyboardId: binding.storyboardId,
      prompt
    })
  }, [binding.storyboardId, node, storyboardModels, submitDisabledReason])

  const resolveBillingRequest = useCallback(
    (prompt: string) => resolveSubmission(prompt)?.billingRequest ?? null,
    [resolveSubmission]
  )

  const regenerate = useCallback(async (prompt: string) => {
    if (submitDisabledReason) {
      message.warning(submitDisabledReason)
      return false
    }
    const submission = resolveSubmission(prompt)
    if (!submission) return false
    const pid = Number(projectId)
    const eid = Number(episodeId)
    return runOnNode({
      label: '分镜生图',
      mediaKind: 'image',
      execute: (callbacks) => runStoryboardImageGenerateTask({
        body: submission.body,
        projectEpisode: Number.isFinite(pid) && pid > 0 && Number.isFinite(eid) && eid >= 0
          ? { projectId: pid, episodeId: eid }
          : null,
        ...callbacks
      })
    })
  }, [episodeId, projectId, resolveSubmission, runOnNode, submitDisabledReason])

  useEffect(() => {
    const run = () => {
      void regenerate(node.data.prompt)
    }
    if (consumePendingStudioFlowStoryboardImageGenerate(node.id)) run()
    const handleGenerate = (event: Event) => {
      const nodeId = String((event as CustomEvent<{ nodeId?: string }>).detail?.nodeId ?? '')
      if (nodeId !== node.id) return
      consumePendingStudioFlowStoryboardImageGenerate(node.id)
      run()
    }
    window.addEventListener(STUDIO_FLOW_GENERATE_STORYBOARD_IMAGE_EVENT, handleGenerate)
    return () => window.removeEventListener(STUDIO_FLOW_GENERATE_STORYBOARD_IMAGE_EVENT, handleGenerate)
  }, [node.data.prompt, node.id, regenerate])

  return {
    binding,
    storyboardModels,
    modelsLoading,
    submitDisabledReason,
    resolveBillingRequest,
    regenerate
  }
}

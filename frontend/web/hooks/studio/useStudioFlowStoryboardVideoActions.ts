'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { message } from 'antd'
import {
  runStoryboardGridVideoGenerateTask,
  runStoryboardImageVideoGenerateTask,
  runStoryboardMultiVideoGenerateTask
} from '@/hooks/useStoryboardVideoGenerateTask'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import type { UserModelListItem } from '@/types/business-api'
import {
  normalizeCreationMode,
  resolveBatchStoryboardVideoModelFuncCodes,
  skipsStoryboardImageGeneration
} from '@/utils/creationModeUiRules'
import { userModelListByFuncCodes } from '@/utils/businessApi'
import { pickFirstNonEmptyModelPool, uniqueTrimmedCodes } from '@/utils/modelListByFuncBatch'
import { parseModelCapability } from '@/utils/modelCapability'
import {
  readRecommendedDurationSeconds,
  resolveVideoDurationOption
} from '@/utils/resolveVideoDurationOption'
import { fetchUserStoryboardDetailOnce } from '@/utils/storyboardDetailOnce'
import {
  resolvePairedStoryboardImageUrl,
  STUDIO_FLOW_GENERATE_VIDEO_EVENT
} from '@/utils/studio/studioFlowStoryboardMedia'
import { resolveStudioComposerModelFromNode } from '@/utils/studio/studioComposerDefaultModel'
import { buildStudioStoryboardVideoSubmission } from '@/utils/studio/studioComposerSubmission'
import { useStudioFlowNodeTask } from './useStudioFlowNodeTask'

export function useStudioFlowStoryboardVideoActions(node: StudioCanvasNode) {
  const projectId = useCreationStore((state) => state.currentProjectId)
  const episodeId = useCreationStore((state) => state.currentEpisodeId)
  const creationMode = useCreationStore((state) => state.formData.globalSetting.creationMode)
  const nodes = useStudioUiStore((state) => state.nodes)
  const [modelsLoading, setModelsLoading] = useState(true)
  const [models, setModels] = useState<UserModelListItem[]>([])
  const { runOnNode } = useStudioFlowNodeTask(node.id, node.data.title)
  const storyboardId = Number(node.data.flowBinding?.serverId ?? node.data.storyboardId)
  const storyboardImageUrl = useMemo(
    () => resolvePairedStoryboardImageUrl(node, nodes),
    [node, nodes]
  )
  const mode = normalizeCreationMode(creationMode)
  const needsImage = !skipsStoryboardImageGeneration(mode)

  useEffect(() => {
    let active = true
    const pid = Number(projectId)
    const eid = Number(episodeId)
    const codes = uniqueTrimmedCodes(resolveBatchStoryboardVideoModelFuncCodes(mode))
    void userModelListByFuncCodes(
      codes,
      Number.isFinite(pid) && pid > 0
        ? { projectId: pid, ...(Number.isFinite(eid) && eid >= 0 ? { episodeId: eid } : {}) }
        : undefined
    )
      .then((groups) => {
        if (!active) return
        setModels(pickFirstNonEmptyModelPool(groups, codes))
      })
      .catch(() => { if (active) setModels([]) })
      .finally(() => { if (active) setModelsLoading(false) })
    return () => { active = false }
  }, [episodeId, mode, projectId])

  const submitDisabledReason = useMemo(() => {
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return '未绑定服务端分镜视频'
    if (modelsLoading) return '正在读取视频模型池'
    if (!models.length) return '视频模型池暂无可用模型'
    if (needsImage && !storyboardImageUrl) return '请先生成分镜图，再生成视频'
    return null
  }, [models.length, modelsLoading, needsImage, storyboardId, storyboardImageUrl])

  const resolveSubmission = useCallback((prompt: string) => {
    if (submitDisabledReason) return null
    const latestNode = useStudioUiStore.getState().nodes.find((item) => item.id === node.id) ?? node
    const model = resolveStudioComposerModelFromNode(models, latestNode) ?? models[0]
    if (!model) return null
    return buildStudioStoryboardVideoSubmission({
      node: latestNode,
      model,
      storyboardId,
      prompt,
      creationMode: mode,
      needsImage,
      storyboardImageUrl
    })
  }, [mode, models, needsImage, node, storyboardId, storyboardImageUrl, submitDisabledReason])

  const resolveBillingRequest = useCallback(
    (prompt: string) => resolveSubmission(prompt)?.billingRequest ?? null,
    [resolveSubmission]
  )

  const resolveSubmissionForGenerate = useCallback(async (prompt: string) => {
    const latestNode = useStudioUiStore.getState().nodes.find((item) => item.id === node.id) ?? node
    const model = resolveStudioComposerModelFromNode(models, latestNode) ?? models[0]
    if (!model) return null

    let recommendedDurationSeconds = latestNode.data.recommendedDurationSeconds ?? null
    let recommendedDurationSource = latestNode.data.recommendedDurationSource
    let recommendedDurationDescription = latestNode.data.recommendedDurationDescription
    try {
      const detail = await fetchUserStoryboardDetailOnce(storyboardId, { force: true })
      recommendedDurationSeconds = readRecommendedDurationSeconds(detail)
      recommendedDurationSource = detail.recommendedDurationSource
      recommendedDurationDescription = detail.recommendedDurationDescription
    } catch {
      // 详情暂不可用时沿用节点已加载的推荐值或当前模型时长。
    }

    const snapshot = parseModelCapability(model)
    const duration = recommendedDurationSeconds == null
      ? latestNode.data.generationOptions?.duration ?? snapshot.defaultDurationSeconds
      : resolveVideoDurationOption({
          recommendedDurationSeconds,
          durationOptions: snapshot.durationOptions,
          defaultDurationSeconds: snapshot.defaultDurationSeconds
        })
    const effectiveNode: StudioCanvasNode = {
      ...latestNode,
      data: {
        ...latestNode.data,
        recommendedDurationSeconds,
        recommendedDurationSource,
        recommendedDurationDescription,
        generationOptions: {
          ...latestNode.data.generationOptions,
          duration
        }
      }
    }
    useStudioUiStore.getState().updateNodeData(node.id, {
      recommendedDurationSeconds,
      recommendedDurationSource,
      recommendedDurationDescription,
      generationOptions: effectiveNode.data.generationOptions
    })
    return buildStudioStoryboardVideoSubmission({
      node: effectiveNode,
      model,
      storyboardId,
      prompt,
      creationMode: mode,
      needsImage,
      storyboardImageUrl
    })
  }, [mode, models, needsImage, node, storyboardId, storyboardImageUrl])

  const generate = useCallback(async (prompt: string) => {
    if (submitDisabledReason) {
      message.warning(submitDisabledReason)
      return false
    }
    const submission = await resolveSubmissionForGenerate(prompt)
    if (!submission) return false
    return runOnNode({
      label: '分镜视频',
      mediaKind: 'video',
      execute: async (callbacks) => {
        const result = submission.kind === 'grid'
          ? await runStoryboardGridVideoGenerateTask({ body: submission.body, ...callbacks })
          : submission.kind === 'image'
            ? await runStoryboardImageVideoGenerateTask({
              body: submission.body,
              ...callbacks
            })
            : await runStoryboardMultiVideoGenerateTask({ body: submission.body, ...callbacks })
        if (!result.ok) return result
        const data = result.data as { videoUrl?: string; finalVideoUrl?: string } | undefined
        return {
          ok: true,
          videoUrl: String(data?.videoUrl || data?.finalVideoUrl || '').trim() || undefined
        }
      }
    })
  }, [
    resolveSubmissionForGenerate,
    runOnNode,
    submitDisabledReason
  ])

  useEffect(() => {
    const handleGenerate = (event: Event) => {
      const nodeId = String((event as CustomEvent<{ nodeId?: string }>).detail?.nodeId ?? '')
      if (nodeId !== node.id) return
      void generate(node.data.prompt)
    }
    window.addEventListener(STUDIO_FLOW_GENERATE_VIDEO_EVENT, handleGenerate)
    return () => window.removeEventListener(STUDIO_FLOW_GENERATE_VIDEO_EVENT, handleGenerate)
  }, [generate, node.data.prompt, node.id])

  return { models, modelsLoading, submitDisabledReason, resolveBillingRequest, generate }
}

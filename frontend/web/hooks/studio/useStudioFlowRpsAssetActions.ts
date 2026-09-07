'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { message } from 'antd'
import { runEditImageTask } from '@/hooks/useEditImageTask'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import type { UserModelListItem } from '@/types/business-api'
import { AI_MODEL_FUNC_CODE } from '@/utils/aiModelFuncCodes'
import { userModelListByFuncCodes } from '@/utils/businessApi'
import { modelsFromListByFuncGroups, uniqueTrimmedCodes } from '@/utils/modelListByFuncBatch'
import { resolveStudioComposerModelFromNode } from '@/utils/studio/studioComposerDefaultModel'
import { buildStudioRpsImageSubmission } from '@/utils/studio/studioComposerSubmission'
import { useStudioFlowNodeTask } from './useStudioFlowNodeTask'

export function useStudioFlowRpsAssetActions(node: StudioCanvasNode) {
  const projectId = useCreationStore((state) => state.currentProjectId)
  const episodeId = useCreationStore((state) => state.currentEpisodeId)
  const [modelsLoading, setModelsLoading] = useState(true)
  const [models, setModels] = useState<UserModelListItem[]>([])
  const { runOnNode } = useStudioFlowNodeTask(node.id, node.data.title)
  const formId = node.data.flowAssetForms?.[0]?.formId

  useEffect(() => {
    let active = true
    const pid = Number(projectId)
    const eid = Number(episodeId)
    void userModelListByFuncCodes(uniqueTrimmedCodes([AI_MODEL_FUNC_CODE.IMAGE_EDIT]), Number.isFinite(pid) && pid > 0
      ? { projectId: pid, ...(Number.isFinite(eid) && eid >= 0 ? { episodeId: eid } : {}) }
      : undefined)
      .then((groups) => {
        if (!active) return
        setModels(modelsFromListByFuncGroups(groups, AI_MODEL_FUNC_CODE.IMAGE_EDIT))
      })
      .catch(() => { if (active) setModels([]) })
      .finally(() => { if (active) setModelsLoading(false) })
    return () => { active = false }
  }, [episodeId, projectId])

  const submitDisabledReason = useMemo(() => {
    if (!formId) return '素材形态信息缺失，请打开高级编辑补充'
    if (modelsLoading) return '正在读取素材生图模型池'
    if (!models.length) return '素材生图模型池暂无可用模型'
    return null
  }, [formId, models.length, modelsLoading])

  const resolveSubmission = useCallback((prompt: string) => {
    if (submitDisabledReason || !formId) return null
    const latestNode = useStudioUiStore.getState().nodes.find((item) => item.id === node.id) ?? node
    const model = resolveStudioComposerModelFromNode(models, latestNode) ?? models[0]
    if (!model) return null
    return buildStudioRpsImageSubmission({ node: latestNode, model, formId, prompt })
  }, [formId, models, node, submitDisabledReason])

  const resolveBillingRequest = useCallback(
    (prompt: string) => resolveSubmission(prompt)?.billingRequest ?? null,
    [resolveSubmission]
  )

  const generate = useCallback(async (prompt: string) => {
    if (submitDisabledReason || !formId) {
      message.warning(submitDisabledReason || '无法提交素材生图')
      return false
    }
    const submission = resolveSubmission(prompt)
    if (!submission) return false
    return runOnNode({
      label: '素材生图',
      mediaKind: 'image',
      execute: (callbacks) => runEditImageTask({
        ...submission.body,
        ...callbacks
      }).then((result) => {
        if (!result.ok) return result
        const item = result.items.at(-1)
        return {
          ok: true,
          imageUrl: item?.imageUrl ?? null,
          recordId: item?.imageId ?? null,
          items: result.items.map((entry) => ({
            imageUrl: entry.imageUrl,
            recordId: entry.imageId,
            imageId: entry.imageId
          }))
        }
      })
    })
  }, [formId, resolveSubmission, runOnNode, submitDisabledReason])

  return { models, modelsLoading, submitDisabledReason, resolveBillingRequest, generate }
}

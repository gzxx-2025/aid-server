'use client'

import type { StudioCanvasNode } from '@/types/studio'
import { useStudioFlowStoryboardImageActions } from '@/hooks/studio/useStudioFlowStoryboardImageActions'
import { useStudioUiStore } from '@/stores/studioUi'
import { useCreationStore } from '@/stores/creation'
import { message } from 'antd'
import { StudioComposerFrame } from '@/components/studio/composer/StudioComposerFrame'
import { StudioModelPicker } from '@/components/studio/composer/StudioModelPicker'
import { StudioParamsPopover } from '@/components/studio/composer/StudioParamsPopover'
import { skipsStoryboardImageGeneration } from '@/utils/creationModeUiRules'
import { userStoryboardUpdate } from '@/utils/businessApi'
import { isStudioFlowStoryboardStillNode } from '@/utils/studio/studioFlowStoryboardMedia'
import { resolveStudioComposerGenSceneCode } from '@/utils/studio/studioComposerDefaultModel'
import { useStudioFlowRuntime } from '../StudioFlowRuntimeProvider'
import { StudioComposerChip } from '@/components/studio/composer/StudioComposerChip'
import { StudioFlowComposerHeader } from './StudioFlowComposerShared'
import { runStudioRequestSingleFlight } from '@/utils/studio/studioRequestSingleFlight'

export function StudioFlowStoryboardComposer({ node }: { node: StudioCanvasNode }) {
  if (isStudioFlowStoryboardStillNode(node.data)) {
    return <StudioFlowStoryboardImageComposer node={node} />
  }
  return <StudioFlowStoryboardScriptComposer node={node} />
}

function StudioFlowStoryboardScriptComposer({ node }: { node: StudioCanvasNode }) {
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const runtime = useStudioFlowRuntime()
  const creationMode = useCreationStore((state) => state.formData.globalSetting.creationMode)
  const skipImage = skipsStoryboardImageGeneration(creationMode)

  const saveScript = async (prompt: string) => {
    const storyboardId = Number(node.data.flowBinding?.serverId ?? node.data.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) {
      message.warning('未绑定服务端分镜')
      return false
    }
    const scopeKey = useStudioUiStore.getState().scopeKey
    return runStudioRequestSingleFlight(`${scopeKey}:${node.id}:save-storyboard-script`, async () => {
      try {
        await userStoryboardUpdate({ id: storyboardId, storyScript: prompt })
        updateNodeData(node.id, {
          prompt,
          resultSummary: prompt,
          status: 'success'
        }, true)
        const store = useCreationStore.getState()
        const panels = store.formData.storyboardScript.panels.map((panel) =>
          Number(panel.id) === storyboardId
            ? { ...panel, scriptContent: prompt }
            : panel
        )
        store.updateFormData({
          storyboardScript: { ...store.formData.storyboardScript, panels }
        })
        await runtime?.refresh({ taskIntent: 'read' })
        message.success('分镜脚本已保存')
        return true
      } catch (error) {
        message.error(error instanceof Error ? error.message : '分镜脚本保存失败')
        return false
      }
    })
  }

  return (
    <div className="studio-flow-composer nodrag nowheel">
      <StudioFlowComposerHeader
        node={node}
        subtitle={skipImage ? '分镜脚本 · 手动编辑后保存' : '分镜脚本 · 可编辑，出图请选中右侧分镜图节点'}
      />
      <StudioComposerFrame
        node={node}
        placeholder="编辑分镜脚本，Enter 保存到当前镜头"
        submitDisabledReason={null}
        onSubmit={saveScript}
      />
    </div>
  )
}

function StudioFlowStoryboardImageComposer({ node }: { node: StudioCanvasNode }) {
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const actions = useStudioFlowStoryboardImageActions(node)
  const sceneCode = resolveStudioComposerGenSceneCode(node.data)

  return (
    <div className="studio-flow-composer nodrag nowheel">
      <StudioFlowComposerHeader
        node={node}
        subtitle="分镜图 · 选中后直接出图，不经 AI 编辑"
      />
      <StudioComposerFrame
        node={node}
        placeholder="确认分镜图提示词后发送，即可生成当前镜头画面"
        submitDisabledReason={actions.submitDisabledReason}
        resolveBillingRequest={actions.resolveBillingRequest}
        onSubmit={actions.regenerate}
        bottomLeft={(
          <>
            <StudioModelPicker
              value={node.data.model}
              mediaKind="image"
              sceneCode={sceneCode}
              options={actions.storyboardModels}
              onChange={(model, item) => updateNodeData(node.id, {
                model,
                modelName: item.modelName,
                modelCostCredits: item.costCredits,
                modelIsFree: item.isFree
              })}
            />
            <StudioParamsPopover
              mode="image"
              value={node.data.generationOptions ?? {}}
              modelCode={node.data.model}
              mediaKind="image"
              sceneCode={sceneCode}
              modelOptions={actions.storyboardModels}
              onChange={(generationOptions) => updateNodeData(node.id, { generationOptions })}
            />
            <StudioComposerChip
              label={node.data.mediaUrl ? '重新出图' : '生成分镜图'}
              arrow={false}
              disabled={Boolean(actions.submitDisabledReason)}
              title={actions.submitDisabledReason ?? undefined}
              onClick={() => void actions.regenerate(node.data.prompt)}
            />
          </>
        )}
      />
    </div>
  )
}

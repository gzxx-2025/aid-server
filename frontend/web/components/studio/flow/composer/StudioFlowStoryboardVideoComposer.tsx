'use client'

import type { StudioCanvasNode } from '@/types/studio'
import { useStudioFlowStoryboardVideoActions } from '@/hooks/studio/useStudioFlowStoryboardVideoActions'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import { StudioComposerFrame } from '@/components/studio/composer/StudioComposerFrame'
import { StudioModelPicker } from '@/components/studio/composer/StudioModelPicker'
import { StudioParamsPopover } from '@/components/studio/composer/StudioParamsPopover'
import { resolveStudioComposerGenSceneCode } from '@/utils/studio/studioComposerDefaultModel'
import { useStudioStoryboardRecommendedDuration } from '@/hooks/studio/useStudioStoryboardRecommendedDuration'
import { StudioFlowComposerHeader } from './StudioFlowComposerShared'

export function StudioFlowStoryboardVideoComposer({ node }: { node: StudioCanvasNode }) {
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const creationMode = useCreationStore((state) => state.formData.globalSetting.creationMode)
  const actions = useStudioFlowStoryboardVideoActions(node)
  const sceneCode = resolveStudioComposerGenSceneCode(node.data, creationMode)
  const recommendedDurationSeconds = useStudioStoryboardRecommendedDuration(node)

  return (
    <div className="studio-flow-composer nodrag nowheel">
      <StudioFlowComposerHeader node={node} subtitle="分镜视频 · 选中后直接生成，不经 AI 编辑" />
      <StudioComposerFrame
        node={node}
        placeholder="描述镜头运动、节奏与画面变化，Enter 生成视频"
        submitDisabledReason={actions.submitDisabledReason}
        resolveBillingRequest={actions.resolveBillingRequest}
        onSubmit={actions.generate}
        bottomLeft={(
          <>
            <StudioModelPicker
              value={node.data.model}
              mediaKind="video"
              sceneCode={sceneCode}
              options={actions.models}
              onChange={(model, item) => updateNodeData(node.id, {
                model,
                modelName: item.modelName,
                modelCostCredits: item.costCredits,
                modelIsFree: item.isFree
              })}
            />
            <StudioParamsPopover
              mode="video"
              value={node.data.generationOptions ?? {}}
              modelCode={node.data.model}
              mediaKind="video"
              sceneCode={sceneCode}
              modelOptions={actions.models}
              recommendedDurationSeconds={recommendedDurationSeconds}
              onChange={(generationOptions) => updateNodeData(node.id, { generationOptions })}
            />
          </>
        )}
      />
    </div>
  )
}

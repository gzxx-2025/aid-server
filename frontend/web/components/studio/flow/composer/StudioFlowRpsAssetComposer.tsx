'use client'

import type { StudioCanvasNode } from '@/types/studio'
import { useStudioFlowRpsAssetActions } from '@/hooks/studio/useStudioFlowRpsAssetActions'
import { useStudioUiStore } from '@/stores/studioUi'
import { StudioComposerFrame } from '@/components/studio/composer/StudioComposerFrame'
import { StudioModelPicker } from '@/components/studio/composer/StudioModelPicker'
import { StudioParamsPopover } from '@/components/studio/composer/StudioParamsPopover'
import { resolveStudioComposerGenSceneCode } from '@/utils/studio/studioComposerDefaultModel'
import { StudioFlowComposerHeader } from './StudioFlowComposerShared'

export function StudioFlowRpsAssetComposer({ node }: { node: StudioCanvasNode }) {
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const actions = useStudioFlowRpsAssetActions(node)
  const assetType = node.data.flowBinding?.assetType
  const sceneCode = resolveStudioComposerGenSceneCode(node.data)

  return (
    <div className="studio-flow-composer nodrag nowheel">
      <StudioFlowComposerHeader node={node} subtitle={assetType ? `素材 · ${assetType}` : '素材'} />
      <StudioComposerFrame
        node={node}
        placeholder="描述素材外观、风格与构图"
        submitDisabledReason={actions.submitDisabledReason}
        resolveBillingRequest={actions.resolveBillingRequest}
        onSubmit={actions.generate}
        bottomLeft={(
          <>
            <StudioModelPicker
              value={node.data.model}
              mediaKind="image"
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
              mode="image"
              value={node.data.generationOptions ?? {}}
              modelCode={node.data.model}
              mediaKind="image"
              sceneCode={sceneCode}
              modelOptions={actions.models}
              onChange={(generationOptions) => updateNodeData(node.id, { generationOptions })}
            />
          </>
        )}
      />
    </div>
  )
}

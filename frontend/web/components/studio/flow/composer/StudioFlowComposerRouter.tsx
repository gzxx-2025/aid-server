'use client'

import type { StudioCanvasNode } from '@/types/studio'
import { StudioFlowDubbingComposer } from './StudioFlowDubbingComposer'
import { StudioFlowGlobalSettingComposer } from './StudioFlowGlobalSettingComposer'
import { StudioFlowPreviewComposer } from './StudioFlowPreviewComposer'
import { StudioFlowRpsAssetComposer } from './StudioFlowRpsAssetComposer'
import { StudioFlowStepComposer } from './StudioFlowStepComposer'
import { StudioFlowStoryScriptComposer } from './StudioFlowStoryScriptComposer'
import { StudioFlowStoryboardComposer } from './StudioFlowStoryboardComposer'
import { StudioFlowStoryboardVideoComposer } from './StudioFlowStoryboardVideoComposer'

export function StudioFlowComposerRouter({ node }: { node: StudioCanvasNode }) {
  const binding = node.data.flowBinding
  if (!binding) return null

  if (binding.role === 'step') {
    if (binding.step === 'global-setting') return <StudioFlowGlobalSettingComposer node={node} />
    if (binding.step === 'story-script') return <StudioFlowStoryScriptComposer node={node} />
    if (binding.step === 'preview') return <StudioFlowPreviewComposer node={node} />
    return <StudioFlowStepComposer node={node} step={binding.step} />
  }

  switch (binding.entityType) {
    case 'story_script':
      return <StudioFlowStoryScriptComposer node={node} />
    case 'rps_asset':
      return <StudioFlowRpsAssetComposer node={node} />
    case 'storyboard':
      return <StudioFlowStoryboardComposer node={node} />
    case 'storyboard_video':
      return <StudioFlowStoryboardVideoComposer node={node} />
    case 'dubbing':
      return <StudioFlowDubbingComposer node={node} />
    case 'preview':
      return <StudioFlowPreviewComposer node={node} />
    default:
      return <StudioFlowStepComposer node={node} step={binding.step} />
  }
}

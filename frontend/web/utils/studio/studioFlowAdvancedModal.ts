import type { CreateFlowStepModalKind } from '@/utils/createFlowStepModalIntent'
import type { StudioFlowStep } from '@/types/studio'

export const STUDIO_FLOW_ADVANCED_MODAL_EVENT = 'studio-flow-advanced-modal'

export type StudioFlowAdvancedModalDetail =
  | { kind: CreateFlowStepModalKind; panelIndex: number }
  | { kind: 'scene-image'; assetType: 'scene' | 'character' | 'prop'; assetId: number }
  | { kind: 'project-gen-config' }
  | { kind: 'global-setting' }
  | { kind: 'story-script' }

export function requestStudioFlowAdvancedModal(detail: StudioFlowAdvancedModalDetail): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent<StudioFlowAdvancedModalDetail>(STUDIO_FLOW_ADVANCED_MODAL_EVENT, {
    detail
  }))
}

export function requestStudioFlowStepPage(step: StudioFlowStep): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent('studio-flow-open-step-page', { detail: { step } }))
}

import type { CreationStep, StoryboardPanel } from '~/types'
import { getPersistedStoryboardScriptPanels } from '~/utils/storyboardPanelMap'

export const STORYBOARD_SCRIPT_ADVANCE_BLOCKED_TOOLTIP =
  '请等待分镜脚本生成成功并展示内容后，再进入视频生成'

const STORYBOARD_SCRIPT_STEP_INDEX = 3

export type StoryboardFlowStepVisualStatus = 'completed' | 'pending' | 'disabled' | 'active'

export function hasPersistedStoryboardScriptContent(panels: StoryboardPanel[]): boolean {
  return getPersistedStoryboardScriptPanels(panels).length > 0
}

export function isStoryboardScriptAdvanceBlocked(payload: {
  currentStep: CreationStep
  storyboardListSyncReady: boolean
  panels: StoryboardPanel[]
}): boolean {
  if (payload.currentStep !== 'storyboard-script') return false
  return isStoryboardFlowSuccessorLocked(payload)
}

export function isStoryboardFlowSuccessorLocked(payload: {
  storyboardListSyncReady: boolean
  panels: StoryboardPanel[]
}): boolean {
  if (!payload.storyboardListSyncReady) return true
  return !hasPersistedStoryboardScriptContent(payload.panels)
}

export function isStoryboardFlowSuccessorStep(index: number): boolean {
  return index > STORYBOARD_SCRIPT_STEP_INDEX
}

export function applyStoryboardFlowStepStatusGate(
  statuses: StoryboardFlowStepVisualStatus[],
  blocked: boolean
): StoryboardFlowStepVisualStatus[] {
  if (!blocked) return statuses
  return statuses.map((status, index) =>
    isStoryboardFlowSuccessorStep(index) ? 'disabled' : status
  )
}

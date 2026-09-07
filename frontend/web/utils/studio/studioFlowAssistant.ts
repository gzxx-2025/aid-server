import type { GlobalSettingData } from '@/types'
import type { StoryScriptAgentProjectState } from '@/utils/storyScriptAgentState'
import {
  isCreationModeDisabledForScriptType,
  isScriptTypeLockedToPlot
} from '@/utils/creationModeUiRules'

export function studioFlowConversationScopeKey(projectId: number | null, episodeId: number): string {
  return `flow:${projectId ?? 'none'}:episode-${episodeId}`
}

export function studioFlowProjectConfigSignature(setting: GlobalSettingData): string {
  return [
    setting.selectedStyle?.id ?? '',
    setting.selectedStyle?.name ?? '',
    setting.creationMode ?? '',
    setting.aspectRatio ?? '',
    setting.scriptType ?? '',
    setting.modelStrategy ?? ''
  ].join('|')
}

export function isStudioFlowProjectConfigCompatible(setting: GlobalSettingData): boolean {
  if (
    !setting.selectedStyle ||
    !setting.creationMode ||
    !setting.aspectRatio ||
    !setting.scriptType ||
    !setting.modelStrategy
  ) return false
  if (isCreationModeDisabledForScriptType(setting.creationMode, setting.scriptType)) return false
  return !isScriptTypeLockedToPlot(setting.creationMode) || setting.scriptType === 'plot'
}

export function hasBlockingStoryScriptAgentHandoff(
  state: StoryScriptAgentProjectState | null
): boolean {
  return Boolean(
    state?.activeRun ||
    String(state?.pendingPrompt || '').trim()
  )
}

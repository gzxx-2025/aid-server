import { shouldPassStoryboardVideoDuration } from '~/utils/creationModeUiRules'
import { resolveStoryboardVideoPromptSubmitAgentCode } from '~/utils/extractAgentBiz'
import {
STORYBOARD_GEN_CONFIG_SCENE_CODES,
resolveStoryboardGenConfigLlmFields
} from '~/utils/projectGenConfig'
import { sanitizeStoryboardPromptModelCode } from '~/utils/storyboardPromptGenerateFlow'
import type { StoryboardVideoBatchCore } from '~/utils/storyboardVideoBatchFollowCore'
import type { StoryboardVideoBatchState } from '~/utils/storyboardVideoBatchShared'
import { formatVideoResolutionForApi } from '~/utils/storyboardVideoGenerateParams'
export function createStoryboardVideoPromptSubmitFields(
  state: StoryboardVideoBatchState,
  getStore: StoryboardVideoBatchCore['getStore']
) {
  function resolvePromptAgentCode(): string {
    return resolveStoryboardVideoPromptSubmitAgentCode(
      'video_prompt',
      getStore().storyboardVideoGenerateSettings.agentId
    )
  }

  function resolvePromptModelCode(): string {
    return sanitizeStoryboardPromptModelCode(
      getStore().storyboardVideoGenerateSettings.videoPromptModelCode
    )
  }

  async function buildVideoPromptSubmitFields(projectId: number) {
    return resolveStoryboardGenConfigLlmFields(
      projectId,
      STORYBOARD_GEN_CONFIG_SCENE_CODES.videoPrompt,
      state.manualPromptAgentModelPick,
      resolvePromptAgentCode(),
      resolvePromptModelCode()
    )
  }

  function buildVideoWithPromptPromptOverrideFields() {
    if (!state.manualPromptAgentModelPick) return {}
    const agentCode = String(getStore().storyboardVideoGenerateSettings.agentId || '').trim()
    const modelCode = resolvePromptModelCode()
    return {
      ...(agentCode ? { agentCode } : {}),
      ...(modelCode ? { modelCode } : {})
    }
  }

  function buildVideoGenSubmitFields(options?: { genDurationSeconds?: number | null }) {
    const settings = getStore().storyboardVideoGenerateSettings
    const modelName = String(settings.videoModel || '').trim()
    const passDuration = shouldPassStoryboardVideoDuration(
      getStore().formData.globalSetting?.creationMode
    )
    const rawDuration = Number(options?.genDurationSeconds ?? settings.durationSeconds)
    const durationSeconds = passDuration && Number.isInteger(rawDuration) && rawDuration > 0
      ? rawDuration
      : undefined
    const resolution = formatVideoResolutionForApi(settings.resolution)
    return {
      ...(state.manualVideoModelPick && modelName ? { genModelName: modelName } : {}),
      ...(settings.aspectRatio ? { genAspectRatio: settings.aspectRatio } : {}),
      ...(durationSeconds ? { genDurationSeconds: durationSeconds } : {}),
      ...(resolution ? { genResolution: resolution } : {}),
      genGenerateAudio: settings.soundEffects === 'with-sound'
    }
  }

  return {
    buildVideoPromptSubmitFields,
    buildVideoWithPromptPromptOverrideFields,
    buildVideoGenSubmitFields
  }
}

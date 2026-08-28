import type { StoryboardPanel } from '~/types'
import type { StoryboardGenerateScriptRequest } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import {
  STORYBOARD_GEN_CONFIG_SCENE_CODES,
  resolveStoryboardGenConfigLlmFields
} from '~/utils/projectGenConfig'
import { hasPersistedStoryboards } from '~/utils/storyboardScriptBatchTrack'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'

type StoryboardScriptCreationStore = Parameters<typeof resolveStoryScriptSaveContext>[0] & {
  storyboardGenerateSettings: {
    agentId?: string
    modelCode?: string
    shotDensity?: string
  }
}

interface BuildStoryboardScriptSubmitBodyOptions {
  store: StoryboardScriptCreationStore
  route: RouteLikeLocation
  currentPanels?: StoryboardPanel[]
  sceneIds?: number[]
  manualAgentModelPick?: boolean
  overwrite?: boolean
}

/** 分镜脚本报价与正式提交共用的最终请求体（含项目配置解析）。 */
export async function buildStoryboardScriptSubmitBody(
  options: BuildStoryboardScriptSubmitBodyOptions
): Promise<StoryboardGenerateScriptRequest | null> {
  const context = await resolveStoryScriptSaveContext(options.store, options.route)
  if (!context) return null

  const sceneIds = (options.sceneIds ?? []).filter(
    (id) => Number.isFinite(id) && id > 0
  )
  const selective = sceneIds.length > 0
  const settings = options.store.storyboardGenerateSettings
  const llmFields = await resolveStoryboardGenConfigLlmFields(
    context.projectId,
    STORYBOARD_GEN_CONFIG_SCENE_CODES.script,
    options.manualAgentModelPick === true,
    String(settings.agentId || '').trim(),
    String(settings.modelCode || '').trim()
  )
  const mode = String(settings.shotDensity || '标准模式').trim()
  const overwrite =
    options.overwrite ??
    (selective ? true : hasPersistedStoryboards(options.currentPanels ?? []))

  return {
    projectId: context.projectId,
    episodeId: context.episodeId,
    ...(selective ? { sceneIds } : {}),
    ...llmFields,
    ...(mode ? { mode } : {}),
    overwrite
  }
}

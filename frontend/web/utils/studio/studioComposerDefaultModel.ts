import type { UserModelListItem } from '@/types/business-api'
import type { StudioNodeData } from '@/types/studio'
import {
  resolveBatchStoryboardVideoAgentBizCategories
} from '@/utils/creationModeUiRules'
import { fetchAgentDefaultModelCode } from '@/utils/extractAgentBiz'
import {
  FORM_IMAGE_SCENE_CODE_BY_TYPE,
  resolveProjectGenModelCode,
  STORYBOARD_GEN_CONFIG_SCENE_CODES
} from '@/utils/projectGenConfig'
import { isStudioFlowStoryboardStillNode } from '@/utils/studio/studioFlowStoryboardMedia'

/** 节点对应的生成配置 / 出片智能体 sceneCode，用于默认模型。 */
export function resolveStudioComposerGenSceneCode(
  data: Pick<StudioNodeData, 'kind' | 'mediaKind' | 'imagePurpose' | 'flowBinding'>,
  creationMode?: string | null
): string {
  const binding = data.flowBinding
  if (isStudioFlowStoryboardStillNode(data)) {
    return STORYBOARD_GEN_CONFIG_SCENE_CODES.image
  }
  if (
    binding?.step === 'storyboard-video'
    || (data.kind === 'video' && binding?.entityType === 'storyboard')
  ) {
    return resolveBatchStoryboardVideoAgentBizCategories(creationMode)[0] || ''
  }
  const assetType = binding?.assetType
  if (assetType === 'character' || assetType === 'scene' || assetType === 'prop') {
    return FORM_IMAGE_SCENE_CODE_BY_TYPE[assetType] || ''
  }
  if (data.kind === 'video' || data.mediaKind === 'video') {
    return resolveBatchStoryboardVideoAgentBizCategories(creationMode)[0] || ''
  }
  if (data.kind === 'image' || data.mediaKind === 'image') {
    return STORYBOARD_GEN_CONFIG_SCENE_CODES.image
  }
  if (
    data.kind === 'text'
    || data.kind === 'storyboard_script'
    || data.mediaKind === 'text'
  ) {
    return STORYBOARD_GEN_CONFIG_SCENE_CODES.script
  }
  return ''
}

/** 优先匹配 preferredCodes，否则取池内第一项。 */
export function pickStudioComposerDefaultModel(
  options: UserModelListItem[],
  preferredCodes: Array<string | null | undefined> = []
): UserModelListItem | undefined {
  if (!options.length) return undefined
  for (const raw of preferredCodes) {
    const code = String(raw || '').trim()
    if (!code) continue
    const hit = options.find((item) => String(item.modelCode || '').trim() === code)
    if (hit) return hit
  }
  return options[0]
}

/** 当前值是否落在真实模型池中（占位假名不算）。 */
export function isStudioComposerModelInOptions(
  options: UserModelListItem[],
  value?: string | null
): boolean {
  const code = String(value || '').trim()
  if (!code || !options.length) return false
  return options.some((item) => String(item.modelCode || '').trim() === code)
}

/** 生成配置 modelCode → 智能体默认 modelCode。 */
export async function resolveStudioPreferredModelCode(options: {
  projectId?: number | null
  episodeId?: number | null
  sceneCode?: string | null
}): Promise<string> {
  const sceneCode = String(options.sceneCode || '').trim()
  const projectId = Number(options.projectId)
  if (!sceneCode || !Number.isFinite(projectId) || projectId <= 0) return ''

  const fromConfig = await resolveProjectGenModelCode(projectId, sceneCode)
  if (fromConfig) return fromConfig

  const episodeId = Number(options.episodeId)
  return fetchAgentDefaultModelCode({
    bizCategoryCode: sceneCode,
    projectId,
    ...(Number.isFinite(episodeId) && episodeId >= 0 ? { episodeId } : {})
  })
}

export function resolveStudioComposerModelFromNode(
  options: UserModelListItem[],
  node: { data: Pick<StudioNodeData, 'model'> },
  preferredCodes: Array<string | null | undefined> = []
): UserModelListItem | undefined {
  const current = String(node.data.model || '').trim()
  if (current && isStudioComposerModelInOptions(options, current)) {
    return options.find((item) => String(item.modelCode || '').trim() === current)
  }
  return pickStudioComposerDefaultModel(options, preferredCodes)
}

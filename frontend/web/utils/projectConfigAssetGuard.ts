import { userAssetRpsList } from '@/utils/businessApi'

type FlowNodeLike = {
  data?: {
    flowBinding?: {
      kind?: unknown
      role?: unknown
      entityType?: unknown
      step?: unknown
    }
  }
}

export const PROJECT_CONFIG_ASSETS_LOCKED_HINT =
  '已生成场景、角色或道具，为避免现有内容与配置不一致，相关配置仅可查看；作品名称仍可修改。'

export const PROJECT_CONFIG_ASSETS_STYLE_LOCK_HINT =
  '已生成场景、角色或道具，画面风格仅可查看。'

export function hasLocalSceneCharacterPropAssets(
  sceneCharacter?: { scenes?: unknown[]; characters?: unknown[]; props?: unknown[] } | null
): boolean {
  return Boolean(
    (sceneCharacter?.scenes?.length ?? 0) > 0
    || (sceneCharacter?.characters?.length ?? 0) > 0
    || (sceneCharacter?.props?.length ?? 0) > 0
  )
}

export function hasFlowSceneCharacterPropNodes(
  nodes: FlowNodeLike[]
): boolean {
  return nodes.some((node) => {
    const binding = node.data?.flowBinding
    if (!binding || binding.role === 'step') return false
    if (binding.entityType === 'rps_asset') return true
    return binding.step === 'scene-character' && binding.role === 'item'
  })
}

function rpsListHasRows(result: { total?: number; rows?: unknown[] } | null | undefined): boolean {
  return Number(result?.total) > 0 || (Array.isArray(result?.rows) && result.rows.length > 0)
}

export async function hasRemoteSceneCharacterPropAssets(
  projectId: number,
  episodeId: number
): Promise<boolean> {
  if (!projectId) return false
  try {
    const [scenes, characters, props] = await Promise.all([
      userAssetRpsList({ projectId, episodeId, assetType: 'scene' }),
      userAssetRpsList({ projectId, episodeId, assetType: 'character' }),
      userAssetRpsList({ projectId, episodeId, assetType: 'prop' })
    ])
    return rpsListHasRows(scenes) || rpsListHasRows(characters) || rpsListHasRows(props)
  } catch {
    return false
  }
}

export function hasImmediateProjectConfigAssetLock(options?: {
  styleLocked?: boolean
  sceneCharacter?: { scenes?: unknown[]; characters?: unknown[]; props?: unknown[] } | null
  nodes?: FlowNodeLike[]
}): boolean {
  return Boolean(
    options?.styleLocked
    || hasLocalSceneCharacterPropAssets(options?.sceneCharacter)
    || (options?.nodes && hasFlowSceneCharacterPropNodes(options.nodes))
  )
}

export async function hasProjectSceneCharacterPropAssets(
  projectId: number,
  episodeId: number,
  options?: Parameters<typeof hasImmediateProjectConfigAssetLock>[0]
): Promise<boolean> {
  if (hasImmediateProjectConfigAssetLock(options)) return true
  return hasRemoteSceneCharacterPropAssets(projectId, episodeId)
}

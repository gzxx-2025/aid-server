import type { AssetCenterCategoryTreeVO } from '~/types/business-api'

function isCategoryTreeNode(node: AssetCenterCategoryTreeVO): boolean {
  const code = String(node.categoryCode ?? '').trim()
  return code.length > 0
}

export function findAssetCenterProject(
  tree: AssetCenterCategoryTreeVO[],
  projectId: number
): AssetCenterCategoryTreeVO | undefined {
  const pid = Number(projectId)
  if (!Number.isFinite(pid)) return undefined
  return tree.find((p) => Number(p.projectId) === pid)
}

export function findAssetCenterEpisode(
  projectNode: AssetCenterCategoryTreeVO | undefined,
  episodeId: number
): AssetCenterCategoryTreeVO | undefined {
  const eid = Number(episodeId)
  return projectNode?.children?.find((ep) => Number(ep.episodeId ?? 0) === eid)
}

/** 剧集节点展示名（与接口 VO 字段一致） */
export function episodeDisplayLabel(ep: AssetCenterCategoryTreeVO): string {
  return (
    ep.episodeTitle ||
    (ep.episodeNo != null ? `第${ep.episodeNo}集` : '电影')
  )
}

/** 分类子节点（过滤无 categoryCode 的占位节点） */
export function getEpisodeCategories(
  episodeNode: AssetCenterCategoryTreeVO | undefined
): AssetCenterCategoryTreeVO[] {
  return (episodeNode?.children ?? []).filter(isCategoryTreeNode)
}

/** 节点 assetCount；分类层用自身计数，剧集层汇总子分类 */
export function resolveNodeAssetCount(node: AssetCenterCategoryTreeVO): number | null {
  if (typeof node.assetCount === 'number') return node.assetCount
  const cats = getEpisodeCategories(node)
  if (!cats.length) return null
  let sum = 0
  let has = false
  for (const c of cats) {
    if (typeof c.assetCount === 'number') {
      sum += c.assetCount
      has = true
    }
  }
  return has ? sum : null
}

/** 当前作品：在分类树中定位项目 → 剧集节点（兼容项目下直接挂分类的两层结构） */
export function resolveCurrentEpisodeNode(
  tree: AssetCenterCategoryTreeVO[],
  projectId: number,
  episodeId: number
): AssetCenterCategoryTreeVO | undefined {
  const project = findAssetCenterProject(tree, projectId)
  const children = project?.children ?? []
  if (!children.length) return undefined

  // 电影等：分类直接挂在项目下
  if (children.some(isCategoryTreeNode)) {
    return {
      ...project!,
      episodeId: Number.isFinite(episodeId) ? episodeId : 0,
      children: children.filter(isCategoryTreeNode)
    }
  }

  let hit = findAssetCenterEpisode(project, episodeId)
  if (hit) return hit
  if (episodeId === 0 || episodeId == null) {
    hit =
      project!.children!.find((c) => Number(c.episodeId ?? 0) === 0) ??
      project!.children![0]
  }
  if (!hit && project!.children!.length === 1) {
    hit = project!.children![0]
  }
  return hit
}

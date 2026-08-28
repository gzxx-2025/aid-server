import type { UserProjectType } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import { fetchUserProjectDetailOnce } from '~/utils/userProjectDetailOnce'

type CreationLike = {
  currentProjectId: number | null
  currentEpisodeId: number | null
  currentProjectType: UserProjectType | null
  setCurrentProjectType: (t: UserProjectType | null) => void
}

/** 从 URL 或 Pinia 解析作品 ID（与 resolveStoryScriptSaveContext 一致：URL 优先） */
export function resolveProjectIdFromRouteAndStore(
  store: Pick<CreationLike, 'currentProjectId'>,
  route: RouteLikeLocation
): number | null {
  const routeProjectIdRaw = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
  const routeProjectId = Number.isFinite(routeProjectIdRaw) && routeProjectIdRaw > 0 ? routeProjectIdRaw : null
  const storePid = Number(store.currentProjectId)
  const storeProjectId = Number.isFinite(storePid) && storePid > 0 ? storePid : null
  return routeProjectId ?? storeProjectId
}

function parseRouteEpisodeId(route: RouteLikeLocation): number | null {
  const raw = route.query.episodeId
  if (raw === undefined || raw === '') return null
  const n = Number(raw)
  return Number.isFinite(n) && n >= 0 ? n : null
}

/**
 * 解析剧本保存/拉取所需的 projectId + episodeId（与 detailByProject 一致：电影 episodeId=0，剧集需有效集 ID）
 *
 * 切作品窗口期：route 已是新 projectId，store 仍可能残留上一作品的 projectType/episodeId。
 * 此时不得混用「新 projectId + 旧剧集 episodeId」，否则 detailByProject 会误带剧集 ID 报错。
 */
export async function resolveStoryScriptSaveContext(
  store: CreationLike,
  route: RouteLikeLocation
): Promise<{ projectId: number; episodeId: number } | null> {
  const projectId = resolveProjectIdFromRouteAndStore(store, route)
  if (!projectId) return null

  const storePid = Number(store.currentProjectId)
  const storeMatchesProject =
    Number.isFinite(storePid) && storePid > 0 && storePid === projectId

  // store 仍指向上一作品时，其 projectType 不可信，必须按当前 projectId 取权威类型
  let projectType: UserProjectType | null = storeMatchesProject ? store.currentProjectType : null
  if (!projectType) {
    try {
      const detail = await fetchUserProjectDetailOnce(projectId)
      projectType = detail.projectType
      // 仅当 store 已对齐到同一作品时回写，避免把新类型写进旧作品上下文
      if (Number(store.currentProjectId) === projectId) {
        store.setCurrentProjectType(projectType)
      }
    } catch {
      return null
    }
  }

  if (projectType === 'movie') {
    // 电影协议固定 episodeId=0，忽略 URL/store 残留的剧集 ID
    return { projectId, episodeId: 0 }
  }

  const routeEp = parseRouteEpisodeId(route)
  const storeEp =
    storeMatchesProject && store.currentEpisodeId != null && store.currentEpisodeId > 0
      ? store.currentEpisodeId
      : null
  const episodeId = routeEp != null && routeEp > 0 ? routeEp : storeEp
  if (episodeId == null || episodeId <= 0) return null

  return { projectId, episodeId }
}

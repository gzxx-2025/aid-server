import type { UserProjectType } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import { userEpisodeCreate,userEpisodeList } from '~/utils/businessApi'
import { fetchUserProjectDetailOnce } from '~/utils/userProjectDetailOnce'
type StoreSlice = {
  currentProjectId: number | null
  currentEpisodeId: number | null
  currentProjectType: UserProjectType | null
  setCurrentProjectType: (t: UserProjectType | null) => void
  setCurrentProjectContext: (p: { projectId?: number | null; episodeId?: number | null }) => void
}

function parseRouteEpisodeId(route: RouteLikeLocation): number | null {
  const raw = route.query.episodeId
  if (raw === undefined || raw === '') return null
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

/**
 * 保存剧本前解析 projectId + episodeId：剧集无分集则拉列表或创建「第1集」；电影 episodeId=0。
 */
export async function ensureScriptSaveContextForUpload(
  store: StoreSlice,
  route: RouteLikeLocation
): Promise<{ projectId: number; episodeId: number; projectType: UserProjectType } | null> {
  const routePid = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
  const projectId =
    store.currentProjectId ?? (Number.isFinite(routePid) && routePid > 0 ? routePid : null)
  if (!projectId) return null

  const storePid = Number(store.currentProjectId)
  const storeMatchesProject =
    Number.isFinite(storePid) && storePid > 0 && storePid === projectId

  // 切作品窗口期：勿沿用上一作品的 projectType / episodeId
  let projectType: UserProjectType | null = storeMatchesProject ? store.currentProjectType : null
  if (!projectType) {
    try {
      const detail = await fetchUserProjectDetailOnce(projectId)
      projectType = detail.projectType
      if (Number(store.currentProjectId) === projectId) {
        store.setCurrentProjectType(projectType)
      }
    } catch {
      return null
    }
  }

  if (projectType === 'movie') {
    return { projectId, episodeId: 0, projectType }
  }

  const routeEp = parseRouteEpisodeId(route)
  const storeEp =
    storeMatchesProject && store.currentEpisodeId != null && store.currentEpisodeId > 0
      ? store.currentEpisodeId
      : null
  const existing = storeEp ?? routeEp
  if (existing != null && existing > 0) {
    return { projectId, episodeId: existing, projectType }
  }

  const list = await userEpisodeList({ projectId })
  const sorted = [...list].sort((a, b) => (a.episodeNo ?? 0) - (b.episodeNo ?? 0))
  if (sorted.length > 0) {
    const id = sorted[0]!.id
    store.setCurrentProjectContext({ episodeId: id })
    return { projectId, episodeId: id, projectType }
  }

  const created = await userEpisodeCreate({
    projectId,
    comicTitle: '第1集'
  })
  store.setCurrentProjectContext({ episodeId: created.id })
  return { projectId, episodeId: created.id, projectType }
}

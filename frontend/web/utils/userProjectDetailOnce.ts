import type { UserProjectRow } from '~/types/business-api';
import { userProjectDetail } from '~/utils/businessApi';

const inflightByProjectId = new Map<number, Promise<UserProjectRow>>()
const resultCache = new Map<number, { at: number; row: UserProjectRow }>()
const CACHE_MS = 5000

/** 同一 projectId 并发只发起一次 /api/user/project/detail；短缓存避免作品库进入后壳层重复拉取 */
export function fetchUserProjectDetailOnce(
  projectId: number,
  options?: { force?: boolean }
): Promise<UserProjectRow> {
  if (!options?.force) {
    const hit = resultCache.get(projectId)
    if (hit && Date.now() - hit.at < CACHE_MS) {
      return Promise.resolve(hit.row)
    }
    const inflight = inflightByProjectId.get(projectId)
    if (inflight) return inflight
  }

  const promise = userProjectDetail(projectId)
    .then((row) => {
      if (inflightByProjectId.get(projectId) === promise) {
        resultCache.set(projectId, { at: Date.now(), row })
      }
      return row
    })
    .finally(() => {
      if (inflightByProjectId.get(projectId) === promise) {
        inflightByProjectId.delete(projectId)
      }
    })
  inflightByProjectId.set(projectId, promise)
  return promise
}

export function invalidateUserProjectDetailCache(projectId?: number): void {
  if (projectId == null) {
    inflightByProjectId.clear()
    resultCache.clear()
    return
  }
  inflightByProjectId.delete(projectId)
  resultCache.delete(projectId)
}

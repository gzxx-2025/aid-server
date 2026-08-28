import type { UserStoryboardListRow } from '~/types/business-api';
import { userStoryboardDetail } from '~/utils/businessApi';

const inflightByStoryboardId = new Map<number, Promise<UserStoryboardListRow>>()
const resultCache = new Map<number, { at: number; row: UserStoryboardListRow }>()
const CACHE_MS = 3000

/** 同一 storyboardId 并发只发起一次 /api/user/storyboard/detail；短缓存避免弹窗打开/切 tab 重复拉取 */
export function fetchUserStoryboardDetailOnce(
  storyboardId: number,
  options?: { force?: boolean }
): Promise<UserStoryboardListRow> {
  const id = Number(storyboardId)
  if (!Number.isFinite(id) || id <= 0) {
    return Promise.reject(new Error('分镜ID无效'))
  }

  if (!options?.force) {
    const hit = resultCache.get(id)
    if (hit && Date.now() - hit.at < CACHE_MS) {
      return Promise.resolve(hit.row)
    }
    const inflight = inflightByStoryboardId.get(id)
    if (inflight) return inflight
  }

  const promise = userStoryboardDetail({ id })
    .then((row) => {
      if (inflightByStoryboardId.get(id) === promise) {
        resultCache.set(id, { at: Date.now(), row })
      }
      return row
    })
    .finally(() => {
      if (inflightByStoryboardId.get(id) === promise) {
        inflightByStoryboardId.delete(id)
      }
    })
  inflightByStoryboardId.set(id, promise)
  return promise
}

export function invalidateUserStoryboardDetailCache(storyboardId?: number): void {
  if (storyboardId == null) {
    inflightByStoryboardId.clear()
    resultCache.clear()
    return
  }
  const id = Number(storyboardId)
  inflightByStoryboardId.delete(id)
  resultCache.delete(id)
}

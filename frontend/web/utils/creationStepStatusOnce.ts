import type { CreationStepRequest,CreationStepState } from '~/types/business-api';
import { creationStepStatus } from '~/utils/businessApi';

const inflightByKey = new Map<string, Promise<CreationStepState>>()
const resultCache = new Map<string, { at: number; data: CreationStepState }>()
const CACHE_MS = 4000

function cacheKey(body: CreationStepRequest): string {
  return `${body.projectId}:${body.episodeId ?? 'na'}`
}

/** 同一作品/剧集短时间内的 step/status 合并为一次请求（作品库进入 + 流程壳层初始化） */
export function fetchCreationStepStatusOnce(
  body: CreationStepRequest,
  options?: { force?: boolean }
): Promise<CreationStepState> {
  const key = cacheKey(body)
  if (!options?.force) {
    const hit = resultCache.get(key)
    if (hit && Date.now() - hit.at < CACHE_MS) {
      return Promise.resolve(hit.data)
    }
    const inflight = inflightByKey.get(key)
    if (inflight) return inflight
  }

  const promise = creationStepStatus(body)
    .then((data) => {
      if (inflightByKey.get(key) === promise) {
        resultCache.set(key, { at: Date.now(), data })
      }
      return data
    })
    .finally(() => {
      if (inflightByKey.get(key) === promise) {
        inflightByKey.delete(key)
      }
    })

  inflightByKey.set(key, promise)
  return promise
}

export function invalidateCreationStepStatusCache(projectId?: number): void {
  if (projectId == null) {
    inflightByKey.clear()
    resultCache.clear()
    return
  }
  const prefix = `${projectId}:`
  for (const key of [...inflightByKey.keys()]) {
    if (key.startsWith(prefix)) inflightByKey.delete(key)
  }
  for (const key of [...resultCache.keys()]) {
    if (key.startsWith(prefix)) resultCache.delete(key)
  }
}

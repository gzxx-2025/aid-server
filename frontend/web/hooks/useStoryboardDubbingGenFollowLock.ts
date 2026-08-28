/** 分镜配音 SSE 跟进 / 恢复：同一 liveGen scope + storyboardId 只允许一条 follow 链路。 */
const inflightPromises = new Map<string, { storyboardId: number; promise: Promise<void> }>()

function followKey(storyboardId: number, scopeKey?: string): string {
  return `${String(scopeKey || '__legacy__').trim()}::${storyboardId}`
}

export function isStoryboardDubbingGenFollowActive(
  storyboardId: number,
  scopeKey?: string
): boolean {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return false
  if (scopeKey) return inflightPromises.has(followKey(sid, scopeKey))
  return [...inflightPromises.values()].some((entry) => entry.storyboardId === sid)
}

export function listActiveStoryboardDubbingGenFollowIds(scopeKey?: string): number[] {
  const ids = new Set<number>()
  const prefix = scopeKey ? `${String(scopeKey).trim()}::` : ''
  for (const [key, entry] of inflightPromises) {
    if (!prefix || key.startsWith(prefix)) ids.add(entry.storyboardId)
  }
  return [...ids]
}

/** 切 Tab 互斥：释放 live 占坑（不断服务端任务；SSE 由调用方 suspend） */
export function releaseStoryboardDubbingGenFollow(storyboardId: number, scopeKey?: string): void {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return
  if (scopeKey) {
    inflightPromises.delete(followKey(sid, scopeKey))
    return
  }
  for (const [key, entry] of inflightPromises) {
    if (entry.storyboardId === sid) inflightPromises.delete(key)
  }
}

export function runStoryboardDubbingGenFollowOnce(
  storyboardId: number,
  fn: () => Promise<void>,
  scopeKey?: string
): Promise<void> {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return Promise.resolve()
  const key = followKey(sid, scopeKey)
  const existing = inflightPromises.get(key)
  if (existing) return existing.promise

  const promise = fn().finally(() => {
    if (inflightPromises.get(key)?.promise === promise) inflightPromises.delete(key)
  })
  inflightPromises.set(key, { storyboardId: sid, promise })
  return promise
}

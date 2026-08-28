export function videoFrameScopeKey(projectId: number, episodeId?: number | null): string {
  const pid = Number(projectId)
  const eid = Number(episodeId)
  if (Number.isFinite(eid) && eid > 0) return `${pid}:${eid}`
  return String(pid)
}

export function videoFrameStorageKey(scopeKey: string): string {
  return `aid:video-frames:v1:${scopeKey}`
}

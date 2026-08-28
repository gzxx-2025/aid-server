import { videoFrameScopeKey,videoFrameStorageKey } from './videoFrameScope'
export type VideoFrameLocalItem = {
  id: string
  url: string
  thumbnail?: string
  name: string
  projectId: number
  episodeId?: number | null
  sourceVideoId?: string
  sourceLabel?: string
  capturedAtMs?: number
  createdAt: string
}

export type VideoFrameStorage = {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

type AppendVideoFrameItem = Omit<VideoFrameLocalItem, 'projectId' | 'episodeId' | 'createdAt'> & {
  createdAt?: string
}

function resolveStorage(storage?: VideoFrameStorage): VideoFrameStorage | null {
  if (storage) return storage
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage
  } catch {
    return null
  }
}

function storageKey(projectId: number, episodeId?: number | null): string {
  return videoFrameStorageKey(videoFrameScopeKey(projectId, episodeId))
}

function isStoredVideoFrameItem(value: unknown): value is VideoFrameLocalItem {
  if (!value || typeof value !== 'object') return false
  const item = value as Partial<VideoFrameLocalItem>
  return (
    typeof item.id === 'string' &&
    item.id.trim().length > 0 &&
    typeof item.url === 'string' &&
    item.url.trim().length > 0 &&
    typeof item.name === 'string' &&
    typeof item.projectId === 'number' &&
    Number.isFinite(item.projectId) &&
    typeof item.createdAt === 'string'
  )
}

export function listVideoFrames(
  projectId: number,
  episodeId?: number | null,
  storage?: VideoFrameStorage
): VideoFrameLocalItem[] {
  const target = resolveStorage(storage)
  if (!target) return []
  try {
    const raw = target.getItem(storageKey(projectId, episodeId))
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    const expectedProjectId = Number(projectId)
    const normalizedEpisodeId = Number(episodeId)
    const expectedEpisodeId =
      Number.isFinite(normalizedEpisodeId) && normalizedEpisodeId > 0 ? normalizedEpisodeId : null
    return parsed.filter(
      (item): item is VideoFrameLocalItem =>
        isStoredVideoFrameItem(item) &&
        item.projectId === expectedProjectId &&
        (item.episodeId ?? null) === expectedEpisodeId
    )
  } catch {
    return []
  }
}

export function appendVideoFrame(
  projectId: number,
  episodeId: number | null | undefined,
  item: AppendVideoFrameItem,
  storage?: VideoFrameStorage
): VideoFrameLocalItem {
  const normalizedEpisodeId = Number(episodeId)
  const saved: VideoFrameLocalItem = {
    ...item,
    projectId: Number(projectId),
    episodeId:
      Number.isFinite(normalizedEpisodeId) && normalizedEpisodeId > 0 ? normalizedEpisodeId : null,
    createdAt: item.createdAt || new Date().toISOString()
  }
  const target = resolveStorage(storage)
  if (!target) return saved

  const next = [...listVideoFrames(projectId, episodeId, target), saved]
  try {
    target.setItem(storageKey(projectId, episodeId), JSON.stringify(next))
  } catch {
    // localStorage 不可用或空间不足时保留当前会话结果，不阻断截帧导入。
  }
  return saved
}

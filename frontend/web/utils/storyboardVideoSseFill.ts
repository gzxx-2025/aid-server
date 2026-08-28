/** 分镜视频弹窗：SSE complete items → videos 回填（纯函数，可单测） */

export type StoryboardVideoSseSuccessItem = {
  storyboardId: number
  recordId: number
  videoUrl: string
}

export type StoryboardVideoUiItem = {
  id?: string
  url?: string
  title?: string
  source?: string
  isStoryboardVideo?: boolean
  _generating?: boolean
  _localGeneratingPlaceholder?: boolean
  _fromServer?: boolean
  _serverRow?: Record<string, unknown> | null
  [key: string]: unknown
}

/** 与 utils/taskPartialFailed.parseVideoBatchSuccessItems 同构（避免测试链路拉 ~ 别名） */
export function parseStoryboardVideoSseSuccessItems(data: unknown): StoryboardVideoSseSuccessItem[] {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return []
  const o = data as Record<string, unknown>
  const rawItems = Array.isArray(o.items) ? o.items : []
  const out: StoryboardVideoSseSuccessItem[] = []
  const seen = new Set<number>()
  for (const raw of rawItems) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue
    const item = raw as Record<string, unknown>
    const storyboardId = Number(item.storyboardId)
    const recordId = Number(item.recordId)
    const videoUrl = String(item.videoUrl ?? '').trim()
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) continue
    if (!Number.isFinite(recordId) || recordId <= 0) continue
    if (!videoUrl) continue
    if (seen.has(storyboardId)) continue
    seen.add(storyboardId)
    out.push({ storyboardId, recordId, videoUrl })
  }
  return out
}

function findVideoIndexByRecordId(videos: StoryboardVideoUiItem[], recordId: number): number {
  return videos.findIndex(
    (v) =>
      String(v?.id ?? '') === String(recordId) ||
      String(v?._serverRow?.id ?? '') === String(recordId)
  )
}

function buildVideoItemFromSuccess(
  hit: StoryboardVideoSseSuccessItem,
  options?: { title?: string; isStoryboardVideo?: boolean }
): StoryboardVideoUiItem {
  const isMain = options?.isStoryboardVideo === true
  return {
    id: String(hit.recordId),
    url: hit.videoUrl,
    title: options?.title || '分镜视频',
    source: '生成记录',
    isStoryboardVideo: isMain,
    _fromServer: true,
    _serverRow: {
      id: hit.recordId,
      storyboardId: hit.storyboardId,
      fileUrl: hit.videoUrl,
      isSelected: isMain ? 1 : 0,
      genType: 'i2v'
    }
  }
}

/**
 * 用 SSE complete / partial_failed 的 items 合并进弹窗 videos，
 * 避免仅依赖 list-by-storyboard 缓存导致成功后仍为空。
 */
export function mergeVideoBatchSuccessItemsIntoVideos(
  videos: StoryboardVideoUiItem[] | null | undefined,
  storyboardId: number,
  data: unknown,
  options?: { title?: string }
): { videos: StoryboardVideoUiItem[]; changed: boolean; focusIndex: number | null } {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) {
    return { videos: Array.isArray(videos) ? [...videos] : [], changed: false, focusIndex: null }
  }

  const hits = parseStoryboardVideoSseSuccessItems(data).filter((it) => it.storyboardId === sid)
  const prev = Array.isArray(videos) ? videos : []
  if (!hits.length) {
    return { videos: [...prev], changed: false, focusIndex: null }
  }

  const next = prev.filter((v) => !v?._localGeneratingPlaceholder)
  let changed = next.length !== prev.length
  let focusIndex: number | null = null
  let hasMain = next.some((v) => v.isStoryboardVideo === true)

  for (const hit of hits) {
    const idx = findVideoIndexByRecordId(next, hit.recordId)
    if (idx >= 0) {
      const cur = next[idx]!
      const nextUrl = hit.videoUrl
      if (
        String(cur.url || '').trim() !== nextUrl ||
        cur._generating ||
        cur._localGeneratingPlaceholder
      ) {
        next[idx] = {
          ...cur,
          url: nextUrl,
          _generating: false,
          _localGeneratingPlaceholder: false,
          _fromServer: true,
          _serverRow: {
            ...(cur._serverRow || {}),
            id: hit.recordId,
            storyboardId: hit.storyboardId,
            fileUrl: nextUrl
          }
        }
        changed = true
      }
      focusIndex = idx
      continue
    }

    const markMain = !hasMain
    next.push(
      buildVideoItemFromSuccess(hit, {
        title: options?.title,
        isStoryboardVideo: markMain
      })
    )
    if (markMain) hasMain = true
    changed = true
    focusIndex = next.length - 1
  }

  return { videos: next, changed, focusIndex }
}

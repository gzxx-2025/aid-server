import type { StoryboardPanel,StoryboardVideoPanel } from '~/types'
import type { StoryboardRecordListType,StoryboardRecordRow } from '~/types/business-api'
import { userStoryboardRecordListByStoryboard } from '~/utils/businessApi'
import { pickStoryboardCoverImage,sortStoryboardImagesForParent } from '~/utils/storyboardImageCover'
import {
isOriginalStoryboardVideoRecord,
isStoryboardRecordSelected,
resolveStoryboardRecordDisplayName,
resolveStoryboardVideoSourceLabel
} from '~/utils/storyboardRecordRow'

export type ProjectEpisodeContext = { projectId: number; episodeId: number }

type RecordCacheEntry = {
  promise?: Promise<StoryboardRecordRow[]>
  data?: StoryboardRecordRow[]
}

const projectRecordCache = new Map<string, RecordCacheEntry>()

function recordCacheKey(ctx: ProjectEpisodeContext, type: StoryboardRecordListType): string {
  return `${ctx.projectId}:${ctx.episodeId}:${type}`
}

/** 生成记录写入/删除/设主图后调用，避免 stale 缓存 */
export function clearProjectStoryboardRecordCache(ctx?: ProjectEpisodeContext): void {
  if (!ctx) {
    projectRecordCache.clear()
    return
  }
  projectRecordCache.delete(recordCacheKey(ctx, 'image'))
  projectRecordCache.delete(recordCacheKey(ctx, 'video'))
  projectRecordCache.delete(recordCacheKey(ctx, 'compose'))
}

export function groupStoryboardRecordsByStoryboardId(
  rows: StoryboardRecordRow[]
): Map<number, StoryboardRecordRow[]> {
  const map = new Map<number, StoryboardRecordRow[]>()
  for (const r of rows) {
    const sid = Number(r.storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) continue
    const list = map.get(sid) ?? []
    list.push(r)
    map.set(sid, list)
  }
  return map
}

export async function fetchProjectStoryboardRecords(
  ctx: ProjectEpisodeContext,
  type: StoryboardRecordListType,
  options?: { force?: boolean }
): Promise<StoryboardRecordRow[]> {
  const key = recordCacheKey(ctx, type)
  const cached = projectRecordCache.get(key)
  if (!options?.force) {
    if (cached?.data) return cached.data
    if (cached?.promise) return cached.promise
  } else {
    // SSE/写操作后强制刷新：丢弃旧 data，也不并入「打开弹窗时」可能仍在飞的旧请求
    projectRecordCache.delete(key)
  }

  const entry: RecordCacheEntry = {}
  const promise = userStoryboardRecordListByStoryboard({
    projectId: ctx.projectId,
    episodeId: ctx.episodeId,
    type
  }).then((rows) => {
    if (projectRecordCache.get(key) === entry) {
      entry.promise = undefined
      entry.data = rows
    }
    return rows
  })

  entry.promise = promise
  projectRecordCache.set(key, entry)
  try {
    return await promise
  } catch (e) {
    if (projectRecordCache.get(key) === entry) {
      projectRecordCache.delete(key)
    }
    throw e
  }
}

export async function fetchStoryboardRecordsForStoryboard(
  ctx: ProjectEpisodeContext,
  storyboardId: number,
  type: StoryboardRecordListType,
  options?: { force?: boolean }
): Promise<StoryboardRecordRow[]> {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return []
  const rows = await fetchProjectStoryboardRecords(ctx, type, options)
  return rows.filter((r) => Number(r.storyboardId) === sid)
}

/** 单分镜原视频轨记录（排除 compose 配音合成视频） */
export async function fetchOriginalVideoRecordsForStoryboard(
  ctx: ProjectEpisodeContext,
  storyboardId: number,
  options?: { force?: boolean }
): Promise<StoryboardRecordRow[]> {
  const rows = await fetchStoryboardRecordsForStoryboard(ctx, storyboardId, 'video', options)
  return rows.filter((r) => isOriginalStoryboardVideoRecord(r))
}

/** 单分镜 compose 配音合成视频记录（type=compose） */
export async function fetchComposeVideoRecordsForStoryboard(
  ctx: ProjectEpisodeContext,
  storyboardId: number,
  options?: { force?: boolean }
): Promise<StoryboardRecordRow[]> {
  return fetchStoryboardRecordsForStoryboard(ctx, storyboardId, 'compose', options)
}

export async function fetchProjectImageAndVideoRecordMaps(
  ctx: ProjectEpisodeContext,
  options?: { force?: boolean }
): Promise<{
  imageRows: StoryboardRecordRow[]
  videoRows: StoryboardRecordRow[]
  imageByStoryboardId: Map<number, StoryboardRecordRow[]>
  videoByStoryboardId: Map<number, StoryboardRecordRow[]>
}> {
  const [imageRows, videoRows] = await Promise.all([
    fetchProjectStoryboardRecords(ctx, 'image', options),
    fetchProjectStoryboardRecords(ctx, 'video', options)
  ])
  return {
    imageRows,
    videoRows,
    imageByStoryboardId: groupStoryboardRecordsByStoryboardId(imageRows),
    videoByStoryboardId: groupStoryboardRecordsByStoryboardId(
      videoRows.filter((r) => isOriginalStoryboardVideoRecord(r))
    )
  }
}

export function mapRecordRowToPanelImageItem(
  r: StoryboardRecordRow,
  options?: { title?: string }
): { id: string; url: string; thumbnail: string; title: string; [key: string]: unknown } {
  const url = String(r.fileUrl ?? '').trim()
  const title =
    resolveStoryboardRecordDisplayName(r, options?.title) || options?.title?.trim() || '未命名'
  return {
    id: String(r.id ?? ''),
    url,
    thumbnail: url,
    title,
    source: '生成记录',
    importDate: r.createTime || undefined,
    createdAt: r.createTime || undefined,
    isSelected: isStoryboardRecordSelected(r),
    _fromServer: true,
    _serverRow: r
  }
}

export function mapRecordRowToPanelVideoItem(
  r: StoryboardRecordRow,
  options?: { title?: string }
): { id: string; url: string; title: string; [key: string]: unknown } {
  const url = String(r.fileUrl ?? '').trim()
  const label = resolveStoryboardRecordDisplayName(r, options?.title) || options?.title?.trim() || '分镜视频'
  return {
    id: String(r.id ?? ''),
    url,
    title: label,
    source: resolveStoryboardVideoSourceLabel({ _fromServer: true, _serverRow: r }),
    importDate: r.createTime || undefined,
    isStoryboardVideo: isStoryboardRecordSelected(r) && isOriginalStoryboardVideoRecord(r),
    _fromServer: true,
    _serverRow: r
  }
}

export function hydrateScriptPanelsWithImageRecords(
  panels: StoryboardPanel[],
  imageByStoryboardId: Map<number, StoryboardRecordRow[]>
): StoryboardPanel[] {
  return panels.map((panel) => {
    const sid = Number(panel.id)
    const rows = imageByStoryboardId.get(sid) ?? []
    if (!rows.length) return panel

    const images = sortStoryboardImagesForParent(
      rows
        .filter((r) => !!String(r.fileUrl ?? '').trim())
        .map((r) => mapRecordRowToPanelImageItem(r))
    )
    const cover = pickStoryboardCoverImage(images)
    const coverUrl = String(cover?.url || cover?.thumbnail || '').trim()
    return {
      ...panel,
      images,
      // 无主图时必须清空，避免取消添加后仍残留 list 上的 finalImageUrl
      finalImageUrl: coverUrl || undefined
    }
  })
}

export function hydrateVideoPanelsWithVideoRecords(
  panels: StoryboardVideoPanel[],
  scriptPanels: StoryboardPanel[],
  videoByStoryboardId: Map<number, StoryboardRecordRow[]>
): StoryboardVideoPanel[] {
  return panels.map((panel, index) => {
    const sid = Number(scriptPanels[index]?.id)
    const rows = (videoByStoryboardId.get(sid) ?? []).filter((r) => isOriginalStoryboardVideoRecord(r))
    if (!rows.length) return panel

    const videos = rows
      .filter((r) => !!String(r.fileUrl ?? '').trim())
      .map((r) => mapRecordRowToPanelVideoItem(r, { title: panel.title }))
    return { ...panel, videos }
  })
}

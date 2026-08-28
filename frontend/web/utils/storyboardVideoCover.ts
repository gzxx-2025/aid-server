import {
isComposeStoryboardVideoRecord,
isStoryboardRecordSelected
} from '~/utils/storyboardRecordRow'

type VideoRecordLike = {
  isStoryboardVideo?: boolean
  isSelected?: boolean | number | null
  _serverRow?: { isSelected?: number | null; genType?: string | null; id?: number | null }
}

function rowIsSelected(v: VideoRecordLike | null | undefined): boolean {
  if (!v) return false
  if (v.isStoryboardVideo === true) return true
  if (v.isSelected === true || v.isSelected === 1) return true
  return isStoryboardRecordSelected(v._serverRow)
}

/** 分镜视频是否为已确认主视频（原视频轨 setFinalVideo / isSelected=1） */
export function isStoryboardVideoSelected(v: VideoRecordLike | null | undefined): boolean {
  if (!v) return false
  const row = v._serverRow
  if (row && isComposeStoryboardVideoRecord(row)) return false
  return rowIsSelected(v)
}

type StoryboardVideoItem = VideoRecordLike & {
  id?: string
  url?: string
  title?: string
}

/** 从分镜视频面板取已确认主视频（原视频轨）；无主视频时返回 null */
export function getPanelStoryboardVideo(panel: {
  videos?: StoryboardVideoItem[] | null
} | null | undefined): StoryboardVideoItem | null {
  if (!panel?.videos?.length) return null
  return panel.videos.find((v) => isStoryboardVideoSelected(v)) ?? null
}

export function getPanelStoryboardVideoUrl(panel: {
  videos?: StoryboardVideoItem[] | null
} | null | undefined): string {
  return String(getPanelStoryboardVideo(panel)?.url ?? '').trim()
}

/** 服务端生成记录列表中取原视频轨主视频记录 */
export function pickSelectedStoryboardVideoRecord<T extends VideoRecordLike & {
  url?: string
}>(records: T[] | null | undefined): T | null {
  if (!records?.length) return null
  return (
    records.find((r) => {
      const row = r._serverRow
      if (row && isComposeStoryboardVideoRecord(row)) return false
      return rowIsSelected(r)
    }) ?? null
  )
}

/** 服务端生成记录列表中取 compose 配音轨使用中视频 */
export function pickSelectedComposeVideoRecord<T extends VideoRecordLike & {
  url?: string
}>(records: T[] | null | undefined): T | null {
  if (!records?.length) return null
  return (
    records.find((r) => {
      const row = r._serverRow
      if (!row || !isComposeStoryboardVideoRecord(row)) return false
      return isStoryboardRecordSelected(row)
    }) ?? null
  )
}

/** 成品预览时间轴：优先 compose 配音轨，否则原视频轨 */
export function pickPreviewStoryboardVideoRecord<T extends VideoRecordLike & {
  url?: string
}>(records: T[] | null | undefined): T | null {
  return pickSelectedComposeVideoRecord(records) ?? pickSelectedStoryboardVideoRecord(records)
}

type PreviewVideoPanelLike = {
  finalVideoUrl?: string | null
  videos?: Array<{
    url?: string
    isStoryboardVideo?: boolean
    isSelected?: boolean | number | null
    _serverRow?: { isSelected?: number | null; genType?: string | null; id?: number | null }
  }> | null
}

type PreviewDubbingPanelLike = {
  dubbingLipSyncVideoUrl?: string | null
}

/**
 * 成品预览时间轴视频 URL 解析（双轨语义）：
 * 1. 配音/对口型结果（dubbingLipSyncVideoUrl，多为 compose 或 lipSync 成片）
 * 2. 面板内 compose 配音轨使用中 / 原视频轨主视频
 * 3. 列表接口 finalVideoUrl（恒为配音前原视频）
 */
export function resolvePreviewTimelineVideoUrl(
  dub: PreviewDubbingPanelLike | null | undefined,
  videoPanel: PreviewVideoPanelLike | null | undefined
): string {
  const lip = String(dub?.dubbingLipSyncVideoUrl || '').trim()
  if (lip) return lip

  const fromPanelVideos = pickPreviewStoryboardVideoRecord(videoPanel?.videos || [])
  const fromVideos = String(fromPanelVideos?.url || '').trim()
  if (fromVideos) return fromVideos

  const original = String(videoPanel?.finalVideoUrl || '').trim()
  if (original) return original

  return getPanelStoryboardVideoUrl(videoPanel)
}

/**
 * 成品预览 / 本地同步字幕文案：只取列表清洗后的 subtitleText。
 * 禁止回退 dialogue/dialogueText（含角色形象标注与引号，不宜直接上字幕轨）。
 */
export function resolvePreviewSubtitleText(dub: {
  subtitleText?: string | null
} | null | undefined): string {
  return String(dub?.subtitleText || '').trim()
}

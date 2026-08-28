import type { EpisodeExportComposeGroup,TimelineData } from '~/types/business-api'
function persistableMediaUrl(url: unknown): string {
  const raw = String(url || '').trim()
  if (!raw || /^(blob:|data:)/i.test(raw) || /\/blob:/i.test(raw)) return ''
  return raw
}

function num(value: unknown, fallback = 0): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

/** 服务端 timeline → 导出 groups（扁平 map，无深层嵌套） */
export function mapTimelineToExportGroups(timeline: TimelineData): {
  groups: EpisodeExportComposeGroup[]
  globalBgmUrl?: string
} {
  const segments = Array.isArray(timeline.segments) ? timeline.segments : []
  const storyboardIds = new Set<number>()
  const groups: EpisodeExportComposeGroup[] = segments
    .filter((s) => String(s.video?.url || '').trim())
    .map((s) => {
      const storyboardId = Number(s.storyboardId)
      if (!Number.isInteger(storyboardId) || storyboardId <= 0) {
        throw new Error('分镜数据异常，请重新初始化时间线')
      }
      if (storyboardIds.has(storyboardId)) {
        throw new Error('分镜数据重复，请重新初始化时间线')
      }
      storyboardIds.add(storyboardId)
      const videoUrl = String(s.video.url).trim()
      const videoDur = Math.max(0.01, num(s.video.durationSeconds, 0.01))
      const audioUrl = String(s.voice?.url || '').trim()
      const audioDur = audioUrl
        ? Math.max(0.01, num(s.voice?.durationSeconds, videoDur))
        : 0
      const timelineSubtitle = s.subtitle
      const subtitle = timelineSubtitle?.show === false ? null : String(timelineSubtitle?.text || '').trim() || null
      const subtitleCues = timelineSubtitle?.show === false || !Array.isArray(timelineSubtitle?.cues)
        ? null
        : timelineSubtitle.cues.filter(
          (cue) => String(cue?.text || '').trim()
            && Number(cue?.endSeconds) > Number(cue?.startSeconds)
        )
      return {
        storyboardId,
        videoUrls: [videoUrl],
        videoDurations: [videoDur],
        audioUrls: audioUrl ? [audioUrl] : [],
        audioDurations: audioUrl ? [audioDur] : [],
        subtitle,
        subtitleCues: subtitleCues?.length ? subtitleCues : null,
        subtitleSourceMediaFingerprint: subtitleCues?.length
          ? (timelineSubtitle?.sourceMediaFingerprint ?? null)
          : null,
        bgmUrl: null
      }
    })

  const globalBgmUrl = persistableMediaUrl(timeline.bgm?.url) || undefined
  return { groups, globalBgmUrl }
}

import {
isComposeStoryboardVideoRecord,
isOriginalStoryboardVideoRecord,
normalizeStoryboardRecordGenType
} from './storyboardRecordRow'

export type StoryboardVideoPick = {
  id: string
  url: string
  label: string
  panelId?: string
  poster?: string
}

type StoryboardVideoCandidate = {
  id?: string | number
  url?: string
  title?: string
  thumbnail?: string
  poster?: string
  isStoryboardVideo?: boolean
  _serverRow?: { genType?: string | null } | null
  genType?: string | null
}

type StoryboardVideoPanelCandidate = {
  id?: string | number
  title?: string
  videos?: StoryboardVideoCandidate[] | null
}

function isOriginalOrLegacyVideo(video: StoryboardVideoCandidate): boolean {
  const row = video._serverRow
  const normalized =
    normalizeStoryboardRecordGenType(row?.genType) ||
    normalizeStoryboardRecordGenType(video.genType)
  if (!normalized) return true
  if (isComposeStoryboardVideoRecord({ genType: normalized })) return false
  return isOriginalStoryboardVideoRecord({ genType: normalized })
}

/** 收集当前 store 中可截帧的分镜原视频，明确排除 compose/对口型等合成轨。 */
export function collectOriginalStoryboardVideosFromPanels(
  panels: StoryboardVideoPanelCandidate[] | null | undefined
): StoryboardVideoPick[] {
  if (!Array.isArray(panels)) return []

  const result: StoryboardVideoPick[] = []
  panels.forEach((panel, panelIndex) => {
    const panelId = String(panel?.id ?? '').trim()
    const label = String(panel?.title ?? '').trim() || `分镜${panelIndex + 1}`
    const videos = Array.isArray(panel?.videos) ? panel.videos : []
    videos.forEach((video, videoIndex) => {
      const url = String(video?.url ?? '').trim()
      if (!url || !isOriginalOrLegacyVideo(video)) return
      const id =
        String(video?.id ?? '').trim() ||
        `${panelId || `panel-${panelIndex + 1}`}-${videoIndex}-${url}`
      const poster = String(video?.poster || video?.thumbnail || '').trim()
      result.push({
        id,
        url,
        label,
        ...(panelId ? { panelId } : {}),
        ...(poster ? { poster } : {})
      })
    })
  })
  return result
}

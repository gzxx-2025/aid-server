/**
 * 分镜视频面板：当 videos 为空但已有 finalVideoUrl 时回填主视频条目，
 * 避免列表同步只更新 finalVideoUrl、卡片/弹窗仍显示「暂无生成记录」。
 */

export type StoryboardVideoSeedItem = {
  id: string
  url: string
  title: string
  source: string
  isStoryboardVideo: boolean
  isSelected: boolean
  _fromServer: true
  _serverRow: {
    id?: number
    isSelected: 1
    genType: 'i2v'
    fileUrl: string
  }
}

export function buildMainVideoItemFromFinalUrl(options: {
  finalVideoUrl: string
  finalVideoId?: number | null
  storyboardId?: string | number
  title?: string
}): StoryboardVideoSeedItem | null {
  const url = String(options.finalVideoUrl ?? '').trim()
  if (!url) return null
  const finalVideoId =
    options.finalVideoId != null && Number(options.finalVideoId) > 0
      ? Number(options.finalVideoId)
      : null
  const sid = options.storyboardId != null ? String(options.storyboardId) : ''
  return {
    id: finalVideoId != null ? String(finalVideoId) : `final-video-${sid || 'unknown'}`,
    url,
    title: String(options.title ?? '').trim() || '分镜原视频',
    source: '生成记录',
    isStoryboardVideo: true,
    isSelected: true,
    _fromServer: true,
    _serverRow: {
      ...(finalVideoId != null ? { id: finalVideoId } : {}),
      isSelected: 1,
      genType: 'i2v',
      fileUrl: url
    }
  }
}

export function resolveSeedFinalVideoUrl(
  scriptFinalVideoUrl?: string | null,
  panelFinalVideoUrl?: string | null
): string {
  const fromScript = scriptFinalVideoUrl != null ? String(scriptFinalVideoUrl).trim() : ''
  if (fromScript) return fromScript
  return panelFinalVideoUrl != null ? String(panelFinalVideoUrl).trim() : ''
}

/** 已有非空 videos 则原样返回；否则用 finalVideoUrl 回填一条主视频 */
export function ensureVideosFromFinalVideoUrl<
  T extends {
    videos?: unknown[] | null
    finalVideoUrl?: string | null
    id?: string
  }
>(
  panel: T,
  options?: {
    scriptFinalVideoUrl?: string | null
    scriptFinalVideoId?: number | null
    title?: string
  }
): T {
  const existing = Array.isArray(panel.videos) ? panel.videos : []
  if (existing.length > 0) return panel

  const url = resolveSeedFinalVideoUrl(options?.scriptFinalVideoUrl, panel.finalVideoUrl)
  const seeded = buildMainVideoItemFromFinalUrl({
    finalVideoUrl: url,
    finalVideoId: options?.scriptFinalVideoId,
    storyboardId: panel.id,
    title: options?.title
  })
  if (!seeded) {
    return url
      ? { ...panel, finalVideoUrl: url }
      : panel
  }
  return {
    ...panel,
    finalVideoUrl: url || panel.finalVideoUrl,
    videos: [seeded]
  }
}

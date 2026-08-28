import { resolveStoryboardPanelCoverImage } from './storyboardImageCover'

export type PreviewPosterScriptPanel = {
  id?: number | string | null
  images?: unknown[] | null
  finalImageUrl?: string | null
}

/**
 * 成品预览播放区占位封面：优先分镜主图（按 storyboardId，其次按索引）。
 */
export function resolvePreviewPlayerPosterUrl(opts: {
  storyboardId?: number | null
  clipIndex?: number | null
  scriptPanels?: PreviewPosterScriptPanel[] | null
}): string {
  const panels = Array.isArray(opts.scriptPanels) ? opts.scriptPanels : []
  if (!panels.length) return ''

  const sid = Number(opts.storyboardId)
  let panel: PreviewPosterScriptPanel | null = null
  if (Number.isFinite(sid) && sid > 0) {
    panel = panels.find((p) => Number(p?.id) === sid) ?? null
  }
  if (!panel) {
    const idx = Number(opts.clipIndex)
    if (Number.isFinite(idx) && idx >= 0 && idx < panels.length) {
      panel = panels[idx] ?? null
    }
  }
  if (!panel) return ''

  const cover = resolveStoryboardPanelCoverImage(panel)
  const url = String(cover?.url || cover?.thumbnail || '').trim()
  return url
}

/** 是否应展示「首帧未就绪」遮罩（纯规则，便于单测） */
export function shouldShowPreviewReadyOverlay(opts: {
  timelineLoading: boolean
  videoClipCount: number
  hasPlayableAtCurrentTime: boolean
  frameReady: boolean
}): boolean {
  if (opts.frameReady) return false
  if (opts.videoClipCount <= 0) return !!opts.timelineLoading
  if (!opts.hasPlayableAtCurrentTime) return false
  return true
}

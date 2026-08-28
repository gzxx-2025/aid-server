import type { CSSProperties } from 'react'
import {
  MIN_CLIP_WIDTH_PX,
  MIN_DURATION,
  SCALE_PX_PER_SEC,
  type ClipDisplayLayout,
  type ClipLayoutEntry,
  type RulerMarkType,
  type TimelineAudioItem,
  type TimelineBase,
  type TimelineVideoClip,
  type TrackType,
  type VideoPreviewCtx
} from './types'

/** 布局 / 查找纯计算层：只读状态，不产生副作用（原 computed 的函数化） */

export function getOrderedVideoClips(ctx: VideoPreviewCtx): TimelineVideoClip[] {
  return [...ctx.state.videoClips.get()].sort((a, b) => a.start - b.start)
}

export function getTotalDuration(ctx: VideoPreviewCtx): number {
  const S = ctx.state
  const all = [...S.videoClips.get(), ...S.voiceItems.get(), ...S.subtitleItems.get(), ...S.musicItems.get()]
  return all.reduce((max, it) => Math.max(max, it.start + it.duration), 0)
}

export function hasClipVideoUrl(clip: Pick<TimelineVideoClip, 'url'>): boolean {
  return !!String(clip.url || '').trim()
}

export function getClipDisplayLayout(ctx: VideoPreviewCtx): ClipDisplayLayout {
  const clips = getOrderedVideoClips(ctx)
  const n = clips.length
  const stripWidthPx = ctx.state.timelineStripWidthPx.get()

  if (!n) {
    const fallbackW = Math.max(400, stripWidthPx)
    return {
      totalWidthPx: fallbackW,
      playheadScalePxPerSec: SCALE_PX_PER_SEC,
      entries: [] as ClipLayoutEntry[]
    }
  }

  const stripW = Math.max(200, stripWidthPx)
  const totalDur = clips.reduce((s, c) => s + c.duration, 0) || 1
  const naturalTimeWidth = totalDur * SCALE_PX_PER_SEC
  const fitsInViewport = naturalTimeWidth <= stripW

  // 与字幕/音乐一致：按时间线性映射，不再叠加额外像素间隔
  const playheadScalePxPerSec = fitsInViewport ? stripW / totalDur : SCALE_PX_PER_SEC
  const totalWidthPx = fitsInViewport ? stripW : Math.max(stripW, naturalTimeWidth)

  const entries: ClipLayoutEntry[] = clips.map((clip) => {
    const rawW = clip.duration * playheadScalePxPerSec
    const blockWidth = hasClipVideoUrl(clip)
      ? Math.max(8, rawW)
      : Math.max(MIN_CLIP_WIDTH_PX, rawW)
    return {
      id: clip.id,
      leftPx: clip.start * playheadScalePxPerSec,
      widthPx: blockWidth,
      startSec: clip.start,
      durationSec: clip.duration
    }
  })

  return {
    totalWidthPx,
    playheadScalePxPerSec,
    entries
  }
}

export function getRulerWidthPx(ctx: VideoPreviewCtx): number {
  return getClipDisplayLayout(ctx).totalWidthPx
}

export function getRulerMarks(ctx: VideoPreviewCtx): Array<{ sec: number; type: RulerMarkType }> {
  const marks: Array<{ sec: number; type: RulerMarkType }> = []
  const maxSec = Math.max(1, Math.ceil(getTotalDuration(ctx)))
  const step = SCALE_PX_PER_SEC >= 60 ? 0.2 : SCALE_PX_PER_SEC >= 30 ? 0.5 : 1
  for (let t = 0; t <= maxSec + 0.001; t += step) {
    const sec = Number(t.toFixed(2))
    const isMajor = Math.abs(sec % 5) < 0.001 || sec === 0
    const isMedium = !isMajor && Math.abs(sec % 1) < 0.001
    marks.push({ sec, type: isMajor ? 'major' : isMedium ? 'medium' : 'minor' })
  }
  return marks
}

export function getRulerMarksWithLayout(
  ctx: VideoPreviewCtx
): Array<{ sec: number; type: RulerMarkType; leftPx: number }> {
  return getRulerMarks(ctx).map((mark) => ({
    ...mark,
    leftPx: secToLayoutPx(ctx, mark.sec)
  }))
}

export function getVideoClipGapIndex(
  ctx: VideoPreviewCtx,
  seg: { id?: string; videoClipId?: string; start: number },
  gapIndex?: number
): number {
  if (gapIndex !== undefined) return gapIndex
  if (seg.id) {
    const idx = getOrderedVideoClips(ctx).findIndex((c) => c.id === seg.id)
    if (idx >= 0) return idx
  }
  if (seg.videoClipId) {
    const idx = getOrderedVideoClips(ctx).findIndex((c) => c.id === seg.videoClipId)
    if (idx >= 0) return idx
  }
  const sorted = getOrderedVideoClips(ctx)
  for (let i = sorted.length - 1; i >= 0; i--) {
    if (seg.start >= sorted[i]!.start - 0.001) return i
  }
  return 0
}

export function secToPlayheadPx(ctx: VideoPreviewCtx, sec: number): number {
  const scale = getClipDisplayLayout(ctx).playheadScalePxPerSec || SCALE_PX_PER_SEC
  return Math.max(0, sec * scale)
}

export function playheadPxToSec(ctx: VideoPreviewCtx, px: number): number {
  const scale = getClipDisplayLayout(ctx).playheadScalePxPerSec || SCALE_PX_PER_SEC
  if (!scale) return 0
  return Math.max(0, px / scale)
}

export function secToLayoutPx(ctx: VideoPreviewCtx, sec: number): number {
  return secToPlayheadPx(ctx, sec)
}

export function layoutPxToSec(ctx: VideoPreviewCtx, px: number): number {
  return playheadPxToSec(ctx, px)
}

export function clipStyle(
  ctx: VideoPreviewCtx,
  seg: { start: number; duration: number; id?: string; videoClipId?: string },
  _gapIndex?: number
): CSSProperties {
  const layout = getClipDisplayLayout(ctx)
  // 始终按 id 取布局条目，避免用 v-for index 错位（音量轨曾因此与字幕不一致）
  const idx = getVideoClipGapIndex(ctx, seg)
  const entry = layout.entries[idx]
  if (!entry) {
    const leftPx = secToPlayheadPx(ctx, seg.start)
    const widthPx = Math.max(8, secToPlayheadPx(ctx, seg.start + seg.duration) - leftPx)
    return { left: `${leftPx}px`, width: `${widthPx}px` }
  }

  const clipEndSec = entry.startSec + entry.durationSec
  const segEnd = seg.start + seg.duration
  const isFullClip =
    Math.abs(seg.start - entry.startSec) < 0.02 && Math.abs(segEnd - clipEndSec) < 0.02

  if (isFullClip) {
    return {
      left: entry.leftPx + 'px',
      width: Math.max(8, entry.widthPx) + 'px'
    }
  }

  const leftPx = secToPlayheadPx(ctx, seg.start)
  const widthPx = Math.max(8, secToPlayheadPx(ctx, segEnd) - secToPlayheadPx(ctx, seg.start))
  return {
    left: leftPx + 'px',
    width: widthPx + 'px'
  }
}

export function musicClipStyle(ctx: VideoPreviewCtx, item: TimelineAudioItem): CSSProperties {
  const leftPx = secToLayoutPx(ctx, item.start)
  const rightPx = secToLayoutPx(ctx, item.start + item.duration)
  return {
    left: leftPx + 'px',
    width: Math.max(8, rightPx - leftPx) + 'px'
  }
}

export function musicBarStyle(
  ctx: VideoPreviewCtx,
  item: TimelineAudioItem,
  empty: boolean
): CSSProperties {
  if (empty) {
    const total = Math.max(MIN_DURATION, getVideoTimelineTotalSec(ctx))
    return {
      left: '0px',
      width: Math.max(8, secToLayoutPx(ctx, total)) + 'px'
    }
  }
  return musicClipStyle(ctx, item)
}

export function musicSourceCycleStyle(item: TimelineAudioItem): CSSProperties {
  const source = item.sourceDuration || item.duration
  if (!source || !item.duration) return { width: '100%' }
  const ratio = Math.min(1, source / item.duration)
  return { width: `${(ratio * 100).toFixed(2)}%` }
}

export function formatTime(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

export function getClipPageLabel(ctx: VideoPreviewCtx, clip: TimelineVideoClip): string {
  const total = ctx.state.videoClips.get().length
  const match = String(clip.name || '').match(/(\d+)/)
  const page = match ? Number(match[1]) : 0
  const safePage = Number.isFinite(page) && page > 0 ? page : 1
  return `${String(safePage).padStart(2, '0')}/${String(total).padStart(2, '0')}`
}

export function getVideoClipAtTime(ctx: VideoPreviewCtx, timeSec: number): TimelineVideoClip | null {
  const t = Number(timeSec.toFixed(3))
  const ordered = getOrderedVideoClips(ctx)
  for (let i = 0; i < ordered.length; i++) {
    const clip = ordered[i]!
    const end = clip.start + clip.duration
    const isLast = i === ordered.length - 1
    if (t >= clip.start - 0.001 && (t < end - 0.001 || (isLast && t <= end + 0.001))) {
      return clip
    }
  }
  return null
}

export function hasPlayableVideoAtTime(ctx: VideoPreviewCtx, timeSec: number): boolean {
  const clip = getVideoClipAtTime(ctx, timeSec)
  return !!clip && hasClipVideoUrl(clip)
}

export function getNextPlayableClip(
  ctx: VideoPreviewCtx,
  afterClip: TimelineVideoClip | null
): TimelineVideoClip | null {
  const clips = getOrderedVideoClips(ctx)
  if (!clips.length) return null
  const startIdx = afterClip ? clips.findIndex((c) => c.id === afterClip.id) : -1
  for (let i = startIdx + 1; i < clips.length; i++) {
    const clip = clips[i]!
    if (hasClipVideoUrl(clip)) return clip
  }
  return null
}

export function findNextPlayableClipStart(ctx: VideoPreviewCtx, fromSec: number): number | null {
  const t = Number(fromSec.toFixed(3))
  for (const clip of getOrderedVideoClips(ctx)) {
    if (!hasClipVideoUrl(clip)) continue
    const end = clip.start + clip.duration
    if (end > t + 0.005) {
      return Number(Math.max(clip.start, t).toFixed(3))
    }
  }
  return null
}

/** 时间轴上最后一个可播放分镜的结束时间 */
export function getFullTimelinePlayableEndSec(ctx: VideoPreviewCtx): number {
  const clips = getOrderedVideoClips(ctx)
  let endSec = 0
  for (const clip of clips) {
    if (hasClipVideoUrl(clip)) {
      endSec = clip.start + clip.duration
    }
  }
  return Number(endSec.toFixed(3)) || getTotalDuration(ctx)
}

export function getVideoTimelineTotalSec(ctx: VideoPreviewCtx): number {
  return ctx.state.videoClips.get().reduce((sum, clip) => sum + clip.duration, 0)
}

export function getSelectedVideoClip(ctx: VideoPreviewCtx): TimelineVideoClip | null {
  const selected = ctx.state.selectedClip.get()
  if (selected?.track !== 'video') return null
  return ctx.state.videoClips.get().find((x) => x.id === selected.id) || null
}

export function findItem(ctx: VideoPreviewCtx, track: TrackType, id: string): any {
  const S = ctx.state
  const list =
    track === 'video' ? S.videoClips.get() :
    track === 'voice' ? S.voiceItems.get() :
    track === 'subtitle' ? S.subtitleItems.get() :
    S.musicItems.get()
  return list.find((x: any) => x.id === id)
}

export function getTrackItems(ctx: VideoPreviewCtx, track: TrackType): Array<TimelineBase> {
  const S = ctx.state
  if (track === 'video') return S.videoClips.get()
  if (track === 'voice') return S.voiceItems.get()
  if (track === 'subtitle') return S.subtitleItems.get()
  return S.musicItems.get()
}

export function getSnapDistanceSec(ctx: VideoPreviewCtx): number {
  return ctx.state.snapDistancePx.get() / SCALE_PX_PER_SEC
}

export function getVoiceItemForVideoClip(
  ctx: VideoPreviewCtx,
  clipId: string
): TimelineAudioItem | undefined {
  return ctx.state.voiceItems.get().find((v) => v.videoClipId === clipId)
}

export function getVideoVolume(ctx: VideoPreviewCtx, clipId: string): number {
  const voice = getVoiceItemForVideoClip(ctx, clipId)
  if (voice) return voice.volume
  return ctx.state.videoVolumePreset.get()[clipId] ?? 1
}

export function getVideoVolumePercent(ctx: VideoPreviewCtx, clipId: string): number {
  return Math.max(0, Math.min(100, (getVideoVolume(ctx, clipId) / 2) * 100))
}

export function formatVolumeLabel(ctx: VideoPreviewCtx, clipId: string): string {
  return `${Math.round(getVideoVolumePercent(ctx, clipId))}%`
}

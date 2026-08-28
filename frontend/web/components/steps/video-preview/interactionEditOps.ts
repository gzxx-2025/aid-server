import { message } from 'antd'
import type * as React from 'react'
import { ensurePreviewAtCurrentTime,scheduleRebuild } from './canvasOps'
import { pxToSec,resolveOverlap } from './interactionPointerOps'
import {
getSelectedVideoClip,
getVideoClipAtTime
} from './layoutOps'
import { openEditDubbingModalForClip,openEditMusicModal } from './modalOps'
import {
scrollPlayheadIntoView,
seekToTime,
stopPlayback,
togglePlay
} from './playbackOps'
import {
probeAudioDuration,
scheduleTimelinePersist,
syncUntimedSubtitleToVoiceDuration,
touchSubtitleItems,
touchVoiceItems
} from './timelineOps'
import {
  MIN_DURATION,
type TimelineAudioItem,
type TimelineVideoClip,
type VideoPreviewCtx
} from './types'

function getActiveVideoClipForOperation(ctx: VideoPreviewCtx): TimelineVideoClip | null {
  const selected = getSelectedVideoClip(ctx)
  if (selected) return selected
  const byTime = getVideoClipAtTime(ctx, ctx.state.currentTime.get())
  return byTime || ctx.state.videoClips.get()[0] || null
}

function seekClipAndPreview(ctx: VideoPreviewCtx, sec: number) {
  stopPlayback(ctx)
  seekToTime(ctx, sec)
  void ensurePreviewAtCurrentTime(ctx)
  setTimeout(() => scrollPlayheadIntoView(ctx), 0)
}

export function onTrackClick(
  ctx: VideoPreviewCtx,
  e: React.MouseEvent,
  track: 'voice' | 'subtitle' | 'music'
) {
  const S = ctx.state
  if (S.subtitleRange.get().active) return
  const wrap = ctx.dom.timelineWrapRef.current
  if (!wrap) return
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const x = e.clientX - rect.left + wrap.scrollLeft
  const t = pxToSec(ctx, x)
  seekClipAndPreview(ctx, t)

  if (track === 'voice') {
    const videoClip = getVideoClipAtTime(ctx, t) || getActiveVideoClipForOperation(ctx)
    if (!videoClip) {
      message.warning('请先同步分镜视频')
      return
    }
    S.selectedClip.set({ track: 'video', id: videoClip.id })
    openEditDubbingModalForClip(ctx, videoClip.id)
    return
  }

  if (track === 'subtitle') {
    const subAtTime = S.subtitleItems.get().find((s) => t >= s.start && t < s.start + s.duration)
    if (subAtTime) {
      S.selectedClip.set({ track: 'subtitle', id: subAtTime.id })
      editSubtitle(ctx, subAtTime.id)
      return
    }
    const activeVideo = getVideoClipAtTime(ctx, t) || getActiveVideoClipForOperation(ctx)
    if (!activeVideo) {
      message.warning('请先选中或定位到某个分镜视频片段')
      return
    }
    const existed = S.subtitleItems.get().find((s) => s.videoClipId === activeVideo.id)
    if (existed) {
      S.selectedClip.set({ track: 'subtitle', id: existed.id })
      editSubtitle(ctx, existed.id)
      return
    }
    const clipStart = activeVideo.start
    const duration = Math.max(MIN_DURATION, Number(activeVideo.duration.toFixed(2)))
    const id = `sub-${Date.now()}`
    S.subtitleItems.set([
      ...S.subtitleItems.get(),
      {
        id,
        kind: 'subtitle',
        text: '请输入字幕',
        fontSize: 40,
        videoClipId: activeVideo.id,
        start: clipStart,
        duration
      }
    ])
    resolveOverlap(ctx, 'subtitle', id)
    S.selectedClip.set({ track: 'subtitle', id })
    editSubtitle(ctx, id)
    scheduleRebuild(ctx, 'subtitle')
    return
  }

  if (track === 'music') {
    openEditMusicModal(ctx)
  }
}

export function onMissingVoiceClick(ctx: VideoPreviewCtx, videoClipId: string) {
  const clip = ctx.state.videoClips.get().find((v) => v.id === videoClipId)
  if (!clip) return
  ctx.state.selectedClip.set({ track: 'video', id: videoClipId })
  seekClipAndPreview(ctx, clip.start)
  openEditDubbingModalForClip(ctx, videoClipId)
}

export function replaceVoiceForItem(ctx: VideoPreviewCtx, voiceId: string) {
  const item = ctx.state.voiceItems.get().find((v) => v.id === voiceId)
  if (!item) return
  ctx.state.selectedClip.set({ track: 'voice', id: voiceId })
  ctx.state.pendingAddAudioTrack.set('voice')
  ctx.state.pendingVoiceVideoClipId.set(item.videoClipId || null)
  ctx.state.replacingVoiceId.set(voiceId)
  ctx.dom.audioInputRef.current?.click()
}

export function onMissingSubtitleClick(ctx: VideoPreviewCtx, videoClipId: string) {
  const clip = ctx.state.videoClips.get().find((v) => v.id === videoClipId)
  if (!clip) return
  ctx.state.selectedClip.set({ track: 'video', id: videoClipId })
  seekClipAndPreview(ctx, clip.start)
  addSubtitleForVideoClip(ctx, clip)
}

function addSubtitleForVideoClip(ctx: VideoPreviewCtx, clip: TimelineVideoClip) {
  const S = ctx.state
  const existed = S.subtitleItems.get().find((s) => s.videoClipId === clip.id)
  if (existed) {
    editSubtitle(ctx, existed.id)
    return
  }
  const clipStart = clip.start
  const duration = Math.max(MIN_DURATION, Number(clip.duration.toFixed(2)))
  const id = `sub-${Date.now()}`
  S.subtitleItems.set([
    ...S.subtitleItems.get(),
    {
      id,
      kind: 'subtitle',
      text: '',
      fontSize: 40,
      videoClipId: clip.id,
      start: clipStart,
      duration
    }
  ])
  resolveOverlap(ctx, 'subtitle', id)
  editSubtitle(ctx, id)
}

export function editSubtitle(ctx: VideoPreviewCtx, id: string) {
  const S = ctx.state
  const item = S.subtitleItems.get().find((x) => x.id === id)
  if (!item) return
  S.editingSubtitleId.set(id)
  S.subtitleDraft.set(item.text)
  S.subtitleFontSizeDraft.set(item.fontSize || 40)
  S.subtitleModalOpen.set(true)
}

export function saveSubtitle(ctx: VideoPreviewCtx) {
  const S = ctx.state
  const id = S.editingSubtitleId.get()
  if (!id) return
  const item = S.subtitleItems.get().find((x) => x.id === id)
  if (!item) return
  item.text = S.subtitleDraft.get().trim()
  item.fontSize = Math.max(20, Math.min(72, Number(S.subtitleFontSizeDraft.get() || 40)))
  touchSubtitleItems(ctx)
  S.subtitleModalOpen.set(false)
  S.editingSubtitleId.set(null)
  scheduleRebuild(ctx, 'subtitle')
  scheduleTimelinePersist(ctx)
}

export async function onAudioFileSelected(ctx: VideoPreviewCtx, e: React.ChangeEvent<HTMLInputElement>) {
  const S = ctx.state
  const input = e.target
  const file = input.files?.[0]
  input.value = ''
  const track = S.pendingAddAudioTrack.get()
  S.pendingAddAudioTrack.set(null)
  const targetVideoClipId = S.pendingVoiceVideoClipId.get()
  S.pendingVoiceVideoClipId.set(null)
  const replaceId = S.replacingVoiceId.get()
  S.replacingVoiceId.set(null)
  if (!file || !track) return

  if (track === 'music') {
    openEditMusicModal(ctx)
    return
  }

  const activeVideo = targetVideoClipId
    ? S.videoClips.get().find((video) => video.id === targetVideoClipId) || null
    : getActiveVideoClipForOperation(ctx)
  if (!activeVideo) {
    message.warning('请先选中或定位到某个分镜视频片段再添加配音')
    return
  }

  const { uploadAudioToOssWithToast } = await import('~/utils/ossUpload')
  const url = await uploadAudioToOssWithToast(file)
  if (!url) return

  if (replaceId) {
    const existing = S.voiceItems.get().find((v) => v.id === replaceId)
    if (existing) {
      existing.name = file.name
      existing.url = url
      existing.videoClipId = activeVideo.id
      existing.start = activeVideo.start
      existing.duration = Math.max(MIN_DURATION, Number(activeVideo.duration.toFixed(2)))
      touchVoiceItems(ctx)
      message.success('已替换配音')
      scheduleRebuild(ctx, 'audio')
      scheduleTimelinePersist(ctx)
      return
    }
  }
  const item: TimelineAudioItem = {
    id: `voice-${Date.now()}`,
    kind: 'voice',
    name: file.name,
    url,
    start: Number(S.currentTime.get().toFixed(2)),
    duration: 5,
    volume: 1,
    fadeIn: 0,
    fadeOut: 0,
    loop: false,
    volumeCurve: [1, 1, 1]
  }
  item.videoClipId = activeVideo.id
  item.start = activeVideo.start
  item.duration = Math.max(MIN_DURATION, Number(activeVideo.duration.toFixed(2)))
  void probeAudioDuration(url).then((dur) => {
    item.sourceDuration = dur
    touchVoiceItems(ctx)
    if (syncUntimedSubtitleToVoiceDuration(ctx, item)) {
      scheduleRebuild(ctx, 'all')
      scheduleTimelinePersist(ctx)
    }
  })
  S.voiceItems.set([...S.voiceItems.get(), item])
  resolveOverlap(ctx, 'voice', item.id)
  scheduleRebuild(ctx, 'audio')
  scheduleTimelinePersist(ctx)
}

export function onSubtitleRangePointerDown(ctx: VideoPreviewCtx, e: React.PointerEvent) {
  const S = ctx.state
  const target = e.target as HTMLElement
  if (target.closest('.track-clip')) return
  const strip = ctx.dom.subtitleStripRef.current
  if (!strip) return
  const rect = strip.getBoundingClientRect()
  const start = pxToSec(ctx, e.clientX - rect.left)
  S.subtitleRange.set({ active: true, startSec: start, endSec: start })

  const move = (ev: PointerEvent) => {
    const x = Math.max(0, Math.min(rect.width, ev.clientX - rect.left))
    S.subtitleRange.set({ ...S.subtitleRange.get(), endSec: pxToSec(ctx, x) })
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    const range = S.subtitleRange.get()
    const s = Math.min(range.startSec, range.endSec)
    const eSec = Math.max(range.startSec, range.endSec)
    const d = Number((eSec - s).toFixed(2))
    S.subtitleRange.set({ ...S.subtitleRange.get(), active: false })
    if (d < 0.15) return
    const id = `sub-range-${Date.now()}`
    S.subtitleItems.set([
      ...S.subtitleItems.get(),
      { id, kind: 'subtitle', text: '请输入字幕', fontSize: 40, start: Number(s.toFixed(2)), duration: d }
    ])
    const activeVideo = getActiveVideoClipForOperation(ctx)
    if (activeVideo) {
      const list = S.subtitleItems.get()
      const item = list[list.length - 1]!
      item.videoClipId = activeVideo.id
      const clipEnd = activeVideo.start + activeVideo.duration
      if (item.start < activeVideo.start) item.start = activeVideo.start
      if (item.start + item.duration > clipEnd)
        item.duration = Math.max(MIN_DURATION, Number((clipEnd - item.start).toFixed(2)))
      touchSubtitleItems(ctx)
    }
    resolveOverlap(ctx, 'subtitle', id)
    editSubtitle(ctx, id)
    scheduleRebuild(ctx, 'subtitle')
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

// --- keyboard ---

function isEditableKeyboardTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el) return false
  const tag = String(el.tagName || '').toUpperCase()
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  if (el.isContentEditable) return true
  return !!el.closest?.('input, textarea, select, [contenteditable="true"]')
}

/** 空格：播放 / 暂停（与点击预览区一致） */
export function onPreviewKeyboard(ctx: VideoPreviewCtx, e: KeyboardEvent) {
  if (e.code !== 'Space' && e.key !== ' ') return
  if (e.repeat) return
  if (isEditableKeyboardTarget(e.target)) return
  if (!ctx.state.videoClips.get().length) return
  e.preventDefault()
  void togglePlay(ctx)
}

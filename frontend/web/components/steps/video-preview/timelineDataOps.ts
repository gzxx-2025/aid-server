import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import type { PreviewTimelineUiState } from '~/utils/episodeTimelineMap'
import {
resolvePreviewSubtitleText,
resolvePreviewTimelineVideoUrl
} from '~/utils/storyboardVideoCover'
import { scheduleRebuild } from './canvasOps'
import { hasClipVideoUrl } from './layoutOps'
import { preloadPreviewTimelineAudios,preloadVideoUrl,resetPlayheadToStart } from './playbackOps'
import {
EMPTY_CLIP_DURATION,
MIN_DURATION,
VIDEO_CLIP_FALLBACK_DURATION,
type TimelineAudioItem,
type TimelineSubtitleItem,
type TimelineVideoClip,
type VideoPreviewCtx
} from './types'
export function touchVideoClips(ctx: VideoPreviewCtx) {
  ctx.state.videoClips.set([...ctx.state.videoClips.get()])
}
export function touchVoiceItems(ctx: VideoPreviewCtx) {
  ctx.state.voiceItems.set([...ctx.state.voiceItems.get()])
}
export function touchSubtitleItems(ctx: VideoPreviewCtx) {
  ctx.state.subtitleItems.set([...ctx.state.subtitleItems.get()])
}
export function touchMusicItems(ctx: VideoPreviewCtx) {
  ctx.state.musicItems.set([...ctx.state.musicItems.get()])
}

export function projectScopeKeyOf(): string {
  const store = useCreationStore.getState()
  return `${store.currentProjectId ?? ''}:${store.currentEpisodeId ?? ''}`
}

export function scheduleTimelinePersist(ctx: VideoPreviewCtx) {
  if (typeof window === 'undefined') return
  const S = ctx.state
  if (!S.videoClips.get().length && !S.serverTimelineBaseline.get()) return
  ctx.runtime.timelineSaver.schedule({
    store: useCreationStore.getState(),
    route: ctx.getRoute(),
    previousTimeline: S.serverTimelineBaseline.get(),
    ui: {
      videoClips: S.videoClips.get(),
      voiceItems: S.voiceItems.get(),
      subtitleItems: S.subtitleItems.get(),
      musicItems: S.musicItems.get(),
      videoVolumePreset: S.videoVolumePreset.get(),
      resolution: S.timelineResolution.get()
    }
  })
}

export async function probeVideoDuration(url: string): Promise<number> {
  if (!url) return EMPTY_CLIP_DURATION
  return await new Promise((resolve) => {
    const el = document.createElement('video')
    el.preload = 'metadata'
    el.src = url
    const done = (v: number) => {
      try {
        el.removeAttribute('src')
        el.load()
      } catch {}
      resolve(Number.isFinite(v) && v > 0 ? v : VIDEO_CLIP_FALLBACK_DURATION)
    }
    el.onloadedmetadata = () => done(el.duration)
    el.onerror = () => done(VIDEO_CLIP_FALLBACK_DURATION)
  })
}

export async function probeAudioDuration(url: string): Promise<number> {
  if (!url) return VIDEO_CLIP_FALLBACK_DURATION
  return await new Promise((resolve) => {
    const el = document.createElement('audio')
    el.preload = 'metadata'
    el.src = url
    const done = (v: number) => {
      try {
        el.removeAttribute('src')
        el.load()
      } catch {}
      resolve(Number.isFinite(v) && v > 0 ? v : VIDEO_CLIP_FALLBACK_DURATION)
    }
    el.onloadedmetadata = () => done(el.duration)
    el.onerror = () => done(VIDEO_CLIP_FALLBACK_DURATION)
  })
}

export function getInitialClipDuration(url: string) {
  return hasClipVideoUrl({ url }) ? VIDEO_CLIP_FALLBACK_DURATION : EMPTY_CLIP_DURATION
}

export function applyClipTimelineDuration(clip: TimelineVideoClip, duration: number) {
  const d = Math.max(MIN_DURATION, Number(duration.toFixed(2)))
  clip.sourceDuration = d
  clip.duration = d
  clip.trimStart = 0
  clip.trimEnd = d
}

function getVideoTimelineTotalSecLocal(ctx: VideoPreviewCtx) {
  return ctx.state.videoClips.get().reduce((sum, clip) => sum + clip.duration, 0)
}

function applyMusicTimelineDuration(ctx: VideoPreviewCtx, m: TimelineAudioItem) {
  const videoTotal = Math.max(MIN_DURATION, getVideoTimelineTotalSecLocal(ctx))
  const source = m.sourceDuration && m.sourceDuration > 0 ? m.sourceDuration : m.duration
  if (m.loop) {
    m.duration = Number(Math.max(videoTotal, MIN_DURATION).toFixed(2))
    return
  }
  const available = Math.max(MIN_DURATION, videoTotal - m.start)
  m.duration = Number(Math.min(source, available).toFixed(2))
}

export function syncMusicTimelineDurations(ctx: VideoPreviewCtx) {
  if (!ctx.state.musicItems.get().length) return
  ctx.state.musicItems.get().forEach((m) => applyMusicTimelineDuration(ctx, m))
  touchMusicItems(ctx)
}

export async function hydrateMusicDurationsFromSource(ctx: VideoPreviewCtx) {
  if (!ctx.state.musicItems.get().length) return
  for (const m of ctx.state.musicItems.get()) {
    if (!m.url) continue
    m.sourceDuration = await probeAudioDuration(m.url)
  }
  syncMusicTimelineDurations(ctx)
}

export async function hydrateVoiceDurationsFromSource(ctx: VideoPreviewCtx) {
  let subtitleChanged = false
  for (const voice of ctx.state.voiceItems.get()) {
    if (!voice.url) continue
    const probed = await probeAudioDuration(voice.url)
    if (probed > 0.5) {
      voice.sourceDuration = probed
    } else if (!(Number(voice.sourceDuration) > 0.5)) {
      voice.sourceDuration = Math.max(0.1, voice.duration)
    }
    subtitleChanged = syncUntimedSubtitleToVoiceDuration(ctx, voice) || subtitleChanged
  }
  touchVoiceItems(ctx)
  if (subtitleChanged) {
    touchSubtitleItems(ctx)
    scheduleRebuild(ctx, 'all')
    scheduleTimelinePersist(ctx)
  }
}

export function syncUntimedSubtitleToVoiceDuration(
  ctx: VideoPreviewCtx,
  voice: TimelineAudioItem
): boolean {
  if (!voice.videoClipId) return false
  const clip = ctx.state.videoClips.get().find((item) => item.id === voice.videoClipId)
  if (!clip) return false
  const voiceDuration = Number(voice.sourceDuration)
  if (!Number.isFinite(voiceDuration) || voiceDuration <= 0.5) return false
  let changed = false
  ctx.state.subtitleItems.get().forEach((sub) => {
    if (sub.videoClipId !== voice.videoClipId || sub.cue) return
    const nextStart = clip.start
    const nextDuration = resolveUntimedSubtitleDuration(clip, voice)
    if (Math.abs(sub.start - nextStart) > 0.01 || Math.abs(sub.duration - nextDuration) > 0.01) {
      sub.start = nextStart
      sub.duration = nextDuration
      changed = true
    }
  })
  if (changed) touchSubtitleItems(ctx)
  return changed
}

export function resolveUntimedSubtitleDuration(
  clip: TimelineVideoClip,
  voice?: TimelineAudioItem
): number {
  const voiceDuration = Number(voice?.sourceDuration)
  const duration = Number.isFinite(voiceDuration) && voiceDuration > 0.5
    ? Math.min(clip.duration, voiceDuration)
    : clip.duration
  return Math.max(MIN_DURATION, Number(duration.toFixed(2)))
}

export function relayoutVideoTrackAndLinkedTracks(ctx: VideoPreviewCtx) {
  const S = ctx.state
  // 按时间顺序紧挨排列，消除 start 与 duration 不一致造成的大间隔
  const ordered = [...S.videoClips.get()].sort((a, b) => a.start - b.start)
  let cursor = 0
  for (const v of ordered) {
    const previousStart = v.start
    v.start = Number(cursor.toFixed(2))
    cursor = Number((cursor + v.duration).toFixed(2))

    // 配音按 videoClipId 对齐（勿用数组下标，缺配音时会错位），轨道宽度始终填满分镜
    S.voiceItems.get().forEach((voice) => {
      if (voice.videoClipId !== v.id) return
      voice.start = v.start
      voice.duration = Math.max(MIN_DURATION, Number(v.duration.toFixed(2)))
    })

    S.subtitleItems.get().forEach((sub) => {
      if (sub.videoClipId !== v.id) return
      if (sub.cue) {
        const relativeStart = Math.max(0, Number((sub.start - previousStart).toFixed(2)))
        const maxDuration = Math.max(MIN_DURATION, Number((v.duration - relativeStart).toFixed(2)))
        sub.start = Number((v.start + relativeStart).toFixed(2))
        sub.duration = Math.max(MIN_DURATION, Math.min(sub.duration, maxDuration))
        return
      }
      const linkedVoice = S.voiceItems
        .get()
        .find((voice) => voice.videoClipId === v.id && Number(voice.sourceDuration) > 0.5)
      sub.start = v.start
      sub.duration = resolveUntimedSubtitleDuration(v, linkedVoice)
    })
  }
  S.videoClips.set(ordered)
  touchVoiceItems(ctx)
  touchSubtitleItems(ctx)
  if (S.musicItems.get().length) {
    syncMusicTimelineDurations(ctx)
  }
}

export function relayoutVideoTrackWithLinkedByOrder(ctx: VideoPreviewCtx) {
  relayoutVideoTrackAndLinkedTracks(ctx)
}

export function normalizeEmptyClipDurations(ctx: VideoPreviewCtx) {
  let changed = false
  ctx.state.videoClips.get().forEach((clip) => {
    if (!hasClipVideoUrl(clip) && Math.abs(clip.duration - EMPTY_CLIP_DURATION) > 0.01) {
      applyClipTimelineDuration(clip, EMPTY_CLIP_DURATION)
      changed = true
    }
  })
  if (changed) {
    touchVideoClips(ctx)
    relayoutVideoTrackAndLinkedTracks(ctx)
    scheduleRebuild(ctx, 'all')
  }
}

export async function hydrateVideoDurationsFromSource(ctx: VideoPreviewCtx) {
  const S = ctx.state
  if (!S.videoClips.get().length) return
  const clips = [...S.videoClips.get()]
  // 先全部探测完再写回，避免探测过程中 duration/start 不同步出现大间隔
  const probed = await Promise.all(
    clips.map(async (clip) => {
      if (!hasClipVideoUrl(clip)) return EMPTY_CLIP_DURATION
      return probeVideoDuration(clip.url)
    })
  )
  clips.forEach((clip, i) => {
    applyClipTimelineDuration(clip, probed[i] ?? EMPTY_CLIP_DURATION)
  })
  S.videoClips.set(clips)
  relayoutVideoTrackAndLinkedTracks(ctx)
  scheduleRebuild(ctx, 'all')
  clips.forEach((clip) => {
    if (hasClipVideoUrl(clip)) preloadVideoUrl(ctx, clip.url)
  })
  void hydrateMusicDurationsFromSource(ctx)
}

export function applyServerTimelineUi(ctx: VideoPreviewCtx, ui: PreviewTimelineUiState) {
  const S = ctx.state
  S.videoClips.set(ui.videoClips as TimelineVideoClip[])
  S.voiceItems.set(ui.voiceItems as TimelineAudioItem[])
  S.subtitleItems.set(ui.subtitleItems as TimelineSubtitleItem[])
  S.musicItems.set(ui.musicItems as TimelineAudioItem[])
  S.videoVolumePreset.set({ ...ui.videoVolumePreset })
  S.timelineResolution.set(ui.resolution || 'FHD')
  S.selectedClip.set(
    ui.videoClips[0] ? { track: 'video', id: ui.videoClips[0]!.id } : null
  )
  resetPlayheadToStart(ctx)
  preloadPreviewTimelineAudios(ctx)
  void hydrateVideoDurationsFromSource(ctx).then(() => {
    resetPlayheadToStart(ctx)
  })
  void hydrateMusicDurationsFromSource(ctx)
  void hydrateVoiceDurationsFromSource(ctx)
  scheduleRebuild(ctx, 'all')
}

// --- build from previous steps ---
export function buildTimelineFromProps(
  ctx: VideoPreviewCtx,
  options?: { showSuccessMessage?: boolean }
): boolean {
  const S = ctx.state
  const props = ctx.getProps()
  const panels = props.dubbingPanels || []
  const videoPanels = props.storyboardVideoPanels || []
  if (!panels.length || !videoPanels.length) return false

  let start = 0
  const nextVideo: TimelineVideoClip[] = []
  const nextVoice: TimelineAudioItem[] = []
  const nextSub: TimelineSubtitleItem[] = []

  for (let i = 0; i < panels.length; i++) {
    const dub = panels[i]
    const vPanel = videoPanels[i]
    const url = resolvePreviewTimelineVideoUrl(dub, vPanel)

    const name = dub?.title || vPanel?.title || `分镜${i + 1}`
    const subtitleText = resolvePreviewSubtitleText(dub)
    const clipDur = getInitialClipDuration(url)

    const clip: TimelineVideoClip = {
      id: dub?.id || `v-${i}-${Date.now()}`,
      kind: 'video',
      name,
      url,
      start,
      duration: clipDur,
      sourceDuration: clipDur,
      trimStart: 0,
      trimEnd: clipDur
    }
    nextVideo.push(clip)

    const voiceUrl = dub?.dubbingUploadedAudioUrl?.trim()
    let timelineVoice: TimelineAudioItem | undefined
    if (voiceUrl) {
      const presetVol = S.videoVolumePreset.get()[clip.id] ?? 1
      timelineVoice = {
        id: `voice-${clip.id}`,
        kind: 'voice',
        name: `配音 ${i + 1}`,
        url: voiceUrl,
        videoClipId: clip.id,
        start,
        duration: clipDur,
        volume: presetVol,
        fadeIn: 0,
        fadeOut: 0,
        loop: false,
        volumeCurve: [presetVol, presetVol, presetVol]
      }
      nextVoice.push(timelineVoice)
    }

    if (subtitleText) {
      nextSub.push({
        id: `sub-${clip.id}`,
        kind: 'subtitle',
        text: subtitleText,
        fontSize: 40,
        videoClipId: clip.id,
        start,
        duration: resolveUntimedSubtitleDuration(clip, timelineVoice)
      })
    }

    start += clip.duration
  }

  S.videoClips.set(nextVideo)
  S.voiceItems.set(nextVoice)
  S.subtitleItems.set(nextSub)
  S.musicItems.set(
    props.bgm?.trim()
      ? [
          {
            id: `music-${Date.now()}`,
            kind: 'music',
            name: '背景音乐',
            url: props.bgm!.trim(),
            start: 0,
            duration: Math.max(1, start),
            sourceDuration: Math.max(1, start),
            volume: 0.25,
            fadeIn: 0,
            fadeOut: 0,
            loop: true,
            volumeCurve: [0.25, 0.25, 0.25]
          }
        ]
      : []
  )

  S.currentTime.set(0)
  S.playing.set(false)
  if (options?.showSuccessMessage) {
    message.success('已同步到时间轴（支持拖拽/新增字幕/新增配音/新增音乐）')
  }
  resetPlayheadToStart(ctx)
  preloadPreviewTimelineAudios(ctx)
  void hydrateVideoDurationsFromSource(ctx).then(() => {
    resetPlayheadToStart(ctx)
  })
  void hydrateMusicDurationsFromSource(ctx)
  void hydrateVoiceDurationsFromSource(ctx)
  return true
}


import {
getNextPlayableClip,
getVideoClipAtTime,
getVideoVolume,
getVoiceItemForVideoClip,
hasClipVideoUrl
} from './layoutOps'
import {
PLAYING_SEEK_DRIFT_SEC,
STANDBY_PREPARE_REMAIN_SEC,
type PreviewAudioEl,
type TimelineVideoClip,
type VideoPreviewCtx
} from './types'
export function setTimelineScrollLeft(ctx: VideoPreviewCtx, wrap: HTMLElement, value: number) {
  ctx.runtime.programmaticScrollLockUntil = performance.now() + 120
  ctx.runtime.suppressScrollFollowPause = true
  wrap.scrollLeft = value
  requestAnimationFrame(() => {
    ctx.runtime.suppressScrollFollowPause = false
  })
}

export function scrollTimelineToStart(ctx: VideoPreviewCtx) {
  const wrap = ctx.dom.timelineWrapRef.current
  if (!wrap) return
  setTimelineScrollLeft(ctx, wrap, 0)
}

export function resetPlayheadToStart(ctx: VideoPreviewCtx) {
  ctx.state.currentTime.set(0)
  ctx.state.playing.set(false)
  setTimeout(() => {
    scrollTimelineToStart(ctx)
    // 布局宽度可能随后续时长探测变化，再补一次滚动
    requestAnimationFrame(() => scrollTimelineToStart(ctx))
  }, 0)
}

export function getActiveNativeEl(ctx: VideoPreviewCtx): HTMLVideoElement | null {
  return ctx.state.nativeActiveSlot.get() === 'A'
    ? ctx.dom.nativePreviewVideoARef.current
    : ctx.dom.nativePreviewVideoBRef.current
}

export function getStandbyNativeEl(ctx: VideoPreviewCtx): HTMLVideoElement | null {
  return ctx.state.nativeActiveSlot.get() === 'A'
    ? ctx.dom.nativePreviewVideoBRef.current
    : ctx.dom.nativePreviewVideoARef.current
}

function getSlotSrc(ctx: VideoPreviewCtx, slot: 'A' | 'B') {
  return slot === 'A' ? ctx.runtime.slotSrcA : ctx.runtime.slotSrcB
}

function setSlotSrc(ctx: VideoPreviewCtx, slot: 'A' | 'B', url: string) {
  if (slot === 'A') ctx.runtime.slotSrcA = url
  else ctx.runtime.slotSrcB = url
}

export function preloadVideoUrl(ctx: VideoPreviewCtx, url: string, mode: 'metadata' | 'auto' = 'metadata') {
  const normalized = String(url || '').trim()
  if (!normalized) return
  const existing = ctx.runtime.previewVideoPreloads.get(normalized)
  if (existing) {
    if (mode === 'auto' && existing.preload !== 'auto') {
      existing.preload = 'auto'
      try {
        existing.load()
      } catch {}
    }
    return
  }
  const el = document.createElement('video')
  // 默认只拉元数据，避免与正在播放的分镜抢带宽
  el.preload = mode
  el.muted = true
  el.playsInline = true
  el.src = normalized
  try {
    el.load()
  } catch {}
  ctx.runtime.previewVideoPreloads.set(normalized, el)
}

export function preloadAdjacentClips(ctx: VideoPreviewCtx, clip: TimelineVideoClip | null) {
  if (!clip) return
  preloadVideoUrl(ctx, clip.url, 'metadata')
  const next = getNextPlayableClip(ctx, clip)
  if (next) preloadVideoUrl(ctx, next.url, 'metadata')
}

function waitVideoReady(el: HTMLVideoElement, timeoutMs = 4000): Promise<boolean> {
  if (el.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) return Promise.resolve(true)
  return new Promise((resolve) => {
    let settled = false
    const done = (ok: boolean) => {
      if (settled) return
      settled = true
      el.removeEventListener('canplay', onReady)
      el.removeEventListener('loadeddata', onReady)
      el.removeEventListener('error', onErr)
      window.clearTimeout(timer)
      resolve(ok)
    }
    const onReady = () => done(true)
    const onErr = () => done(false)
    const timer = window.setTimeout(() => done(el.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA), timeoutMs)
    el.addEventListener('canplay', onReady)
    el.addEventListener('loadeddata', onReady)
    el.addEventListener('error', onErr)
  })
}

async function loadUrlOntoVideoEl(
  ctx: VideoPreviewCtx,
  el: HTMLVideoElement,
  slot: 'A' | 'B',
  url: string,
  seekTo = 0
): Promise<boolean> {
  const normalized = String(url || '').trim()
  if (!normalized) return false
  preloadVideoUrl(ctx, normalized, 'auto')
  const current = getSlotSrc(ctx, slot)
  if (current !== normalized) {
    setSlotSrc(ctx, slot, normalized)
    el.src = normalized
    try {
      el.load()
    } catch {}
  }
  const ready = await waitVideoReady(el)
  if (!ready) return false
  try {
    const target = Math.max(0, seekTo)
    if (Number.isFinite(el.duration) && el.duration > 0) {
      el.currentTime = Math.min(target, Math.max(0, el.duration - 0.05))
    } else {
      el.currentTime = target
    }
  } catch {}
  return true
}

/** 仅在切镜前短窗口内预载下一分镜，避免播放中全程抢带宽 */
async function prepareStandbyForClip(ctx: VideoPreviewCtx, clip: TimelineVideoClip | null) {
  const R = ctx.runtime
  if (!clip || !hasClipVideoUrl(clip)) return
  const standby = getStandbyNativeEl(ctx)
  if (!standby) return
  if (R.standbyPreparedClipId === clip.id && standby.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
    return
  }
  const token = ++R.standbyPrepareToken
  const slot: 'A' | 'B' = ctx.state.nativeActiveSlot.get() === 'A' ? 'B' : 'A'
  const seekTo = Math.max(0, Number(clip.trimStart) || 0)
  const ok = await loadUrlOntoVideoEl(ctx, standby, slot, clip.url, seekTo)
  if (token !== R.standbyPrepareToken) return
  if (ok) R.standbyPreparedClipId = clip.id
}

export async function ensureActiveNativeVideoSrc(ctx: VideoPreviewCtx, url: string, seekTo = 0) {
  const el = getActiveNativeEl(ctx)
  if (!el) return
  const slot = ctx.state.nativeActiveSlot.get()
  await loadUrlOntoVideoEl(ctx, el, slot, url, seekTo)
  refreshNativePreviewFrameReady(ctx)
}

export function refreshNativePreviewFrameReady(ctx: VideoPreviewCtx) {
  const el = getActiveNativeEl(ctx)
  ctx.state.nativePreviewFrameReady.set(
    !!el && el.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA
  )
}

export function onNativePreviewMediaReady(ctx: VideoPreviewCtx) {
  syncNativePreviewVideoTime(ctx)
  refreshNativePreviewFrameReady(ctx)
}

function mapVolumeToGain(vol: number) {
  return Math.max(0, Math.min(1, vol / 2))
}

export function getPreviewAudioEl(ctx: VideoPreviewCtx, id: string, url: string): PreviewAudioEl {
  let el = ctx.runtime.previewAudioEls.get(id) as PreviewAudioEl | undefined
  if (!el) {
    el = new Audio() as PreviewAudioEl
    el.preload = 'auto'
    // 不设 crossOrigin：部分 CDN 未回 CORS 头时会导致配音完全无法加载
    ctx.runtime.previewAudioEls.set(id, el)
  }
  // 勿用 el.src（浏览器会解析成绝对地址）与入参直接比较，否则每帧重置 src 导致配音播不起来
  if (el._aidUrl !== url) {
    el._aidUrl = url
    el._aidPlayFailed = false
    el.src = url
    try {
      el.load()
    } catch {
      /* ignore */
    }
  }
  return el
}

/** 时间轴音频音量多为 0~1；兼容音量条 0~2 */
function mapTimelineAudioGain(vol: number) {
  if (!Number.isFinite(vol)) return 1
  if (vol <= 1) return Math.max(0, Math.min(1, vol))
  return Math.max(0, Math.min(1, vol / 2))
}

export function resolveAudioPlayableDuration(
  item: { sourceDuration?: number; duration: number },
  audio: HTMLAudioElement
): number {
  if (Number.isFinite(audio.duration) && audio.duration > 0) return audio.duration
  const src = Number(item.sourceDuration)
  // 过小的 sourceDuration 多为脏数据（曾把 0 写成 0.1），回落轨道时长
  if (Number.isFinite(src) && src > 0.5) return src
  return Math.max(0.1, Number(item.duration) || 0.1)
}

export function stopAllPreviewAudios(ctx: VideoPreviewCtx) {
  for (const el of ctx.runtime.previewAudioEls.values()) {
    try {
      el.pause()
    } catch {}
  }
}

function playPreviewAudioEl(audio: PreviewAudioEl) {
  if (!audio.paused) return
  const p = audio.play()
  if (p && typeof p.then === 'function') {
    void p.then(() => {
      audio._aidPlayFailed = false
    }).catch(() => {
      audio._aidPlayFailed = true
    })
  }
}

export function preloadPreviewTimelineAudios(ctx: VideoPreviewCtx) {
  for (const voice of ctx.state.voiceItems.get()) {
    if (!voice.url) continue
    getPreviewAudioEl(ctx, `voice-${voice.id}`, voice.url)
  }
  for (const music of ctx.state.musicItems.get()) {
    if (!music.url) continue
    getPreviewAudioEl(ctx, `music-${music.id}`, music.url)
  }
}

export function syncPreviewAudios(ctx: VideoPreviewCtx) {
  const S = ctx.state
  const t = S.currentTime.get()
  const shouldPlay = S.playing.get() && !S.muted.get()

  for (const voice of S.voiceItems.get()) {
    if (!voice.url) continue
    const audio = getPreviewAudioEl(ctx, `voice-${voice.id}`, voice.url)
    audio.volume = mapTimelineAudioGain(voice.volume ?? 1)
    const inRange = t >= voice.start && t < voice.start + voice.duration
    if (shouldPlay && inRange) {
      const playableDur = resolveAudioPlayableDuration(voice, audio)
      const offset = t - voice.start
      const metaReady = Number.isFinite(audio.duration) && audio.duration > 0
      // 元数据就绪后才按真实音频时长截断；未就绪时先播，避免被错误的 0.1s sourceDuration 卡死
      if (metaReady && offset >= playableDur) {
        if (!audio.paused) audio.pause()
        continue
      }
      if (metaReady && Math.abs(audio.currentTime - Math.min(offset, playableDur)) > 0.25) {
        try {
          audio.currentTime = Math.max(0, Math.min(offset, Math.max(0, playableDur - 0.05)))
        } catch {
          /* ignore */
        }
      }
      playPreviewAudioEl(audio)
    } else if (!audio.paused) {
      audio.pause()
    }
  }

  for (const music of S.musicItems.get()) {
    if (!music.url) continue
    const audio = getPreviewAudioEl(ctx, `music-${music.id}`, music.url)
    audio.volume = mapTimelineAudioGain(music.volume ?? 1)
    const inRange = t >= music.start && t < music.start + music.duration
    if (shouldPlay && inRange) {
      const playableDur = resolveAudioPlayableDuration(music, audio)
      let offset = t - music.start
      if (music.loop && playableDur > 0) {
        offset = offset % playableDur
      } else {
        offset = Math.max(0, Math.min(offset, playableDur))
      }
      const metaReady = Number.isFinite(audio.duration) && audio.duration > 0
      if (metaReady && Math.abs(audio.currentTime - offset) > 0.25) {
        try {
          audio.currentTime = Math.max(0, offset)
        } catch {
          /* ignore */
        }
      }
      playPreviewAudioEl(audio)
    } else if (!audio.paused) {
      audio.pause()
    }
  }
}

export function applyNativeVideoVolume(
  ctx: VideoPreviewCtx,
  el: HTMLVideoElement,
  clip: TimelineVideoClip
) {
  const voice = getVoiceItemForVideoClip(ctx, clip.id)
  const vol = getVideoVolume(ctx, clip.id)
  const voiceUrl = String(voice?.url || '').trim()
  const voiceAudio = voiceUrl
    ? (ctx.runtime.previewAudioEls.get(`voice-${voice!.id}`) as PreviewAudioEl | undefined)
    : undefined
  // 有独立配音时视频原声静音，改走 Audio 轨；配音播放失败则回退视频声道（compose 成片可能已含配音）
  const voiceFailed = !!voiceAudio?._aidPlayFailed
  const muteForVoice = !!voiceUrl && !voiceFailed
  el.muted = ctx.state.muted.get() || muteForVoice
  if (!muteForVoice) el.volume = ctx.state.muted.get() ? 0 : mapVolumeToGain(vol)
}

export function syncNativePreviewVideoTime(ctx: VideoPreviewCtx) {
  const S = ctx.state
  const R = ctx.runtime
  const clip = getVideoClipAtTime(ctx, S.currentTime.get())
  if (!clip || !hasClipVideoUrl(clip)) return

  const offset = Math.max(0, S.currentTime.get() - clip.start + (clip.trimStart || 0))
  const clipEnd = clip.start + clip.duration
  const remain = clipEnd - S.currentTime.get()
  const nearClipEnd = remain <= STANDBY_PREPARE_REMAIN_SEC
  const nextClip = getNextPlayableClip(ctx, clip)

  // 只在临近切镜时预载待命层（勿在整个播放过程中抢带宽）
  if (nextClip && nearClipEnd) {
    void prepareStandbyForClip(ctx, nextClip)
  }

  if (R.activeNativeClipId !== clip.id) {
    const standby = getStandbyNativeEl(ctx)
    const canSeamlessSwap =
      !!standby &&
      R.standbyPreparedClipId === clip.id &&
      standby.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA

    if (canSeamlessSwap && standby) {
      applyNativeVideoVolume(ctx, standby, clip)
      try {
        const seekTo = Math.max(0, Number(clip.trimStart) || 0)
        if (Number.isFinite(standby.duration) && standby.duration > 0) {
          standby.currentTime = Math.min(seekTo, Math.max(0, standby.duration - 0.05))
        } else {
          standby.currentTime = seekTo
        }
      } catch {}
      const prev = getActiveNativeEl(ctx)
      S.nativeActiveSlot.set(S.nativeActiveSlot.get() === 'A' ? 'B' : 'A')
      R.activeNativeClipId = clip.id
      R.standbyPreparedClipId = ''
      S.nativePreviewFrameReady.set(true)
      if (S.playing.get()) {
        standby.play().catch(() => {})
      } else {
        try {
          standby.pause()
        } catch {}
      }
      if (prev && prev !== standby) {
        try {
          prev.pause()
        } catch {}
      }
      return
    }

    R.activeNativeClipId = clip.id
    R.standbyPreparedClipId = ''
    S.nativePreviewFrameReady.set(false)
    void ensureActiveNativeVideoSrc(ctx, clip.url, offset).then(() => {
      const el = getActiveNativeEl(ctx)
      if (!el || R.activeNativeClipId !== clip.id) return
      applyNativeVideoVolume(ctx, el, clip)
      refreshNativePreviewFrameReady(ctx)
      if (S.playing.get() && el.paused) el.play().catch(() => {})
    })
    return
  }

  const el = getActiveNativeEl(ctx)
  if (!el) return
  applyNativeVideoVolume(ctx, el, clip)

  try {
    if (S.playing.get()) {
      // 播放中让 video 自然播，仅纠正明显漂移；禁止每帧 seek
      if (!el.seeking && Number.isFinite(el.duration) && el.duration > 0) {
        const target = Math.min(offset, Math.max(0, el.duration - 0.05))
        if (Math.abs(el.currentTime - target) > PLAYING_SEEK_DRIFT_SEC) {
          el.currentTime = target
        }
      }
      if (el.paused) el.play().catch(() => {})
    } else {
      const syncThreshold = 0.08
      if (Number.isFinite(el.duration) && el.duration > 0) {
        const target = Math.min(offset, Math.max(0, el.duration - 0.05))
        if (Math.abs(el.currentTime - target) > syncThreshold) el.currentTime = target
      } else if (Math.abs(el.currentTime - offset) > syncThreshold) {
        el.currentTime = offset
      }
      if (!el.paused) el.pause()
    }
  } catch {}
}


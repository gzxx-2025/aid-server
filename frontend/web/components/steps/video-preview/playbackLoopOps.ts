import { message } from 'antd'
import {
findNextPlayableClipStart,
getFullTimelinePlayableEndSec,
getSelectedVideoClip,
getTotalDuration,
getVideoClipAtTime,
hasClipVideoUrl,
hasPlayableVideoAtTime,
secToPlayheadPx
} from './layoutOps'
import {
type VideoPreviewCtx
} from './types'
import { applyNativeVideoVolume,getActiveNativeEl,getStandbyNativeEl,preloadAdjacentClips,preloadPreviewTimelineAudios,setTimelineScrollLeft,stopAllPreviewAudios,syncNativePreviewVideoTime,syncPreviewAudios } from './playbackMediaOps'

export function finishPreviewPlayback(ctx: VideoPreviewCtx) {
  stopPreviewPlaybackLoop(ctx)
  stopAllPreviewAudios(ctx)
  const el = getActiveNativeEl(ctx)
  if (el) {
    try {
      el.pause()
    } catch {}
  }
  const standby = getStandbyNativeEl(ctx)
  if (standby) {
    try {
      standby.pause()
    } catch {}
  }
  ctx.state.playing.set(false)
}

export function stopPreviewPlaybackLoop(ctx: VideoPreviewCtx) {
  if (ctx.runtime.previewPlayRaf !== null) {
    cancelAnimationFrame(ctx.runtime.previewPlayRaf)
    ctx.runtime.previewPlayRaf = null
  }
}

function anchorPreviewPlayClock(ctx: VideoPreviewCtx, atSec: number) {
  ctx.runtime.previewPlayStartSec = atSec
  ctx.runtime.previewPlayStartedAt = performance.now()
}

function refreshPreviewPlayEndSec(ctx: VideoPreviewCtx, _fromSec: number) {
  ctx.runtime.previewPlayEndSec = getFullTimelinePlayableEndSec(ctx)
}

/**
 * 播放头优先跟当前分镜 video.currentTime：
 * 缓冲卡住时时间轴停住，播完再进入下一分镜，避免「画面冻住、分割线继续跑」。
 */
function resolvePlaybackTimelineSec(ctx: VideoPreviewCtx, wallNext: number, maxSec: number): number {
  const S = ctx.state
  const clip = getVideoClipAtTime(ctx, S.currentTime.get()) || getVideoClipAtTime(ctx, wallNext)
  const el = getActiveNativeEl(ctx)
  if (!clip || !hasClipVideoUrl(clip) || !el || ctx.runtime.activeNativeClipId !== clip.id) {
    return Math.min(maxSec, wallNext)
  }

  const trim = Math.max(0, Number(clip.trimStart) || 0)
  const clipEnd = clip.start + clip.duration
  const mediaDur =
    Number.isFinite(el.duration) && el.duration > 0 ? el.duration : clip.sourceDuration || clip.duration

  // 等数据 / 正在 seek：钉住时间轴
  if (el.seeking || el.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) {
    anchorPreviewPlayClock(ctx, S.currentTime.get())
    return S.currentTime.get()
  }

  // 解码卡住（有当前帧但几乎无未来数据）且未结束：等待缓冲
  if (
    !el.ended &&
    el.readyState < HTMLMediaElement.HAVE_FUTURE_DATA &&
    el.networkState === HTMLMediaElement.NETWORK_LOADING
  ) {
    anchorPreviewPlayClock(ctx, S.currentTime.get())
    return S.currentTime.get()
  }

  const videoBased = clip.start + Math.max(0, el.currentTime - trim)

  // 媒体已到片尾：推到该分镜时间轴终点，触发切镜
  if (el.ended || el.currentTime >= mediaDur - 0.06) {
    const endAt = Math.min(maxSec, clipEnd)
    anchorPreviewPlayClock(ctx, endAt)
    return endAt
  }

  const next = Math.min(maxSec, clipEnd, Math.max(clip.start, videoBased))
  anchorPreviewPlayClock(ctx, next)
  return next
}

export function startPreviewPlaybackLoop(ctx: VideoPreviewCtx) {
  const S = ctx.state
  const R = ctx.runtime
  stopPreviewPlaybackLoop(ctx)
  anchorPreviewPlayClock(ctx, S.currentTime.get())
  refreshPreviewPlayEndSec(ctx, R.previewPlayStartSec)
  const startClip = getVideoClipAtTime(ctx, R.previewPlayStartSec)
  preloadAdjacentClips(ctx, startClip)
  // 不在开播时全量加载下一分镜，避免与当前片抢带宽

  const tick = () => {
    if (!S.playing.get()) {
      R.previewPlayRaf = null
      return
    }
    const elapsed = (performance.now() - R.previewPlayStartedAt) / 1000
    const maxSec = Math.min(getTotalDuration(ctx), R.previewPlayEndSec)
    const wallNext = Math.min(maxSec, R.previewPlayStartSec + elapsed)
    let next = resolvePlaybackTimelineSec(ctx, wallNext, maxSec)

    if (!hasPlayableVideoAtTime(ctx, next)) {
      const skipTo = findNextPlayableClipStart(ctx, next)
      if (skipTo !== null && skipTo < maxSec - 0.01) {
        anchorPreviewPlayClock(ctx, skipTo)
        next = skipTo
        preloadAdjacentClips(ctx, getVideoClipAtTime(ctx, skipTo))
      }
    }

    S.currentTime.set(Number(next.toFixed(3)))
    followPlayheadSmoothly(ctx)
    syncNativePreviewVideoTime(ctx)
    syncPreviewAudios(ctx)

    if (next >= maxSec - 0.02) {
      S.currentTime.set(Number(maxSec.toFixed(3)))
      finishPreviewPlayback(ctx)
      return
    }
    R.previewPlayRaf = requestAnimationFrame(tick)
  }
  R.previewPlayRaf = requestAnimationFrame(tick)
}

export async function togglePlay(ctx: VideoPreviewCtx) {
  const S = ctx.state
  if (!S.videoClips.get().length) return
  if (S.playing.get()) {
    stopPlayback(ctx)
    return
  }
  const startSec = resolvePlaybackStartSec(ctx)
  if (!hasPlayableVideoAtTime(ctx, startSec)) {
    message.warning('暂无视频无法播放')
    return
  }
  seekToTime(ctx, startSec, { preview: true })
  S.autoFollowEnabled.set(true)
  S.playing.set(true)
  // 必须在用户手势的同步阶段拉起配音 Audio：await 后再 play 易被浏览器自动播放策略拦截
  preloadPreviewTimelineAudios(ctx)
  const startClip = getVideoClipAtTime(ctx, startSec)
  syncPreviewAudios(ctx)
  if (startClip) {
    const el = getActiveNativeEl(ctx)
    if (el) applyNativeVideoVolume(ctx, el, startClip)
  }
  syncNativePreviewVideoTime(ctx)
  // 再同步一次：确保视频静音与配音起播落在同一手势周期
  syncPreviewAudios(ctx)
  startPreviewPlaybackLoop(ctx)
  preloadAdjacentClips(ctx, startClip)
}

export function onPreviewPlayerAreaClick(ctx: VideoPreviewCtx) {
  if (!ctx.state.videoClips.get().length) return
  void togglePlay(ctx)
}

function resolvePlaybackStartSec(ctx: VideoPreviewCtx): number {
  const end = getTotalDuration(ctx)
  const t = ctx.state.currentTime.get()
  const clip = getSelectedVideoClip(ctx)

  if (end > 0 && t >= end - 0.05) {
    return clip ? clip.start : 0
  }

  return t
}

export function seekToTime(ctx: VideoPreviewCtx, sec: number, opts?: { preview?: boolean }) {
  const S = ctx.state
  const R = ctx.runtime
  const clamped = Math.max(0, Math.min(getTotalDuration(ctx), Number(sec.toFixed(3))))
  S.currentTime.set(clamped)
  if (S.playing.get()) {
    if (!hasPlayableVideoAtTime(ctx, clamped)) {
      finishPreviewPlayback(ctx)
    } else {
      R.previewPlayStartedAt = performance.now()
      R.previewPlayStartSec = clamped
      refreshPreviewPlayEndSec(ctx, clamped)
    }
  }
  if (opts?.preview !== false) {
    if (hasPlayableVideoAtTime(ctx, clamped)) {
      R.avCanvas?.previewFrame?.(Math.round(clamped * 1_000_000))
      setTimeout(() => {
        syncNativePreviewVideoTime(ctx)
        syncPreviewAudios(ctx)
      }, 0)
    } else {
      syncNativePreviewVideoTime(ctx)
      syncPreviewAudios(ctx)
    }
  }
}

export function stopPlayback(ctx: VideoPreviewCtx) {
  if (!ctx.state.playing.get()) return
  finishPreviewPlayback(ctx)
  try {
    ctx.runtime.avCanvas?.pause?.()
  } catch {}
}

export function toggleMute(ctx: VideoPreviewCtx) {
  ctx.state.muted.set(!ctx.state.muted.get())
  syncNativePreviewVideoTime(ctx)
  syncPreviewAudios(ctx)
}

export function pauseAutoFollow(ctx: VideoPreviewCtx) {
  ctx.state.autoFollowEnabled.set(false)
  if (ctx.runtime.autoFollowResumeTimer) {
    window.clearTimeout(ctx.runtime.autoFollowResumeTimer)
    ctx.runtime.autoFollowResumeTimer = null
  }
}

export function scheduleAutoFollowResume(ctx: VideoPreviewCtx, delayMs = 1500) {
  if (ctx.runtime.autoFollowResumeTimer) window.clearTimeout(ctx.runtime.autoFollowResumeTimer)
  ctx.runtime.autoFollowResumeTimer = window.setTimeout(() => {
    ctx.state.autoFollowEnabled.set(true)
    ctx.runtime.autoFollowResumeTimer = null
    if (ctx.state.playing.get()) ensurePlayheadVisible(ctx)
  }, delayMs)
}

export function onTimelineUserScroll(ctx: VideoPreviewCtx) {
  if (performance.now() < ctx.runtime.programmaticScrollLockUntil) return
  if (ctx.runtime.suppressScrollFollowPause || !ctx.state.playing.get()) return
  pauseAutoFollow(ctx)
  scheduleAutoFollowResume(ctx)
}

export function scrollPlayheadIntoView(ctx: VideoPreviewCtx) {
  const wrap = ctx.dom.timelineWrapRef.current
  if (!wrap) return
  const playheadPx = secToPlayheadPx(ctx, ctx.state.currentTime.get())
  const viewportWidth = wrap.clientWidth - ctx.state.trackLabelWidth.get()
  const margin = 96
  let nextScrollLeft = wrap.scrollLeft
  if (playheadPx < wrap.scrollLeft + margin) {
    nextScrollLeft = Math.max(0, playheadPx - margin)
  } else if (playheadPx > wrap.scrollLeft + viewportWidth - margin) {
    nextScrollLeft = Math.max(0, playheadPx - viewportWidth + margin)
  }
  if (Math.abs(nextScrollLeft - wrap.scrollLeft) < 1) return
  setTimelineScrollLeft(ctx, wrap, nextScrollLeft)
}

/** 播放时让时间轴随分割线连续平移，与播放进度保持同速 */
export function followPlayheadSmoothly(ctx: VideoPreviewCtx) {
  if (!ctx.state.playing.get() || !ctx.state.autoFollowEnabled.get()) return
  const wrap = ctx.dom.timelineWrapRef.current
  if (!wrap) return

  const playheadPx = secToPlayheadPx(ctx, ctx.state.currentTime.get())
  const viewportWidth = Math.max(1, wrap.clientWidth - ctx.state.trackLabelWidth.get())
  const maxScroll = Math.max(0, wrap.scrollWidth - wrap.clientWidth)
  const anchorPx = viewportWidth * 0.38
  const targetScroll = Math.max(0, Math.min(maxScroll, playheadPx - anchorPx))

  setTimelineScrollLeft(ctx, wrap, targetScroll)
}

export function ensurePlayheadVisible(ctx: VideoPreviewCtx) {
  if (!ctx.state.autoFollowEnabled.get()) return
  if (ctx.state.playing.get()) {
    followPlayheadSmoothly(ctx)
    return
  }
  scrollPlayheadIntoView(ctx)
}

function syncTrackLabelWidth(ctx: VideoPreviewCtx) {
  const inner = ctx.dom.timelineWrapRef.current?.querySelector('.timeline-inner') as HTMLElement | null
  if (!inner) return
  const pad = parseFloat(getComputedStyle(inner).paddingLeft)
  if (Number.isFinite(pad) && pad > 0) ctx.state.trackLabelWidth.set(pad)
}

export function updateTimelineStripWidth(ctx: VideoPreviewCtx) {
  const wrap = ctx.dom.timelineWrapRef.current
  if (!wrap) return
  syncTrackLabelWidth(ctx)
  ctx.state.timelineStripWidthPx.set(Math.max(200, wrap.clientWidth - ctx.state.trackLabelWidth.get()))
}

/** 视图派生：当前时间点原生预览视频 URL（原 computed nativePreviewVideoUrl） */
export function getNativePreviewVideoUrl(ctx: VideoPreviewCtx): string {
  const clip = getVideoClipAtTime(ctx, ctx.state.currentTime.get()) || getSelectedVideoClip(ctx)
  if (clip && hasClipVideoUrl(clip)) return clip.url
  return ''
}

/** 视图派生：是否展示原生双缓冲视频层（原 computed showNativePreviewVideo） */
export function getShowNativePreviewVideo(ctx: VideoPreviewCtx): boolean {
  if (!ctx.state.videoClips.get().length) return false
  return hasPlayableVideoAtTime(ctx, ctx.state.currentTime.get())
}

/** 视图派生：是否展示「暂无视频」浮层（原 computed showNoVideoOverlay） */
export function getShowNoVideoOverlay(ctx: VideoPreviewCtx): boolean {
  if (!ctx.state.videoClips.get().length) return false
  return !hasPlayableVideoAtTime(ctx, ctx.state.currentTime.get())
}

/** 视图派生：当前时间点字幕文案（原 computed activeSubtitleText） */
export function getActiveSubtitleText(ctx: VideoPreviewCtx): string {
  const t = ctx.state.currentTime.get()
  const sub = ctx.state.subtitleItems
    .get()
    .find((s) => s.text?.trim() && t >= s.start && t < s.start + s.duration)
  return sub?.text?.trim() || ''
}

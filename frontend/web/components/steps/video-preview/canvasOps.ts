import { fetchMediaBlob } from '~/utils/mediaFetch'
import { hasPlayableVideoAtTime } from './layoutOps'
import {
stopAllPreviewAudios,
stopPlayback,
stopPreviewPlaybackLoop,
syncNativePreviewVideoTime
} from './playbackOps'
import type { VideoPreviewCtx } from './types'

/** AVCanvas 预览合成层：懒加载 @webav、按状态重建 sprite、销毁清理 */

export async function ensureCanvas(ctx: VideoPreviewCtx) {
  if (typeof window === 'undefined') return null
  const R = ctx.runtime
  if (R.avCanvas) return R.avCanvas
  const host = ctx.dom.canvasHostRef.current
  if (!host) return null
  const { AVCanvas } = await import('@webav/av-canvas')
  R.avCanvas = new AVCanvas(host, { bgColor: '#000', width: 1280, height: 720 })
  R.avUnsubTime = R.avCanvas.on('timeupdate', (t: number) => {
    ctx.state.currentTime.set(t / 1_000_000)
  })
  R.avUnsubPlaying = R.avCanvas.on('playing', () => {
    ctx.state.playing.set(true)
  })
  R.avUnsubPaused = R.avCanvas.on('paused', () => {
    ctx.state.playing.set(false)
  })
  return R.avCanvas
}

async function getCachedStream(
  ctx: VideoPreviewCtx,
  url: string
): Promise<ReadableStream<Uint8Array> | null> {
  const cache = ctx.runtime.mediaBlobCache
  if (cache.has(url)) {
    const blob = cache.get(url)!
    return blob.stream() as ReadableStream<Uint8Array>
  }
  // 跨域 CDN 无 CORS 时走同源 /api/media-proxy，避免 fetch 触发控制台 CORS 报错
  const blob = await fetchMediaBlob(url)
  if (!blob) return null
  cache.set(url, blob)
  return blob.stream() as ReadableStream<Uint8Array>
}

export function scheduleRebuild(
  ctx: VideoPreviewCtx,
  _reason: 'video' | 'subtitle' | 'audio' | 'all' = 'all'
) {
  const R = ctx.runtime
  if (R.rebuildTimer) window.clearTimeout(R.rebuildTimer)
  R.rebuildTimer = window.setTimeout(() => {
    R.rebuildTimer = null
    void rebuildCanvas(ctx).catch((error) => {
      console.error('Failed to rebuild preview canvas:', error)
    })
  }, 80)
}

function isVideoSampleBoundaryError(error: unknown): boolean {
  return error instanceof Error && error.message.includes('Not found video sample by time')
}

export async function rebuildCanvas(ctx: VideoPreviewCtx) {
  if (typeof window === 'undefined') return
  const S = ctx.state
  const R = ctx.runtime
  const host = ctx.dom.canvasHostRef.current
  if (!host) return

  try {
    R.avCanvas?.destroy?.()
  } catch {}
  R.avCanvas = null
  R.avUnsubTime?.()
  R.avUnsubPlaying?.()
  R.avUnsubPaused?.()
  R.avUnsubTime = null
  R.avUnsubPlaying = null
  R.avUnsubPaused = null
  await new Promise((resolve) => setTimeout(resolve, 0))

  const token = Date.now()
  R.currentPreviewToken = token
  const cvs = await ensureCanvas(ctx)
  if (!cvs) return

  const webav = await import('@webav/av-cliper')
  const { MP4Clip, AudioClip, ImgClip, VisibleSprite, renderTxt2ImgBitmap, Rect } = webav as any
  const secToUs = (s: number) => Math.max(0, Math.round(s * 1_000_000))

  const videosOrdered = [...S.videoClips.get()].sort((a, b) => a.start - b.start)
  for (const clip of videosOrdered) {
    if (!clip.url) continue
    const stream = await getCachedStream(ctx, clip.url)
    if (!stream) continue
    let mp4 = new MP4Clip(stream)
    await mp4.ready

    const trimStartUs = secToUs(clip.trimStart || 0)
    const trimEndUs = secToUs(clip.trimEnd || clip.duration)
    if (trimStartUs > 0) {
      if (trimStartUs >= mp4.meta.duration) continue
      try {
        const [, rest] = await mp4.split(trimStartUs)
        mp4 = rest
      } catch (error) {
        // No sample after trimStart means this source has no playable trimmed range.
        if (isVideoSampleBoundaryError(error)) continue
        throw error
      }
    }
    const keepUs = Math.max(1, trimEndUs - trimStartUs)
    if (keepUs < mp4.meta.duration) {
      try {
        const [kept] = await mp4.split(keepUs)
        mp4 = kept
      } catch (error) {
        // Container duration can extend beyond the final sample CTS. In that tail,
        // keeping the remaining clip is equivalent to splitting at the requested end.
        if (!isVideoSampleBoundaryError(error)) throw error
      }
    }

    const spr = new VisibleSprite(mp4)
    spr.time = { offset: secToUs(clip.start), duration: secToUs(clip.duration) }
    spr.zIndex = 1
    // 等比 contain 进 16:9 画布，避免非 16:9 素材被拉伸撑满
    const srcW = Number(mp4.meta?.width) || 1280
    const srcH = Number(mp4.meta?.height) || 720
    const scale = Math.min(1280 / srcW, 720 / srcH)
    const drawW = Math.max(1, Math.round(srcW * scale))
    const drawH = Math.max(1, Math.round(srcH * scale))
    spr.rect = new Rect(
      Math.round((1280 - drawW) / 2),
      Math.round((720 - drawH) / 2),
      drawW,
      drawH
    )
    await cvs.addSprite(spr)
    if (R.currentPreviewToken !== token) return
  }

  for (const sub of S.subtitleItems.get()) {
    const text = (sub.text || '').trim()
    if (!text) continue
    const subtitleFontSize = Math.max(20, Math.min(72, Number(sub.fontSize || 40)))
    const bmp = await renderTxt2ImgBitmap(
      text,
      `font-size:${subtitleFontSize}px;color:#fff;background:rgba(0,0,0,0.6);padding:10px 16px;border-radius:10px;line-height:1.35;max-width:90%;text-align:center;`
    )
    const spr = new VisibleSprite(new ImgClip(bmp))
    spr.time = { offset: secToUs(sub.start), duration: secToUs(sub.duration) }
    spr.zIndex = 20
    const w = bmp.width || 900
    const h = bmp.height || 90
    spr.rect = new Rect(Math.round((1280 - w) / 2), Math.round(720 - 64 - h), w, h)
    await cvs.addSprite(spr)
    if (R.currentPreviewToken !== token) return
  }

  for (const a of [...S.voiceItems.get(), ...S.musicItems.get()]) {
    if (!a.url) continue
    const stream = await getCachedStream(ctx, a.url)
    if (!stream) continue
    const clip = new AudioClip(stream, { loop: !!a.loop })
    await clip.ready
    const curve = a.volumeCurve?.length ? a.volumeCurve : [a.volume, a.volume, a.volume]
    const parts = [0, 1, 2]
    for (const idx of parts) {
      const segStart = a.start + a.duration * (idx / 3)
      const segDur = a.duration / 3
      const spr = new VisibleSprite(clip)
      spr.time = { offset: secToUs(segStart), duration: secToUs(segDur) }
      ;(spr as any).volume = Math.max(0, curve[idx] ?? a.volume ?? 1)
      spr.zIndex = 0
      await cvs.addSprite(spr)
    }
    if (R.currentPreviewToken !== token) return
  }

  if (!S.playing.get() && hasPlayableVideoAtTime(ctx, S.currentTime.get())) {
    cvs.previewFrame?.(Math.round(S.currentTime.get() * 1_000_000))
    await new Promise((resolve) => setTimeout(resolve, 0))
    syncNativePreviewVideoTime(ctx)
  }
}

export async function ensurePreviewAtCurrentTime(ctx: VideoPreviewCtx) {
  if (!ctx.state.videoClips.get().length) return
  if (!ctx.runtime.avCanvas) await rebuildCanvas(ctx)
  if (hasPlayableVideoAtTime(ctx, ctx.state.currentTime.get())) {
    ctx.runtime.avCanvas?.previewFrame?.(Math.round(ctx.state.currentTime.get() * 1_000_000))
    await new Promise((resolve) => setTimeout(resolve, 0))
    syncNativePreviewVideoTime(ctx)
  }
}

/** 切作品/集时的整体重置（原 resetPreviewTimelineState） */
export function resetPreviewTimelineState(ctx: VideoPreviewCtx) {
  const S = ctx.state
  const R = ctx.runtime
  stopPlayback(ctx)
  stopPreviewPlaybackLoop(ctx)
  stopAllPreviewAudios(ctx)
  R.previewAudioEls.clear()
  if (R.rebuildTimer) window.clearTimeout(R.rebuildTimer)
  R.rebuildTimer = null
  R.timelineSaver.cancel()
  R.currentPreviewToken += 1

  S.videoClips.set([])
  S.voiceItems.set([])
  S.subtitleItems.set([])
  S.musicItems.set([])
  S.videoVolumePreset.set({})
  S.serverTimelineBaseline.set(null)
  S.selectedClip.set(null)
  S.currentTime.set(0)
  S.playing.set(false)
  S.scrubbing.set(false)
  S.snapIndicatorPx.set(null)
  S.nativePreviewFrameReady.set(false)
  R.lastHydratedScopeKey = ''

  try {
    R.avCanvas?.destroy?.()
  } catch {}
  R.avCanvas = null
  R.avUnsubTime?.()
  R.avUnsubPlaying?.()
  R.avUnsubPaused?.()
  R.avUnsubTime = null
  R.avUnsubPlaying = null
  R.avUnsubPaused = null
  R.mediaBlobCache.clear()
}

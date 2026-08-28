'use client'

import { message } from 'antd'
import { useRef } from 'react'
import { downloadMediaFileWithToast } from '~/utils/mediaFileDownload'
import type { VideoModalCtx,VideoPreviewPlaybackApi } from './types'

/** 中间画布视频预览播放控制（原 playingVideoIdx / videoPreviewRefs 段逻辑） */
export function useVideoPreviewPlayback(ctx: VideoModalCtx): void {
  const videoPreviewRefsRef = useRef<Map<number, HTMLVideoElement> | null>(null)
  const videoPreviewComponentRefsRef = useRef<Map<number, unknown> | null>(null)
  if (!videoPreviewRefsRef.current) videoPreviewRefsRef.current = new Map()
  if (!videoPreviewComponentRefsRef.current) videoPreviewComponentRefsRef.current = new Map()
  const videoPreviewRefs = videoPreviewRefsRef.current
  const videoPreviewComponentRefs = videoPreviewComponentRefsRef.current

  function resolveShimmerVideoEl(el: unknown): HTMLVideoElement | null {
    if (!el) return null
    if (el instanceof HTMLVideoElement) return el
    const ref = (el as { videoRef?: HTMLVideoElement | null | { value?: HTMLVideoElement | null } })
      .videoRef
    if (ref instanceof HTMLVideoElement) return ref
    if (ref && typeof ref === 'object' && 'value' in ref) return ref.value ?? null
    return null
  }

  function syncVideoPreviewRef(idx: number) {
    const video = resolveShimmerVideoEl(videoPreviewComponentRefs.get(idx))
    if (video) videoPreviewRefs.set(idx, video)
    else videoPreviewRefs.delete(idx)
  }

  function setVideoPreviewRef(el: unknown, idx: number) {
    if (el) videoPreviewComponentRefs.set(idx, el)
    else videoPreviewComponentRefs.delete(idx)
    syncVideoPreviewRef(idx)
  }

  function getVideoPreviewEl(idx: number): HTMLVideoElement | null {
    const cached = videoPreviewRefs.get(idx)
    if (cached) return cached
    syncVideoPreviewRef(idx)
    return videoPreviewRefs.get(idx) ?? null
  }

  function markVideoPreviewMediaReady(idx: number) {
    ctx.videoPreviewMediaReady.set({ ...ctx.videoPreviewMediaReady.get(), [idx]: true })
    syncVideoPreviewRef(idx)
  }

  function pauseAllVideoPreviews(exceptIdx = -1) {
    videoPreviewRefs.forEach((videoEl, i) => {
      if (i === exceptIdx) return
      videoEl.pause()
      videoEl.currentTime = 0
      videoEl.muted = true
    })
  }

  /** 切分镜 / 关窗时清空引用（原 videoPreviewRefs.clear() 等语句） */
  function clearVideoPreviewRefs() {
    videoPreviewRefs.clear()
    videoPreviewComponentRefs.clear()
  }

  async function toggleVideoPreviewPlayback(idx: number) {
    const v = ctx.currentSceneVideos()[idx]
    if (!v?.url) return

    const videoEl = getVideoPreviewEl(idx)
    if (!videoEl) return

    if (!videoEl.paused) {
      videoEl.pause()
      videoEl.muted = true
      ctx.playingVideoIdx.set(-1)
      return
    }

    pauseAllVideoPreviews(idx)
    if (videoEl.ended) videoEl.currentTime = 0
    videoEl.muted = false
    ctx.playingVideoIdx.set(idx)
    ctx.selectedVideoIdx.set(idx)
    try {
      await videoEl.play()
    } catch {
      ctx.playingVideoIdx.set(-1)
      videoEl.muted = true
      message.warning('无法自动播放，请稍后重试')
    }
  }

  function toggleSelectedVideoPreviewPlayback() {
    const currentPlayingIdx = ctx.playingVideoIdx.get()
    if (currentPlayingIdx >= 0) {
      void toggleVideoPreviewPlayback(currentPlayingIdx)
      return
    }

    const selectedIdx = ctx.selectedVideoIdx.get()
    const videos = ctx.currentSceneVideos()
    const targetIdx = videos[selectedIdx]?.url
      ? selectedIdx
      : videos.findIndex((video: any) => Boolean(video?.url))
    if (targetIdx >= 0) void toggleVideoPreviewPlayback(targetIdx)
  }

  function onVideoPreviewEnded(idx: number) {
    if (ctx.playingVideoIdx.get() !== idx) return
    ctx.playingVideoIdx.set(-1)
    const videoEl = videoPreviewRefs.get(idx)
    if (videoEl) {
      videoEl.muted = true
      videoEl.currentTime = 0
    }
  }

  function onVideoPreviewPause(idx: number) {
    const videoEl = videoPreviewRefs.get(idx)
    if (!videoEl || !videoEl.paused || ctx.playingVideoIdx.get() !== idx) return
    ctx.playingVideoIdx.set(-1)
    videoEl.muted = true
  }

  async function handleFullscreenVideo(idx: number) {
    const videoEl = getVideoPreviewEl(idx)
    if (!videoEl) return
    try {
      if (videoEl.paused) {
        pauseAllVideoPreviews(idx)
        videoEl.muted = false
        ctx.playingVideoIdx.set(idx)
        await videoEl.play()
      }
      await videoEl.requestFullscreen()
    } catch {
      message.warning('全屏预览不可用')
    }
  }

  function handleDownloadVideo(_idx: number, v: any) {
    const url = v?.url
    if (!url) {
      message.warning('暂无视频可下载')
      return
    }
    void downloadMediaFileWithToast(url, v?.title || '分镜视频', '视频')
  }

  const api: VideoPreviewPlaybackApi = {
    setVideoPreviewRef,
    getVideoPreviewEl,
    markVideoPreviewMediaReady,
    pauseAllVideoPreviews,
    clearVideoPreviewRefs,
    toggleVideoPreviewPlayback,
    toggleSelectedVideoPreviewPlayback,
    onVideoPreviewEnded,
    onVideoPreviewPause,
    handleFullscreenVideo,
    handleDownloadVideo
  }
  Object.assign(ctx, api)
}

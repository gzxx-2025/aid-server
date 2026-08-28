'use client'

import { message } from 'antd'
import { useRef } from 'react'
import type { ShimmerVideoHandle } from '~/components/common/ShimmerVideo'
import { downloadMediaFileWithToast } from '~/utils/mediaFileDownload'
import { useMirrored } from './useMirrored'

/** 中间大图视频预览：播放/暂停/全屏/下载 与 ready 态（原 heroVideo* 一组 ref 与方法） */
export function useDubbingHeroVideo(options: {
  getPreviewUrl: () => string
  getDownloadName: () => string
}) {
  const heroVideoComponentRef = useRef<ShimmerVideoHandle | null>(null)
  const heroVideoPlaying = useMirrored(false)
  const heroVideoMediaReady = useMirrored(false)

  const optionsRef = useRef(options)
  optionsRef.current = options

  function resolveHeroVideoEl(): HTMLVideoElement | null {
    const el = heroVideoComponentRef.current?.videoRef
    return el instanceof HTMLVideoElement ? el : null
  }

  function markHeroVideoMediaReady() {
    heroVideoMediaReady.set(true)
  }

  function pauseHeroVideoPlayback() {
    heroVideoPlaying.set(false)
    const videoEl = resolveHeroVideoEl()
    if (!videoEl) return
    videoEl.pause()
    videoEl.currentTime = 0
    videoEl.muted = true
  }

  function resetHeroVideoPreviewState() {
    heroVideoPlaying.set(false)
    heroVideoMediaReady.set(false)
    pauseHeroVideoPlayback()
  }

  async function toggleHeroVideoPlayback() {
    const url = optionsRef.current.getPreviewUrl()
    if (!url) return

    const videoEl = resolveHeroVideoEl()
    if (!videoEl) return

    if (!videoEl.paused) {
      videoEl.pause()
      videoEl.muted = true
      heroVideoPlaying.set(false)
      return
    }

    if (videoEl.ended) videoEl.currentTime = 0
    videoEl.muted = false
    heroVideoPlaying.set(true)
    try {
      await videoEl.play()
    } catch {
      heroVideoPlaying.set(false)
      videoEl.muted = true
      message.warning('无法自动播放，请稍后重试')
    }
  }

  function onHeroVideoEnded() {
    heroVideoPlaying.set(false)
    const videoEl = resolveHeroVideoEl()
    if (!videoEl) return
    videoEl.muted = true
    videoEl.currentTime = 0
  }

  function onHeroVideoPause() {
    const videoEl = resolveHeroVideoEl()
    if (!videoEl || !videoEl.paused || !heroVideoPlaying.get()) return
    heroVideoPlaying.set(false)
    videoEl.muted = true
  }

  async function handleFullscreenHeroVideo() {
    const videoEl = resolveHeroVideoEl()
    if (!videoEl) return
    try {
      if (videoEl.paused) {
        videoEl.muted = false
        heroVideoPlaying.set(true)
        await videoEl.play()
      }
      await videoEl.requestFullscreen()
    } catch {
      message.warning('全屏预览不可用')
    }
  }

  function downloadPreviewVideo() {
    const url = optionsRef.current.getPreviewUrl()
    if (!url) {
      message.warning('暂无视频可下载')
      return
    }
    void downloadMediaFileWithToast(url, optionsRef.current.getDownloadName(), '视频')
  }

  return {
    heroVideoComponentRef,
    heroVideoPlaying,
    heroVideoMediaReady,
    markHeroVideoMediaReady,
    pauseHeroVideoPlayback,
    resetHeroVideoPreviewState,
    toggleHeroVideoPlayback,
    onHeroVideoEnded,
    onHeroVideoPause,
    handleFullscreenHeroVideo,
    downloadPreviewVideo
  }
}

'use client'

import { message } from 'antd'
import type { MutableRefObject } from 'react'
import { useRef,useState } from 'react'
import type { StoryboardVideoPanel } from '~/types'
import { getPanelStoryboardVideo } from './storyboardVideoViewShared'
/**
 * 分镜视频列表内联播放控制（原 StoryboardVideo.vue script 中
 * playingPanelIndex / panelVideoRefs / 播放暂停全屏部分原样搬迁）。
 */
export function useStoryboardVideoPlayback(opts: {
  panelsRef: MutableRefObject<StoryboardVideoPanel[]>
}) {
  const { panelsRef } = opts
  const [playingPanelIndex, setPlayingPanelIndex] = useState(-1)
  const playingPanelIndexRef = useRef(playingPanelIndex)
  playingPanelIndexRef.current = playingPanelIndex
  const panelVideoRefs = useRef(new Map<number, HTMLVideoElement>()).current
  const panelVideoComponentRefs = useRef(new Map<number, unknown>()).current
  const [panelVideoMediaReady, setPanelVideoMediaReady] = useState<Record<number, boolean>>({})

  function resolveShimmerVideoEl(el: unknown): HTMLVideoElement | null {
    if (!el) return null
    if (el instanceof HTMLVideoElement) return el
    const ref = (
      el as { videoRef?: HTMLVideoElement | null | { value?: HTMLVideoElement | null } }
    ).videoRef
    if (ref instanceof HTMLVideoElement) return ref
    if (ref && typeof ref === 'object' && 'value' in ref) return ref.value ?? null
    return null
  }

  function syncPanelVideoRef(panelIndex: number) {
    const video = resolveShimmerVideoEl(panelVideoComponentRefs.get(panelIndex))
    if (video) panelVideoRefs.set(panelIndex, video)
    else panelVideoRefs.delete(panelIndex)
  }

  function setPanelVideoRef(el: unknown, panelIndex: number) {
    if (el) panelVideoComponentRefs.set(panelIndex, el)
    else panelVideoComponentRefs.delete(panelIndex)
    syncPanelVideoRef(panelIndex)
  }

  function getPanelVideoEl(panelIndex: number): HTMLVideoElement | null {
    const cached = panelVideoRefs.get(panelIndex)
    if (cached) return cached
    syncPanelVideoRef(panelIndex)
    return panelVideoRefs.get(panelIndex) ?? null
  }

  function markPanelVideoMediaReady(panelIndex: number) {
    setPanelVideoMediaReady((prev) => ({ ...prev, [panelIndex]: true }))
    syncPanelVideoRef(panelIndex)
  }

  function pauseAllPanelVideos(exceptIndex = -1) {
    panelVideoRefs.forEach((videoEl, i) => {
      if (i === exceptIndex) return
      videoEl.pause()
      videoEl.currentTime = 0
      videoEl.muted = true
    })
  }

  function handlePlayPanelVideo(panelIndex: number) {
    const panel = panelsRef.current[panelIndex]
    const video = getPanelStoryboardVideo(panel)
    if (!video?.url) return

    pauseAllPanelVideos(panelIndex)

    const videoEl = getPanelVideoEl(panelIndex)
    if (!videoEl) return

    videoEl.muted = false
    setPlayingPanelIndex(panelIndex)
    void videoEl.play().catch(() => {
      setPlayingPanelIndex(-1)
      videoEl.muted = true
      message.warning('无法自动播放，请稍后重试')
    })
  }

  function onPanelVideoEnded(panelIndex: number) {
    if (playingPanelIndexRef.current !== panelIndex) return
    setPlayingPanelIndex(-1)
    const videoEl = panelVideoRefs.get(panelIndex)
    if (videoEl) {
      videoEl.muted = true
      videoEl.currentTime = 0
    }
  }

  function onPanelVideoPause(panelIndex: number) {
    const videoEl = panelVideoRefs.get(panelIndex)
    if (!videoEl || !videoEl.paused || playingPanelIndexRef.current !== panelIndex) return
    setPlayingPanelIndex(-1)
    videoEl.muted = true
  }

  async function handleFullscreenPanelVideo(panelIndex: number) {
    const videoEl = getPanelVideoEl(panelIndex)
    if (!videoEl) return
    try {
      if (videoEl.paused) {
        pauseAllPanelVideos(panelIndex)
        videoEl.muted = false
        setPlayingPanelIndex(panelIndex)
        await videoEl.play()
      }
      await videoEl.requestFullscreen()
    } catch {
      message.warning('全屏预览不可用')
    }
  }

  return {
    playingPanelIndex,
    panelVideoMediaReady,
    setPanelVideoRef,
    markPanelVideoMediaReady,
    handlePlayPanelVideo,
    onPanelVideoEnded,
    onPanelVideoPause,
    handleFullscreenPanelVideo
  }
}

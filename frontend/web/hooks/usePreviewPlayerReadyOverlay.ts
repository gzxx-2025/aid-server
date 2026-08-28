'use client'

import { useEffect,useRef,useState } from 'react'
import { shouldShowPreviewReadyOverlay } from '~/utils/previewPlayerPoster'

const FADE_MS = 180

/**
 * 成品预览播放区：首帧未就绪时展示封面/渐变 + 文案，就绪后淡出。
 * 首版仅覆盖首进与切镜未就绪，不处理播放中途卡顿缓冲。
 */
export function usePreviewPlayerReadyOverlay(deps: {
  scopeKey: string
  timelineLoading: boolean
  videoClipCount: number
  hasPlayableAtCurrentTime: boolean
  frameReady: boolean
  posterUrl: string
}) {
  const shouldShow = shouldShowPreviewReadyOverlay({
    timelineLoading: deps.timelineLoading,
    videoClipCount: deps.videoClipCount,
    hasPlayableAtCurrentTime: deps.hasPlayableAtCurrentTime,
    frameReady: deps.frameReady
  })

  const [overlayMounted, setOverlayMounted] = useState(shouldShow)
  const [overlayOpaque, setOverlayOpaque] = useState(shouldShow)
  const fadeTimerRef = useRef<number | null>(null)
  const shouldShowRef = useRef(shouldShow)
  shouldShowRef.current = shouldShow

  function clearFadeTimer() {
    if (fadeTimerRef.current != null) {
      window.clearTimeout(fadeTimerRef.current)
      fadeTimerRef.current = null
    }
  }

  // 原 watch(shouldShow, ..., { immediate: true })：出现即挂载，消失走淡出
  useEffect(() => {
    if (shouldShow) {
      clearFadeTimer()
      setOverlayMounted(true)
      setOverlayOpaque(true)
      return
    }
    setOverlayOpaque(false)
    clearFadeTimer()
    fadeTimerRef.current = window.setTimeout(() => {
      fadeTimerRef.current = null
      if (!shouldShowRef.current) setOverlayMounted(false)
    }, FADE_MS)
  }, [shouldShow])

  // 原 watch(scopeKey)：切作品/集立即按当前 shouldShow 重置，不做淡出
  const prevScopeKeyRef = useRef(deps.scopeKey)
  useEffect(() => {
    if (prevScopeKeyRef.current === deps.scopeKey) return
    prevScopeKeyRef.current = deps.scopeKey
    clearFadeTimer()
    setOverlayMounted(shouldShowRef.current)
    setOverlayOpaque(shouldShowRef.current)
  }, [deps.scopeKey])

  useEffect(() => {
    return () => clearFadeTimer()
  }, [])

  return {
    overlayMounted,
    overlayOpaque,
    hintText: '正在加载预览…',
    posterUrl: deps.posterUrl,
    shouldShow
  }
}

'use client'

import type { SyntheticEvent } from 'react'
import { useEffect,useRef,useState } from 'react'
import { captureVideoFirstFrame } from '~/utils/videoPoster'
export interface VideoPosterImgProps {
  src?: string
  imgClass?: string
}

/** 视频首帧封面：优先截帧为图片，失败时由单格 video 兜底展示首帧 */
export function VideoPosterImg({ src = '', imgClass = '' }: VideoPosterImgProps) {
  const [posterSrc, setPosterSrc] = useState('')
  const posterCacheRef = useRef<Map<string, string>>(new Map())
  const srcRef = useRef(src)
  srcRef.current = src

  useEffect(() => {
    const url = String(src || '').trim()
    if (!url) {
      setPosterSrc('')
      return
    }
    const cached = posterCacheRef.current.get(url)
    if (cached) {
      setPosterSrc(cached)
      return
    }
    setPosterSrc('')
    let cancelled = false
    void (async () => {
      try {
        const dataUrl = await captureVideoFirstFrame(url)
        posterCacheRef.current.set(url, dataUrl)
        if (!cancelled && srcRef.current === url) setPosterSrc(dataUrl)
      } catch {
        /* 截帧失败时由 video 兜底展示首帧 */
      }
    })()
    return () => {
      cancelled = true
    }
  }, [src])

  function handleVideoLoaded(event: SyntheticEvent<HTMLVideoElement>) {
    const video = event.currentTarget
    if (!video) return
    const duration = Number.isFinite(video.duration) ? video.duration : 0
    video.currentTime = duration > 0 ? Math.min(0.1, duration * 0.01) : 0
  }

  if (posterSrc) {
    return <img src={posterSrc} className={imgClass} alt="" />
  }
  if (src) {
    return (
      <video
        src={src}
        className={imgClass}
        muted
        playsInline
        preload="metadata"
        onLoadedData={handleVideoLoaded}
      />
    )
  }
  return null
}

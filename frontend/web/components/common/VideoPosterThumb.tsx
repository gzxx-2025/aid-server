'use client'

import { useEffect, useRef, useState } from 'react'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { ShimmerVideo } from '~/components/common/ShimmerVideo'
import {
  cancelPendingVideoPosters,
  ensureVideoPosterObjectUrl,
  peekVideoPosterObjectUrl,
  VIDEO_POSTER_PRIORITY
} from '~/utils/ensureVideoPoster'
import './VideoPosterThumb.css'

export interface VideoPosterThumbProps {
  /** 视频 URL（非图片） */
  src?: string
  /**
   * 抽帧完成前的即时占位图（如分镜图封面）。
   * 用于头部 Tab：避免非当前 Tab 抽帧慢/失败时一直空白。
   */
  placeholderSrc?: string
  priority?: number
  imgClass?: string
  videoClass?: string
  wrapperClass?: string
  objectFit?: 'cover' | 'contain' | 'fill' | 'none' | 'scale-down'
  /** 抽帧失败后是否允许单格 video 兜底 */
  allowVideoFallback?: boolean
  fallbackLazy?: boolean
  fallbackGated?: boolean
}

type Mode = 'pending' | 'image' | 'video-fallback' | 'empty'

/** 视频缩略图：抽帧封面优先；无占位时先挂 video，禁止一直卡在 loading shimmer */
export function VideoPosterThumb({
  src = '',
  placeholderSrc = '',
  priority = VIDEO_POSTER_PRIORITY.history,
  imgClass = '',
  videoClass = '',
  wrapperClass = '',
  objectFit = 'cover',
  allowVideoFallback = true,
  fallbackLazy = true,
  fallbackGated = true
}: VideoPosterThumbProps) {
  const [mode, setMode] = useState<Mode>('pending')
  const [posterSrc, setPosterSrc] = useState('')
  const [videoSrc, setVideoSrc] = useState('')
  const reqTokenRef = useRef(0)
  const srcRef = useRef(src)
  srcRef.current = src
  const allowFallbackRef = useRef(allowVideoFallback)
  allowFallbackRef.current = allowVideoFallback
  const placeholder = String(placeholderSrc || '').trim()
  const placeholderRef = useRef(placeholder)
  placeholderRef.current = placeholder

  function resetEmpty() {
    setMode('empty')
    setPosterSrc('')
    setVideoSrc('')
  }

  function showPending() {
    setMode('pending')
    setPosterSrc('')
    setVideoSrc('')
  }

  function showImage(url: string) {
    setPosterSrc(url)
    setMode('image')
    setVideoSrc('')
  }

  function showVideoFallback(url: string) {
    if (!allowFallbackRef.current) {
      // 不允许挂 mp4：有占位图则继续展示占位，否则结束 loading
      if (placeholderRef.current) {
        showPending()
        return
      }
      resetEmpty()
      return
    }
    setPosterSrc('')
    setVideoSrc(url)
    setMode('video-fallback')
  }

  function handleImageError() {
    const url = String(srcRef.current || '').trim()
    setPosterSrc('')
    if (url) showVideoFallback(url)
    else if (placeholderRef.current) showPending()
    else resetEmpty()
  }

  /**
   * 抽帧进行中的展示策略：
   * - 有占位图 → pending（渲染占位图，不闪 shimmer）
   * - 无占位但允许 video 兜底 → 先挂 video，抽帧成功后再替换
   * - 否则才走 shimmer（应尽量避免）
   */
  function showWhilePosterResolving(videoUrl: string) {
    if (placeholderRef.current) {
      showPending()
      return
    }
    if (allowFallbackRef.current) {
      showVideoFallback(videoUrl)
      return
    }
    showPending()
  }

  async function resolvePoster(raw: string, rawPriority: number) {
    const token = ++reqTokenRef.current
    const url = String(raw || '').trim()
    if (!url) {
      if (placeholderRef.current) {
        showPending()
        return
      }
      resetEmpty()
      return
    }

    const peeked = peekVideoPosterObjectUrl(url)
    if (peeked) {
      showImage(peeked)
      return
    }

    showWhilePosterResolving(url)

    const ensurePoster = ensureVideoPosterObjectUrl as (
      videoUrl: string,
      priority?: number
    ) => Promise<string>
    const objectUrl = await ensurePoster(url, rawPriority)
    if (token !== reqTokenRef.current) return
    if (objectUrl) {
      showImage(objectUrl)
      return
    }
    showVideoFallback(url)
  }

  useEffect(() => {
    void resolvePoster(String(src || ''), Number(priority) || 0)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src, priority])

  useEffect(() => {
    return () => {
      reqTokenRef.current += 1
      const url = String(srcRef.current || '').trim()
      if (url) cancelPendingVideoPosters([url])
    }
  }, [])

  const wrapperClassName = ['video-poster-thumb relative w-full h-full overflow-hidden', wrapperClass]
    .filter(Boolean)
    .join(' ')

  return (
    <div className={wrapperClassName}>
      {mode === 'image' && posterSrc ? (
        <ShimmerImage
          src={posterSrc}
          imgClass={imgClass}
          objectFit={objectFit}
          revealDirection="fade"
          onError={handleImageError}
        />
      ) : mode === 'video-fallback' && videoSrc ? (
        <ShimmerVideo
          src={videoSrc}
          videoClass={videoClass}
          objectFit={objectFit}
          revealDirection="fade"
          preload="metadata"
          lazy={fallbackLazy}
          gated={fallbackGated}
        />
      ) : placeholder ? (
        <ShimmerImage
          src={placeholder}
          imgClass={imgClass}
          objectFit={objectFit}
          revealDirection="fade"
        />
      ) : mode === 'empty' ? (
        <div className="video-poster-thumb__empty absolute inset-0 w-full h-full" aria-hidden="true" />
      ) : (
        <div
          className="shimmer-image shimmer-image--waiting shimmer-image--reveal-fade video-poster-thumb__pending absolute inset-0 w-full h-full"
          aria-hidden="true"
        >
          <div className="shimmer-image__placeholder">
            <div className="shimmer-image__shimmer shimmer-image__shimmer--loop" />
          </div>
        </div>
      )}
    </div>
  )
}

'use client'

import { useEffect, useRef, useState } from 'react'
import type {
  AnimationEvent as ReactAnimationEvent,
  CSSProperties,
  MouseEvent as ReactMouseEvent,
  ReactNode
} from 'react'

type RevealPhase = 'waiting' | 'revealing' | 'done'
type RevealDirection = 'vertical' | 'horizontal' | 'fade'

export interface ShimmerImageProps {
  src?: string
  alt?: string
  imgClass?: string
  wrapperClass?: string
  objectFit?: 'cover' | 'contain' | 'fill' | 'none' | 'scale-down'
  /** 流光占位最短展示时长（ms），仅对需要渐入的图片生效 */
  minShimmerMs?: number
  /** 图片揭示方式：滑入或渐入 */
  revealDirection?: RevealDirection
  /** 揭示动画时长（ms） */
  revealMs?: number
  /** 若图片在该时间内加载完成（多为缓存），跳过流光与渐入动效 */
  fastRevealThresholdMs?: number
  onClick?: (event: ReactMouseEvent<HTMLImageElement>) => void
  onLoad?: () => void
  onError?: () => void
  /** 原 error 具名插槽：加载失败时渲染 */
  errorSlot?: ReactNode
}

const REVEAL_ANIM_MAP: Record<RevealDirection, string> = {
  horizontal: 'shimmer-image-reveal-horizontal',
  vertical: 'shimmer-image-reveal',
  fade: 'shimmer-image-reveal-fade'
}

export function ShimmerImage({
  src = '',
  alt = '',
  imgClass = '',
  wrapperClass = '',
  objectFit = 'cover',
  minShimmerMs = 0,
  revealDirection = 'vertical',
  revealMs = 780,
  fastRevealThresholdMs = 120,
  onClick,
  onLoad,
  onError,
  errorSlot
}: ShimmerImageProps) {
  const [revealPhase, setRevealPhaseState] = useState<RevealPhase>('waiting')
  const [hasError, setHasErrorState] = useState(false)

  const imgRef = useRef<HTMLImageElement | null>(null)
  const revealPhaseRef = useRef<RevealPhase>('waiting')
  const hasErrorRef = useRef(false)
  const imgReadyRef = useRef(false)
  const loadStartedAtRef = useRef(0)
  const shimmerDelayTimerRef = useRef<number | null>(null)
  const revealFallbackTimerRef = useRef<number | null>(null)
  const cacheSyncFallbackTimerRef = useRef<number | null>(null)

  const resolvedSrc = String(src || '').trim()

  function setRevealPhase(phase: RevealPhase) {
    revealPhaseRef.current = phase
    setRevealPhaseState(phase)
  }

  function setHasError(value: boolean) {
    hasErrorRef.current = value
    setHasErrorState(value)
  }

  function clearShimmerDelayTimer() {
    if (shimmerDelayTimerRef.current != null) {
      clearTimeout(shimmerDelayTimerRef.current)
      shimmerDelayTimerRef.current = null
    }
  }

  function clearRevealFallbackTimer() {
    if (revealFallbackTimerRef.current != null) {
      clearTimeout(revealFallbackTimerRef.current)
      revealFallbackTimerRef.current = null
    }
  }

  function clearCacheSyncFallbackTimer() {
    if (cacheSyncFallbackTimerRef.current != null) {
      clearTimeout(cacheSyncFallbackTimerRef.current)
      cacheSyncFallbackTimerRef.current = null
    }
  }

  function clearRevealTimers() {
    clearShimmerDelayTimer()
    clearRevealFallbackTimer()
    clearCacheSyncFallbackTimer()
  }

  function finishImmediately() {
    clearRevealTimers()
    setRevealPhase('done')
    onLoad?.()
  }

  function scheduleRevealFallback() {
    clearRevealFallbackTimer()
    revealFallbackTimerRef.current = window.setTimeout(() => {
      revealFallbackTimerRef.current = null
      if (revealPhaseRef.current === 'revealing') {
        setRevealPhase('done')
        onLoad?.()
      }
    }, revealMs + 120)
  }

  function startReveal() {
    if (!imgReadyRef.current || hasErrorRef.current || revealPhaseRef.current !== 'waiting') return
    setRevealPhase('revealing')
    scheduleRevealFallback()
  }

  function tryReveal() {
    if (!imgReadyRef.current || hasErrorRef.current) return
    const elapsed = Date.now() - loadStartedAtRef.current

    // 缓存或极快加载：直接展示，不播流光/渐入
    if (elapsed <= fastRevealThresholdMs) {
      finishImmediately()
      return
    }

    const remain = Math.max(0, minShimmerMs - elapsed)
    clearShimmerDelayTimer()
    if (remain <= 0) {
      startReveal()
      return
    }
    shimmerDelayTimerRef.current = window.setTimeout(() => {
      shimmerDelayTimerRef.current = null
      startReveal()
    }, remain)
  }

  function handleRevealAnimationEnd(event: ReactAnimationEvent<HTMLDivElement>) {
    if (event.animationName !== REVEAL_ANIM_MAP[revealDirection]) return
    clearRevealFallbackTimer()
    setRevealPhase('done')
    onLoad?.()
  }

  function handleImgReady() {
    imgReadyRef.current = true
    tryReveal()
  }

  function handleError() {
    setHasError(true)
    clearRevealTimers()
    onError?.()
  }

  function tryApplyCachedReady(): boolean {
    const el = imgRef.current
    if (!el?.complete || el.naturalWidth <= 0) return false
    imgReadyRef.current = true
    tryReveal()
    return revealPhaseRef.current !== 'waiting'
  }

  async function syncLoadedFromCache() {
    if (tryApplyCachedReady()) return

    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => {
        requestAnimationFrame(() => resolve())
      })
    })
    if (tryApplyCachedReady()) return

    clearCacheSyncFallbackTimer()
    cacheSyncFallbackTimerRef.current = window.setTimeout(() => {
      cacheSyncFallbackTimerRef.current = null
      tryApplyCachedReady()
    }, 64)
  }

  function resetLoadState() {
    clearRevealTimers()
    setRevealPhase('waiting')
    imgReadyRef.current = false
    setHasError(false)
    loadStartedAtRef.current = Date.now()
  }

  useEffect(() => {
    resetLoadState()
    if (!resolvedSrc) return
    void syncLoadedFromCache()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resolvedSrc])

  useEffect(() => {
    return () => clearRevealTimers()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const wrapperClassName = [
    'shimmer-image',
    wrapperClass,
    `shimmer-image--${revealPhase}`,
    `shimmer-image--reveal-${revealDirection}`,
    hasError ? 'shimmer-image--error' : ''
  ]
    .filter(Boolean)
    .join(' ')

  const imgClassName = [
    'shimmer-image__img',
    imgClass,
    revealPhase !== 'waiting' && !hasError ? 'is-loaded' : ''
  ]
    .filter(Boolean)
    .join(' ')

  const revealStyle = { '--shimmer-reveal-ms': `${revealMs}ms` } as CSSProperties

  return (
    <div
      className={wrapperClassName}
      style={revealStyle}
      onAnimationEnd={handleRevealAnimationEnd}
    >
      <div className="shimmer-image__reveal">
        {resolvedSrc && !hasError ? (
          <img
            ref={imgRef}
            src={resolvedSrc}
            alt={alt}
            decoding="async"
            className={imgClassName}
            style={{ objectFit, objectPosition: 'center' }}
            onLoad={handleImgReady}
            onError={handleError}
            onClick={onClick}
          />
        ) : null}
      </div>

      <div
        className="shimmer-image__placeholder"
        aria-hidden="true"
        style={revealPhase === 'waiting' ? undefined : { display: 'none' }}
      >
        <div className="shimmer-image__shimmer shimmer-image__shimmer--loop" />
      </div>

      {revealPhase === 'revealing' && revealDirection !== 'fade' ? (
        <div className="shimmer-image__scan-line" aria-hidden="true" />
      ) : null}

      {hasError ? errorSlot : null}
    </div>
  )
}

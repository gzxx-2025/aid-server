'use client'

import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react'
import type {
  AnimationEvent as ReactAnimationEvent,
  CSSProperties,
  MouseEvent as ReactMouseEvent,
  ReactNode,
  SyntheticEvent
} from 'react'
import { acquireMediaLoadSlot } from '~/utils/mediaLoadGate'

type RevealPhase = 'waiting' | 'revealing' | 'done'
type RevealDirection = 'vertical' | 'horizontal' | 'fade'

export interface ShimmerVideoProps {
  src?: string
  videoClass?: string
  wrapperClass?: string
  objectFit?: 'cover' | 'contain' | 'fill' | 'none' | 'scale-down'
  muted?: boolean
  playsInline?: boolean
  preload?: 'auto' | 'metadata' | 'none'
  /**
   * 进入滚动容器可视区后再挂 src。
   * 缩略图/列表务必开启；主预览可关闭。
   */
  lazy?: boolean
  /** 限制同时加载路数（默认开启），避免弹窗一次打爆网络 */
  gated?: boolean
  minShimmerMs?: number
  revealDirection?: RevealDirection
  revealMs?: number
  fastRevealThresholdMs?: number
  onClick?: (event: ReactMouseEvent<HTMLVideoElement>) => void
  onLoad?: () => void
  onError?: () => void
  onEnded?: (event: SyntheticEvent<HTMLVideoElement>) => void
  onPause?: (event: SyntheticEvent<HTMLVideoElement>) => void
  /** 原 error 具名插槽：加载失败时渲染 */
  errorSlot?: ReactNode
}

/** 原 defineExpose({ videoRef }) 契约 */
export interface ShimmerVideoHandle {
  readonly videoRef: HTMLVideoElement | null
}

const REVEAL_ANIM_MAP: Record<RevealDirection, string> = {
  horizontal: 'shimmer-image-reveal-horizontal',
  vertical: 'shimmer-image-reveal',
  fade: 'shimmer-image-reveal-fade'
}

/**
 * 单路占用闸门过久则强制释放，仅用于解堵后续队列；
 * 本卡若始终无 loadeddata/error，仍会保持 waiting shimmer（不误报 error）。
 */
const SLOT_HOLD_MAX_MS = 30_000

function findScrollRoot(el: HTMLElement): Element | null {
  let parent = el.parentElement
  while (parent) {
    const style = window.getComputedStyle(parent)
    const ox = style.overflowX
    const oy = style.overflowY
    if (/(auto|scroll|overlay)/.test(ox) || /(auto|scroll|overlay)/.test(oy)) {
      return parent
    }
    parent = parent.parentElement
  }
  return null
}

export const ShimmerVideo = forwardRef<ShimmerVideoHandle, ShimmerVideoProps>(
  function ShimmerVideo(
    {
      src = '',
      videoClass = '',
      wrapperClass = '',
      objectFit = 'cover',
      muted = true,
      playsInline = true,
      preload = 'metadata',
      lazy = false,
      gated = true,
      minShimmerMs = 0,
      revealDirection = 'fade',
      revealMs = 780,
      fastRevealThresholdMs = 120,
      onClick,
      onLoad,
      onError,
      onEnded,
      onPause,
      errorSlot
    },
    ref
  ) {
    const [revealPhase, setRevealPhaseState] = useState<RevealPhase>('waiting')
    const [hasError, setHasErrorState] = useState(false)
    const [inView, setInViewState] = useState(!lazy)
    const [slotReady, setSlotReadyState] = useState(!gated)

    const rootRef = useRef<HTMLDivElement | null>(null)
    const videoElRef = useRef<HTMLVideoElement | null>(null)
    const revealPhaseRef = useRef<RevealPhase>('waiting')
    const hasErrorRef = useRef(false)
    const mediaReadyRef = useRef(false)
    const loadStartedAtRef = useRef(0)
    const inViewRef = useRef(!lazy)
    const slotReadyRef = useRef(!gated)
    const loadEmittedRef = useRef(false)
    const observerRef = useRef<IntersectionObserver | null>(null)
    const releaseSlotRef = useRef<(() => void) | null>(null)
    const slotTokenRef = useRef(0)
    const revealTimerRef = useRef<number | null>(null)
    const revealFallbackTimerRef = useRef<number | null>(null)
    const cacheSyncFallbackTimerRef = useRef<number | null>(null)
    const slotHoldTimerRef = useRef<number | null>(null)

    const resolvedSrc = String(src || '').trim()
    const resolvedSrcRef = useRef(resolvedSrc)
    resolvedSrcRef.current = resolvedSrc

    const canBindSrc = inView && slotReady
    const shouldMountVideo = !!resolvedSrc && canBindSrc
    const activeSrc = shouldMountVideo ? resolvedSrc : ''
    const effectivePreload = canBindSrc ? preload : 'none'

    useImperativeHandle(
      ref,
      () => ({
        get videoRef() {
          return videoElRef.current
        }
      }),
      []
    )

    function setRevealPhase(phase: RevealPhase) {
      revealPhaseRef.current = phase
      setRevealPhaseState(phase)
    }

    function setHasError(value: boolean) {
      hasErrorRef.current = value
      setHasErrorState(value)
    }

    function setInView(value: boolean) {
      inViewRef.current = value
      setInViewState(value)
    }

    function setSlotReady(value: boolean) {
      slotReadyRef.current = value
      setSlotReadyState(value)
    }

    function clearRevealTimer() {
      if (revealTimerRef.current != null) {
        clearTimeout(revealTimerRef.current)
        revealTimerRef.current = null
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
      clearRevealTimer()
      clearRevealFallbackTimer()
      clearCacheSyncFallbackTimer()
    }

    function emitLoadOnce() {
      if (loadEmittedRef.current) return
      loadEmittedRef.current = true
      onLoad?.()
    }

    function finishImmediately() {
      clearRevealTimers()
      setRevealPhase('done')
      emitLoadOnce()
    }

    function scheduleRevealFallback() {
      clearRevealFallbackTimer()
      revealFallbackTimerRef.current = window.setTimeout(() => {
        revealFallbackTimerRef.current = null
        if (revealPhaseRef.current === 'revealing') {
          setRevealPhase('done')
          emitLoadOnce()
        }
      }, revealMs + 120)
    }

    function startReveal() {
      if (!mediaReadyRef.current || hasErrorRef.current || revealPhaseRef.current !== 'waiting')
        return
      setRevealPhase('revealing')
      scheduleRevealFallback()
    }

    function tryReveal() {
      if (!mediaReadyRef.current || hasErrorRef.current) return
      const elapsed = Date.now() - loadStartedAtRef.current

      if (elapsed <= fastRevealThresholdMs) {
        finishImmediately()
        return
      }

      const remain = Math.max(0, minShimmerMs - elapsed)
      clearRevealTimer()
      if (remain <= 0) {
        startReveal()
        return
      }
      revealTimerRef.current = window.setTimeout(() => {
        startReveal()
        revealTimerRef.current = null
      }, remain)
    }

    function handleRevealAnimationEnd(event: ReactAnimationEvent<HTMLDivElement>) {
      if (event.animationName !== REVEAL_ANIM_MAP[revealDirection]) return
      clearRevealFallbackTimer()
      setRevealPhase('done')
      emitLoadOnce()
    }

    function handleVideoReady() {
      if (mediaReadyRef.current) return
      mediaReadyRef.current = true
      // 闸门只限制「同时发起」的请求数；就绪后必须释放，否则列表/弹窗后续视频永久卡 shimmer
      releaseMediaSlot()
      tryReveal()
    }

    function handleError() {
      setHasError(true)
      clearRevealTimers()
      releaseMediaSlot()
      onError?.()
    }

    function tryApplyCachedReady(): boolean {
      const el = videoElRef.current
      if (!el || el.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) return false
      mediaReadyRef.current = true
      releaseMediaSlot()
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
      mediaReadyRef.current = false
      setHasError(false)
      loadEmittedRef.current = false
      loadStartedAtRef.current = Date.now()
    }

    function disconnectObserver() {
      if (!observerRef.current) return
      observerRef.current.disconnect()
      observerRef.current = null
    }

    function clearSlotHoldTimer() {
      if (slotHoldTimerRef.current == null) return
      clearTimeout(slotHoldTimerRef.current)
      slotHoldTimerRef.current = null
    }

    function scheduleSlotHoldTimeout() {
      clearSlotHoldTimer()
      if (!gated) return
      slotHoldTimerRef.current = window.setTimeout(() => {
        slotHoldTimerRef.current = null
        releaseMediaSlot()
      }, SLOT_HOLD_MAX_MS)
    }

    function releaseMediaSlot() {
      clearSlotHoldTimer()
      if (!releaseSlotRef.current) return
      releaseSlotRef.current()
      releaseSlotRef.current = null
    }

    function markInView() {
      if (inViewRef.current) return
      setInView(true)
      disconnectObserver()
    }

    function setupLazyObserver() {
      disconnectObserver()
      if (!lazy || typeof window === 'undefined') {
        setInView(true)
        return
      }
      const el = rootRef.current
      if (!el) return
      if (typeof IntersectionObserver === 'undefined') {
        setInView(true)
        return
      }

      const root = findScrollRoot(el)
      observerRef.current = new IntersectionObserver(
        (entries) => {
          if (!entries.some((entry) => entry.isIntersecting)) return
          markInView()
        },
        {
          root,
          // 只预热邻近一屏，避免头部横向 Tab 一次加载过多
          rootMargin: root ? '40px 48px' : '80px 40px',
          threshold: 0.15
        }
      )
      observerRef.current.observe(el)
    }

    async function ensureLoadSlot() {
      const token = ++slotTokenRef.current
      releaseMediaSlot()
      if (!gated) {
        setSlotReady(true)
        return
      }
      setSlotReady(false)
      const release = await acquireMediaLoadSlot(2)
      if (token !== slotTokenRef.current) {
        release()
        return
      }
      releaseSlotRef.current = release
      setSlotReady(true)
      scheduleSlotHoldTimeout()
    }

    // lazy 观察器：挂载与 lazy 变化时重建
    useEffect(() => {
      if (!lazy) {
        setInView(true)
        disconnectObserver()
        return
      }
      setInView(false)
      const raf = requestAnimationFrame(() => setupLazyObserver())
      return () => {
        cancelAnimationFrame(raf)
        disconnectObserver()
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [lazy])

    // src / 可视状态变化：重置并按闸门加载
    useEffect(() => {
      resetLoadState()
      if (!resolvedSrc || !inView) {
        releaseMediaSlot()
        setSlotReady(!gated)
        return
      }
      void (async () => {
        await ensureLoadSlot()
        if (!slotReadyRef.current || !resolvedSrcRef.current) return
        await syncLoadedFromCache()
      })()
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [resolvedSrc, inView])

    useEffect(() => {
      return () => {
        slotTokenRef.current += 1
        clearRevealTimers()
        disconnectObserver()
        releaseMediaSlot()
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    const wrapperClassName = [
      'shimmer-image',
      'shimmer-video',
      wrapperClass,
      `shimmer-image--${revealPhase}`,
      `shimmer-image--reveal-${revealDirection}`,
      hasError ? 'shimmer-image--error' : ''
    ]
      .filter(Boolean)
      .join(' ')

    const videoClassName = ['shimmer-image__img', videoClass].filter(Boolean).join(' ')

    const revealStyle = { '--shimmer-reveal-ms': `${revealMs}ms` } as CSSProperties

    return (
      <div ref={rootRef} className={wrapperClassName} style={revealStyle}>
        <div className="shimmer-image__reveal" onAnimationEnd={handleRevealAnimationEnd}>
          {shouldMountVideo && !hasError ? (
            <video
              ref={videoElRef}
              src={activeSrc}
              className={videoClassName}
              style={{ objectFit, objectPosition: 'center' }}
              muted={muted}
              playsInline={playsInline}
              preload={effectivePreload}
              onLoadedData={handleVideoReady}
              onCanPlay={handleVideoReady}
              onError={handleError}
              onClick={onClick}
              onEnded={onEnded}
              onPause={onPause}
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
)

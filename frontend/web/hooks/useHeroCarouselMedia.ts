import type { HeroCarouselSlide,HeroPhase } from '~/types/heroCarousel'
import { HERO_POSTER_HOLD_MS,HERO_VIDEO_MAX_PLAY_MS } from '~/types/heroCarousel'

export interface UseHeroCarouselMediaOptions {
  /** 原 ComputedRef：改为 getter，调用时取最新中心 slide */
  centerSlide: () => HeroCarouselSlide | undefined
  defaultVideoUrl?: () => string | undefined
  /** 原 Ref<HeroPhase>：改为 ref-like 容器（React useRef） */
  phase: { current: HeroPhase }
  emitPhaseChange: (phase: HeroPhase) => void
  getAnimToken: () => number
  isCycleAllowed: () => boolean
  onCycleComplete: () => void
}

/**
 * 原 composables/useHeroCarouselMedia：纯闭包工厂，不含 React Hook，
 * 组件内通过 useRef 创建一次（options 闭包读 ref 保证取到最新值）。
 */
export function createHeroCarouselMedia(options: UseHeroCarouselMediaOptions) {
  let videoEl: HTMLVideoElement | null = null
  let loadedUrl = ''
  let videoEndHandler: (() => void) | null = null
  let videoMaxTimer: ReturnType<typeof setTimeout> | null = null
  let posterHoldTimer: ReturnType<typeof setTimeout> | null = null
  let mediaRunToken = 0

  function setHeroVideoElement(el: HTMLVideoElement | null) {
    videoEl = el
  }

  function clearTimers() {
    if (videoMaxTimer) {
      clearTimeout(videoMaxTimer)
      videoMaxTimer = null
    }
    if (posterHoldTimer) {
      clearTimeout(posterHoldTimer)
      posterHoldTimer = null
    }
  }

  function clearVideoEndHandler() {
    if (videoEl && videoEndHandler) {
      videoEl.removeEventListener('ended', videoEndHandler)
      videoEndHandler = null
    }
  }

  function resolvePlayVideoUrl() {
    const fromSlide = options.centerSlide()?.videoUrl?.trim()
    if (fromSlide) return fromSlide
    return options.defaultVideoUrl?.()?.trim() || ''
  }

  function setIdlePhase() {
    options.phase.current = 'idle'
    options.emitPhaseChange('idle')
  }

  function setPlayingPhase() {
    options.phase.current = 'playing'
    options.emitPhaseChange('playing')
  }

  /** 切换前轻量暂停：不 unload / load，避免与轮播动画争抢主线程 */
  function pauseHeroVideo() {
    mediaRunToken++
    clearTimers()
    clearVideoEndHandler()
    if (videoEl) {
      videoEl.pause()
    }
    setIdlePhase()
  }

  function releaseHeroVideo() {
    pauseHeroVideo()
    loadedUrl = ''
    if (videoEl) {
      videoEl.removeAttribute('src')
      videoEl.load()
    }
  }

  function cancelMedia() {
    mediaRunToken++
    clearTimers()
    clearVideoEndHandler()
  }

  function finishMediaRun(runToken: number, animToken: number) {
    if (runToken !== mediaRunToken) return

    clearTimers()
    clearVideoEndHandler()
    if (videoEl) {
      videoEl.pause()
    }
    setIdlePhase()

    if (animToken !== options.getAnimToken() || !options.isCycleAllowed()) return
    options.onCycleComplete()
  }

  async function playSlideMedia(animToken: number) {
    const runToken = ++mediaRunToken
    clearTimers()
    clearVideoEndHandler()
    if (videoEl) {
      videoEl.pause()
    }
    setIdlePhase()

    const url = resolvePlayVideoUrl()
    if (!videoEl || !url) {
      await new Promise<void>((resolve) => {
        posterHoldTimer = setTimeout(() => {
          posterHoldTimer = null
          resolve()
        }, HERO_POSTER_HOLD_MS)
      })
      if (runToken !== mediaRunToken || animToken !== options.getAnimToken()) return
      finishMediaRun(runToken, animToken)
      return
    }

    if (animToken !== options.getAnimToken()) return

    await new Promise<void>((resolve) => {
      posterHoldTimer = setTimeout(() => {
        posterHoldTimer = null
        resolve()
      }, HERO_POSTER_HOLD_MS)
    })

    if (runToken !== mediaRunToken || animToken !== options.getAnimToken()) return

    const poster = options.centerSlide()?.cover ?? ''
    videoEl.poster = poster

    if (loadedUrl !== url) {
      loadedUrl = url
      videoEl.src = url
    }
    videoEl.currentTime = 0

    setPlayingPhase()

    await new Promise<void>((resolve) => {
      const finish = () => {
        if (runToken !== mediaRunToken) {
          resolve()
          return
        }
        clearTimers()
        clearVideoEndHandler()
        if (videoEl) {
          videoEl.pause()
        }
        setIdlePhase()
        resolve()
        finishMediaRun(runToken, animToken)
      }

      const onEnd = () => finish()
      videoEndHandler = onEnd
      videoEl!.addEventListener('ended', onEnd)
      videoMaxTimer = setTimeout(finish, HERO_VIDEO_MAX_PLAY_MS)

      const tryPlay = () => {
        if (runToken !== mediaRunToken) return
        videoEl!.play().catch(finish)
      }

      if (videoEl!.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) {
        tryPlay()
      } else {
        videoEl!.addEventListener('canplay', tryPlay, { once: true })
      }
    })
  }

  return {
    setHeroVideoElement,
    pauseHeroVideo,
    releaseHeroVideo,
    playSlideMedia,
    cancelMedia
  }
}

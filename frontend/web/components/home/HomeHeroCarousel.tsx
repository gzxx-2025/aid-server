'use client'

import type {
CSSProperties,
MutableRefObject,
KeyboardEvent as ReactKeyboardEvent,
MouseEvent as ReactMouseEvent
} from 'react'
import {
forwardRef,
useCallback,
useEffect,
useImperativeHandle,
useLayoutEffect,
useMemo,
useRef,
useState
} from 'react'
import HomeHeroCarouselCardReveal from '~/components/home/HomeHeroCarouselCardReveal'
import { createHeroCarouselMedia } from '~/composables/useHeroCarouselMedia'
import type { HeroCarouselSlide,HeroPhase } from '~/types/heroCarousel'
import { HERO_CAROUSEL_TRANSITION_MS } from '~/types/heroCarousel'
import { scaleForHomeNewCompact } from '~/utils/homeNewCompactViewport'
import './HomeHeroCarousel.css'

export type { HeroCarouselSlide, HeroPhase }

const TRANSITION_MS = HERO_CAROUSEL_TRANSITION_MS
const MAX_OFFSET = 2
/** Coverflow 每槽旋转角（°） */
const COVERFLOW_ROTATE = 28
const COVERFLOW_DEPTH = 86
/** 任意相邻两张卡片边缘间距（px），含 1-2、4-5 */
const CARD_EDGE_GAP = 30
/** 中心卡高度（整体压矮轮播占比） */
const CENTER_CARD_H_DESKTOP = 158
const CENTER_CARD_H_MOBILE = 100
/** 卡片宽高比（以高度反推宽度，避免铺满视口过宽） */
const CARD_ASPECT = 16 / 5
/** 两侧留白（px），越大卡片越窄 */
const SIDE_INSET_DESKTOP = 96
const SIDE_INSET_MOBILE = 36

const HERO_SKELETON_SLIDES: HeroCarouselSlide[] = [
  { id: '__hero_sk_0', title: '加载中', cover: '' },
  { id: '__hero_sk_1', title: '加载中', cover: '' },
  { id: '__hero_sk_2', title: '加载中', cover: '' }
]

export interface HomeHeroCarouselProps {
  slides: HeroCarouselSlide[]
  heroStageRef?: HTMLElement | null
  heroVideoEl?: HTMLVideoElement | null
  defaultVideoUrl?: string
  initialCenterIndex?: number
  cycleEnabled?: boolean
  /** Banner 未到前展示三张骨架卡 */
  skeleton?: boolean
  /** 原 emit('update:activeIndex') */
  onActiveIndexChange?: (index: number) => void
  /** 原 emit('phaseChange') */
  onPhaseChange?: (phase: HeroPhase) => void
  /** 原 emit('openLink') */
  onOpenLink?: (slide: HeroCarouselSlide) => void
}

/** 原 defineExpose 暴露面 */
export interface HomeHeroCarouselHandle {
  readonly phase: HeroPhase
  readonly centerSlide: HeroCarouselSlide | undefined
  readonly centerContentIndex: number
  setHeroVideoElement: (el: HTMLVideoElement | null) => void
  restartCycle: () => Promise<void>
  pauseAutoPlay: () => void
  resumeAutoPlay: () => void
}

interface HeroLayoutMetricsState {
  cardW: number
  cardH: number
  edgeGap: number
  cylinderRadius: number
  viewportW: number
  viewportH: number
}

/** 原 await nextTick()：React 下用 RAF 让出一帧（DOM 已提交） */
function nextFrame() {
  return new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
}

function prefersReducedMotion() {
  if (!(typeof window !== 'undefined')) return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function normalizeIndex(index: number, count: number) {
  if (!count) return 0
  return ((index % count) + count) % count
}

function shortestDelta(from: number, to: number, count: number) {
  if (!count) return 0
  let delta = to - from
  if (delta > count / 2) delta -= count
  if (delta < -count / 2) delta += count
  return delta
}

function transitionEase(t: number) {
  if (t <= 0) return 0
  if (t >= 1) return 1
  return 1 - Math.pow(1 - t, 3)
}

/** 原 ref/computed 改为「state + ref 镜像」：渲染读 state，异步/闭包读 ref 取最新值 */
function useStateRef<T>(initial: T): [T, MutableRefObject<T>, (v: T) => void] {
  const [value, setValue] = useState(initial)
  const ref = useRef(initial)
  const set = useCallback((v: T) => {
    ref.current = v
    setValue(v)
  }, [])
  return [value, ref, set]
}

const HomeHeroCarousel = forwardRef<HomeHeroCarouselHandle, HomeHeroCarouselProps>(
  function HomeHeroCarousel(props, ref) {
    const [layoutReady, layoutReadyRef, setLayoutReady] = useStateRef(false)
    const [activeIndex, activeIndexRef, setActiveIndex] = useStateRef(0)
    /** 动画过程中的连续位置（可小数） */
    const [, scrollPositionRef, setScrollPosition] = useStateRef(0)
    const [isAnimating, , setIsAnimating] = useStateRef(false)
    const [isHovered, isHoveredRef, setIsHovered] = useStateRef(false)

    const viewportRef = useRef<HTMLDivElement | null>(null)
    const [layoutMetrics, layoutMetricsRef, setLayoutMetrics] =
      useStateRef<HeroLayoutMetricsState>({
        cardW: 200,
        cardH: CENTER_CARD_H_DESKTOP,
        edgeGap: CARD_EDGE_GAP,
        cylinderRadius: 520,
        viewportW: 1200,
        viewportH: CENTER_CARD_H_DESKTOP
      })

    const animTokenRef = useRef(0)
    const autoTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
    const cyclePendingRef = useRef(false)
    const resizeObserverRef = useRef<ResizeObserver | null>(null)

    const realSlides = useMemo(() => props.slides.filter((slide) => slide.cover), [props.slides])
    const isSkeletonMode = !!props.skeleton && realSlides.length === 0
    const sourceSlides = useMemo(
      () => (realSlides.length ? realSlides : isSkeletonMode ? HERO_SKELETON_SLIDES : []),
      [isSkeletonMode, realSlides]
    )
    /** 闭包（媒体阶段机 / RAF 动画）统一经 ref 读最新 props 与派生值 */
    const propsRef = useRef(props)
    const sourceSlidesRef = useRef(sourceSlides)
    const isSkeletonModeRef = useRef(isSkeletonMode)
    useLayoutEffect(() => {
      propsRef.current = props
      sourceSlidesRef.current = sourceSlides
      isSkeletonModeRef.current = isSkeletonMode
    }, [isSkeletonMode, props, sourceSlides])

    const phaseRef = useRef<HeroPhase>('idle')

    const carouselCssVars = {
      '--carousel-card-w': `${layoutMetrics.cardW}px`,
      '--carousel-card-h': `${layoutMetrics.cardH}px`,
      '--carousel-viewport-h': `${layoutMetrics.viewportH}px`,
      '--carousel-cylinder-r': `${Math.round(layoutMetrics.cylinderRadius)}px`
    } as CSSProperties

    function isCycleAllowed() {
      return !!(
        propsRef.current.cycleEnabled &&
        !isSkeletonModeRef.current &&
        sourceSlidesRef.current.length > 1 &&
        !isHoveredRef.current
      )
    }

    function queueNextAdvance() {
      if (cyclePendingRef.current || !isCycleAllowed()) return
      cyclePendingRef.current = true
      queueMicrotask(() => {
        cyclePendingRef.current = false
        void advanceAuto()
      })
    }

    /** 原 useHeroCarouselMedia()：闭包工厂仅创建一次，options 全部经 ref 取最新值 */
    const mediaRef = useRef<ReturnType<typeof createHeroCarouselMedia> | null>(null)
    if (!mediaRef.current) {
      mediaRef.current = createHeroCarouselMedia({
        centerSlide: () =>
          sourceSlidesRef.current[activeIndexRef.current] ?? sourceSlidesRef.current[0],
        defaultVideoUrl: () => propsRef.current.defaultVideoUrl,
        phase: phaseRef,
        emitPhaseChange: (nextPhase) => propsRef.current.onPhaseChange?.(nextPhase),
        getAnimToken: () => animTokenRef.current,
        isCycleAllowed,
        onCycleComplete: queueNextAdvance
      })
    }
    const media = mediaRef.current

    function setHeroVideoElement(el: HTMLVideoElement | null) {
      media.setHeroVideoElement(el)
    }

    /* 原 watch(() => props.heroVideoEl, immediate, flush: post) */
    useEffect(() => {
      media.setHeroVideoElement(props.heroVideoEl ?? null)
    }, [media, props.heroVideoEl])

    function resolveInitialIndex(count: number) {
      if (!count) return 0
      const preferred = propsRef.current.initialCenterIndex ?? 0
      return Math.min(Math.max(0, preferred), count - 1)
    }

    function getOffset(index: number) {
      const count = sourceSlidesRef.current.length
      if (!count) return 0
      const raw = index - scrollPositionRef.current
      let offset = raw
      if (offset > count / 2) offset -= count
      if (offset < -count / 2) offset += count
      return offset
    }

    /** 悬停前后对比滑动揭示已下线，卡片仅展示封面 */
    function isRevealEnabled(_index: number) {
      return false
    }

    function scaleAtOffset(offset: number) {
      const abs = Math.min(Math.abs(offset), MAX_OFFSET)
      // 中心 1.0；左右邻卡（第1/3张）约 0.78；更外侧约 0.62，高度层级更明显
      if (abs < 0.001) return 1
      return Math.max(0.6, 1 - abs * 0.2)
    }

    /** rotateY 后屏幕上的水平半宽（与 2-3 同规则，避免 1-2 / 4-5 视觉缝变大） */
    function horizontalHalfExtent(offset: number) {
      const cardW = layoutMetricsRef.current.cardW
      const scale = scaleAtOffset(offset)
      const rotateRad = Math.abs(offset * COVERFLOW_ROTATE) * (Math.PI / 180)
      return (cardW * scale * Math.cos(rotateRad)) / 2
    }

    /** 相邻两槽中心距 = 投影半宽之和 + 固定边缘缝 */
    function stepDistanceBetween(oInner: number, oOuter: number) {
      const gap = layoutMetricsRef.current.edgeGap
      return horizontalHalfExtent(oInner) + horizontalHalfExtent(oOuter) + gap
    }

    /** 按实际卡片宽度逐步累加 X，保证 1-2、2-3…每对边缘缝一致 */
    function translateXAtOffset(offset: number) {
      if (!offset) return 0

      const sign = Math.sign(offset)
      const abs = Math.abs(offset)
      const fullSteps = Math.floor(abs)
      const frac = abs - fullSteps
      let x = 0

      for (let i = 0; i < fullSteps; i += 1) {
        const oInner = sign * i
        const oOuter = sign * (i + 1)
        x += sign * stepDistanceBetween(oInner, oOuter)
      }

      if (frac > 0) {
        const oInner = sign * fullSteps
        const oOuter = sign * (fullSteps + 1)
        x += sign * stepDistanceBetween(oInner, oOuter) * frac
      }

      return x
    }

    /** 最外侧卡片外缘到视口中心的距离 */
    function outerEdgeAtOffset2() {
      return Math.abs(translateXAtOffset(MAX_OFFSET)) + horizontalHalfExtent(MAX_OFFSET)
    }

    /** Coverflow 3D */
    function getCylinderTransform(offset: number) {
      const translateX = translateXAtOffset(offset)
      const abs = Math.abs(offset)
      const theta = offset * COVERFLOW_ROTATE * (Math.PI / 180)
      const translateZ = -abs * COVERFLOW_DEPTH
      const rotateY = -offset * COVERFLOW_ROTATE
      const depth = Math.cos(theta)
      const scale = scaleAtOffset(offset)
      const zIndex = Math.round(38 + depth * 10 - abs * 2)

      return { translateX, translateZ, rotateY, scale, zIndex }
    }

    function getSlideShellStyle(index: number): CSSProperties {
      const offset = getOffset(index)
      const abs = Math.abs(offset)

      if (abs > MAX_OFFSET + 0.85) {
        return {
          opacity: 0,
          visibility: 'hidden',
          pointerEvents: 'none',
          transform: 'translate3d(-50%, -50%, -420px) scale(0.62)',
          zIndex: 0
        }
      }

      const { translateX, translateZ, scale, zIndex } = getCylinderTransform(offset)

      return {
        transform: `translate3d(calc(-50% + ${translateX.toFixed(2)}px), -50%, ${translateZ.toFixed(2)}px) scale(${scale.toFixed(3)})`,
        opacity: 1,
        zIndex
      }
    }

    /** 内层 rotateY：Swiper coverflow 惯例，侧卡内侧边朝向中心微倾 */
    function getSlideRotateStyle(index: number): CSSProperties {
      const { rotateY } = getCylinderTransform(getOffset(index))
      return {
        transform: `rotateY(${rotateY.toFixed(2)}deg)`
      }
    }

    function syncLayoutMetrics() {
      const vp = viewportRef.current
      if (!vp) return false

      const viewportW = vp.clientWidth
      if (viewportW <= 0) return false

      const isMobile = viewportW <= 768
      const cardH = scaleForHomeNewCompact(
        isMobile ? CENTER_CARD_H_MOBILE : CENTER_CARD_H_DESKTOP
      )
      const edgeGap = isMobile ? 24 : CARD_EDGE_GAP
      const sideInset = isMobile ? SIDE_INSET_MOBILE : SIDE_INSET_DESKTOP
      const targetOuterEdge = viewportW / 2 - sideInset
      const viewportH = cardH

      // 以高度 × 16:9 定宽，不再横向铺满；若仍超出两侧留白再收缩
      let cardW = Math.round(cardH * CARD_ASPECT)
      layoutMetricsRef.current = {
        cardW,
        cardH,
        edgeGap,
        cylinderRadius: 520,
        viewportW,
        viewportH
      }

      for (let i = 0; i < 48; i += 1) {
        const outer = outerEdgeAtOffset2()
        if (outer > targetOuterEdge + 2 && cardW > 120) {
          cardW -= 2
          layoutMetricsRef.current = { ...layoutMetricsRef.current, cardW }
          continue
        }
        break
      }

      layoutMetricsRef.current = {
        ...layoutMetricsRef.current,
        cylinderRadius: Math.max(360, Math.abs(translateXAtOffset(MAX_OFFSET)) * 1.35)
      }
      setLayoutMetrics(layoutMetricsRef.current)
      setLayoutReady(true)
      return true
    }

    function ensureLayoutReady() {
      return syncLayoutMetrics()
    }

    function waitForLayoutReady(maxFrames = 40) {
      if (!(typeof window !== 'undefined')) return Promise.resolve()
      return new Promise<void>((resolve) => {
        let frames = 0
        const tick = () => {
          if (ensureLayoutReady() || frames++ >= maxFrames) {
            resolve()
            return
          }
          requestAnimationFrame(tick)
        }
        requestAnimationFrame(tick)
      })
    }

    function emitActiveIndex(index: number) {
      setActiveIndex(index)
      propsRef.current.onActiveIndexChange?.(index)
    }

    function animateToIndex(targetIndex: number, duration = TRANSITION_MS) {
      const count = sourceSlidesRef.current.length
      if (!count) return Promise.resolve()

      const token = animTokenRef.current
      const normalizedTarget = normalizeIndex(targetIndex, count)
      const start = scrollPositionRef.current
      const delta = shortestDelta(Math.round(start), normalizedTarget, count)
      if (!delta) {
        setScrollPosition(normalizedTarget)
        emitActiveIndex(normalizedTarget)
        return Promise.resolve()
      }

      if (prefersReducedMotion()) {
        setScrollPosition(normalizedTarget)
        emitActiveIndex(normalizedTarget)
        return Promise.resolve()
      }

      setIsAnimating(true)

      return new Promise<void>((resolve) => {
        let startTime: number | null = null
        const tick = (now: number) => {
          if (startTime === null) startTime = now
          if (token !== animTokenRef.current) {
            setIsAnimating(false)
            resolve()
            return
          }

          const t = Math.min(1, (now - startTime) / duration)
          const eased = transitionEase(t)
          setScrollPosition(start + delta * eased)

          if (t < 1) {
            requestAnimationFrame(tick)
          } else {
            setScrollPosition(normalizedTarget)
            emitActiveIndex(normalizedTarget)
            setIsAnimating(false)
            resolve()
          }
        }
        requestAnimationFrame(tick)
      })
    }

    function clearAutoTimer() {
      if (autoTimerRef.current) {
        clearTimeout(autoTimerRef.current)
        autoTimerRef.current = null
      }
    }

    function beginSlideMedia(token: number) {
      void media.playSlideMedia(token)
    }

    function onMouseEnter() {
      setIsHovered(true)
      clearAutoTimer()
      media.cancelMedia()
      media.pauseHeroVideo()
    }

    function onMouseLeave() {
      setIsHovered(false)
      if (!isCycleAllowed()) return
      void media.playSlideMedia(animTokenRef.current)
    }

    async function advanceAuto() {
      const count = sourceSlidesRef.current.length
      if (!count || !isCycleAllowed()) return

      const token = animTokenRef.current
      const next = normalizeIndex(activeIndexRef.current + 1, count)

      media.pauseHeroVideo()
      await animateToIndex(next)
      if (token !== animTokenRef.current) return

      beginSlideMedia(token)
    }

    async function goToIndex(index: number) {
      if (isSkeletonModeRef.current) return
      const count = sourceSlidesRef.current.length
      if (!count || index < 0 || index >= count || index === activeIndexRef.current) return

      clearAutoTimer()
      animTokenRef.current++
      media.pauseHeroVideo()

      const token = animTokenRef.current
      await animateToIndex(index)
      if (token !== animTokenRef.current) return

      beginSlideMedia(token)
    }

    function goPrev() {
      const count = sourceSlidesRef.current.length
      if (!count) return
      void goToIndex(normalizeIndex(activeIndexRef.current - 1, count))
    }

    function goNext() {
      const count = sourceSlidesRef.current.length
      if (!count) return
      void goToIndex(normalizeIndex(activeIndexRef.current + 1, count))
    }

    function tryOpenCenterSlideLink(index: number) {
      const slide = sourceSlidesRef.current[index]
      if (!slide) return
      const linkType = String(slide.linkType || 'none').toLowerCase()
      const linkUrl = String(slide.linkUrl || '').trim()
      if (linkType === 'none' || !linkUrl) return
      propsRef.current.onOpenLink?.(slide)
    }

    function onSlideClick(index: number) {
      if (isSkeletonModeRef.current) return
      const offset = getOffset(index)
      if (!layoutReadyRef.current) return
      if (offset === 0) {
        tryOpenCenterSlideLink(index)
        return
      }
      void goToIndex(index)
    }

    /** 原 @keydown.enter.prevent / @keydown.space.prevent */
    function onSlideKeyDown(event: ReactKeyboardEvent<HTMLDivElement>, index: number) {
      if (event.key !== 'Enter' && event.key !== ' ') return
      event.preventDefault()
      onSlideClick(index)
    }

    /** 点击轮播区域左半/右半切换 */
    function onViewportClick(event: ReactMouseEvent<HTMLDivElement>) {
      if (
        isSkeletonModeRef.current ||
        !layoutReadyRef.current ||
        sourceSlidesRef.current.length <= 1
      )
        return

      const vp = viewportRef.current
      if (!vp) return

      const target = event.target as HTMLElement
      const slideEl = target.closest('.home-hero-carousel__slide') as HTMLElement | null
      if (slideEl) {
        const offset = Number(slideEl.dataset.offset ?? '0')
        if (offset !== 0) {
          const index = Number(slideEl.dataset.index ?? '-1')
          if (index >= 0) {
            void goToIndex(index)
            return
          }
        }
      }

      const rect = vp.getBoundingClientRect()
      const x = event.clientX - rect.left
      if (x < rect.width * 0.5) goPrev()
      else goNext()
    }

    async function restartCycle() {
      animTokenRef.current++
      clearAutoTimer()
      media.pauseHeroVideo()

      await nextFrame()
      await waitForLayoutReady()

      const initial = resolveInitialIndex(sourceSlidesRef.current.length)
      setScrollPosition(initial)
      emitActiveIndex(initial)

      if (!propsRef.current.cycleEnabled) return

      beginSlideMedia(animTokenRef.current)
    }

    function onResize() {
      ensureLayoutReady()
    }

    /* 原 watch(() => props.cycleEnabled)（非 immediate，跳过首轮） */
    const cycleEnabledWatchReadyRef = useRef(false)
    useEffect(() => {
      if (!cycleEnabledWatchReadyRef.current) {
        cycleEnabledWatchReadyRef.current = true
        return
      }
      if (props.cycleEnabled) {
        void restartCycle()
      } else {
        animTokenRef.current++
        clearAutoTimer()
        media.releaseHeroVideo()
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [props.cycleEnabled])

    /* 原 watch(() => sourceSlides.map((s) => s.id).join(','))（非 immediate，跳过首轮） */
    const slidesKey = sourceSlides.map((s) => s.id).join(',')
    const slidesKeyWatchReadyRef = useRef(false)
    useEffect(() => {
      if (!slidesKeyWatchReadyRef.current) {
        slidesKeyWatchReadyRef.current = true
        return
      }
      void (async () => {
        await nextFrame()
        await waitForLayoutReady()
        const initial = resolveInitialIndex(sourceSlidesRef.current.length)
        setScrollPosition(initial)
        emitActiveIndex(initial)
        if (propsRef.current.cycleEnabled) void restartCycle()
      })()
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [slidesKey])

    /* 原 onMounted / onBeforeUnmount */
    useEffect(() => {
      const handleResize = onResize
      const animationToken = animTokenRef
      void (async () => {
        await nextFrame()
        if (viewportRef.current) {
          resizeObserverRef.current = new ResizeObserver(handleResize)
          resizeObserverRef.current.observe(viewportRef.current)
        }
        window.addEventListener('resize', handleResize)
        await waitForLayoutReady()
        const initial = resolveInitialIndex(sourceSlidesRef.current.length)
        setScrollPosition(initial)
        emitActiveIndex(initial)
        if (propsRef.current.cycleEnabled) void restartCycle()
      })()
      return () => {
        animationToken.current++
        clearAutoTimer()
        resizeObserverRef.current?.disconnect()
        if (typeof window !== 'undefined') {
          window.removeEventListener('resize', handleResize)
        }
        media.releaseHeroVideo()
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    /* 原 onMounted 内对 heroStageRef 的 observe：React 下该 prop 在父级挂载后才有值，改为独立副作用 */
    useEffect(() => {
      const stage = props.heroStageRef
      if (!stage) return
      if (!resizeObserverRef.current) {
        resizeObserverRef.current = new ResizeObserver(onResize)
      }
      const observer = resizeObserverRef.current
      observer.observe(stage)
      return () => {
        observer.unobserve(stage)
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [props.heroStageRef])

    /* 原 defineExpose */
    useImperativeHandle(
      ref,
      () => ({
        get phase() {
          return phaseRef.current
        },
        get centerSlide() {
          return sourceSlidesRef.current[activeIndexRef.current] ?? sourceSlidesRef.current[0]
        },
        get centerContentIndex() {
          return activeIndexRef.current
        },
        setHeroVideoElement,
        restartCycle,
        pauseAutoPlay: onMouseEnter,
        resumeAutoPlay: onMouseLeave
      }),
      // eslint-disable-next-line react-hooks/exhaustive-deps
      []
    )

    const rootClassName = [
      'home-hero-carousel',
      layoutReady ? 'is-ready' : '',
      isAnimating ? 'is-animating' : '',
      isHovered ? 'is-paused' : '',
      isSkeletonMode ? 'is-skeleton' : ''
    ]
      .filter(Boolean)
      .join(' ')

    return (
      <div
        className={rootClassName}
        role="region"
        aria-label="精选案例轮播"
        style={carouselCssVars}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
      >
        <div
          ref={viewportRef}
          className="home-hero-carousel__viewport"
          onClick={onViewportClick}
        >
          <div className="home-hero-carousel__track">
            {sourceSlides.map((slide, index) => (
              <div
                key={slide.id}
                className={[
                  'home-hero-carousel__slide',
                  index === activeIndex && !isAnimating ? 'is-active' : '',
                  layoutReady && getOffset(index) !== 0 ? 'is-clickable' : ''
                ]
                  .filter(Boolean)
                  .join(' ')}
                style={getSlideShellStyle(index)}
                data-index={index}
                data-offset={getOffset(index).toFixed(3)}
                role={isSkeletonMode ? 'presentation' : 'button'}
                tabIndex={
                  isSkeletonMode || !layoutReady || Math.abs(getOffset(index)) > 2 ? -1 : 0
                }
                aria-label={isSkeletonMode ? undefined : slide.title}
                aria-hidden={isSkeletonMode ? true : undefined}
                aria-current={!isSkeletonMode && index === activeIndex ? 'true' : undefined}
                onKeyDown={(event) => onSlideKeyDown(event, index)}
              >
                <div className="home-hero-carousel__card" style={getSlideRotateStyle(index)}>
                  <HomeHeroCarouselCardReveal
                    cover={slide.cover}
                    title={slide.title}
                    videoUrl={slide.videoUrl}
                    defaultVideoUrl={props.defaultVideoUrl}
                    enabled={isRevealEnabled(index)}
                    skeleton={isSkeletonMode}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>

        {!isSkeletonMode ? (
          <div
            className="home-hero-carousel__dots"
            role="tablist"
            aria-label="轮播分页"
          >
            {sourceSlides.map((_, index) => (
              <button
                key={`dot-${index}`}
                type="button"
                role="tab"
                className={`home-hero-carousel__dot${index === activeIndex ? ' is-active' : ''}`}
                aria-selected={index === activeIndex}
                aria-label={`第 ${index + 1} 项`}
                onClick={() => void goToIndex(index)}
              />
            ))}
          </div>
        ) : null}
      </div>
    )
  }
)

export default HomeHeroCarousel

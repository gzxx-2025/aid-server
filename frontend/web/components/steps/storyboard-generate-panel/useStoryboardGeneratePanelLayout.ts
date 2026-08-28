'use client'

import { useEffect,useMemo,useRef,useState,type RefObject } from 'react'

/** 对齐 Vue nextTick：DOM 量取 / flag 复位统一走宏任务 */
function nextTick(fn: () => void) {
  setTimeout(fn, 0)
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

function pxToNumber(v: string) {
  const n = Number.parseFloat(v)
  return Number.isFinite(n) ? n : 0
}

function maxPx(a: string, b: string) {
  return `${Math.max(pxToNumber(a), pxToNumber(b))}px`
}

interface PanelLayoutOptions {
  usePreciseLayout: boolean
  isSettingExpanded: boolean
  panelRootRef: RefObject<HTMLDivElement | null>
  headerWrapRef: RefObject<HTMLDivElement | null>
  slotWrapRef: RefObject<HTMLDivElement | null>
  promptCollapsedRef: RefObject<HTMLDivElement | null>
}

/**
 * 面板高度自适应：视口断点插值 + （精确模式）按父级剩余高度实测计算中部双栏高度。
 * 原 StoryboardGeneratePanel.vue 的 viewportHeight / recomputePreciseLayout / panelCssVars 逻辑。
 */
export function useStoryboardGeneratePanelLayout(options: PanelLayoutOptions) {
  const { usePreciseLayout, isSettingExpanded, panelRootRef, headerWrapRef, slotWrapRef, promptCollapsedRef } =
    options

  const [viewportHeight, setViewportHeight] = useState(1080)
  const [panelClientHeight, setPanelClientHeight] = useState(0)
  const [preciseTopCollapsedHeight, setPreciseTopCollapsedHeight] = useState<string | null>(null)
  const [preciseTopExpandedHeight, setPreciseTopExpandedHeight] = useState<string | null>(null)

  const layoutObserverRef = useRef<ResizeObserver | null>(null)
  const usePreciseLayoutRef = useRef(usePreciseLayout)
  usePreciseLayoutRef.current = usePreciseLayout

  /** 仅在与父级「精确高度」配合时使用；父级用外层滚动承载时（usePreciseLayout=false）勿启用，否则会与外层滚动冲突 */
  const isCompactHeight = usePreciseLayout && (viewportHeight <= 980 || panelClientHeight <= 760)

  function readPanelGapPx() {
    const panel = panelRootRef.current
    if (!panel || typeof window === 'undefined') return 0
    const styles = window.getComputedStyle(panel)
    const raw = styles.rowGap || styles.gap || '0'
    const n = Number.parseFloat(raw)
    return Number.isFinite(n) ? n : 0
  }

  function recomputePreciseLayout() {
    if (typeof window === 'undefined') return
    if (!usePreciseLayoutRef.current) {
      setPreciseTopCollapsedHeight(null)
      setPreciseTopExpandedHeight(null)
      if (panelRootRef.current) setPanelClientHeight(panelRootRef.current.clientHeight)
      return
    }
    const panel = panelRootRef.current
    const header = headerWrapRef.current
    const slotWrap = slotWrapRef.current
    if (!panel || !header || !slotWrap) return

    const panelH = panel.clientHeight
    setPanelClientHeight(panelH)
    const headerH = header.offsetHeight
    const slotH = slotWrap.offsetHeight
    const gap = readPanelGapPx()

    const expandedGaps = gap * 2
    const expandedTop = panelH - headerH - slotH - expandedGaps
    setPreciseTopExpandedHeight(`${Math.round(clamp(expandedTop, 112, 420))}px`)

    const collapsedPromptH = promptCollapsedRef.current?.offsetHeight ?? 0
    const collapsedGaps = gap * 3
    const collapsedTop = panelH - headerH - slotH - collapsedPromptH - collapsedGaps
    setPreciseTopCollapsedHeight(`${Math.round(clamp(collapsedTop, 96, 460))}px`)
  }

  const recomputeRef = useRef(recomputePreciseLayout)
  recomputeRef.current = recomputePreciseLayout

  function fitHeightByBreakpoints(v768: number, v900: number, v1080: number, v1400: number) {
    const vp = clamp(viewportHeight, 768, 1400)
    if (vp <= 900) {
      const ratio = (vp - 768) / (900 - 768)
      return `${Math.round(v768 + ratio * (v900 - v768))}px`
    }
    if (vp <= 1080) {
      const ratio = (vp - 900) / (1080 - 900)
      return `${Math.round(v900 + ratio * (v1080 - v900))}px`
    }
    const ratio = (vp - 1080) / (1400 - 1080)
    return `${Math.round(v1080 + ratio * (v1400 - v1080))}px`
  }

  /**
   * 外层滚动模式：高度不要随 1080+ 继续变大（否则顶部区域会越拉越高，出现不必要的滚动条）。
   * 这里把视口高度上限钉在 1080，对齐设计稿「1080 以上一屏铺满」的观感。
   */
  function fitHeightOuterScroll(v768: number, v900: number, v1080: number) {
    const vp = clamp(viewportHeight, 768, 1080)
    if (vp <= 900) {
      const ratio = (vp - 768) / (900 - 768)
      return `${Math.round(v768 + ratio * (v900 - v768))}px`
    }
    const ratio = (vp - 900) / (1080 - 900)
    return `${Math.round(v900 + ratio * (v1080 - v900))}px`
  }

  const promptHeightExpanded = fitHeightByBreakpoints(28, 36, 66, 105)
  const promptHeightCollapsed = fitHeightByBreakpoints(36, 46, 78, 120)

  const panelCssVars = useMemo<Record<string, string>>(() => {
    const outerScroll = !usePreciseLayout
    const promptExpanded = outerScroll
      ? fitHeightOuterScroll(28, 36, 66)
      : fitHeightByBreakpoints(28, 36, 66, 105)
    const promptCollapsed = outerScroll
      ? fitHeightOuterScroll(36, 46, 78)
      : fitHeightByBreakpoints(36, 46, 78, 120)
    const topCollapsed = outerScroll
      ? fitHeightOuterScroll(96, 148, 246)
      : fitHeightByBreakpoints(96, 148, 246, 320)
    // 1080+ 时展开态需要更高一些（约 374px）才能完全露出左侧素材区（避免被裁切）
    const topExpanded = outerScroll
      ? fitHeightOuterScroll(112, 168, 374)
      : fitHeightByBreakpoints(112, 168, 340, 500)
    const isLowViewport = viewportHeight <= 900
    // 低分辨率（高度较低）下的按钮高度微调：
    // - 场景/角色/道具行里的「导入」按钮（asset-card-inline）跟随 --storyboard-thumb-height：40px 更好点按
    // - “其他”四个入口按钮（.asset-card.small）跟随 --storyboard-card-small-height：46px 与设计稿一致
    const thumbHeight = isLowViewport ? '40px' : fitHeightByBreakpoints(32, 38, 46, 54)
    const cardSmallHeight = isLowViewport ? '46px' : fitHeightByBreakpoints(22, 28, 38, 46)

    return {
      // 外层滚动模式：避免低高度把区域压成几十像素，但不要强行放大到 1400px 的高度（会导致 1080+ 无故出现滚动条）
      '--storyboard-prompt-expanded-height': outerScroll
        ? maxPx(promptExpanded, '66px')
        : promptExpanded,
      '--storyboard-prompt-collapsed-height': outerScroll
        ? maxPx(promptCollapsed, '78px')
        : promptCollapsed,
      '--storyboard-top-collapsed-height':
        usePreciseLayout && preciseTopCollapsedHeight
          ? preciseTopCollapsedHeight
          : outerScroll
            ? maxPx(topCollapsed, isLowViewport ? '240px' : '246px')
            : topCollapsed,
      '--storyboard-top-expanded-height':
        usePreciseLayout && preciseTopExpandedHeight
          ? preciseTopExpandedHeight
          : outerScroll
            ? maxPx(topExpanded, isLowViewport ? '388px' : '374px')
            : topExpanded,
      '--storyboard-block-gap': fitHeightByBreakpoints(2, 3, 6, 10),
      '--storyboard-row-gap': fitHeightByBreakpoints(3, 5, 10, 14),
      '--storyboard-panel-padding-y': fitHeightByBreakpoints(2, 4, 6, 9),
      '--storyboard-panel-padding-x': fitHeightByBreakpoints(4, 6, 8, 12),
      '--storyboard-card-height': fitHeightByBreakpoints(24, 30, 42, 52),
      '--storyboard-card-small-height': cardSmallHeight,
      '--storyboard-thumb-height': thumbHeight,
      '--storyboard-thumb-img-height': fitHeightByBreakpoints(22, 26, 32, 38),
      '--storyboard-field-gap': fitHeightByBreakpoints(2, 4, 8, 14)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [usePreciseLayout, viewportHeight, preciseTopCollapsedHeight, preciseTopExpandedHeight])

  // 原 onMounted：初始化视口高度 + resize 监听 + ResizeObserver
  useEffect(() => {
    if (typeof window === 'undefined') return

    const updateViewportHeight = () => {
      setViewportHeight(window.innerHeight)
      recomputeRef.current()
    }
    updateViewportHeight()
    window.addEventListener('resize', updateViewportHeight)

    if (usePreciseLayoutRef.current && typeof ResizeObserver !== 'undefined') {
      layoutObserverRef.current = new ResizeObserver(() => {
        recomputeRef.current()
      })
      if (panelRootRef.current) layoutObserverRef.current.observe(panelRootRef.current)
      if (headerWrapRef.current) layoutObserverRef.current.observe(headerWrapRef.current)
      if (slotWrapRef.current) layoutObserverRef.current.observe(slotWrapRef.current)
      if (promptCollapsedRef.current) layoutObserverRef.current.observe(promptCollapsedRef.current)
    }
    nextTick(() => {
      recomputeRef.current()
    })

    return () => {
      window.removeEventListener('resize', updateViewportHeight)
      if (layoutObserverRef.current) {
        layoutObserverRef.current.disconnect()
        layoutObserverRef.current = null
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 原 watch(isSettingExpanded, immediate)：展开态切换后补观察折叠 prompt 容器并重算
  useEffect(() => {
    nextTick(() => {
      if (usePreciseLayoutRef.current && layoutObserverRef.current && promptCollapsedRef.current) {
        layoutObserverRef.current.observe(promptCollapsedRef.current)
      }
      recomputeRef.current()
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isSettingExpanded])

  // 原 watch(usePreciseLayout)：切换精确布局时重建观察器
  const firstPreciseRunRef = useRef(true)
  useEffect(() => {
    if (firstPreciseRunRef.current) {
      firstPreciseRunRef.current = false
      return
    }
    if (typeof window === 'undefined') return
    if (layoutObserverRef.current) {
      layoutObserverRef.current.disconnect()
      layoutObserverRef.current = null
    }
    setPreciseTopCollapsedHeight(null)
    setPreciseTopExpandedHeight(null)
    if (usePreciseLayout && typeof ResizeObserver !== 'undefined') {
      layoutObserverRef.current = new ResizeObserver(() => {
        recomputeRef.current()
      })
      nextTick(() => {
        if (panelRootRef.current) layoutObserverRef.current!.observe(panelRootRef.current)
        if (headerWrapRef.current) layoutObserverRef.current!.observe(headerWrapRef.current)
        if (slotWrapRef.current) layoutObserverRef.current!.observe(slotWrapRef.current)
        if (promptCollapsedRef.current) layoutObserverRef.current!.observe(promptCollapsedRef.current)
        recomputeRef.current()
      })
    } else {
      nextTick(() => recomputeRef.current())
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [usePreciseLayout])

  return {
    isCompactHeight,
    panelCssVars,
    promptHeightExpanded,
    promptHeightCollapsed
  }
}

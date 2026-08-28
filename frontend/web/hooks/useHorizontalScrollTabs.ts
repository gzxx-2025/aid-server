'use client'

import { useCallback,useEffect,useRef,useState } from 'react'

const SCROLL_EPS = 2

/** 横向滚动 tab 条：左右箭头显隐、按页翻动、选中项滚入视野 */
export function useHorizontalScrollTabs() {
  const scrollerRef = useRef<HTMLElement | null>(null)
  const [showLeftArrow, setShowLeftArrow] = useState(false)
  const [showRightArrow, setShowRightArrow] = useState(false)

  const updateArrows = useCallback(() => {
    const el = scrollerRef.current
    if (!el) {
      setShowLeftArrow(false)
      setShowRightArrow(false)
      return
    }
    const { scrollLeft, clientWidth, scrollWidth } = el
    const overflow = scrollWidth > clientWidth + SCROLL_EPS
    setShowLeftArrow(overflow && scrollLeft > SCROLL_EPS)
    setShowRightArrow(overflow && scrollLeft + clientWidth < scrollWidth - SCROLL_EPS)
  }, [])

  const onScroll = updateArrows

  /** 按可视区域宽度翻页；不足一页则滚到尽头 */
  const scrollByPage = useCallback((direction: -1 | 1) => {
    const el = scrollerRef.current
    if (!el) return
    const maxScroll = Math.max(0, el.scrollWidth - el.clientWidth)
    const step = el.clientWidth
    const nextLeft =
      direction > 0
        ? Math.min(el.scrollLeft + step, maxScroll)
        : Math.max(el.scrollLeft - step, 0)
    el.scrollTo({ left: nextLeft, behavior: 'smooth' })
  }, [])

  const scrollItemIntoView = useCallback(
    (selector: string, behavior: ScrollBehavior = 'smooth', padding = 8) => {
      const el = scrollerRef.current
      if (!el) return
      const item = el.querySelector(selector) as HTMLElement | null
      if (!item) return
      const tabLeft = item.offsetLeft
      const tabRight = tabLeft + item.offsetWidth
      const viewLeft = el.scrollLeft
      const viewRight = viewLeft + el.clientWidth
      if (tabLeft < viewLeft + padding) {
        el.scrollTo({ left: Math.max(0, tabLeft - padding), behavior })
      } else if (tabRight > viewRight - padding) {
        el.scrollTo({ left: tabRight - el.clientWidth + padding, behavior })
      }
      requestAnimationFrame(updateArrows)
    },
    [updateArrows]
  )

  const refresh = useCallback(() => {
    requestAnimationFrame(updateArrows)
  }, [updateArrows])

  useEffect(() => {
    let resizeObserver: ResizeObserver | null = null

    const bindResizeObserver = () => {
      resizeObserver?.disconnect()
      const el = scrollerRef.current
      if (!el || typeof ResizeObserver === 'undefined') return
      resizeObserver = new ResizeObserver(() => updateArrows())
      resizeObserver.observe(el)
      const track = el.firstElementChild
      if (track) resizeObserver.observe(track)
    }

    const raf = requestAnimationFrame(() => {
      updateArrows()
      bindResizeObserver()
    })
    window.addEventListener('resize', updateArrows)

    return () => {
      cancelAnimationFrame(raf)
      resizeObserver?.disconnect()
      window.removeEventListener('resize', updateArrows)
    }
  }, [updateArrows])

  return {
    scrollerRef,
    showLeftArrow,
    showRightArrow,
    onScroll,
    scrollByPage,
    scrollItemIntoView,
    refresh,
    updateArrows
  }
}

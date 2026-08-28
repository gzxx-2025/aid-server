'use client'

import { useHorizontalScrollTabs } from '@/hooks/useHorizontalScrollTabs'
import { LeftOutlined,RightOutlined } from '@ant-design/icons'
import { forwardRef,useImperativeHandle,type ReactNode } from 'react'
import './HorizontalScrollTabBar.css'

export interface HorizontalScrollTabBarProps {
  rootClass?: string
  trackClass?: string
  children?: ReactNode
  /** 原 suffix 插槽：右侧固定区（如「更多」按钮） */
  suffix?: ReactNode
}

export interface HorizontalScrollTabBarHandle {
  scrollItemIntoView: (selector: string, behavior?: ScrollBehavior, padding?: number) => void
  refresh: () => void
  updateArrows: () => void
}

/** 横向滚动 tab 条：溢出时两侧出翻页箭头 */
export const HorizontalScrollTabBar = forwardRef<
  HorizontalScrollTabBarHandle,
  HorizontalScrollTabBarProps
>(function HorizontalScrollTabBar({ rootClass = '', trackClass = '', children, suffix }, ref) {
  const {
    scrollerRef,
    showLeftArrow,
    showRightArrow,
    onScroll,
    scrollByPage,
    scrollItemIntoView,
    refresh,
    updateArrows
  } = useHorizontalScrollTabs()

  useImperativeHandle(ref, () => ({ scrollItemIntoView, refresh, updateArrows }), [
    scrollItemIntoView,
    refresh,
    updateArrows
  ])

  return (
    <div className={`h-scroll-tab-bar${rootClass ? ` ${rootClass}` : ''}`}>
      <button
        type="button"
        className="h-scroll-tab-bar__arrow h-scroll-tab-bar__arrow--left"
        aria-label="向左滚动"
        style={showLeftArrow ? undefined : { display: 'none' }}
        onClick={() => scrollByPage(-1)}
      >
        <LeftOutlined />
      </button>

      <div
        ref={scrollerRef as React.RefObject<HTMLDivElement>}
        className="h-scroll-tab-bar__viewport"
        onScroll={onScroll}
      >
        <div className={`h-scroll-tab-bar__track${trackClass ? ` ${trackClass}` : ''}`}>
          {children}
        </div>
      </div>

      <button
        type="button"
        className="h-scroll-tab-bar__arrow h-scroll-tab-bar__arrow--right"
        aria-label="向右滚动"
        style={showRightArrow ? undefined : { display: 'none' }}
        onClick={() => scrollByPage(1)}
      >
        <RightOutlined />
      </button>

      {suffix ? <div className="h-scroll-tab-bar__suffix">{suffix}</div> : null}
    </div>
  )
})

export default HorizontalScrollTabBar

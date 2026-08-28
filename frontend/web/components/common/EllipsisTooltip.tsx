'use client'

import { Tooltip } from 'antd'
import type { ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'

// 创作流存在 12000 层级的嵌套弹窗，显式声明 Tooltip 层级可避免 antd 继承层级告警。
const ELLIPSIS_TOOLTIP_Z_INDEX = 12050

export interface EllipsisTooltipProps {
  title: string
  placement?: 'top' | 'bottom' | 'left' | 'right'
  children?: ReactNode
}

/** 文本溢出（省略号）时才显示 Tooltip */
export function EllipsisTooltip({ title, placement = 'top', children }: EllipsisTooltipProps) {
  const textRef = useRef<HTMLSpanElement | null>(null)
  const [showTooltip, setShowTooltip] = useState(false)

  function checkOverflow() {
    const el = textRef.current
    if (!el) {
      setShowTooltip(false)
      return
    }
    setShowTooltip(el.scrollWidth > el.clientWidth + 1)
  }

  useEffect(() => {
    checkOverflow()
    if (typeof ResizeObserver === 'undefined' || !textRef.current) return
    const resizeObserver = new ResizeObserver(checkOverflow)
    resizeObserver.observe(textRef.current)
    return () => resizeObserver.disconnect()
     
  }, [])

  useEffect(() => {
    const raf = requestAnimationFrame(checkOverflow)
    return () => cancelAnimationFrame(raf)
     
  }, [title])

  return (
    <Tooltip
      title={showTooltip ? title : undefined}
      placement={placement}
      zIndex={ELLIPSIS_TOOLTIP_Z_INDEX}
    >
      <span
        ref={textRef}
        className="ellipsis-tooltip-text inline-block max-w-full overflow-hidden text-ellipsis whitespace-nowrap align-bottom"
      >
        {children ?? title}
      </span>
    </Tooltip>
  )
}

'use client'

import { isValidElement, type ReactElement, type ReactNode } from 'react'
import { Tooltip, type TooltipProps } from 'antd'

export type StudioCanvasTooltipProps = TooltipProps

const popupContainer = () => document.body

function wrapTrigger(children: ReactNode): ReactElement {
  if (
    isValidElement(children) &&
    typeof children.type === 'string' &&
    children.type === 'span'
  ) {
    return children as ReactElement
  }
  return <span className="studio-canvas-tooltip-trigger">{children}</span>
}

/** Canvas chrome tooltip: always portals to body, works on disabled buttons. */
export function StudioCanvasTooltip({
  children,
  classNames,
  rootClassName,
  mouseEnterDelay = 0.12,
  mouseLeaveDelay = 0.08,
  arrow = false,
  placement = 'bottom',
  getPopupContainer = popupContainer,
  ...props
}: StudioCanvasTooltipProps) {
  const mergedRootClassName = [
    'studio-canvas-tooltip',
    rootClassName
  ].filter(Boolean).join(' ')

  return (
    <Tooltip
      {...props}
      mouseEnterDelay={mouseEnterDelay}
      mouseLeaveDelay={mouseLeaveDelay}
      arrow={arrow}
      placement={placement}
      getPopupContainer={getPopupContainer}
      classNames={classNames}
      rootClassName={mergedRootClassName}
    >
      {wrapTrigger(children)}
    </Tooltip>
  )
}

export default StudioCanvasTooltip

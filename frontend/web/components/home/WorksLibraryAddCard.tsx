'use client'

import { PlusOutlined } from '@ant-design/icons'
import type { MouseEvent as ReactMouseEvent,ReactNode } from 'react'
export interface WorksLibraryAddCardProps {
  /** 主文案，如「新建作品」「新增角色」 */
  label: string
  /** 副文案，显示在主文案下方 */
  hint?: string
  disabled?: boolean
  ariaLabel?: string
  /** 原 icon 具名插槽：自定义图标 */
  icon?: ReactNode
  onClick?: (event: ReactMouseEvent<HTMLButtonElement>) => void
}

export function WorksLibraryAddCard({
  label,
  hint,
  disabled = false,
  ariaLabel,
  icon,
  onClick
}: WorksLibraryAddCardProps) {
  function handleClick(e: ReactMouseEvent<HTMLButtonElement>) {
    if (disabled) return
    onClick?.(e)
  }

  return (
    <button
      type="button"
      className="works-lib-add-card"
      disabled={disabled}
      aria-label={ariaLabel || label}
      onClick={handleClick}
    >
      {/* 撑开与普通卡片封面同高（不参与展示）；封面绝对定位铺满整卡 */}
      <span className="works-lib-add-card__sizer" aria-hidden="true" />
      <div className="works-lib-add-card__cover">
        <div className="works-lib-add-card__content">
          <span className="works-lib-add-card__icon-wrap">
            {icon ?? <PlusOutlined className="works-lib-add-card__icon" />}
          </span>
          <span className="works-lib-add-card__label">{label}</span>
          {hint ? <span className="works-lib-add-card__hint">{hint}</span> : null}
        </div>
      </div>
      {/* 与普通卡片底部信息区等高，保证网格总高度一致 */}
      <div className="works-lib-add-card__body" aria-hidden="true" />
    </button>
  )
}

export default WorksLibraryAddCard

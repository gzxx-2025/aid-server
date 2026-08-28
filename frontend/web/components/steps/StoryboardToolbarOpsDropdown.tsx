'use client'

import type { ComponentType } from 'react'
import { Button, Dropdown, Tooltip } from 'antd'
import { DownOutlined } from '@ant-design/icons'

export interface StoryboardOpsMenuItem {
  key: string
  label: string
  icon?: ComponentType
  danger?: boolean
  disabled?: boolean
  disabledTooltip?: string
}

export interface StoryboardToolbarOpsDropdownProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  items: StoryboardOpsMenuItem[]
  loading?: boolean
  disabled?: boolean
  ariaLabel?: string
  onSelect: (key: string) => void
  /** 原 useAttrs 透传（class/style 除外）：额外属性原样落到触发按钮上 */
  [key: string]: unknown
}

export function StoryboardToolbarOpsDropdown({
  open,
  onOpenChange,
  items,
  loading = false,
  disabled = false,
  ariaLabel = '批量操作',
  onSelect,
  className: _className,
  style: _style,
  ...buttonAttrs
}: StoryboardToolbarOpsDropdownProps) {
  function onItemClick(item: StoryboardOpsMenuItem) {
    if (item.disabled) return
    onOpenChange(false)
    onSelect(item.key)
  }

  const overlay = (
    <div className="storyboard-ops-panel" role="menu" aria-label={ariaLabel}>
      <div className="storyboard-ops-panel__list">
        {items.map((item) => {
          const ItemIcon = item.icon
          if (item.disabled && item.disabledTooltip) {
            return (
              <Tooltip
                key={item.key}
                title={item.disabledTooltip}
                placement="left"
                mouseEnterDelay={0.2}
              >
                <span className="storyboard-ops-panel__item-wrap">
                  <button
                    type="button"
                    className={`storyboard-ops-panel__item storyboard-ops-panel__item--disabled${item.danger ? ' storyboard-ops-panel__item--danger' : ''}`}
                    disabled
                    role="menuitem"
                  >
                    {ItemIcon ? <ItemIcon /> : null}
                    <span>{item.label}</span>
                  </button>
                </span>
              </Tooltip>
            )
          }
          return (
            <button
              key={item.key}
              type="button"
              className={`storyboard-ops-panel__item${item.danger ? ' storyboard-ops-panel__item--danger' : ''}`}
              role="menuitem"
              disabled={item.disabled}
              onClick={() => onItemClick(item)}
            >
              {ItemIcon ? <ItemIcon /> : null}
              <span>{item.label}</span>
            </button>
          )
        })}
      </div>
    </div>
  )

  return (
    <Dropdown
      open={open}
      onOpenChange={(o) => onOpenChange(o)}
      trigger={['click']}
      placement="bottomRight"
      classNames={{ root: 'storyboard-ops-dropdown-overlay' }}
      popupRender={() => overlay}
    >
      <Button
        type="primary"
        className="storyboard-action-btn storyboard-toolbar-cyan-btn storyboard-toolbar-ops-btn"
        loading={loading}
        disabled={disabled}
        {...(buttonAttrs as Record<string, unknown>)}
      >
        批量操作
        <DownOutlined
          className={`storyboard-toolbar-ops-btn__arrow${open ? ' storyboard-toolbar-ops-btn__arrow--open' : ''}`}
        />
      </Button>
    </Dropdown>
  )
}

export default StoryboardToolbarOpsDropdown

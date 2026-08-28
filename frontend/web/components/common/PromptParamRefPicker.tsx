'use client'

import { CheckCircleFilled } from '@ant-design/icons'
import type { CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import type { PromptParamOption } from '~/utils/storyboardPromptParamRef'
import './PromptParamRefPicker.css'

export interface PromptParamRefPickerProps {
  open: boolean
  options: PromptParamOption[]
  selectedKey?: string
  anchorRect?: DOMRect | null
  onClose: () => void
  onSelect: (option: PromptParamOption) => void
}

/** 富文本 @参数引用 选择浮层（body portal，锚点定位） */
export function PromptParamRefPicker({
  open,
  options,
  selectedKey,
  anchorRect = null,
  onClose,
  onSelect
}: PromptParamRefPickerProps) {
  if (!open || typeof document === 'undefined') return null

  const panelStyle: CSSProperties = (() => {
    const r = anchorRect
    if (!r) return { top: '50%', left: '50%', transform: 'translate(-50%, -50%)' }
    const top = Math.min(r.bottom + 6, window.innerHeight - 280)
    const left = Math.min(Math.max(8, r.left), window.innerWidth - 220)
    return { top: `${top}px`, left: `${left}px` }
  })()

  return createPortal(
    <>
      <div className="prompt-param-ref-picker-mask" onClick={onClose} />
      <div
        className="prompt-param-ref-picker"
        style={panelStyle}
        role="listbox"
        onClick={(e) => e.stopPropagation()}
      >
        {options.map((opt) => (
          <button
            key={opt.key}
            type="button"
            className={`prompt-param-ref-picker__item${opt.key === selectedKey ? ' is-selected' : ''}`}
            onClick={() => onSelect(opt)}
          >
            <span className="prompt-param-ref-picker__name">{opt.value}</span>
            {opt.key === selectedKey ? (
              <CheckCircleFilled className="prompt-param-ref-picker__check" />
            ) : null}
          </button>
        ))}
        {!options.length ? <div className="prompt-param-ref-picker__empty">暂无可选项</div> : null}
      </div>
    </>,
    document.body
  )
}

export default PromptParamRefPicker

'use client'

import { CheckCircleFilled } from '@ant-design/icons'
import type { CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import type { PromptAssetItem } from '~/utils/storyboardPromptAssetRef'
import './PromptAssetRefPicker.css'

export interface PromptAssetRefPickerProps {
  open: boolean
  assets: PromptAssetItem[]
  selectedAssetId?: string
  anchorRect?: DOMRect | null
  onClose: () => void
  onSelect: (item: PromptAssetItem) => void
}

/** 富文本 @资产引用 选择浮层（body portal，锚点定位） */
export function PromptAssetRefPicker({
  open,
  assets,
  selectedAssetId,
  anchorRect = null,
  onClose,
  onSelect
}: PromptAssetRefPickerProps) {
  if (!open || typeof document === 'undefined') return null

  const panelStyle: CSSProperties = (() => {
    const r = anchorRect
    if (!r) return { top: '50%', left: '50%', transform: 'translate(-50%, -50%)' }
    const top = Math.min(r.bottom + 6, window.innerHeight - 280)
    const left = Math.min(Math.max(8, r.left), window.innerWidth - 280)
    return { top: `${top}px`, left: `${left}px` }
  })()

  const assetsAreAudio = assets.some((a) => a.assetType === 'audio')

  return createPortal(
    <>
      <div className="prompt-asset-ref-picker-mask" onClick={onClose} />
      <div
        className="prompt-asset-ref-picker"
        style={panelStyle}
        role="listbox"
        onClick={(e) => e.stopPropagation()}
      >
        {assets.map((item) => (
          <button
            key={`${item.assetType}:${item.assetId}:${item.imageIndex}`}
            type="button"
            className={`prompt-asset-ref-picker__item${
              item.assetId === selectedAssetId ? ' is-selected' : ''
            }`}
            onClick={() => onSelect(item)}
          >
            {item.assetType === 'audio' ? (
              <span
                className="prompt-asset-ref-picker__thumb prompt-asset-ref-picker__thumb--audio"
                aria-hidden="true"
              />
            ) : item.url ? (
              <img className="prompt-asset-ref-picker__thumb" src={item.url} alt={item.name} />
            ) : (
              <span className="prompt-asset-ref-picker__thumb prompt-asset-ref-picker__thumb--empty" />
            )}
            <span className="prompt-asset-ref-picker__name">{item.label}</span>
            {item.assetId === selectedAssetId ? (
              <CheckCircleFilled className="prompt-asset-ref-picker__check" />
            ) : null}
          </button>
        ))}
        {!assets.length ? (
          <div className="prompt-asset-ref-picker__empty">
            {assetsAreAudio ? '暂无已导入音频' : '暂无已导入图片'}
          </div>
        ) : null}
      </div>
    </>,
    document.body
  )
}

export default PromptAssetRefPicker

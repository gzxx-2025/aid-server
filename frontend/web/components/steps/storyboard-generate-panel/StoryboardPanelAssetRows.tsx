'use client'

import { useRef } from 'react'
import { Switch } from 'antd'
import { CloseOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { assetUrl } from '~/utils/assetUrl'
import dialogAddIconRaw from '~/assets/img/icon/dialog-add.svg'
import type { PanelReferenceImage, PanelSelectModalType, StoryboardGeneratePanelMode } from './types'

const dialogAddIcon = assetUrl(dialogAddIconRaw)

interface AssetRowsProps {
  mode: StoryboardGeneratePanelMode
  showStoryboardVideoAssets: boolean
  nineGridEnabled: boolean
  referenceImage: PanelReferenceImage
  sceneImages: any[]
  characterImages: any[]
  propImages: any[]
  otherImages: any[]
  onNineGridEnabledChange?: (value: boolean) => void
  onStoryboardVideoReferenceClick: () => void
  onClearReference?: () => void
  onOpenSelectModal?: (type: PanelSelectModalType) => void
  onPreviewAssetImage?: (img: any) => void
  onRemoveOtherImage?: (index: number) => void
}

/** 左侧素材面板：分镜视频单参考图 / 场景·角色·道具·其他 四行（原 .asset-panel 模板段） */
export function StoryboardPanelAssetRows({
  mode,
  showStoryboardVideoAssets,
  nineGridEnabled,
  referenceImage,
  sceneImages,
  characterImages,
  propImages,
  otherImages,
  onNineGridEnabledChange,
  onStoryboardVideoReferenceClick,
  onClearReference,
  onOpenSelectModal,
  onPreviewAssetImage,
  onRemoveOtherImage
}: AssetRowsProps) {
  const sceneRowRef = useRef<HTMLDivElement | null>(null)
  const characterRowRef = useRef<HTMLDivElement | null>(null)
  const propRowRef = useRef<HTMLDivElement | null>(null)

  /** 横向滚动缩略图：必须在子组件内使用本地 ref（父组件拿不到这里的 DOM，原先 emit 后父级 scroll 不生效） */
  function scrollRow(row: 'scene' | 'character' | 'prop', direction: number) {
    const refMap = {
      scene: sceneRowRef,
      character: characterRowRef,
      prop: propRowRef
    }
    const el = refMap[row].current
    if (!el) return
    const step = 200
    try {
      el.scrollBy({ left: step * direction, behavior: 'smooth' })
    } catch {
      el.scrollLeft += step * direction
    }
  }

  if (mode === 'storyboardVideo' && showStoryboardVideoAssets) {
    const hasReference = !!(referenceImage?.url || referenceImage?.thumbnail)
    return (
      <div className="asset-panel">
        <div className="asset-row asset-row-switch">
          <div className="asset-label">
            <span className="asset-dot" />
            九宫格多机位
          </div>
          <Switch
            checked={nineGridEnabled}
            disabled={!referenceImage}
            size="small"
            onChange={(checked) => onNineGridEnabledChange?.(checked)}
          />
        </div>
        <div className="asset-row">
          <div className="asset-reference-single">
            <div className="asset-reference-item" onClick={onStoryboardVideoReferenceClick}>
              {hasReference ? (
                <ShimmerImage
                  src={String(referenceImage?.url || referenceImage?.thumbnail || '')}
                  imgClass="asset-reference-img"
                  objectFit="cover"
                  revealDirection="fade"
                />
              ) : (
                <div className="asset-reference-placeholder">
                  <img src={dialogAddIcon} alt="" />
                  <span>导入参考图</span>
                </div>
              )}
              {hasReference ? (
                <div
                  className="asset-reference-remove"
                  onClick={(e) => {
                    e.stopPropagation()
                    onClearReference?.()
                  }}
                >
                  <CloseOutlined />
                </div>
              ) : null}
            </div>
          </div>
        </div>
      </div>
    )
  }

  function renderScrollRow(
    label: string,
    images: any[],
    rowKey: 'scene' | 'character' | 'prop',
    rowRef: React.RefObject<HTMLDivElement | null>,
    importText: string
  ) {
    return (
      <div className="asset-row">
        <div className="asset-label">{label}</div>
        <div className="asset-horizontal-wrap">
          {images.length > 1 ? (
            <button
              type="button"
              className="asset-arrow asset-arrow-left"
              onClick={() => scrollRow(rowKey, -1)}
            >
              <LeftOutlined />
            </button>
          ) : null}
          <div ref={rowRef} className="asset-horizontal">
            {images.map((img, idx) => (
              <div
                key={img.id || idx}
                className="asset-thumb-item"
                onClick={() => onPreviewAssetImage?.(img)}
              >
                {img.url || img.thumbnail ? (
                  <ShimmerImage
                    src={img.url || img.thumbnail}
                    imgClass="asset-thumb-img"
                    objectFit="cover"
                    revealDirection="fade"
                  />
                ) : null}
                <span className="asset-thumb-name">{img.title || img.name || label}</span>
              </div>
            ))}
            <button
              type="button"
              className="asset-card asset-card-inline"
              onClick={() => onOpenSelectModal?.(rowKey)}
            >
              <div className="asset-icon">
                <img src={dialogAddIcon} alt="" />
              </div>
              <span>{importText}</span>
            </button>
          </div>
          {images.length > 1 ? (
            <button
              type="button"
              className="asset-arrow asset-arrow-right"
              onClick={() => scrollRow(rowKey, 1)}
            >
              <RightOutlined />
            </button>
          ) : null}
        </div>
      </div>
    )
  }

  return (
    <div className="asset-panel">
      {renderScrollRow('场景', sceneImages, 'scene', sceneRowRef, '导入场景')}
      {renderScrollRow('角色', characterImages, 'character', characterRowRef, '导入角色')}
      {renderScrollRow('道具', propImages, 'prop', propRowRef, '导入道具')}
      {/* 其他：四宫格 + 与场景一致的缩略图横滑；引用以 @名称 展示在下方描述文本域 */}
      <div className="asset-row asset-row-other">
        <div className="asset-label">其他</div>
        <div className="asset-other-content">
          <div className="asset-grid asset-grid-other-top">
            <button type="button" className="asset-card small" onClick={() => onOpenSelectModal?.('pose')}>
              <div className="asset-icon">
                <img src={dialogAddIcon} alt="" />
              </div>
              <span>姿态图</span>
            </button>
            <button
              type="button"
              className="asset-card small"
              onClick={() => onOpenSelectModal?.('expression')}
            >
              <div className="asset-icon">
                <img src={dialogAddIcon} alt="" />
              </div>
              <span>表情图</span>
            </button>
            <button type="button" className="asset-card small" onClick={() => onOpenSelectModal?.('effect')}>
              <div className="asset-icon">
                <img src={dialogAddIcon} alt="" />
              </div>
              <span>特效图</span>
            </button>
            <button type="button" className="asset-card small" onClick={() => onOpenSelectModal?.('draft')}>
              <div className="asset-icon">
                <img src={dialogAddIcon} alt="" />
              </div>
              <span>手绘稿</span>
            </button>
          </div>
          {/* 仅有「姿态/表情/特效/手绘」四类入口；导入缩略图后才显示横滑与「导入其他」 */}
          {otherImages.length > 0 ? (
            <div className="asset-horizontal-wrap">
              <div className="asset-horizontal">
                {otherImages.map((img, idx) => (
                  <div
                    key={img.id || idx}
                    className="asset-thumb-item asset-thumb-item--removable"
                    onClick={() => onPreviewAssetImage?.(img)}
                  >
                    {img.url || img.thumbnail ? (
                      <ShimmerImage
                        src={img.url || img.thumbnail}
                        imgClass="asset-thumb-img"
                        objectFit="cover"
                        revealDirection="fade"
                      />
                    ) : null}
                    <span className="asset-thumb-name">{img.title || img.name || '参考'}</span>
                    <button
                      type="button"
                      className="asset-thumb-remove"
                      title="移除"
                      onClick={(e) => {
                        e.stopPropagation()
                        onRemoveOtherImage?.(idx)
                      }}
                    >
                      ×
                    </button>
                  </div>
                ))}
                <button
                  type="button"
                  className="asset-card asset-card-inline"
                  onClick={() => onOpenSelectModal?.('other')}
                >
                  <div className="asset-icon">+</div>
                  <span>导入其他</span>
                </button>
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  )
}

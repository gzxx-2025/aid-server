import { LeftOutlined,RightOutlined } from '@ant-design/icons'
import { Modal } from 'antd'
import { useState } from 'react'
import ImagePreviewViewer from '~/components/common/ImagePreviewViewer'
export interface ImageGalleryPreviewItem {
  url: string
  title?: string
}

function GalleryPreviewContent({
  list,
  initialIndex
}: {
  list: Array<{ url: string; title: string }>
  initialIndex: number
}) {
  const [index, setIndex] = useState(() =>
    Math.min(Math.max(initialIndex, 0), list.length - 1)
  )
  const current = list[index]!
  const hasMulti = list.length > 1

  const goPrev = () => setIndex((i) => (i - 1 + list.length) % list.length)
  const goNext = () => setIndex((i) => (i + 1) % list.length)

  return (
    <div className="image-gallery-preview-modal">
      <div className="image-gallery-preview-modal__stage-wrap">
        {hasMulti ? (
          <button
            className="image-gallery-preview-modal__nav image-gallery-preview-modal__nav--prev"
            type="button"
            aria-label="上一张"
            onClick={goPrev}
          >
            <LeftOutlined />
          </button>
        ) : null}
        {/* 原 viewerKey 随 index 递增强制重建 viewer（重置缩放/位移）；React 用 key=index 等价 */}
        <ImagePreviewViewer
          key={index}
          url={current.url}
          alt={current.title}
          headerTitle={current.title}
          fillStage
          stageFitRatio={1}
        />
        {hasMulti ? (
          <button
            className="image-gallery-preview-modal__nav image-gallery-preview-modal__nav--next"
            type="button"
            aria-label="下一张"
            onClick={goNext}
          >
            <RightOutlined />
          </button>
        ) : null}
      </div>
      {hasMulti ? (
        <div className="image-gallery-preview-modal__counter">
          {index + 1} / {list.length}
        </div>
      ) : null}
    </div>
  )
}

/** 单张或多张参考图预览；多张时支持左右切换；右上角关闭 */
export function openImageGalleryPreviewModal(
  images: ImageGalleryPreviewItem[],
  initialIndex = 0
) {
  const list = images
    .map((item) => ({
      url: String(item.url || '').trim(),
      title: String(item.title || '').trim() || '参考图'
    }))
    .filter((item) => item.url)
  if (!list.length) return

  Modal.info({
    icon: null,
    width: '100%',
    centered: false,
    closable: true,
    mask: { closable: true },
    footer: null,
    style: { top: 0, paddingBottom: 0, margin: 0 },
    wrapClassName: 'image-gallery-preview-modal-wrap',
    content: <GalleryPreviewContent list={list} initialIndex={initialIndex} />
  })
}

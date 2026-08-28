'use client'

import { ShimmerImage } from '@/components/common/ShimmerImage'
import { ZoomInOutlined } from '@ant-design/icons'
import { openImagePreviewModal } from '~/utils/openImagePreviewModal'
import './PreviewableImageThumb.css'

export interface PreviewableImageThumbProps {
  src?: string
  alt?: string
  title?: string
  objectFit?: 'cover' | 'contain' | 'fill' | 'none' | 'scale-down'
}

/** 可点击放大预览的图片缩略图（hover 出放大镜遮罩） */
export function PreviewableImageThumb({
  src = '',
  alt = '',
  title = '',
  objectFit = 'contain'
}: PreviewableImageThumbProps) {
  const resolvedSrc = String(src || '').trim()

  function handlePreview(event: React.MouseEvent) {
    event.stopPropagation()
    if (!resolvedSrc) return
    openImagePreviewModal({
      url: resolvedSrc,
      title: title || alt || '预览'
    })
  }

  return (
    <div
      className={`previewable-image-thumb${resolvedSrc ? '' : ' previewable-image-thumb--disabled'}`}
      onClick={handlePreview}
    >
      {resolvedSrc ? (
        <ShimmerImage
          src={resolvedSrc}
          alt={alt}
          imgClass="previewable-image-thumb__img"
          wrapperClass="previewable-image-thumb__shimmer"
          objectFit={objectFit}
          revealDirection="fade"
        />
      ) : null}
      {resolvedSrc ? (
        <div className="previewable-image-thumb__overlay" aria-hidden="true">
          <ZoomInOutlined className="previewable-image-thumb__zoom" />
        </div>
      ) : null}
    </div>
  )
}

export default PreviewableImageThumb

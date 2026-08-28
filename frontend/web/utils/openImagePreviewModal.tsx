import ImagePreviewViewer from '@/components/common/ImagePreviewViewer'
import { Modal } from 'antd'

export interface OpenImagePreviewModalOptions {
  url: string
  title?: string
  width?: string | number
}

/** 全屏一屏预览，支持放大、缩小、旋转、拖拽、滚轮缩放；右上角关闭 */
export function openImagePreviewModal(options: OpenImagePreviewModalOptions) {
  const url = String(options.url || '').trim()
  if (!url) return
  const title = options.title?.trim() || '预览'
  Modal.info({
    icon: null,
    width: options.width ?? '100%',
    centered: false,
    closable: true,
    mask: { closable: true },
    footer: null,
    // 高于创作流 / 发布弹窗，避免嵌套 Modal 时预览被挡住
    zIndex: 1100,
    style: { top: 0, paddingBottom: 0, margin: 0 },
    wrapClassName: 'image-preview-modal-wrap',
    content: (
      <div className="image-preview-modal-shell">
        <ImagePreviewViewer url={url} alt={title} headerTitle={title} fillStage stageFitRatio={1} />
      </div>
    )
  })
}

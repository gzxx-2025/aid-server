import { Modal } from 'antd'

export interface OpenVideoPreviewModalOptions {
  url: string
  title?: string
  width?: string | number
}

/** 与图片预览弹窗同全屏壳层；右上角关闭，视频等比适配且高度不超出 */
export function openVideoPreviewModal(options: OpenVideoPreviewModalOptions) {
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
    style: { top: 0, paddingBottom: 0, margin: 0 },
    wrapClassName: 'video-preview-modal-wrap',
    content: (
      <div className="video-preview-modal-shell">
        <div className="video-preview-modal-header">
          <div className="video-preview-modal-header__title" title={title}>
            {title}
          </div>
          <div className="video-preview-modal-header__trail" aria-hidden="true" />
        </div>
        <div className="video-preview-modal-stage">
          <video
            className="video-preview-modal__video"
            src={url}
            controls
            playsInline
            preload="metadata"
            controlsList="nodownload"
          />
        </div>
      </div>
    )
  })
}

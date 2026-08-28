import './ModalTitleWatermark.css'

export interface ModalTitleWatermarkProps {
  /** 弹窗主标题（由父组件传入） */
  title: string
  /** 背景大字水印，默认 CHOICE */
  watermark?: string
}

/** 弹窗标题：居中主标题 + 背景渐变大字水印 */
export function ModalTitleWatermark({ title, watermark = 'CHOICE' }: ModalTitleWatermarkProps) {
  return (
    <div className="modal-title-watermark">
      <span className="modal-title-watermark__bg" aria-hidden="true">
        {watermark}
      </span>
      <span className="modal-title-watermark__text">{title}</span>
    </div>
  )
}

export default ModalTitleWatermark

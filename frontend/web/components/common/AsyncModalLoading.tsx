'use client'

import { LoadingOutlined } from '@ant-design/icons'

/** 异步弹窗 chunk 加载期间的全屏 loading 遮罩 */
export function AsyncModalLoading() {
  return (
    <div
      className="async-modal-loading fixed inset-0 z-[1100] grid place-items-center"
      role="dialog"
      aria-modal="true"
      aria-label="正在打开编辑器"
    >
      <div className="async-modal-loading__backdrop absolute inset-0 bg-[rgb(0_0_0/45%)]" />
      <div
        className="async-modal-loading__panel relative flex min-w-[184px] min-h-[88px] items-center justify-center gap-[10px] px-[24px] py-[20px] rounded-[8px] border border-solid border-[rgba(74,231,253,0.28)] bg-[#191a1d] shadow-[0_12px_36px_rgb(0_0_0/45%)] text-[#e6edf3] text-[14px]"
        aria-live="polite"
        aria-busy="true"
      >
        <LoadingOutlined spin className="async-modal-loading__icon text-[#00abd8] text-[20px]" />
        <span>正在打开编辑器...</span>
      </div>
    </div>
  )
}

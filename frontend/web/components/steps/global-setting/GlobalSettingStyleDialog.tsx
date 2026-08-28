'use client'

import { Modal } from 'antd'
import type { ReactNode } from 'react'

interface GlobalSettingStyleDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  children: ReactNode
}

/** 创建页、项目配置页与画布共用的响应式风格选择弹窗。 */
export function GlobalSettingStyleDialog({
  open,
  onOpenChange,
  children
}: GlobalSettingStyleDialogProps) {
  return (
    <Modal
      open={open}
      title="更多风格"
      width={840}
      footer={null}
      centered
      destroyOnHidden
      zIndex={12000}
      className="global-setting global-setting-style-modal"
      wrapClassName="create-flow-modal global-setting-style-modal-wrap"
      onCancel={() => onOpenChange(false)}
    >
      {children}
    </Modal>
  )
}

export default GlobalSettingStyleDialog

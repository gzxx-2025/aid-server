'use client'

import type { ModalFuncProps } from 'antd'
import { Modal } from 'antd'
import AppConfirmIcon,{ type AppConfirmIconVariant } from '~/components/atoms/AppConfirmIcon'
export type AppConfirmOptions = ModalFuncProps & {
  confirmVariant?: AppConfirmIconVariant
}

function mergeWrapClassName(wrapClassName?: string): string {
  const base = 'app-confirm-wrap'
  if (!wrapClassName) return base
  return wrapClassName.includes(base) ? wrapClassName : `${wrapClassName} ${base}`
}

/** 根据弹窗配置推断图标类型（不使用默认红色感叹号） */
export function inferAppConfirmVariant(config: ModalFuncProps): AppConfirmIconVariant {
  if (config.okType === 'danger') return 'danger'
  const title = String(config.title ?? '')
  if (/删除|移除|清空|危险/.test(title)) return 'danger'
  if (/覆盖|警告|注意|提示/.test(title)) return 'warning'
  return 'info'
}

export function createAppConfirmIcon(variant?: AppConfirmIconVariant) {
  return <AppConfirmIcon variant={variant ?? 'info'} />
}

export function withAppConfirmDefaults(config: AppConfirmOptions): ModalFuncProps {
  const variant = config.confirmVariant ?? inferAppConfirmVariant(config)
  const icon = config.icon !== undefined ? config.icon : createAppConfirmIcon(variant)
  return {
    centered: config.centered ?? true,
    ...config,
    icon,
    wrapClassName: mergeWrapClassName(String(config.wrapClassName || '')),
    className: config.className ? `${config.className} app-confirm-modal` : 'app-confirm-modal'
  }
}

/** 项目统一确认弹窗（替换 Ant Design 默认红色感叹号图标），原 utils/appConfirm.ts */
export function appConfirm(config: AppConfirmOptions) {
  return Modal.confirm(withAppConfirmDefaults(config))
}

export function appInfo(config: AppConfirmOptions) {
  return Modal.info(
    withAppConfirmDefaults({ ...config, confirmVariant: config.confirmVariant ?? 'info' })
  )
}

export function appWarning(config: AppConfirmOptions) {
  return Modal.warning(
    withAppConfirmDefaults({ ...config, confirmVariant: config.confirmVariant ?? 'warning' })
  )
}

export function appError(config: AppConfirmOptions) {
  return Modal.error(
    withAppConfirmDefaults({ ...config, confirmVariant: config.confirmVariant ?? 'danger' })
  )
}

let modalPatchInstalled = false

/**
 * 全局替换 Modal.confirm / info / warning / error 的默认图标与样式
 * （原 plugins/modal-confirm.client.ts），避免 Ant Design 自带红色圆圈感叹号。
 */
export function installAppConfirmModalPatch(): void {
  if (modalPatchInstalled) return
  modalPatchInstalled = true

  const originalConfirm = Modal.confirm
  const originalInfo = Modal.info
  const originalWarning = Modal.warning
  const originalError = Modal.error

  Object.assign(Modal, {
    confirm: (config: ModalFuncProps) =>
      originalConfirm(withAppConfirmDefaults(config as AppConfirmOptions)),
    info: (config: ModalFuncProps) =>
      originalInfo(withAppConfirmDefaults({ ...config, confirmVariant: 'info' } as AppConfirmOptions)),
    warning: (config: ModalFuncProps) =>
      originalWarning(
        withAppConfirmDefaults({ ...config, confirmVariant: 'warning' } as AppConfirmOptions)
      ),
    error: (config: ModalFuncProps) =>
      originalError(
        withAppConfirmDefaults({ ...config, confirmVariant: 'danger' } as AppConfirmOptions)
      )
  })
}

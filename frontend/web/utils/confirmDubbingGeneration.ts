'use client'

import { Modal } from 'antd'

const DUBBING_GENERATION_CONFIRM_CONTENT = '请确认当前分镜视频是否已有配音！'

/** 配音生成的统一前置确认，onOk 返回 Promise 时由 Ant Design 自动维持确认按钮 loading。 */
export function confirmDubbingGeneration(onConfirm: () => void | Promise<void>): void {
  Modal.confirm({
    title: '生成配音确认',
    content: DUBBING_GENERATION_CONFIRM_CONTENT,
    okText: '确定',
    cancelText: '取消',
    onOk: onConfirm
  })
}

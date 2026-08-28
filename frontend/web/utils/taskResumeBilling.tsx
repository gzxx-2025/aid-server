'use client'

import { Modal } from 'antd'
import type { ReactNode } from 'react'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import type { BillingQuoteVO } from '~/types/business-api'
import { userBillingQuote } from '~/utils/business/billing'
import { buildTaskResumeBillingRequest } from '~/utils/taskPartialFailed'

interface TaskResumeConfirmOptions {
  title: ReactNode
  content: ReactNode
  okText?: string
  cancelText?: string
}

function errorMessage(error: unknown): string {
  if (!error || typeof error !== 'object') return '报价暂不可用'
  const value = error as { msg?: string; message?: string }
  return String(value.msg || value.message || '报价暂不可用')
}

function confirmContent(
  content: ReactNode,
  quote: BillingQuoteVO | null,
  loading: boolean,
  error: string
) {
  return (
    <div>
      <div>{content}</div>
      <BillingQuoteHint quote={quote} loading={loading} error={error} active />
    </div>
  )
}

/** 命令式续生确认层：先显示报价加载态，报价失败仍允许用户继续提交。 */
export function confirmTaskResumeWithBilling(
  taskId: unknown,
  options: TaskResumeConfirmOptions
): Promise<boolean> {
  const request = buildTaskResumeBillingRequest(taskId)
  if (!request) return Promise.resolve(false)

  return new Promise<boolean>((resolve) => {
    const controller = new AbortController()
    let active = true
    let settled = false
    const settle = (value: boolean) => {
      active = false
      controller.abort()
      if (settled) return
      settled = true
      resolve(value)
    }
    const modal = Modal.confirm({
      title: options.title,
      content: confirmContent(options.content, null, true, ''),
      okText: options.okText ?? '续生',
      cancelText: options.cancelText ?? '暂不续生',
      okButtonProps: { loading: true },
      onOk: () => settle(true),
      onCancel: () => settle(false),
      afterClose: () => settle(false)
    })

    void userBillingQuote(request, { signal: controller.signal })
      .then((quote) => {
        if (!active) return
        modal.update({
          content: confirmContent(options.content, quote, false, ''),
          okButtonProps: { loading: false }
        })
      })
      .catch((error: unknown) => {
        if (!active) return
        modal.update({
          content: confirmContent(options.content, null, false, errorMessage(error)),
          okButtonProps: { loading: false }
        })
      })
  })
}

'use client'

import {
  cloneElement,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
  type ReactElement,
  type ReactNode
} from 'react'
import type { BillingQuoteRequest } from '~/types/business-api'

interface DirectActionChildProps {
  disabled?: boolean
  onClick?: (event: ReactMouseEvent<HTMLElement>) => void
  'aria-busy'?: boolean
}

interface Props {
  children: ReactElement
  resolveRequest: () => BillingQuoteRequest | null | Promise<BillingQuoteRequest | null>
  onConfirm: () => void | Promise<void>
  disabled?: boolean
  title?: ReactNode
  okText?: string
  cancelText?: string
}

/**
 * 生成操作兼容壳：保留既有调用契约，但点击后直接执行，不再展示积分确认浮层。
 * 后端任务与扣费链路保持不变，本组件仅负责防止用户连续重复提交。
 */
export function BillingQuoteConfirm({
  children,
  onConfirm,
  disabled = false
}: Props) {
  const runningRef = useRef(false)
  const [running, setRunning] = useState(false)
  const child = children as ReactElement<DirectActionChildProps>

  const handleClick = (event: ReactMouseEvent<HTMLElement>) => {
    child.props.onClick?.(event)
    if (event.defaultPrevented || disabled || child.props.disabled || runningRef.current) return

    runningRef.current = true
    setRunning(true)
    void Promise.resolve()
      .then(onConfirm)
      .finally(() => {
        runningRef.current = false
        setRunning(false)
      })
  }

  // cloneElement 仅覆盖按钮交互属性，不读取或改写调用方 ref。
  // eslint-disable-next-line react-hooks/refs
  return cloneElement(child, {
    disabled: disabled || running || child.props.disabled,
    'aria-busy': running,
    onClick: handleClick
  })
}

export default BillingQuoteConfirm

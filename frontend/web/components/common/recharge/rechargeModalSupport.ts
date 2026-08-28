'use client'

import { useCallback,useRef,useState } from 'react'
import type { AuthPublicConfigData } from '~/types/business-api'
import { rechargeOrderList } from '~/utils/businessApi'

export type PayChannel = 'alipay' | 'wxpay'

export interface PayFlags {
  alipayEnabled: boolean
  wxpayEnabled: boolean
  anyPaymentEnabled: boolean
  defaultPayType: PayChannel | null
}

/** 与 useAuthPublicConfig 同口径：从 public-config 计算支付开关（供 await 后取最新值） */
export function payFlagsFromConfig(config: AuthPublicConfigData | null): PayFlags {
  const alipay = config?.payment?.alipayEnabled === true
  const wxpay = config?.payment?.wxpayEnabled === true
  return {
    alipayEnabled: alipay,
    wxpayEnabled: wxpay,
    anyPaymentEnabled: alipay || wxpay,
    defaultPayType: alipay ? 'alipay' : wxpay ? 'wxpay' : null
  }
}

/** state + 同步 ref 镜像：异步流程 / 定时器里读 ref 拿最新值 */
export function useStateRef<T>(initial: T): [T, { current: T }, (v: T) => void] {
  const [value, setValue] = useState<T>(initial)
  const ref = useRef<T>(initial)
  const set = useCallback((v: T) => {
    ref.current = v
    setValue(v)
  }, [])
  return [value, ref, set]
}

/** 等待一帧：替代原 nextTick 的 DOM 就绪时机 */
export const nextFrame = () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))

/** 后端可能用非 200/0 返回「已有待支付订单」，axios 拦截器 reject 后走 catch，需与成功体 pendingOrderNo 分支一致处理 */
export function isPendingOrderConflictError(e: any): boolean {
  const msg = String(e?.msg ?? e?.message ?? '')
  return /待支付|存在.*订单|继续支付|重新创建|未支付完成/.test(msg)
}

export function extractPendingOrderNoFromReject(e: any): string | null {
  const d = e?.data
  if (d && typeof d === 'object') {
    const n =
      (d as { pendingOrderNo?: string; orderNo?: string }).pendingOrderNo ??
      (d as { orderNo?: string }).orderNo
    if (n) return String(n)
  }
  const n2 = e?.pendingOrderNo ?? e?.orderNo
  if (n2) return String(n2)
  return null
}

export async function resolvePendingOrderNoForModal(e: any): Promise<string | null> {
  const direct = extractPendingOrderNoFromReject(e)
  if (direct) return direct
  try {
    const { rows } = await rechargeOrderList({ payStatus: 'pending', pageNum: 1, pageSize: 20 })
    const p = rows.find((r) => r.payStatus === 'pending')
    return p?.orderNo ?? null
  } catch {
    return null
  }
}

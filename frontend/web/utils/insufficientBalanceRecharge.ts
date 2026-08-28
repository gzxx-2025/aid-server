/**
 * 余额/额度不足 → 是否应对「用户」弹充值中心。
 * 模型/商户侧额度（如「模型额度不足」）不算用户余额不足。
 */
export function isInsufficientBalanceMessage(msg: string): boolean {
  const text = String(msg ?? '')
  // 模型/供应商/商户额度：不应对用户弹充值中心（与 rechargeOwner=MERCHANT 一致）
  if (/模型额度|模型免费额度|服务额度不足|商户.*额度|平台.*额度/.test(text)) return false
  return /余额不足|算力不足|额度不足|请先充值/.test(text)
}

export type SseRechargeErrorData = {
  needRecharge?: boolean
  rechargeOwner?: string
  userMessage?: string
  errorMessage?: string
} | null

/**
 * 是否应唤起用户充值中心（纯判断，无副作用）。
 * - rechargeOwner=USER → true
 * - rechargeOwner=MERCHANT → false（模型额度不足等）
 * - 无结构化字段时按文案兼容判断
 */
export function shouldOpenUserRechargeFromSseError(
  errorData?: SseRechargeErrorData,
  fallbackMessage?: string
): boolean {
  const owner = String(errorData?.rechargeOwner || '')
    .trim()
    .toUpperCase()
  if (owner === 'MERCHANT') return false
  if (errorData?.needRecharge && owner === 'USER') return true
  const msg = errorData?.userMessage || errorData?.errorMessage || fallbackMessage || ''
  return isInsufficientBalanceMessage(msg)
}

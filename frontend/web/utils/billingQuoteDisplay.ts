import type { BillingQuoteVO } from '~/types/business-api'

/** 仅用于前端展示的整数积分；不改动报价原始数值。 */
export function billingQuoteCreditsForDisplay(quote: BillingQuoteVO | null | undefined): number | null {
  if (!quote) return null
  if (quote.isFree) return 0

  const raw = quote.preHoldAmount ?? quote.amount
  if (raw != null && Number.isFinite(Number(raw))) {
    return Math.round(Number(raw))
  }

  const text = String(quote.displayText || '')
  const matched = text.match(/(\d+(?:\.\d+)?)/)
  if (!matched) return null
  const parsed = Number(matched[1])
  return Number.isFinite(parsed) ? Math.round(parsed) : null
}

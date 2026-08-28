import type { CreditConsumeRecordRow } from '~/types/business-api'

/** 金额展示：整数不带小数，小数去尾零，非法值 -- */
export function formatPrice(n: number) {
  const x = Number(n)
  if (!Number.isFinite(x)) return '--'
  if (Number.isInteger(x)) return String(x)
  const t = x.toFixed(2)
  return t.replace(/\.?0+$/, '')
}

export function formatCreditAmount(n: number) {
  const x = Number(n)
  if (!Number.isFinite(x)) return '—'
  return formatPrice(x)
}

export function formatConsumeChange(changeAmount: number) {
  const n = Number(changeAmount)
  if (!Number.isFinite(n)) return '—'
  if (n === 0) return '0'
  const abs = formatCreditAmount(Math.abs(n))
  return n > 0 ? `+${abs}` : `-${abs}`
}

export function consumeAmountLabel(row: CreditConsumeRecordRow) {
  const n = Number(row.changeAmount)
  if (!Number.isFinite(n)) return ''
  if (n < 0) return '净消耗'
  if (n > 0) return '净增加'
  if (row.hasRefund && Number(row.refundAmount) > 0) return '已全额退还'
  return '无变动'
}

export function consumeDetailItems(row: CreditConsumeRecordRow): string[] {
  const items: string[] = []
  const frozen = Number(row.frozenAmount)
  if (Number.isFinite(frozen) && frozen > 0) {
    items.push(`预扣 ${formatCreditAmount(frozen)}`)
  }
  const consumed = Number(row.consumedAmount)
  if (Number.isFinite(consumed) && consumed > 0) {
    items.push(`实际消耗 ${formatCreditAmount(consumed)}`)
  }
  const refund = Number(row.refundAmount)
  if (row.hasRefund && Number.isFinite(refund) && refund > 0) {
    items.push(`退还 ${formatCreditAmount(refund)}`)
  }
  const extra = Number(row.extraAmount)
  if (Number.isFinite(extra) && extra > 0) {
    items.push(`补扣 ${formatCreditAmount(extra)}`)
  }
  return items
}

export function formatPayStatus(v: string) {
  if (v === 'pending') return '待支付'
  if (v === 'paid') return '已支付'
  if (v === 'failed') return '支付失败'
  if (v === 'closed') return '已取消'
  if (v === 'refunded') return '已退款'
  return v
}

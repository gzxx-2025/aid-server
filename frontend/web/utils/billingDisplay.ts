import type { BillingColumnVO,BillingRuleItemVO,ModelBillingDetailVO } from '~/types/business-api'

export type BillingScenarioType = 'standard' | 'without-input-video' | 'with-input-video'

export interface BillingScenario {
  type: BillingScenarioType
  title: string
  applicability: string
  formula: string
  rules: BillingRuleItemVO[]
  columns: BillingColumnVO[]
}

const INPUT_VIDEO_PRICE_KEY = 'inputVideoPricePerSecond'

function requiresInputVideo(rule: BillingRuleItemVO) {
  return Number(rule.inputVideoCountMin || 0) > 0
}

function withScenarioColumns(
  columns: BillingColumnVO[],
  includeInputVideoPrice: boolean
) {
  return columns
    .filter((column) => includeInputVideoPrice || column.key !== INPUT_VIDEO_PRICE_KEY)
    .map((column) => {
      if (column.key === 'pricePerSecond') {
        return { ...column, label: '输出视频单价' }
      }
      return column
    })
}

export function deriveBillingScenarios(model: ModelBillingDetailVO): BillingScenario[] {
  const rules = model.rules || []
  const withVideoRules = rules.filter(requiresInputVideo)
  if (withVideoRules.length === 0) {
    const chargesInputVideo = rules.some((rule) => Number(rule.inputVideoPricePerSecond || 0) > 0)
    return [{
      type: 'standard',
      title: '计费档位',
      applicability: chargesInputVideo ? '适用于下方档位；如上传视频，输入视频费用另计' : '适用于下方档位',
      formula: chargesInputVideo
        ? '总费用 = 输出费用 + 输入媒体费用'
        : model.billingDesc || '',
      rules,
      columns: withScenarioColumns(model.columns || [], chargesInputVideo)
    }]
  }

  const withoutVideoRules = rules.filter((rule) => !requiresInputVideo(rule))
  const scenarios: BillingScenario[] = []
  if (withoutVideoRules.length > 0) {
    scenarios.push({
      type: 'without-input-video',
      title: '图片/文字输入（无输入视频）',
      applicability: '适用于纯文字、图片＋文字等未上传视频的生成请求',
      formula: '总费用 = 输出秒数 × 输出视频单价',
      rules: withoutVideoRules,
      columns: withScenarioColumns(model.columns || [], false)
    })
  }
  scenarios.push({
    type: 'with-input-video',
    title: '含输入视频',
    applicability: '仅适用于上传了输入视频的生成请求',
    formula: '总费用 = 输出秒数 × 输出视频单价 + 输入视频秒数 × 输入视频单价',
    rules: withVideoRules,
    columns: withScenarioColumns(model.columns || [], true)
  })
  return scenarios
}

function sampleDuration(rule: BillingRuleItemVO) {
  const min = Number(rule.durationMin)
  const max = Number(rule.durationMax)
  if (Number.isFinite(min) && min > 10) return min
  if (Number.isFinite(max) && max > 0 && max < 10) return max
  return 10
}

function multiplyPrice(price: number, seconds: number) {
  const priceMicros = BigInt(price.toFixed(6).replace('.', ''))
  const amountTenThousandths = (priceMicros * BigInt(seconds) + 50n) / 100n
  return formatTenThousandths(amountTenThousandths)
}

function formatTenThousandths(value: bigint) {
  const integer = value / 10000n
  const fraction = (value % 10000n).toString().padStart(4, '0')
  return `${integer}.${fraction}`
}

function addAmounts(left: string, right: string) {
  const toTenThousandths = (value: string) => BigInt(value.replace('.', ''))
  return formatTenThousandths(toTenThousandths(left) + toTenThousandths(right))
}

export function formatRuleExample(
  rule: BillingRuleItemVO,
  scenarioType: BillingScenarioType,
  creditUnit: string,
  inputVideoMaxSeconds?: number | null
) {
  const outputPrice = Number(rule.pricePerSecond)
  if (!Number.isFinite(outputPrice) || outputPrice <= 0) return ''
  const duration = sampleDuration(rule)
  const outputTotal = multiplyPrice(outputPrice, duration)
  const outputPart = `${duration}秒输出费用 ${outputTotal} ${creditUnit}`
  const inputPrice = Number(rule.inputVideoPricePerSecond)
  if (scenarioType !== 'with-input-video' || !Number.isFinite(inputPrice) || inputPrice <= 0) return outputPart
  const maxInputSeconds = Number(inputVideoMaxSeconds)
  const sampleInputSeconds = Number.isFinite(maxInputSeconds) && maxInputSeconds > 0
    ? Math.min(5, Math.floor(maxInputSeconds))
    : 5
  const inputTotal = multiplyPrice(inputPrice, sampleInputSeconds)
  const sampleTotal = addAmounts(outputTotal, inputTotal)
  return `${duration}秒输出 + ${sampleInputSeconds}秒输入示例：${outputTotal} + ${inputTotal} = ${sampleTotal} ${creditUnit}`
}

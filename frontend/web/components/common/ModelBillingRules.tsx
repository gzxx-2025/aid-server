import type { BillingRuleItemVO, ModelBillingDetailVO } from '~/types/business-api'
import './ModelBillingRules.css'

interface Props {
  billing?: ModelBillingDetailVO | null
  maxRules?: number
  className?: string
}

const CONDITION_LABELS: Record<string, string> = {
  resolution: '分辨率',
  generateMode: '模式',
  duration: '时长',
  durationSeconds: '时长',
  inputTokens: '输入Token',
  referenceImageCount: '参考图',
  inputVideoCount: '输入视频',
  audio: '音频',
  audioMode: '音频'
}

const VALUE_LABELS: Record<string, string> = {
  TEXT_TO_IMAGE: '文生图',
  IMAGE_TO_IMAGE: '图生图',
  IMAGE_EDIT: '图片编辑',
  TEXT_TO_VIDEO: '文生视频',
  IMAGE_TO_VIDEO: '图生视频',
  REFERENCE_TO_VIDEO: '参考图生视频',
  FIRST_LAST_FRAME_TO_VIDEO: '首尾帧生视频',
  TRUE: '支持',
  FALSE: '不支持'
}

function parseChoices(value: unknown): unknown[] {
  if (Array.isArray(value)) return value
  if (typeof value !== 'string') return [value]
  const text = value.trim()
  if (text.startsWith('[') && text.endsWith(']')) {
    try {
      const parsed = JSON.parse(text)
      if (Array.isArray(parsed)) return parsed
    } catch {
      // 非 JSON 文本继续按单值展示。
    }
  }
  return [text]
}

function displayChoice(value: unknown): string {
  const choices = parseChoices(value)
    .filter((item) => item != null && String(item).trim())
    .map((item) => {
      const raw = String(item).trim()
      return VALUE_LABELS[raw.toUpperCase()] || raw
    })
  const normalized = new Set(choices.map((item) => item.toUpperCase()))
  if (
    choices.length === 3 &&
    normalized.has('SD') &&
    normalized.has('1K') &&
    normalized.has('2K')
  ) {
    return '2K及以下'
  }
  return choices.join('、')
}

function fallbackRuleName(value: unknown): string {
  const name = String(value || '').trim()
  if (!name) return '默认档位'
  return /fallback|default|兜底/i.test(name) ? '其他规格' : name
}

function displayNumber(value: unknown): string {
  if (value == null || value === '') return ''
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return String(value)
  return numeric.toLocaleString('zh-CN', { maximumFractionDigits: 6 })
}

function displayRange(min: unknown, max: unknown, unit = ''): string {
  const lower = displayNumber(min)
  const upper = displayNumber(max)
  if (lower && upper && lower !== upper) return `${lower}-${upper}${unit}`
  return `${lower || upper}${unit}`
}

function ruleConditions(rule: BillingRuleItemVO): string {
  const parts: string[] = []
  if (rule.resolution) parts.push(`规格：${displayChoice(rule.resolution)}`)
  if (rule.generateMode) parts.push(`能力：${displayChoice(rule.generateMode)}`)
  if (rule.durationMin != null || rule.durationMax != null) {
    parts.push(displayRange(rule.durationMin, rule.durationMax, '秒'))
  }
  if (rule.inputTokensMin != null || rule.inputTokensMax != null) {
    parts.push(`输入 ${displayRange(rule.inputTokensMin, rule.inputTokensMax)} Token`)
  }
  if (rule.referenceImageCountMin != null || rule.referenceImageCountMax != null) {
    parts.push(`参考图 ${displayRange(rule.referenceImageCountMin, rule.referenceImageCountMax, '张')}`)
  }
  if (rule.inputVideoCountMin != null || rule.inputVideoCountMax != null) {
    parts.push(`输入视频 ${displayRange(rule.inputVideoCountMin, rule.inputVideoCountMax, '段')}`)
  }
  if (rule.audioMode) parts.push(`音频 ${displayChoice(rule.audioMode)}`)

  const known = new Set([
    'resolution',
    'generateMode',
    'duration',
    'durationSeconds',
    'durationMin',
    'durationMax',
    'inputTokens',
    'inputTokensMin',
    'inputTokensMax',
    'referenceImageCount',
    'referenceImageCountMin',
    'referenceImageCountMax',
    'inputVideoCount',
    'inputVideoCountMin',
    'inputVideoCountMax',
    'audio',
    'audioMode'
  ])
  for (const [key, value] of Object.entries(rule.matchConditions || {})) {
    if (known.has(key) || value == null || value === '') continue
    const rendered = displayChoice(value)
    parts.push(`${CONDITION_LABELS[key] || key} ${rendered}`)
  }
  return [...new Set(parts)].join(' · ') || fallbackRuleName(rule.skuName)
}

function rulePrice(rule: BillingRuleItemVO, creditUnit: string): string {
  const meterType = String(rule.meterType || '').toUpperCase()
  if (meterType === 'TOKEN' || rule.inputPricePerMillion != null || rule.outputPricePerMillion != null) {
    const input = displayNumber(rule.inputPricePerMillion)
    const output = displayNumber(rule.outputPricePerMillion)
    const tokenPrices = [input ? `输入 ${input}` : '', output ? `输出 ${output}` : '']
      .filter(Boolean)
      .join(' / ')
    return tokenPrices ? `${tokenPrices} ${creditUnit}/百万Token` : '价格以实际报价为准'
  }
  if (rule.pricePerSecond != null) return `${displayNumber(rule.pricePerSecond)} ${creditUnit}/秒`
  if (rule.packagePrice != null) return `${displayNumber(rule.packagePrice)} ${creditUnit}/${rule.unitName || '次'}`
  if (rule.unitPrice != null) return `${displayNumber(rule.unitPrice)} ${creditUnit}/${rule.unitName || '次'}`
  return '价格以实际报价为准'
}

/** 展示服务端倍率折算后的 SKU 规则；不会把 MIXED 模型拆成多个模型项。 */
export function ModelBillingRules({ billing, maxRules = 2, className = '' }: Props) {
  const rules = Array.isArray(billing?.rules) ? billing.rules : []
  if (!rules.length) return null
  const visible = rules.slice(0, Math.max(1, maxRules))
  const rawCreditUnit = String(billing?.creditUnit || 'Credits').trim()
  const creditUnit = /^credits?$/i.test(rawCreditUnit) ? '积分' : rawCreditUnit
  const imageInputPrice =
    billing?.inputPricing?.imageUnitPrice != null
      ? billing.inputPricing.imageUnitPrice
      : rules.find((rule) => rule.inputImagePrice != null)?.inputImagePrice
  const imageFreeCount =
    billing?.inputPricing?.imageFreeCount != null
      ? billing.inputPricing.imageFreeCount
      : rules.find((rule) => rule.inputImageFreeCount != null)?.inputImageFreeCount
  const videoInputPrice =
    billing?.inputPricing?.videoUnitPrice != null
      ? billing.inputPricing.videoUnitPrice
      : rules.find((rule) => rule.inputVideoPricePerSecond != null)?.inputVideoPricePerSecond
  return (
    <div className={['model-billing-rules', className].filter(Boolean).join(' ')}>
      {visible.map((rule, index) => (
        <div className="model-billing-rules__row" key={rule.skuCode || `${rule.skuName || 'rule'}-${index}`}>
          <span className="model-billing-rules__condition" title={ruleConditions(rule)}>
            {ruleConditions(rule)}
          </span>
          <span className="model-billing-rules__price">{rulePrice(rule, creditUnit)}</span>
        </div>
      ))}
      {rules.length > visible.length ? (
        <span className="model-billing-rules__more">另有 {rules.length - visible.length} 个档位</span>
      ) : null}
      {imageInputPrice != null || videoInputPrice != null ? (
        <div className="model-billing-rules__row">
          <span className="model-billing-rules__condition">输入媒体</span>
          <span className="model-billing-rules__price">
            {[
              imageInputPrice != null
                ? `图片 ${displayNumber(imageInputPrice)} ${creditUnit}/张${
                    Number(imageFreeCount) > 0 ? `（前 ${imageFreeCount} 张免费）` : ''
                  }`
                : '',
              videoInputPrice != null
                ? `视频 ${displayNumber(videoInputPrice)} ${creditUnit}/秒`
                : ''
            ]
              .filter(Boolean)
              .join(' · ')}
          </span>
        </div>
      ) : null}
    </div>
  )
}

export default ModelBillingRules

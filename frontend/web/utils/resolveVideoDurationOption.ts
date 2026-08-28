/**
 * 视频时长档位归一化：推荐秒数 → 模型 durationOptions。
 * 规则：命中 → 直接用；未命中 → 向上取最近档；超过最大 → 最大档；无推荐 → 模型默认。
 */

export interface ResolveVideoDurationOptionInput {
  recommendedDurationSeconds?: number | null
  durationOptions?: number[] | null
  defaultDurationSeconds?: number | null
}

function toPositiveInt(raw: unknown): number | null {
  const n = Number(raw)
  if (!Number.isFinite(n) || n <= 0) return null
  return Math.floor(n)
}

/** 从分镜 detail 读取推荐时长秒数；无效时返回 null */
export function readRecommendedDurationSeconds(
  row: { recommendedDurationSeconds?: number | null } | null | undefined
): number | null {
  return toPositiveInt(row?.recommendedDurationSeconds)
}

function normalizeDurationOptions(raw?: number[] | null): number[] {
  if (!Array.isArray(raw) || !raw.length) return []
  const list = raw
    .map((n) => toPositiveInt(n))
    .filter((n): n is number => n != null)
  return [...new Set(list)].sort((a, b) => a - b)
}

/** 将推荐秒数归一化到模型支持的时长档位 */
export function resolveVideoDurationOption(input: ResolveVideoDurationOptionInput): number {
  const options = normalizeDurationOptions(input.durationOptions)
  const recommended = toPositiveInt(input.recommendedDurationSeconds)
  const modelDefault = toPositiveInt(input.defaultDurationSeconds)

  if (recommended == null) {
    if (modelDefault != null) {
      if (!options.length) return modelDefault
      if (options.includes(modelDefault)) return modelDefault
      const ceilDefault = options.find((sec) => sec >= modelDefault)
      return ceilDefault ?? options[options.length - 1]!
    }
    return options[0] ?? 5
  }

  if (!options.length) return recommended
  if (options.includes(recommended)) return recommended
  const ceil = options.find((sec) => sec >= recommended)
  return ceil ?? options[options.length - 1]!
}

/** 时长下拉旁动态提示文案 */
export function buildRecommendedDurationTipText(
  selectedSeconds: number | string | null | undefined,
  recommendedSeconds: number | null | undefined
): string {
  const recommended = toPositiveInt(recommendedSeconds)
  if (recommended == null) return ''
  const selected = toPositiveInt(selectedSeconds)
  if (selected != null && selected === recommended) return '当前为推荐最优时长'
  return `建议 ${recommended} 秒`
}

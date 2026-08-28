import type { GlobalSettingData } from '~/types'
import type { EnumDictGroup } from '~/types/business-api'
export type GlobalSettingScriptType = GlobalSettingData['scriptType']
export type GlobalSettingCreationMode = GlobalSettingData['creationMode']
export type GlobalSettingModelStrategy = GlobalSettingData['modelStrategy']

export type EnumChipOption<T extends string = string> = { value: T; label: string }

/** 从 /api/user/dict/enum/list 分组构建选项，label 使用接口 desc */
export function buildEnumChipOptions(groups: EnumDictGroup[], enumType: string): EnumChipOption[] {
  const group = groups.find((g) => g.enumType === enumType)
  return (group?.items ?? []).map((item) => ({
    value: String(item.value),
    label: String(item.desc || item.value)
  }))
}

/** 兼容旧版 UI 值与后端枚举 value */
export function normalizeScriptTypeValue(raw: string | null | undefined): GlobalSettingScriptType | null {
  if (raw === 'plot' || raw === 'story') return 'plot'
  if (raw === 'monologue' || raw === 'live-commentary') return 'monologue'
  return null
}

export function normalizeCreationModeValue(raw: string | null | undefined): GlobalSettingCreationMode | null {
  if (raw === 'i2v' || raw === 'image-to-video') return 'i2v'
  if (raw === 'multi' || raw === 'multi-parameter') return 'multi'
  if (raw === 'pro') return 'pro'
  if (raw === 'auto_grid' || raw === 'auto-grid') return 'auto_grid'
  return null
}

/**
 * 从 project/detail（或剧集）回填创作模式。
 * API 缺字段 / 非法值时保留 previous，避免把已选专业版冲成默认 i2v（刷新后分镜列表 UI 规则失效的根因之一）。
 */
export function resolveProjectCreationMode(
  apiValue?: string | null,
  previous?: string | null
): GlobalSettingCreationMode {
  const fromApi = normalizeCreationModeValue(String(apiValue || '').trim())
  if (fromApi) return fromApi
  const fromPrevious = normalizeCreationModeValue(String(previous || '').trim())
  if (fromPrevious) return fromPrevious
  return 'i2v'
}

export function normalizeModelStrategyValue(raw: string | null | undefined): GlobalSettingModelStrategy | null {
  if (raw === 'economy' || raw === 'cost-saving') return 'economy'
  if (raw === 'performance' || raw === 'quality') return 'performance'
  return null
}

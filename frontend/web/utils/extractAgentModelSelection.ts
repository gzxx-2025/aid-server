import type { AssetExtractType } from '~/types/business-api';

export type ExtractModelCodes = Record<AssetExtractType, string>

export type ModelOptionMatchable = { id: string; serverModelId?: number }

/** 下拉 option 是否与 modelCode / modelId 一致（含 serverModelId 与大小写容错） */
export function modelOptionMatchesCode(option: ModelOptionMatchable, code: string): boolean {
  const normalized = String(code || '').trim()
  if (!normalized) return false
  const id = String(option.id || '').trim()
  if (!id) return false
  if (id === normalized) return true
  if (id.toLowerCase() === normalized.toLowerCase()) return true
  const sid = option.serverModelId
  if (sid != null && String(sid) === normalized) return true
  return false
}

export function findModelOptionIdByCode<T extends ModelOptionMatchable>(
  options: T[],
  code: string | null | undefined
): string {
  const normalized = String(code || '').trim()
  if (!normalized) return ''
  const hit = options.find((o) => modelOptionMatchesCode(o, normalized))
  return hit ? String(hit.id || '').trim() : ''
}

/**
 * 模型下拉默认项：已保存 > 智能体 modelCode 列表（按顺序，须在 options 内）> 空。
 */
export function resolvePreferredModelIdFromAgentCodes<T extends ModelOptionMatchable>(
  options: T[],
  opts: { savedId?: string; agentDefaultCodes?: Array<string | null | undefined> }
): string {
  const savedHit = findModelOptionIdByCode(options, opts.savedId)
  if (savedHit) return savedHit
  for (const raw of opts.agentDefaultCodes ?? []) {
    const hit = findModelOptionIdByCode(options, raw)
    if (hit) return hit
  }
  return ''
}

/**
 * 模型下拉默认项：已保存 > 智能体 modelCode（须在 options 内）> 空。
 * 智能体 modelCode 与下拉不匹配时不回退到列表第一项。
 */
export function resolvePreferredModelId<T extends { id: string }>(
  options: T[],
  opts: { agentDefaultCode?: string; savedId?: string }
): string {
  return resolvePreferredModelIdFromAgentCodes(options, {
    savedId: opts.savedId,
    agentDefaultCodes: opts.agentDefaultCode ? [opts.agentDefaultCode] : []
  })
}

/** 模型下拉未选中时的占位项（勿当作有效 modelCode 提交） */
export const EMPTY_MODEL_PLACEHOLDER = {
  id: '',
  name: '请选择模型',
  iconBg: '#60A5FA',
  desc: '',
  prices: [] as Array<{ resolution: string; cost: number }>
}

/**
 * 根据 modelId 解析下拉展示项；未匹配时不回退到 options[0]。
 */
export function resolveSelectedModelOption<T extends ModelOptionMatchable & { name: string }>(
  options: T[],
  modelId: string | undefined | null,
  placeholder: T = EMPTY_MODEL_PLACEHOLDER as unknown as T
): T {
  const id = String(modelId || '').trim()
  if (!id) return placeholder
  return options.find((o) => modelOptionMatchesCode(o, id)) ?? placeholder
}

export function emptyExtractModelCodes(): ExtractModelCodes {
  return { scene: '', character: '', prop: '' }
}

/** 并行提取提交：仅包含用户手动选择过的 modelCode（空则整段省略，走后端项目配置兜底） */
export function buildParallelModelCodesPayload(
  types: AssetExtractType[],
  modelCodes: ExtractModelCodes
): Partial<Record<AssetExtractType, string>> | undefined {
  const map: Partial<Record<AssetExtractType, string>> = {}
  for (const t of types) {
    const code = String(modelCodes[t] || '').trim()
    if (code) map[t] = code
  }
  return Object.keys(map).length ? map : undefined
}

/** @deprecated 并行提取请使用 buildParallelModelCodesPayload；保留兼容单 modelCode 场景 */
export function resolveParallelSubmitModelCode(
  types: AssetExtractType[],
  modelCodes: ExtractModelCodes
): string | undefined {
  for (const t of types) {
    const code = String(modelCodes[t] || '').trim()
    if (code) return code
  }
  return undefined
}


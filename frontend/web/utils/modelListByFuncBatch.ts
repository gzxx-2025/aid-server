import type {
UserModelListByFuncGroupVO,
UserModelListByFuncRequest,
UserModelListItem
} from '~/types/business-api'

export type ModelListByFuncScope = Pick<UserModelListByFuncRequest, 'projectId' | 'episodeId'>

export function uniqueTrimmedCodes(codes: readonly string[]): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const raw of codes) {
    const code = String(raw || '').trim()
    if (!code || seen.has(code)) continue
    seen.add(code)
    out.push(code)
  }
  return out
}

function emptyListByFuncGroup(funcCode: string): UserModelListByFuncGroupVO {
  return { funcCode, models: [] }
}

/**
 * 将 listByFunc 出参归一化为与入参 funcCodes 顺序一致的分组列表。
 * 专业版带 projectId 时后端可能把 main_storyboard_video 重映射为 multi_pro：
 * 未认领的非空分组会补进仍为空的请求槽，避免多参下拉空白。
 */
export function normalizeListByFuncGroups(
  data: unknown,
  requestedCodes: readonly string[]
): UserModelListByFuncGroupVO[] {
  if (!requestedCodes.length) return []

  if (!Array.isArray(data) || data.length === 0) {
    return requestedCodes.map(emptyListByFuncGroup)
  }

  const first = data[0]
  if (first && typeof first === 'object' && ('funcCode' in first || 'models' in first)) {
    const byCode = new Map<string, UserModelListByFuncGroupVO>()
    for (const raw of data as UserModelListByFuncGroupVO[]) {
      const code = String(raw?.funcCode || '').trim()
      if (!code) continue
      byCode.set(code, {
        ...raw,
        funcCode: code,
        models: Array.isArray(raw.models) ? raw.models : []
      })
    }
    const requestedSet = new Set(requestedCodes)
    const result = requestedCodes.map((code) => {
      const hit = byCode.get(code)
      if (!hit) return emptyListByFuncGroup(code)
      return {
        ...hit,
        funcCode: code,
        models: Array.isArray(hit.models) ? hit.models : []
      }
    })
    const leftovers = [...byCode.entries()]
      .filter(([code]) => !requestedSet.has(code))
      .map(([, g]) => g)
      .filter((g) => Array.isArray(g.models) && g.models.length > 0)
    for (const slot of result) {
      if (slot.models && slot.models.length > 0) continue
      const donor = leftovers.shift()
      if (!donor) break
      slot.models = donor.models
    }
    return result
  }

  if (requestedCodes.length === 1) {
    return [{ funcCode: requestedCodes[0], models: data as UserModelListItem[] }]
  }

  return requestedCodes.map(emptyListByFuncGroup)
}

/** 按单个 funcCode 拉取该功能池模型（listByFunc 分组出参） */
export async function fetchModelsForFuncCode(
  funcCode: string,
  scope?: ModelListByFuncScope
): Promise<UserModelListItem[]> {
  const normalized = String(funcCode || '').trim()
  if (!normalized) return []
  const { userModelListByFuncCodes } = await import('~/utils/businessApi')
  const groups = await userModelListByFuncCodes([normalized], scope)
  return modelsFromListByFuncGroups(groups, normalized)
}

export function modelsFromListByFuncGroups(
  groups: readonly UserModelListByFuncGroupVO[],
  funcCode: string
): UserModelListItem[] {
  const normalized = String(funcCode || '').trim()
  if (!normalized) return []
  const hit = groups.find((g) => String(g.funcCode || '').trim() === normalized)
  return Array.isArray(hit?.models) ? hit.models : []
}

/** 按优先级取第一个非空模型池 */
export function pickFirstNonEmptyModelPool(
  groups: readonly UserModelListByFuncGroupVO[],
  funcCodesInPriority: readonly string[]
): UserModelListItem[] {
  for (const code of funcCodesInPriority) {
    const list = modelsFromListByFuncGroups(groups, code)
    if (list.length > 0) return list
  }
  return []
}

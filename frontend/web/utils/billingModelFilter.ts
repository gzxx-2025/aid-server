import type { ModelBillingDetailVO } from '~/types/business-api'

export type BillingModelTypeFilter = 'all' | 'text' | 'image' | 'video' | 'audio'

export interface BillingModelFilters {
  modelType: BillingModelTypeFilter
  providerName: string
  keyword: string
}

function normalizedModelType(model: ModelBillingDetailVO): string {
  const type = String(model.modelType || '').trim().toLowerCase()
  return type === 'voice' ? 'audio' : type
}

/** 厂商选项仅取本次计费接口实际返回的非空厂商名称。 */
export function listBillingModelProviders(models: readonly ModelBillingDetailVO[]): string[] {
  const providers = new Set<string>()
  for (const model of models) {
    const name = String(model.providerName || '').trim()
    if (name) providers.add(name)
  }
  return [...providers]
}

/** 类型、厂商与搜索词在同一份接口结果上组合过滤。 */
export function filterBillingModels(
  models: readonly ModelBillingDetailVO[],
  filters: BillingModelFilters
): ModelBillingDetailVO[] {
  const provider = filters.providerName.trim()
  const keyword = filters.keyword.trim().toLowerCase()

  return models.filter((model) => {
    if (filters.modelType !== 'all' && normalizedModelType(model) !== filters.modelType) {
      return false
    }
    if (provider && String(model.providerName || '').trim() !== provider) return false
    if (!keyword) return true

    const name = String(model.modelName || '').toLowerCase()
    const code = String(model.modelCode || '').toLowerCase()
    return name.includes(keyword) || code.includes(keyword)
  })
}

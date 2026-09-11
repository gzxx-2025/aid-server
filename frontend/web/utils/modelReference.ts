import type { UserModelListItem } from '~/types/business-api'

/** 恢复迁移前的模型引用；历史编码不会变成额外的下拉选项。 */
export function findModelByReference(
  models: readonly UserModelListItem[],
  reference: string | number | null | undefined
): UserModelListItem | null {
  const value = String(reference ?? '').trim()
  if (!value) return null
  const current = models.find((model) => model.modelCode === value || String(model.id) === value)
  if (current) return current
  const previous = models.filter((model) => model.legacyModelCodes?.includes(value)
    || model.legacyModelIds?.some((id) => String(id) === value))
  return previous.length === 1 ? previous[0] : null
}

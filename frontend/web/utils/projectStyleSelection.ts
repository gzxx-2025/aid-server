import type { ProjectStyleSelection } from '~/utils/buildProjectVideoStyleFields'
import { resolveProjectStyleReference } from '~/utils/buildProjectVideoStyleFields'
type ComparableStyle = Pick<
  ProjectStyleSelection,
  'id' | 'name' | 'assetId' | 'sourceFlag' | 'assetName' | 'promptText'
>

type ResolvedStyleSnapshot = Pick<
  ProjectStyleSelection,
  'id' | 'name' | 'thumbnail' | 'assetId' | 'sourceFlag' | 'assetName' | 'promptText'
>

/**
 * 判断两次风格选择是否指向同一条资产记录。
 * 稳定 ID/source+assetId 一旦存在便只按来源与主键判断；名称+公共提示词仅用于旧项目回显兜底。
 */
export function isSameProjectStyleSelection(
  current: ComparableStyle | null | undefined,
  next: ComparableStyle
): boolean {
  if (!current) return false
  if (current.id === next.id) return true

  const currentReference = resolveProjectStyleReference(current)
  const nextReference = resolveProjectStyleReference(next)
  if (currentReference) {
    return Boolean(
      nextReference &&
        currentReference.styleSource === nextReference.styleSource &&
        currentReference.styleAssetId === nextReference.styleAssetId
    )
  }

  const currentName = String(current.assetName || current.name || '').trim()
  const nextName = String(next.assetName || next.name || '').trim()
  const currentPrompt = String(current.promptText ?? '').trim()
  const nextPrompt = String(next.promptText ?? '').trim()
  return Boolean(currentName && currentName === nextName && currentPrompt === nextPrompt)
}

/** 判断已选风格是否已补齐列表中的最新展示字段，避免分类重载时缩略图退回空值。 */
export function isSameResolvedStyleSnapshot(
  current: ResolvedStyleSnapshot,
  resolved: ResolvedStyleSnapshot
): boolean {
  return (
    current.id === resolved.id &&
    current.name === resolved.name &&
    current.thumbnail === resolved.thumbnail &&
    (current.assetId ?? null) === (resolved.assetId ?? null) &&
    (current.sourceFlag ?? '') === (resolved.sourceFlag ?? '') &&
    (current.assetName ?? '') === (resolved.assetName ?? '') &&
    (current.promptText ?? '') === (resolved.promptText ?? '')
  )
}

/**
 * 创建/切换作品风格时的字段：
 * - 公共展示字段仍使用 assetName / promptText；
 * - styleSource / styleAssetId 只定位风格记录，隐藏模板始终由后端读取并形成项目快照；
 * - 自定义上传等无 promptText 时保留历史退化规则，兼容旧数据。
 */
export type ProjectStyleSelection = {
  id: string
  name: string
  thumbnail: string
  assetId?: number
  sourceFlag?: 'official' | 'custom' | string
  assetName?: string
  promptText?: string | null
}

export type ProjectVideoStyleFields = {
  videoStyleType: string
  videoStyleValue: string
  styleSource?: 'official' | 'custom'
  styleAssetId?: number
}

function normalizeStyleSource(value: unknown): 'official' | 'custom' | null {
  const source = String(value ?? '').trim().toLowerCase()
  if (source === 'official' || source === 'offical') return 'official'
  if (source === 'custom' || source === 'user') return 'custom'
  return null
}

/** 从 OFFICIAL-123 / CUSTOM-123（兼容 USER-123）中解析后端风格定位信息。 */
export function resolveProjectStyleReference(
  selected: Pick<ProjectStyleSelection, 'id' | 'assetId' | 'sourceFlag'>
): { styleSource: 'official' | 'custom'; styleAssetId: number } | null {
  const explicitSource = normalizeStyleSource(selected.sourceFlag)
  const explicitId = Number(selected.assetId)
  if (explicitSource && Number.isFinite(explicitId) && explicitId > 0) {
    return { styleSource: explicitSource, styleAssetId: explicitId }
  }

  const match = /^(OFFICIAL|OFFICAL|CUSTOM|USER)-(\d+)$/i.exec(String(selected.id || '').trim())
  if (!match) return null
  const styleSource = normalizeStyleSource(match[1])
  const styleAssetId = Number(match[2])
  if (!styleSource || !Number.isFinite(styleAssetId) || styleAssetId <= 0) return null
  return { styleSource, styleAssetId }
}

export function buildProjectVideoStyleFields(
  selected: ProjectStyleSelection | null,
  fallbackStyleLabel?: string
): ProjectVideoStyleFields | null {
  if (!selected) return null
  const prompt = String(selected.promptText ?? '').trim()
  const typeFromAsset = String(selected.assetName ?? selected.name ?? '').trim()
  const reference = resolveProjectStyleReference(selected)

  if (prompt) {
    return {
      videoStyleType: typeFromAsset || String(selected.name ?? '').trim(),
      videoStyleValue: prompt,
      ...(reference ?? {})
    }
  }

  const fb = String(fallbackStyleLabel ?? '').trim()
  const value = String(selected.thumbnail || selected.id || fb || '').trim()
  if (!value) return null

  return {
    videoStyleType: typeFromAsset || 'custom',
    videoStyleValue: value,
    ...(reference ?? {})
  }
}

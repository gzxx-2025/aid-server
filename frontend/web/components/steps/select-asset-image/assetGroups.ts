export type AssetImageType =
  | 'scene'
  | 'character'
  | 'prop'
  | 'pose'
  | 'expression'
  | 'effect'
  | 'draft'
  | 'other'
  | 'reference'
  | 'multiParamReference'

export const TITLE_MAP: Record<AssetImageType, string> = {
  scene: '选择场景',
  character: '选择角色',
  prop: '选择道具',
  pose: '选择姿态图',
  expression: '选择表情图',
  effect: '选择特效图',
  draft: '选择手绘稿',
  other: '选择其他',
  reference: '选择分镜画面',
  multiParamReference: '导入参考图'
}

/** 按 url / id 去重，避免主列表与各形态图列表重复展示同一张图 */
export function dedupeAssetImages(images: any[]): any[] {
  const seen = new Set<string>()
  const out: any[] = []
  for (const img of images) {
    const key = String(img?.url || img?.thumbnail || img?.id || '').trim()
    if (!key || seen.has(key)) continue
    seen.add(key)
    out.push(img)
  }
  return out
}

export const TAB_OPTIONS_MAP: Record<AssetImageType, Record<string, string>> = {
  scene: { current: '本作品资产', step: '当前分镜' },
  character: { current: '本作品资产', step: '当前分镜' },
  prop: { current: '本作品资产', step: '当前分镜' },
  pose: { current: '本作品资产', step: '当前分镜' },
  expression: { current: '本作品资产', step: '当前分镜' },
  effect: { current: '本作品资产', step: '当前分镜' },
  draft: { current: '本作品资产', step: '当前分镜' },
  other: { current: '本作品资产', step: '当前分镜' },
  reference: { current: '本作品资产', step: '当前分镜' },
  multiParamReference: { current: '本作品资产', step: '当前分镜' }
}

export const OTHER_TYPE_LABELS: Record<string, string> = {
  pose: '姿态图',
  expression: '表情图',
  effect: '特效图',
  draft: '手绘稿',
  other: '其他参考'
}

export type AssetGroup = { label: string; images: any[] }

/** 组装分组所需的创作 store 数据切片（由宿主组件用 selector 取出后传入） */
export interface ProjectAssetGroupSources {
  sceneCharacter:
    | { scenes?: string[]; characters?: string[]; props?: string[] }
    | null
    | undefined
  sceneImages: Record<number, any[]>
  characterImages: Record<number, any[]>
  propImages: Record<number, any[]>
  characterForms: Record<number, any[]>
  propForms: Record<number, any[]>
  characterFormImages: Record<string, any[]>
  propFormImages: Record<string, any[]>
}

export function formatCategoryLabel(prefix: string, index: number, name?: string) {
  const n = (name && String(name).trim()) || '未命名'
  return `${prefix}${index + 1}: ${n}`
}

export function hasDisplayableImage(img: any): boolean {
  return !!String(img?.url || img?.thumbnail || '').trim()
}

/** reference 弹窗：仅保留有可用图片的分组 */
export function filterGroupsWithImages(groups: AssetGroup[]): AssetGroup[] {
  return groups.filter((g) => g.images.some(hasDisplayableImage))
}

/** 第三步：场景 / 分镜参考图 左侧分组 + 图片 */
export function buildSceneGroups(sources: ProjectAssetGroupSources): AssetGroup[] {
  const names = sources.sceneCharacter?.scenes || []
  if (!names.length) {
    return [{ label: '（暂无场景，请先在第三步添加）', images: [] }]
  }
  return names.map((name, idx) => {
    const raw = sources.sceneImages[idx] || []
    const images = raw.map((img: any, j: number) => ({
      ...img,
      id: img.id || `proj-scene-${idx}-${j}-${img.url || j}`
    }))
    return {
      label: formatCategoryLabel('场景', idx, name),
      images
    }
  })
}

export function buildCharacterGroups(sources: ProjectAssetGroupSources): AssetGroup[] {
  const names = sources.sceneCharacter?.characters || []
  if (!names.length) {
    return [{ label: '（暂无角色，请先在第三步添加）', images: [] }]
  }
  return names.map((name, charIdx) => {
    const forms = sources.characterForms[charIdx] || []
    const formImgs: any[] = []
    forms.forEach((_, fi) => {
      formImgs.push(...(sources.characterFormImages[`${charIdx}-${fi}`] || []))
    })
    const main = sources.characterImages[charIdx] || []
    // 接口同步时 main 常为各形态图的合并副本，与 formImgs 叠加会重复展示
    const merged = dedupeAssetImages(formImgs.length > 0 ? formImgs : main)
    const images = merged.map((img: any, j: number) => ({
      ...img,
      id: img.id || `proj-char-${charIdx}-${j}-${img.url || j}`
    }))
    return {
      label: formatCategoryLabel('角色', charIdx, name),
      images
    }
  })
}

export function buildPropGroups(sources: ProjectAssetGroupSources): AssetGroup[] {
  const names = sources.sceneCharacter?.props || []
  if (!names.length) {
    return [{ label: '（暂无道具，请先在第三步添加）', images: [] }]
  }
  return names.map((name, propIdx) => {
    const forms = sources.propForms[propIdx] || []
    const formImgs: any[] = []
    forms.forEach((_, fi) => {
      formImgs.push(...(sources.propFormImages[`${propIdx}-${fi}`] || []))
    })
    const main = sources.propImages[propIdx] || []
    const merged = dedupeAssetImages(formImgs.length > 0 ? formImgs : main)
    const images = merged.map((img: any, j: number) => ({
      ...img,
      id: img.id || `proj-prop-${propIdx}-${j}-${img.url || j}`
    }))
    return {
      label: formatCategoryLabel('道具', propIdx, name),
      images
    }
  })
}

export function buildReferenceGroups(
  sources: ProjectAssetGroupSources,
  storyboardScriptGroups: AssetGroup[]
): AssetGroup[] {
  const sceneGroups = filterGroupsWithImages(buildSceneGroups(sources))
  const characterGroups = filterGroupsWithImages(buildCharacterGroups(sources))
  const propGroups = filterGroupsWithImages(buildPropGroups(sources))
  const storyboardGroups = filterGroupsWithImages(
    (storyboardScriptGroups || []).map((g) => ({
      label: g.label,
      images: (g.images || []).map((img: any, j: number) => ({
        ...img,
        id: img.id || `ref-sb-${j}-${img.url || img.thumbnail || j}`
      }))
    }))
  )

  const merged = [...sceneGroups, ...characterGroups, ...propGroups, ...storyboardGroups]
  if (merged.length > 0) return merged

  return [{ label: '（暂无可选参考图，请先在第三步或第四步生图/上传）', images: [] }]
}

export function buildProjectAssetGroups(
  type: AssetImageType,
  sources: ProjectAssetGroupSources,
  storyboardScriptGroups: AssetGroup[]
): AssetGroup[] {
  if (type === 'reference' || type === 'multiParamReference') {
    return buildReferenceGroups(sources, storyboardScriptGroups)
  }
  if (type === 'scene') return buildSceneGroups(sources)
  if (type === 'character') return buildCharacterGroups(sources)
  if (type === 'prop') return buildPropGroups(sources)
  const label = OTHER_TYPE_LABELS[type] || '其他'
  return [
    {
      label: `（第三步暂无「${label}」清单，请用本地上传或资源库）`,
      images: [] as any[]
    }
  ]
}

export function isAudioPendingItem(item: any): boolean {
  return item?.kind === 'audio' || item?.audioSource === 'voice_sample' || item?.audioSource === 'upload'
}

export function rowKey(item: any) {
  if (isAudioPendingItem(item)) {
    return `audio-${item.referenceAudioId || item.id || item.url || item.name}`
  }
  const u = item.url || item.thumbnail || ''
  return `img-${item.id}-${u}`
}

export function formatDate(str: string) {
  if (!str) return ''
  const d = new Date(str)
  return d.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

export function resolveAudioRelativePath(url: string): string {
  const raw = String(url || '').trim()
  if (!raw) return ''
  if (raw.startsWith('/')) return raw
  try {
    const u = new URL(raw)
    return u.pathname || raw
  } catch {
    return raw
  }
}

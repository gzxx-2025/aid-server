/** 分镜图描述框内 @图片N[name] 资产引用 */

export type PromptAssetType = 'scene' | 'character' | 'prop' | 'other' | 'audio'

export interface PromptAssetItem {
  assetId: string
  assetType: PromptAssetType
  /** API 占位 name（不含 @） */
  name: string
  /** @图片N / @音频N 中的 N */
  imageIndex: number
  url?: string
  /** 展示用，如 @场景1 / @音频-法海 */
  label: string
}

export interface PromptAssetRefValue {
  assetId: string
  assetType: PromptAssetType
  name: string
  imageIndex: number
  url?: string
  label: string
}

const API_PLACEHOLDER_RE = /@图片(\d+)\[([^\]]+)\]/g
const API_AUDIO_PLACEHOLDER_RE = /@音频(\d+)\[([^\]]+)\]/g
const LEGACY_TAG_RE = /@([^\s@]+)/g

const TYPE_LABEL: Record<PromptAssetType, string> = {
  scene: '场景',
  character: '角色',
  prop: '道具',
  other: '其他',
  audio: '音频'
}

function stripAt(s: string): string {
  return s.startsWith('@') ? s.slice(1) : s
}

/**
 * 将资产名称收敛为占位协议可安全承载的文本。
 *
 * `@图片N[name]` / `@音频N[name]` 的前后端解析器都以 ASCII `]` 作为 name
 * 的结束符，因此名称里原本包含的方括号不能直接写进占位符。视频截帧名称会包含
 * `[秒.毫秒]`，若不在协议边界统一处理，富文本的 HTML → plain → HTML 同步每轮都会
 * 把右括号后的时间戳当作普通文本再追加一次。
 */
export function normalizePromptAssetPlaceholderName(
  value: unknown,
  fallback = ''
): string {
  const raw = stripAt(String(value ?? '').trim())
  const normalized = raw
    .replace(/[\u0000-\u001f\u007f]+/g, ' ')
    .replace(/\[/g, '【')
    .replace(/\]/g, '】')
    .trim()
  return normalized || fallback
}

export function isEmptyPromptAssetUrl(url?: string): boolean {
  return !String(url || '').trim()
}

export function promptAssetNamesMatch(
  a: { name?: string; label?: string },
  b: { name?: string; label?: string }
): boolean {
  const na = normalizePromptAssetPlaceholderName(a.name || a.label)
  const nb = normalizePromptAssetPlaceholderName(b.name || b.label)
  return !!na && !!nb && na === nb
}

/** 用本地真实图片补全解析失败（无 url）的 resolved 资产，保留原 @图片N 序号 */
export function patchEmptyResolvedPromptAssets(
  resolved: PromptAssetItem[],
  local: PromptAssetItem[]
): PromptAssetItem[] {
  if (!resolved.length || !local.length) return resolved
  return resolved.map((item) => {
    if (!isEmptyPromptAssetUrl(item.url)) return item
    const matches = local.filter(
      (candidate) =>
        promptAssetMediaKind(candidate) === promptAssetMediaKind(item) &&
        promptAssetNamesMatch(item, candidate) &&
        !isEmptyPromptAssetUrl(candidate.url)
    )
    const match = matches.length === 1 ? matches[0] : undefined
    if (!match) return item
    return {
      ...item,
      assetId: match.assetId,
      assetType: match.assetType,
      url: match.url,
      label: item.label || match.label
    }
  })
}

function assetDisplayName(img: { title?: string; name?: string }, type: PromptAssetType, index: number): string {
  const raw = String(img?.title ?? img?.name ?? '').trim()
  if (raw) return normalizePromptAssetPlaceholderName(raw)
  return `${TYPE_LABEL[type]}${index + 1}`
}

function assetDisplayLabel(img: { title?: string; name?: string }, type: PromptAssetType, index: number): string {
  const name = assetDisplayName(img, type, index)
  return `@${name}`
}

/** 解析纯文本中的 @图片N[name] 占位（按 N 升序） */
export function parseApiImagePlaceholders(plain: string): Array<{ imageIndex: number; name: string }> {
  const list: Array<{ imageIndex: number; name: string }> = []
  const re = /@图片(\d+)\[([^\]]+)\]/g
  let m: RegExpExecArray | null
  while ((m = re.exec(plain)) !== null) {
    list.push({ imageIndex: Number(m[1]), name: m[2] })
  }
  return list.sort((a, b) => a.imageIndex - b.imageIndex)
}

/** 将 resolve 接口结果映射为可点击资产项（优先 references[]，平行数组作兼容） */
export function buildPromptAssetsFromResolve(
  plain: string,
  data: {
    referenceImageIds?: number[]
    referenceImageUrls?: string[]
    unresolvedNames?: string[]
    references?: Array<{ n?: number | null; name?: string | null; imageId?: number | null; url?: string | null }> | null
  }
): PromptAssetItem[] {
  const refs = Array.isArray(data.references) ? data.references : []
  if (refs.length > 0) {
    return dedupePromptAssets(
      refs.map((ref, i) => {
        const name = normalizePromptAssetPlaceholderName(ref?.name, `参考图${i + 1}`)
        const imageIndex = Number(ref?.n) > 0 ? Number(ref.n) : i + 1
        const imageId = Number(ref?.imageId)
        return {
          assetId: Number.isFinite(imageId) && imageId > 0
            ? String(imageId)
            : `resolved-${imageIndex}-${name}`,
          assetType: 'other' as PromptAssetType,
          name,
          imageIndex,
          url: String(ref?.url || '').trim(),
          label: `@${name}`
        }
      })
    )
  }

  const placeholders = parseApiImagePlaceholders(plain)
  const ids = data.referenceImageIds || []
  const urls = data.referenceImageUrls || []
  return dedupePromptAssets(
    placeholders.map((ph, i) => ({
      assetId: String(ids[i] ?? `resolved-${ph.imageIndex}-${ph.name}`),
      assetType: 'other' as PromptAssetType,
      name: ph.name,
      imageIndex: ph.imageIndex,
      url: urls[i] || '',
      label: `@${ph.name}`
    }))
  )
}

function promptAssetMediaKind(asset: Pick<PromptAssetItem, 'assetType'>): 'audio' | 'image' {
  return asset.assetType === 'audio' ? 'audio' : 'image'
}

function promptAssetStableIdentity(asset: PromptAssetItem): string {
  const id = String(asset.assetId || '').trim()
  if (id) return `${promptAssetMediaKind(asset)}:id:${id}`
  return `${promptAssetMediaKind(asset)}:fallback:${asset.imageIndex}:${normalizePromptAssetPlaceholderName(asset.name || asset.label)}`
}

function isProvisionalPromptAssetId(value: unknown): boolean {
  const id = String(value || '').trim()
  return !id || /^(?:resolved|placeholder|audio-placeholder)-/.test(id)
}

/** 仅按稳定身份去重；同名资产必须保留，序号冲突则在各媒体命名空间内顺延。 */
export function dedupePromptAssets(assets: PromptAssetItem[]): PromptAssetItem[] {
  const seen = new Set<string>()
  const usedIndexes = {
    image: new Set<number>(),
    audio: new Set<number>()
  }
  const result: PromptAssetItem[] = []
  for (const item of assets) {
    const identity = promptAssetStableIdentity(item)
    if (seen.has(identity)) continue
    seen.add(identity)

    const kind = promptAssetMediaKind(item)
    const indexes = usedIndexes[kind]
    let imageIndex = Math.max(1, Math.floor(Number(item.imageIndex) || 1))
    if (indexes.has(imageIndex)) {
      imageIndex = 1
      while (indexes.has(imageIndex)) imageIndex += 1
    }
    indexes.add(imageIndex)
    result.push(imageIndex === item.imageIndex ? item : { ...item, imageIndex })
  }
  return result
}

/** 合并接口解析资产与本地导入资产（按 assetId / name 去重，本地序号冲突时顺延） */
export function mergePromptAssets(
  resolved: PromptAssetItem[],
  local: PromptAssetItem[]
): PromptAssetItem[] {
  const merged = dedupePromptAssets(resolved).map((asset) => ({ ...asset }))

  for (const item of local) {
    const kind = promptAssetMediaKind(item)
    const itemId = String(item.assetId || '').trim()
    const localName = normalizePromptAssetPlaceholderName(item.name)
    const exactIndex = itemId
      ? merged.findIndex(
          (candidate) =>
            promptAssetMediaKind(candidate) === kind &&
            String(candidate.assetId || '').trim() === itemId
        )
      : -1

    if (exactIndex >= 0) {
      if (isEmptyPromptAssetUrl(merged[exactIndex]!.url) && !isEmptyPromptAssetUrl(item.url)) {
        merged[exactIndex] = {
          ...merged[exactIndex]!,
          ...item,
          imageIndex: merged[exactIndex]!.imageIndex,
          name: merged[exactIndex]!.name,
          label: merged[exactIndex]!.label || item.label
        }
      }
      continue
    }

    const sameName = localName
      ? merged
          .map((candidate, index) => ({ candidate, index }))
          .filter(
            ({ candidate }) =>
              promptAssetMediaKind(candidate) === kind &&
              normalizePromptAssetPlaceholderName(candidate.name) === localName
          )
      : []
    const provisionalByName = sameName.filter(
      ({ candidate }) =>
        isProvisionalPromptAssetId(candidate.assetId) && isEmptyPromptAssetUrl(candidate.url)
    )

    if (
      provisionalByName.length === 1 &&
      !isEmptyPromptAssetUrl(item.url)
    ) {
      const { candidate, index } = provisionalByName[0]!
      merged[index] = {
        ...item,
        imageIndex: candidate.imageIndex,
        name: candidate.name,
        label: candidate.label || item.label
      }
      continue
    }

    const usedIndexes = new Set(
      merged
        .filter((candidate) => promptAssetMediaKind(candidate) === kind)
        .map((candidate) => candidate.imageIndex)
    )
    let imageIndex = Math.max(1, Math.floor(Number(item.imageIndex) || 1))
    if (usedIndexes.has(imageIndex)) {
      imageIndex = 1
      while (usedIndexes.has(imageIndex)) imageIndex += 1
    }
    merged.push(imageIndex === item.imageIndex ? { ...item } : { ...item, imageIndex })
  }
  return dedupePromptAssets(merged)
}

/** 将四类导入列表合并为带全局图片序号的资产表 */
export function collectStoryboardPromptAssets(
  sceneImages: any[],
  characterImages: any[],
  propImages: any[],
  otherImages: any[],
  startImageIndex = 1
): PromptAssetItem[] {
  const list: PromptAssetItem[] = []
  let imageIndex = startImageIndex

  const pushList = (images: any[], assetType: PromptAssetType) => {
    images.forEach((img, i) => {
      const name = assetDisplayName(img, assetType, i)
      list.push({
        assetId: String(img?.id ?? `${assetType}-${i}-${name}`),
        assetType,
        name,
        imageIndex: imageIndex++,
        url: img?.url || img?.thumbnail || '',
        label: assetDisplayLabel(img, assetType, i)
      })
    })
  }

  pushList(sceneImages, 'scene')
  pushList(characterImages, 'character')
  pushList(propImages, 'prop')
  pushList(otherImages, 'other')
  return list
}

export function formatAssetApiPlaceholder(imageIndex: number, name: string): string {
  const n = Math.max(1, Math.floor(Number(imageIndex) || 1))
  const safeName = normalizePromptAssetPlaceholderName(name, '参考图')
  return `@图片${n}[${safeName}]`
}

export function formatAudioApiPlaceholder(audioIndex: number, name: string): string {
  const n = Math.max(1, Math.floor(Number(audioIndex) || 1))
  const raw = normalizePromptAssetPlaceholderName(name, '未命名')
  const full = raw.startsWith('音频-') ? raw : `音频-${raw}`
  return `@音频${n}[${full}]`
}

/** 将参考音频媒体项转为可点击的 prompt 资产（供文本域 @音频 chip） */
export function collectPromptAudioAssetsFromMedia(
  audios: Array<{
    id?: string | number
    name?: string
    title?: string
    url?: string
    referenceAudioId?: number
    audioSource?: string
  }>
): PromptAssetItem[] {
  const list: PromptAssetItem[] = []
  ;(audios || []).forEach((a, i) => {
    const nameRaw = normalizePromptAssetPlaceholderName(
      a?.name || a?.title,
      `未命名${i + 1}`
    )
    const name = nameRaw.startsWith('音频-') ? nameRaw : `音频-${nameRaw}`
    list.push({
      assetId: String(a?.id ?? a?.referenceAudioId ?? `audio-${i}-${name}`),
      assetType: 'audio',
      name,
      imageIndex: i + 1,
      url: String(a?.url || '').trim() || undefined,
      label: `@${name}`
    })
  })
  return list
}

/** 资产库内记录（aid_role_prop_scene_form_image）使用数字 ID */
export function isLibraryPromptAssetId(assetId: string | number | undefined | null): boolean {
  const id = String(assetId ?? '').trim()
  return /^\d+$/.test(id) && Number(id) > 0
}

/** 按占位序号/名称查找带有效 URL 的资产（优先 imageIndex，再按 name 兜底） */
export function findPromptAssetWithUrl(
  assets: PromptAssetItem[],
  hint: { imageIndex?: number; name?: string }
): PromptAssetItem | undefined {
  const primary = findPromptAsset(assets, hint)
  if (primary && !isEmptyPromptAssetUrl(primary.url)) return primary

  if (hint.imageIndex != null && hint.imageIndex > 0) {
    const byIdx = assets.find(
      (a) => a.imageIndex === hint.imageIndex && !isEmptyPromptAssetUrl(a.url)
    )
    if (byIdx) return byIdx
  }

  const name = normalizePromptAssetPlaceholderName(hint.name)
  if (name) {
    const byName = assets.filter(
      (a) =>
        !isEmptyPromptAssetUrl(a.url) &&
        (normalizePromptAssetPlaceholderName(a.name) === name ||
          normalizePromptAssetPlaceholderName(a.label) === name)
    )
    if (byName.length === 1) return byName[0]
  }

  return undefined
}

/** 提示词中无法匹配到有效 URL 的 @图片N[name] 占位 */
export function listUnresolvedPromptImagePlaceholders(
  plain: string,
  assets: PromptAssetItem[]
): Array<{ imageIndex: number; name: string }> {
  return parseApiImagePlaceholders(plain).filter(
    (ph) => !findPromptAssetWithUrl(assets, { imageIndex: ph.imageIndex, name: ph.name })
  )
}

export interface StoryboardReferenceImageItem {
  id: string
  url: string
  thumbnail: string
  title: string
  name: string
}

/** 将解析得到的 PromptAssetItem 转为「导入参考图」展示项（无 URL 则跳过） */
export function promptAssetToReferenceImageItem(
  asset: PromptAssetItem
): StoryboardReferenceImageItem | null {
  const url = String(asset.url || '').trim()
  if (!url) return null
  const id = String(asset.assetId || '').trim() || `resolved-${asset.imageIndex}-${asset.name}`
  const name = stripAt(String(asset.name || asset.label || '').trim()) || '参考图'
  return {
    id,
    url,
    thumbnail: url,
    title: name,
    name
  }
}

function referenceImageItemKey(img: {
  id?: string
  url?: string
  thumbnail?: string
  name?: string
  title?: string
}): string {
  const id = String(img.id || '').trim()
  if (id && !id.startsWith('resolved-') && !id.startsWith('placeholder-')) return `id:${id}`
  const url = String(img.url || img.thumbnail || '').trim()
  if (url) return `url:${url}`
  const name = stripAt(String(img.name || img.title || '').trim())
  if (name) return `name:${name}`
  return ''
}

/** 合并参考图列表（按 id / url / name 去重，保留已有项顺序） */
export function mergeReferenceImageItems<
  T extends { id?: string; url?: string; thumbnail?: string; name?: string; title?: string }
>(existing: T[], incoming: T[]): T[] {
  const seen = new Set(existing.map(referenceImageItemKey).filter(Boolean))
  const merged = [...existing]
  for (const item of incoming) {
    const key = referenceImageItemKey(item)
    if (!key || seen.has(key)) continue
    seen.add(key)
    merged.push(item)
  }
  return merged
}

/** 将 resolve 资产按类型拆分到场景/角色/道具/其他四类参考图 */
export function splitResolvedPromptAssetsToReferenceBuckets(
  assets: PromptAssetItem[],
  inferType?: (item: StoryboardReferenceImageItem) => PromptAssetType
): {
  scene: StoryboardReferenceImageItem[]
  character: StoryboardReferenceImageItem[]
  prop: StoryboardReferenceImageItem[]
  other: StoryboardReferenceImageItem[]
} {
  const buckets = {
    scene: [] as StoryboardReferenceImageItem[],
    character: [] as StoryboardReferenceImageItem[],
    prop: [] as StoryboardReferenceImageItem[],
    other: [] as StoryboardReferenceImageItem[]
  }
  for (const asset of assets) {
    const item = promptAssetToReferenceImageItem(asset)
    if (!item) continue
    let type: PromptAssetType = asset.assetType
    if (type === 'other' && inferType) {
      type = inferType(item)
    }
    // audio 等非图片类型不参与参考图分桶，统一归入 other
    const bucketKey = (type in buckets ? type : 'other') as keyof typeof buckets
    buckets[bucketKey].push(item)
  }
  return buckets
}

/**
 * 多参出片：从提示词占位 + 本地/解析资产构建 referenceOverrides。
 * key 必须为 prompt 内 @图片N[name] 的 name；有 URL 时一律传入（含库内资产，后端优先采用 overrides）。
 */
export function buildStoryboardVideoReferenceOverrides(
  plain: string,
  assets: PromptAssetItem[]
): Record<string, string> {
  const overrides: Record<string, string> = {}
  for (const ph of parseApiImagePlaceholders(plain)) {
    const asset = findPromptAssetWithUrl(assets, {
      imageIndex: ph.imageIndex,
      name: ph.name
    })
    const url = String(asset?.url ?? '').trim()
    if (!url) continue
    overrides[ph.name] = url
  }
  return overrides
}

export function promptAssetRefToPlaceholder(v: PromptAssetRefValue): string {
  return formatAssetApiPlaceholder(v.imageIndex, v.name)
}

export function promptAssetItemToRefValue(item: PromptAssetItem): PromptAssetRefValue {
  const fallbackName = item.assetType === 'audio' ? '音频-未命名' : '参考图'
  const name = normalizePromptAssetPlaceholderName(item.name || item.label, fallbackName)
  const labelName = normalizePromptAssetPlaceholderName(item.label || name, name)
  return {
    assetId: item.assetId,
    assetType: item.assetType,
    name,
    imageIndex: item.imageIndex,
    url: item.url,
    label: `@${labelName}`
  }
}

/** 在资产表中查找（按 id、name、label） */
export function findPromptAsset(
  assets: PromptAssetItem[],
  hint: {
    assetId?: string
    assetType?: PromptAssetType
    name?: string
    label?: string
    imageIndex?: number
  }
): PromptAssetItem | undefined {
  const candidates = hint.assetType
    ? assets.filter((asset) => asset.assetType === hint.assetType)
    : assets
  if (hint.assetId) {
    const byId = candidates.find((a) => a.assetId === hint.assetId)
    if (byId) return byId
  }
  if (hint.imageIndex != null) {
    const byIdx = candidates.filter((a) => a.imageIndex === hint.imageIndex)
    if (byIdx.length === 1) return byIdx[0]
  }
  const name = normalizePromptAssetPlaceholderName(hint.name)
  if (name) {
    const byName = candidates.filter(
      (a) =>
        normalizePromptAssetPlaceholderName(a.name) === name ||
        normalizePromptAssetPlaceholderName(a.label) === name
    )
    if (byName.length === 1) return byName[0]
  }
  const label = normalizePromptAssetPlaceholderName(hint.label)
  if (label) {
    const byLabel = candidates.filter(
      (a) =>
        normalizePromptAssetPlaceholderName(a.label) === label ||
        normalizePromptAssetPlaceholderName(a.name) === label
    )
    if (byLabel.length === 1) return byLabel[0]
  }
  return undefined
}


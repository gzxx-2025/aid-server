export const FORM_IMAGE_REFERENCE_LIMIT = 4

export interface FormImageEditPrefillSource {
  promptText?: unknown
  referenceImages?: unknown
  imageUrl?: unknown
}

export interface FormImageEditPrefill {
  promptText: string
  sourceImages: Array<{ url: string }>
}

export interface SelectedFormImageSource {
  url?: unknown
  title?: unknown
  name?: unknown
}

/**
 * 读取形态图列表项的可复用编辑输入；当前图片地址不属于历史参考图来源。
 */
export function resolveFormImageEditPrefill(
  source: FormImageEditPrefillSource | null | undefined,
  maxReferenceImages = FORM_IMAGE_REFERENCE_LIMIT
): FormImageEditPrefill {
  const rawPrompt = typeof source?.promptText === 'string' ? source.promptText : ''
  const promptText = rawPrompt.trim() ? rawPrompt : ''
  const limit = Math.max(0, Math.trunc(maxReferenceImages))
  const seen = new Set<string>()
  const sourceImages: Array<{ url: string }> = []

  if (Array.isArray(source?.referenceImages) && limit > 0) {
    for (const candidate of source.referenceImages) {
      if (typeof candidate !== 'string') continue
      const url = candidate.trim()
      if (!url || seen.has(url)) continue
      seen.add(url)
      sourceImages.push({ url })
      if (sourceImages.length >= limit) break
    }
  }

  return { promptText, sourceImages }
}

/**
 * 工具栏进入对话作图时保留旧交互：仅在没有保存参考图时，以当前图片作为单图兜底。
 */
export function resolveDialogueToolbarSourceImages(
  prefilled: Array<{ url: string; title?: string }>,
  selectedImage: SelectedFormImageSource | null | undefined
): Array<{ url: string; title?: string }> {
  if (prefilled.length > 0) return prefilled.map((item) => ({ ...item }))

  const url = typeof selectedImage?.url === 'string' ? selectedImage.url.trim() : ''
  if (!url) return []
  const rawTitle =
    typeof selectedImage?.title === 'string'
      ? selectedImage.title
      : typeof selectedImage?.name === 'string'
        ? selectedImage.name
        : ''
  const title = rawTitle.trim()
  return [{ url, ...(title ? { title } : {}) }]
}

/** 分镜图是否为已确认主图（setFinalImage / isSelected=1） */
export function isStoryboardImageSelected(img: {
  isSelected?: boolean
  _serverRow?: { isSelected?: number | null }
}): boolean {
  return img?.isSelected === true || img?._serverRow?.isSelected === 1
}

/** Tab / 列表封面：仅展示已确认主图，不把生成记录候选当作封面 */
export function pickStoryboardCoverImage(images: any[] | null | undefined): any | null {
  if (!images?.length) return null
  return images.find((im) => isStoryboardImageSelected(im)) ?? null
}

/** 分镜脚本列表封面：优先 images 中 isSelected=1，其次 finalImageUrl */
export function resolveStoryboardPanelCoverImage(panel: {
  images?: any[] | null
  finalImageUrl?: string | null
} | null | undefined): any | null {
  const fromImages = pickStoryboardCoverImage(panel?.images)
  if (fromImages?.url || fromImages?.thumbnail) return fromImages
  const url = (panel?.finalImageUrl ?? '').trim()
  if (!url) return null
  return { url, thumbnail: url, title: '分镜图' }
}

/** 同步父组件：主图在前；无主图时不改变候选顺序 */
export function sortStoryboardImagesForParent(images: any[]): any[] {
  const selected = images.filter((im) => isStoryboardImageSelected(im))
  const rest = images.filter((im) => !isStoryboardImageSelected(im))
  return [...selected, ...rest]
}

/** 取消添加后：将指定记录移到末尾，避免 images[0] 仍显示该候选 */
export function moveStoryboardImageToEnd(images: any[], imageId: string): any[] {
  const id = String(imageId || '').trim()
  if (!id) return images
  const idx = images.findIndex((im) => String(im?.id ?? '') === id)
  if (idx < 0) return images
  const next = [...images]
  const [item] = next.splice(idx, 1)
  next.push(item)
  return next
}

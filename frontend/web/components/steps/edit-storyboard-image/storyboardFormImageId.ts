import { userAssetRpsFormImageList } from '~/utils/businessApi'

/**
 * 通过 form-image/list 解析分镜图对应的形态图实例 ID（需图片带 rpsFormId 或由列表匹配 url/标题）。
 */
export async function resolveStoryboardFormImageId(payload: {
  formId?: number
  imageId?: number
  imageUrl?: string
  imageTitle?: string
}): Promise<number | null> {
  const formId =
    payload.formId != null && Number.isFinite(Number(payload.formId))
      ? Number(payload.formId)
      : null
  if (formId == null) {
    return payload.imageId != null && Number.isFinite(Number(payload.imageId))
      ? Number(payload.imageId)
      : null
  }

  try {
    const list = await userAssetRpsFormImageList({ formId })
    if (!Array.isArray(list) || list.length === 0) return null

    const normalizedInputId =
      payload.imageId != null && Number.isFinite(Number(payload.imageId))
        ? Number(payload.imageId)
        : null
    if (normalizedInputId != null) {
      const hitById = list.find(
        (x: any) => Number.isFinite(Number(x?.id)) && Number(x.id) === normalizedInputId
      )
      if (hitById?.id != null && Number.isFinite(Number(hitById.id))) return Number(hitById.id)
    }

    const normalizedUrl = String(payload.imageUrl || '').trim()
    if (normalizedUrl) {
      const hitByUrl = list.find((x: any) => String(x?.imageUrl || '').trim() === normalizedUrl)
      if (hitByUrl?.id != null && Number.isFinite(Number(hitByUrl.id))) return Number(hitByUrl.id)
    }

    const normalizedTitle = String(payload.imageTitle || '').trim()
    if (normalizedTitle) {
      const hitByName = list.find((x: any) => String(x?.name || '').trim() === normalizedTitle)
      if (hitByName?.id != null && Number.isFinite(Number(hitByName.id)))
        return Number(hitByName.id)
    }

    const fallback = list.find((x: any) => x?.id != null && Number.isFinite(Number(x.id)))
    return fallback?.id != null && Number.isFinite(Number(fallback.id)) ? Number(fallback.id) : null
  } catch {
    return null
  }
}

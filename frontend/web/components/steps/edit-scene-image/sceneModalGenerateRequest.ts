import type { UserAssetExtractFormGenerateCreationImageRequest } from '~/types/business-api'

export function resolveSceneModalGenerationFormId(
  currentImage: Record<string, unknown> | null | undefined,
  activeFormIds: readonly number[]
): number | null {
  const current = Number(currentImage?.rpsFormId)
  if (Number.isFinite(current) && current > 0) return current
  const fallback = Number(activeFormIds[0])
  return Number.isFinite(fallback) && fallback > 0 ? fallback : null
}

export function buildSceneModalGenerateRequest(
  input: UserAssetExtractFormGenerateCreationImageRequest
): UserAssetExtractFormGenerateCreationImageRequest {
  const referenceImages = (input.referenceImages || [])
    .map((url) => String(url || '').trim())
    .filter(Boolean)
  return {
    formId: Number(input.formId),
    genMode: input.genMode,
    ...(referenceImages.length ? { referenceImages } : {}),
    prompt: String(input.prompt || '').trim(),
    modelCode: String(input.modelCode || '').trim(),
    aspectRatio: String(input.aspectRatio || '').trim() || '1:1',
    size: String(input.size || '').trim().toUpperCase() || '2K',
    imageCount: Math.max(1, Math.min(4, Number(input.imageCount) || 1))
  }
}

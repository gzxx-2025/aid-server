import type {
  StoryboardGenerateImageRequest,
  UserStoryboardGenerateEditImageRequest
} from '~/types/business-api'

export function buildStoryboardModalGenerateRequest(
  input: StoryboardGenerateImageRequest
): StoryboardGenerateImageRequest {
  const size = String(input.size || '').trim().toUpperCase()
  const agentCode = String(input.agentCode || '').trim()
  return {
    storyboardIds: (input.storyboardIds || [])
      .map(Number)
      .filter((id) => Number.isFinite(id) && id > 0),
    ...(agentCode ? { agentCode } : {}),
    imagePrompt: String(input.imagePrompt || '').trim(),
    modelName: String(input.modelName || '').trim(),
    aspectRatio: String(input.aspectRatio || '').trim() || '16:9',
    ...(size ? { size } : {}),
    count: Math.max(1, Math.min(8, Number(input.count) || 1))
  }
}

export function buildStoryboardModalEditRequest(
  input: UserStoryboardGenerateEditImageRequest
): UserStoryboardGenerateEditImageRequest {
  return {
    storyboardId: Number(input.storyboardId),
    referenceImage: String(input.referenceImage || '').trim(),
    prompt: String(input.prompt || '').trim(),
    modelCode: String(input.modelCode || '').trim(),
    aspectRatio: String(input.aspectRatio || '').trim() || '16:9',
    size: String(input.size || '').trim().toUpperCase() || '2K',
    imageCount: Math.max(1, Math.min(4, Number(input.imageCount) || 1))
  }
}

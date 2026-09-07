import type {
  BillingQuoteRequest,
  StoryboardGenerateImageRequest,
  StoryboardVideoGenerateRequest,
  StoryboardVideoGridGenerateRequest,
  StoryboardVideoImageGenerateRequest,
  UserAssetExtractFormGenerateCreationImageRequest,
  UserModelListItem
} from '@/types/business-api'
import type { StudioCanvasNode } from '@/types/studio'
import { parseModelCapability } from '@/utils/modelCapability'
import {
  readRecommendedDurationSeconds,
  resolveVideoDurationOption
} from '@/utils/resolveVideoDurationOption'

function positiveInteger(value: unknown, fallback = 1, max = 8): number {
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed <= 0) return fallback
  return Math.min(max, Math.max(1, Math.floor(parsed)))
}

function positiveDuration(value: unknown): number | undefined {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
}

export function buildStudioStoryboardImageSubmission(input: {
  node: StudioCanvasNode
  model: UserModelListItem
  storyboardId: number
  prompt: string
}): {
  body: StoryboardGenerateImageRequest
  billingRequest: BillingQuoteRequest
} {
  const options = input.node.data.generationOptions ?? {}
  const body: StoryboardGenerateImageRequest = {
    storyboardIds: [input.storyboardId],
    imagePrompt: input.prompt,
    modelName: input.model.modelCode,
    aspectRatio: options.aspectRatio || input.model.defaultAspectRatio || undefined,
    size: options.quality || input.model.defaultSizeCode || undefined,
    count: positiveInteger(options.count, 1, 8),
    negativePrompt: input.node.data.negativePrompt
  }
  return {
    body,
    billingRequest: { quoteType: 'STORYBOARD_IMAGE', payload: { ...body } }
  }
}

export function buildStudioStoryboardEditImageSubmission(input: {
  node: StudioCanvasNode
  model: UserModelListItem
  storyboardId: number
  prompt: string
  /** 覆盖节点 mediaUrl（如擦除/重绘带标记的合成图） */
  referenceImage?: string
}): {
  body: {
    storyboardId: number
    referenceImage: string
    prompt: string
    modelCode: string
    aspectRatio: string
    size: string
    imageCount: number
  }
  billingRequest: BillingQuoteRequest
} | null {
  const referenceImage = String(input.referenceImage || input.node.data.mediaUrl || '').trim()
  if (!referenceImage) return null
  const options = input.node.data.generationOptions ?? {}
  const body = {
    storyboardId: input.storyboardId,
    referenceImage,
    prompt: input.prompt,
    modelCode: input.model.modelCode,
    aspectRatio: options.aspectRatio || input.model.defaultAspectRatio || '1:1',
    size: options.quality || input.model.defaultSizeCode || '2K',
    imageCount: positiveInteger(options.count, 1, 4)
  }
  return {
    body,
    billingRequest: { quoteType: 'STORYBOARD_EDIT_IMAGE', payload: { ...body } }
  }
}

export function buildStudioRpsImageSubmission(input: {
  node: StudioCanvasNode
  model: UserModelListItem
  formId: number
  prompt: string
}): {
  body: UserAssetExtractFormGenerateCreationImageRequest
  billingRequest: BillingQuoteRequest
} {
  const referenceImages = input.node.data.mediaUrl ? [input.node.data.mediaUrl] : undefined
  const options = input.node.data.generationOptions ?? {}
  const body: UserAssetExtractFormGenerateCreationImageRequest = {
    formId: input.formId,
    genMode: referenceImages?.length ? 'edit' : 'chat',
    ...(referenceImages?.length ? { referenceImages } : {}),
    prompt: input.prompt,
    modelCode: input.model.modelCode,
    aspectRatio: options.aspectRatio || input.model.defaultAspectRatio || '1:1',
    size: options.quality || input.model.defaultSizeCode || '2K',
    imageCount: positiveInteger(options.count, 1, 4)
  }
  return {
    body,
    billingRequest: { quoteType: 'FORM_EDIT_CHAT_IMAGE', payload: { ...body } }
  }
}

type StudioStoryboardVideoSubmission =
  | {
      kind: 'grid'
      body: StoryboardVideoGridGenerateRequest
      billingRequest: BillingQuoteRequest
    }
  | {
      kind: 'image'
      body: StoryboardVideoImageGenerateRequest
      billingRequest: BillingQuoteRequest
    }
  | {
      kind: 'multi'
      body: StoryboardVideoGenerateRequest
      billingRequest: BillingQuoteRequest
    }

export function buildStudioStoryboardVideoSubmission(input: {
  node: StudioCanvasNode
  model: UserModelListItem
  storyboardId: number
  prompt: string
  creationMode: string
  needsImage: boolean
  storyboardImageUrl?: string | null
}): StudioStoryboardVideoSubmission {
  const options = input.node.data.generationOptions ?? {}
  const capability = parseModelCapability(input.model)
  const recommendedDurationSeconds = readRecommendedDurationSeconds(input.node.data)
  const selectedDuration = recommendedDurationSeconds == null
    ? options.duration
    : resolveVideoDurationOption({
        recommendedDurationSeconds,
        durationOptions: capability.durationOptions,
        defaultDurationSeconds: capability.defaultDurationSeconds
      })
  const durationSeconds = capability.supportsDuration
    ? positiveDuration(selectedDuration)
    : undefined
  const common: StoryboardVideoGenerateRequest = {
    storyboardIds: [input.storyboardId],
    videoPrompt: input.prompt,
    modelName: input.model.modelCode,
    aspectRatio: options.aspectRatio || input.model.defaultAspectRatio || undefined,
    resolution: options.quality || input.model.defaultSizeCode || undefined,
    ...(durationSeconds ? { durationSeconds } : {}),
    count: positiveInteger(options.count, 1, 4)
  }

  if (input.creationMode === 'auto_grid') {
    const body: StoryboardVideoGridGenerateRequest = { ...common }
    return {
      kind: 'grid',
      body,
      billingRequest: { quoteType: 'STORYBOARD_VIDEO_GRID', payload: { ...body } }
    }
  }

  if (input.needsImage) {
    const storyboardImageUrl = String(input.storyboardImageUrl || '').trim()
    const body: StoryboardVideoImageGenerateRequest = {
      ...common,
      ...(storyboardImageUrl ? { images: [storyboardImageUrl] } : {})
    }
    return {
      kind: 'image',
      body,
      billingRequest: { quoteType: 'STORYBOARD_VIDEO_IMAGE', payload: { ...body } }
    }
  }

  const body: StoryboardVideoGenerateRequest = { ...common }
  return {
    kind: 'multi',
    body,
    billingRequest: { quoteType: 'STORYBOARD_VIDEO', payload: { ...body } }
  }
}

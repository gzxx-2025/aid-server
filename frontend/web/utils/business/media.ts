/** 媒体生成域：分镜脚本/图/视频提示词批量生成、画面与视频出片、编辑图/高清/多机位宫格、点选改图。 */
import type {
ApiEnvelope,
StoryboardGenerateGridVideoWithPromptData,
StoryboardGenerateGridVideoWithPromptRequest,
StoryboardGenerateImageData,
StoryboardGenerateImagePromptData,
StoryboardGenerateImagePromptRequest,
StoryboardGenerateImagePromptResumeData,
StoryboardGenerateImagePromptResumeRequest,
StoryboardGenerateImageRequest,
StoryboardGenerateImageResumeRequest,
StoryboardGenerateImageWithPromptData,
StoryboardGenerateImageWithPromptRequest,
StoryboardGenerateMediaData,
StoryboardGenerateMediaRequest,
StoryboardGenerateScriptData,
StoryboardGenerateScriptRequest,
StoryboardGenerateScriptResumeData,
StoryboardGenerateScriptResumeRequest,
StoryboardGenerateVideoPromptData,
StoryboardGenerateVideoPromptGridData,
StoryboardGenerateVideoPromptGridRequest,
StoryboardGenerateVideoPromptImageData,
StoryboardGenerateVideoPromptImageRequest,
StoryboardGenerateVideoPromptRequest,
StoryboardGenerateVideoPromptResumeData,
StoryboardGenerateVideoPromptResumeRequest,
StoryboardGenerateVideoWithPromptData,
StoryboardGenerateVideoWithPromptRequest,
StoryboardVideoEdgeGenerateData,
StoryboardVideoEdgeGenerateRequest,
StoryboardVideoGenerateData,
StoryboardVideoGenerateRequest,
StoryboardVideoGridGenerateData,
StoryboardVideoGridGenerateRequest,
StoryboardVideoImageGenerateData,
StoryboardVideoImageGenerateRequest,
StoryboardVideoResumeRequest,
TouchEditPointDetectData,
TouchEditPointDetectRequest,
UserStoryboardGenerateEditImageData,
UserStoryboardGenerateEditImageRequest,
UserStoryboardGenerateMultiViewGridImageData,
UserStoryboardGenerateMultiViewGridImageRequest,
UserStoryboardGenerateUpscaleData,
UserStoryboardGenerateUpscaleRequest,
UserTaskResumeData
} from '~/types/business-api'
import { request } from '~/utils/api'
import { unwrap } from '~/utils/business/shared'
import { userTaskResume } from '~/utils/business/task'

/**
 * 分镜机位生图（v2.62）：POST /api/user/storyboard/generate/multi-view-grid-image
 * angles 长度 1 → 单机位；长度 9 → 九宫格。
 */
export async function userStoryboardGenerateMultiViewGridImage(
  body: UserStoryboardGenerateMultiViewGridImageRequest
): Promise<UserStoryboardGenerateMultiViewGridImageData> {
  const res = await request.post<ApiEnvelope<UserStoryboardGenerateMultiViewGridImageData>>(
    '/api/user/storyboard/generate/multi-view-grid-image',
    body
  )
  return unwrap(res)
}

/** 点选改图：按坐标识别编辑目标（如空调、窗户等） */
export async function userTouchEditPointDetect(
  body: TouchEditPointDetectRequest
): Promise<TouchEditPointDetectData> {
  const res = await request.post<ApiEnvelope<TouchEditPointDetectData>>('/api/user/media/touch/edit/point', body)
  return unwrap(res)
}

/** 分镜工作台：批量生成分镜脚本（异步任务）POST /api/user/storyboard/generate/script */
export async function userStoryboardGenerateScript(
  body: StoryboardGenerateScriptRequest
): Promise<StoryboardGenerateScriptData> {
  const res = await request.post<ApiEnvelope<StoryboardGenerateScriptData>>(
    '/api/user/storyboard/generate/script',
    body
  )
  return unwrap(res)
}

/** @deprecated 请使用 userTaskResume */
export async function userStoryboardGenerateScriptResume(
  body: StoryboardGenerateScriptResumeRequest
): Promise<StoryboardGenerateScriptResumeData> {
  return userTaskResume(body)
}

/** 分镜工作台：批量生成分镜图脚本 POST /api/user/storyboard/generate/image-prompt */
export async function userStoryboardGenerateImagePrompt(
  body: StoryboardGenerateImagePromptRequest
): Promise<StoryboardGenerateImagePromptData> {
  const res = await request.post<ApiEnvelope<StoryboardGenerateImagePromptData>>(
    '/api/user/storyboard/generate/image-prompt',
    body
  )
  return unwrap(res)
}

/** @deprecated 请使用 userTaskResume */
export async function userStoryboardGenerateImagePromptResume(
  body: StoryboardGenerateImagePromptResumeRequest
): Promise<StoryboardGenerateImagePromptResumeData> {
  return userTaskResume(body)
}

/** 分镜工作台：批量生成分镜图提示词 + 自动出图 POST /api/user/storyboard/generate/image-with-prompt（任务3） */
export async function userStoryboardGenerateImageWithPrompt(
  body: StoryboardGenerateImageWithPromptRequest
): Promise<StoryboardGenerateImageWithPromptData> {
  const res = await request.post<ApiEnvelope<StoryboardGenerateImageWithPromptData>>(
    '/api/user/storyboard/generate/image-with-prompt',
    body
  )
  return unwrap(res)
}

/** 分镜工作台：批量生成分镜视频提示词 POST /api/user/storyboard/generate/video-prompt（v3.0） */
export async function userStoryboardGenerateVideoPrompt(
  body: StoryboardGenerateVideoPromptRequest
): Promise<StoryboardGenerateVideoPromptData> {
  const res = await request.post<ApiEnvelope<StoryboardGenerateVideoPromptData>>(
    '/api/user/storyboard/generate/video-prompt',
    body
  )
  return unwrap(res)
}

/** 分镜工作台：批量生成图生方向分镜视频提示词 POST /api/user/storyboard/generate/video-prompt-image */
export async function userStoryboardGenerateVideoPromptImage(
  body: StoryboardGenerateVideoPromptImageRequest
): Promise<StoryboardGenerateVideoPromptImageData> {
  const res = await request.post<ApiEnvelope<StoryboardGenerateVideoPromptImageData>>(
    '/api/user/storyboard/generate/video-prompt-image',
    body
  )
  return unwrap(res)
}

/** 分镜工作台：批量生成宫格方向分镜视频提示词 POST /api/user/storyboard/generate/video-prompt-grid */
export async function userStoryboardGenerateVideoPromptGrid(
  body: StoryboardGenerateVideoPromptGridRequest
): Promise<StoryboardGenerateVideoPromptGridData> {
  const res = await request.post<ApiEnvelope<StoryboardGenerateVideoPromptGridData>>(
    '/api/user/storyboard/generate/video-prompt-grid',
    body
  )
  return unwrap(res)
}

/** @deprecated 请使用 userTaskResume */
export async function userStoryboardGenerateVideoPromptResume(
  body: StoryboardGenerateVideoPromptResumeRequest
): Promise<StoryboardGenerateVideoPromptResumeData> {
  return userTaskResume(body)
}

/** 分镜工作台：批量生成分镜视频提示词 + 自动出片 POST /api/user/storyboard/generate/video-with-prompt（任务4，按创作模式自动路由） */
export async function userStoryboardGenerateVideoWithPrompt(
  body: StoryboardGenerateVideoWithPromptRequest
): Promise<StoryboardGenerateVideoWithPromptData> {
  const res = await request.post<ApiEnvelope<StoryboardGenerateVideoWithPromptData>>(
    '/api/user/storyboard/generate/video-with-prompt',
    body
  )
  return unwrap(res)
}

/** 分镜工作台：批量生成宫格视频提示词 + 自动出宫格视频 POST /api/user/storyboard/generate/grid-video-with-prompt（仅 auto_grid） */
export async function userStoryboardGenerateGridVideoWithPrompt(
  body: StoryboardGenerateGridVideoWithPromptRequest
): Promise<StoryboardGenerateGridVideoWithPromptData> {
  const res = await request.post<ApiEnvelope<StoryboardGenerateGridVideoWithPromptData>>(
    '/api/user/storyboard/generate/grid-video-with-prompt',
    body
  )
  return unwrap(res)
}

/** 分镜工作台：发起画面生成/抽卡 POST /api/user/storyboard/generate/media */
export async function userStoryboardGenerateMedia(
  body: StoryboardGenerateMediaRequest
): Promise<StoryboardGenerateMediaData> {
  const res = await request.post<ApiEnvelope<StoryboardGenerateMediaData>>(
    '/api/user/storyboard/generate/media',
    body
  )
  return unwrap(res)
}

/** 多参生视频（v2.58）：POST /api/user/storyboard/generate/video */
export async function userStoryboardGenerateVideo(
  body: StoryboardVideoGenerateRequest
): Promise<StoryboardVideoGenerateData> {
  const res = await request.post<ApiEnvelope<StoryboardVideoGenerateData>>(
    '/api/user/storyboard/generate/video',
    body
  )
  return unwrap(res)
}

/** 图生视频出片（图生方向）：POST /api/user/storyboard/generate/video/image */
export async function userStoryboardGenerateVideoImage(
  body: StoryboardVideoImageGenerateRequest
): Promise<StoryboardVideoImageGenerateData> {
  const res = await request.post<ApiEnvelope<StoryboardVideoImageGenerateData>>(
    '/api/user/storyboard/generate/video/image',
    body
  )
  return unwrap(res)
}

/** 首尾帧生视频出片：POST /api/user/storyboard/generate/video/edge */
export async function userStoryboardGenerateVideoEdge(
  body: StoryboardVideoEdgeGenerateRequest
): Promise<StoryboardVideoEdgeGenerateData> {
  const res = await request.post<ApiEnvelope<StoryboardVideoEdgeGenerateData>>(
    '/api/user/storyboard/generate/video/edge',
    body
  )
  return unwrap(res)
}

/** 宫格生视频出片：POST /api/user/storyboard/generate/video/grid */
export async function userStoryboardGenerateVideoGrid(
  body: StoryboardVideoGridGenerateRequest
): Promise<StoryboardVideoGridGenerateData> {
  const res = await request.post<ApiEnvelope<StoryboardVideoGridGenerateData>>(
    '/api/user/storyboard/generate/video/grid',
    body
  )
  return unwrap(res)
}

/** @deprecated 请使用 userTaskResume */
export async function userStoryboardGenerateVideoResume(
  body: StoryboardVideoResumeRequest
): Promise<StoryboardVideoGenerateData> {
  return userTaskResume(body) as Promise<StoryboardVideoGenerateData>
}

/** @deprecated 请使用 userTaskResume */
export async function userStoryboardGenerateImageResume(
  body: StoryboardGenerateImageResumeRequest
): Promise<UserTaskResumeData> {
  return userTaskResume(body)
}

/** 分镜工作台：生成分镜图 POST /api/user/storyboard/generate/image（v2.63+ 批量父任务） */
export async function userStoryboardGenerateImage(
  body: StoryboardGenerateImageRequest
): Promise<StoryboardGenerateImageData> {
  const res = await request.post<ApiEnvelope<StoryboardGenerateImageData>>(
    '/api/user/storyboard/generate/image',
    body
  )
  return unwrap(res)
}

/** 分镜编辑图 / 对话作图（v2.57）：POST /api/user/storyboard/generate/edit-image */
export async function userStoryboardGenerateEditImage(
  body: UserStoryboardGenerateEditImageRequest
): Promise<UserStoryboardGenerateEditImageData> {
  const res = await request.post<ApiEnvelope<UserStoryboardGenerateEditImageData>>(
    '/api/user/storyboard/generate/edit-image',
    body
  )
  return unwrap(res)
}

/** 分镜图高清（v2.57）：POST /api/user/storyboard/generate/upscale */
export async function userStoryboardGenerateUpscale(
  body: UserStoryboardGenerateUpscaleRequest
): Promise<UserStoryboardGenerateUpscaleData> {
  const res = await request.post<ApiEnvelope<UserStoryboardGenerateUpscaleData>>(
    '/api/user/storyboard/generate/upscale',
    body
  )
  return unwrap(res)
}

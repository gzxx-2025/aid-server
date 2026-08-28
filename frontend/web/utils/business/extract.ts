/** 资产提取与形态生成域：提取费用预估/提交/取消/续生、形态生成、形态图/设定卡/多机位/创作图生成。 */
import type {
ApiEnvelope,
UserAssetExtractCancelRequest,
UserAssetExtractEstimateData,
UserAssetExtractEstimateRequest,
UserAssetExtractFormGenerateCardImageData,
UserAssetExtractFormGenerateCardImageRequest,
UserAssetExtractFormGenerateCreationImageData,
UserAssetExtractFormGenerateCreationImageRequest,
UserAssetExtractFormGenerateData,
UserAssetExtractFormGenerateEditImageData,
UserAssetExtractFormGenerateEditImageRequest,
UserAssetExtractFormGenerateImageData,
UserAssetExtractFormGenerateImageRequest,
UserAssetExtractFormGenerateMultiViewImageData,
UserAssetExtractFormGenerateMultiViewImageRequest,
UserAssetExtractFormGenerateRequest,
UserAssetExtractParallelRequest,
UserAssetExtractResumeData,
UserAssetExtractResumeRequest,
UserAssetExtractStoryboardGenerateMultiViewGridImageData,
UserAssetExtractStoryboardGenerateMultiViewGridImageRequest,
UserTaskRow
} from '~/types/business-api'
import { request } from '~/utils/api'
import { unwrap } from '~/utils/business/shared'
import { userTaskCancel,userTaskResume } from '~/utils/business/task'

/** 多机位形态生图（v2.35.0 真实接口）：POST /api/user/asset/extract/form/generate-multi-view-image */
export async function userAssetExtractFormGenerateMultiViewImage(
  body: UserAssetExtractFormGenerateMultiViewImageRequest
): Promise<UserAssetExtractFormGenerateMultiViewImageData> {
  const res = await request.post<ApiEnvelope<UserAssetExtractFormGenerateMultiViewImageData>>(
    '/api/user/asset/extract/form/generate-multi-view-image',
    body
  )
  return unwrap(res)
}

/**
 * @deprecated v2.62 请改用 userStoryboardGenerateMultiViewGridImage
 */
export async function userAssetExtractStoryboardGenerateMultiViewGridImage(
  body: UserAssetExtractStoryboardGenerateMultiViewGridImageRequest
): Promise<UserAssetExtractStoryboardGenerateMultiViewGridImageData> {
  const res = await request.post<ApiEnvelope<UserAssetExtractStoryboardGenerateMultiViewGridImageData>>(
    '/api/user/asset/extract/storyboard/generate-multi-view-grid-image',
    body
  )
  return unwrap(res)
}

/** 形态图片创作（编辑图片 / 对话作图）：POST /api/user/asset/extract/form/generate-creation-image */
export async function userAssetExtractFormGenerateCreationImage(
  body: UserAssetExtractFormGenerateCreationImageRequest
): Promise<UserAssetExtractFormGenerateCreationImageData> {
  const res = await request.post<ApiEnvelope<UserAssetExtractFormGenerateCreationImageData>>(
    '/api/user/asset/extract/form/generate-creation-image',
    body
  )
  return unwrap(res)
}

/** @deprecated 请改用 userAssetExtractFormGenerateCreationImage */
export async function userAssetExtractFormGenerateEditImage(
  body: UserAssetExtractFormGenerateEditImageRequest
): Promise<UserAssetExtractFormGenerateEditImageData> {
  return userAssetExtractFormGenerateCreationImage(body)
}

/** 资产提取费用预估：POST /api/user/asset/extract/estimate */
export async function userAssetExtractEstimate(
  body: UserAssetExtractEstimateRequest
): Promise<UserAssetExtractEstimateData> {
  const res = await request.post<ApiEnvelope<UserAssetExtractEstimateData>>(
    '/api/user/asset/extract/estimate',
    body
  )
  return unwrap(res)
}

/** 资产提取任务提交：POST /api/user/asset/extract/parallel */
export async function userAssetExtractParallel(
  body: UserAssetExtractParallelRequest
): Promise<UserTaskRow> {
  const res = await request.post<ApiEnvelope<UserTaskRow>>('/api/user/asset/extract/parallel', body)
  return unwrap(res)
}

/** 取消任务：POST /api/user/asset/extract/cancel — @deprecated 请使用 userTaskCancel */
export async function userAssetExtractCancel(body: UserAssetExtractCancelRequest): Promise<unknown> {
  return userTaskCancel({ taskId: body.taskId })
}

/** @deprecated 请使用 userTaskResume */
export async function userAssetExtractResume(
  body: UserAssetExtractResumeRequest
): Promise<UserAssetExtractResumeData> {
  return userTaskResume(body)
}

/** 资产形态生成（父任务 form_generate_batch）：POST /api/user/asset/extract/form/generate */
export async function userAssetExtractFormGenerate(
  body: UserAssetExtractFormGenerateRequest
): Promise<UserAssetExtractFormGenerateData> {
  const res = await request.post<ApiEnvelope<UserAssetExtractFormGenerateData>>(
    '/api/user/asset/extract/form/generate',
    body
  )
  return unwrap(res)
}

/** 形态图生成（父任务 form_image_batch，v2.41+ 纯文生图）：POST /api/user/asset/extract/form/generate-image */
export async function userAssetExtractFormGenerateImage(
  body: UserAssetExtractFormGenerateImageRequest
): Promise<UserAssetExtractFormGenerateImageData> {
  const res = await request.post<ApiEnvelope<UserAssetExtractFormGenerateImageData>>(
    '/api/user/asset/extract/form/generate-image',
    body
  )
  return unwrap(res)
}

/** 角色设定卡生成（第二阶段 form_card_image_batch）：POST /api/user/asset/extract/form/generate-card-image */
export async function userAssetExtractFormGenerateCardImage(
  body: UserAssetExtractFormGenerateCardImageRequest
): Promise<UserAssetExtractFormGenerateCardImageData> {
  const res = await request.post<ApiEnvelope<UserAssetExtractFormGenerateCardImageData>>(
    '/api/user/asset/extract/form/generate-card-image',
    body
  )
  return unwrap(res)
}

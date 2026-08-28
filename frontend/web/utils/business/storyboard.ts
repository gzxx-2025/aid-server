/** 分镜工作台域：分镜增删改查/排序、提示词占位解析、最终产物设置、生成记录、用户上传媒体。 */
import type {
ApiEnvelope,
StoryboardAudioReferenceItem,
StoryboardImagePromptReferenceItem,
StoryboardImagePromptResolveData,
StoryboardImagePromptResolveRequest,
StoryboardRecordDeleteRequest,
StoryboardRecordListByStoryboardRequest,
StoryboardRecordRow,
StoryboardSaveVideoPromptRequest,
StoryboardSetFinalImageBatchData,
StoryboardSetFinalImageRequest,
StoryboardSetFinalRequest,
StoryboardSetFinalVideoRequest,
StoryboardUnSetFinalAudioRequest,
StoryboardUnSetFinalImageRequest,
StoryboardUnSetFinalVideoRequest,
StoryboardUploadData,
StoryboardUploadImageData,
StoryboardUploadImageRequest,
StoryboardUploadRequest,
StoryboardUploadVideoData,
StoryboardUploadVideoRequest,
UserStoryboardCreateData,
UserStoryboardCreateRequest,
UserStoryboardDeleteRequest,
UserStoryboardDetailRequest,
UserStoryboardListRequest,
UserStoryboardListRow,
UserStoryboardSortRequest,
UserStoryboardUpdateRequest
} from '~/types/business-api'
import { request } from '~/utils/api'
import {
runListDedupe,
stableRequestKey,
unwrap,
type ListBurstSlot
} from '~/utils/business/shared'

const storyboardListInflight = new Map<string, Promise<UserStoryboardListRow[]>>()
const storyboardListBurst: ListBurstSlot<UserStoryboardListRow[]> = { current: null }

/** 分镜写操作完成后清理列表并发/短时缓存，确保随后刷新读取最新副本内容。 */
export function invalidateStoryboardListCache(): void {
  storyboardListInflight.clear()
  storyboardListBurst.current = null
}

const imagePromptResolveInflight = new Map<string, Promise<StoryboardImagePromptResolveData>>()
const imagePromptResolveBurst: ListBurstSlot<StoryboardImagePromptResolveData> = { current: null }

/** 分镜工作台：查询分镜列表 POST /api/user/storyboard/list */
export async function userStoryboardList(body: UserStoryboardListRequest): Promise<UserStoryboardListRow[]> {
  const key = stableRequestKey(body)
  return runListDedupe(key, storyboardListInflight, storyboardListBurst, async () => {
    const res = await request.post<ApiEnvelope<UserStoryboardListRow[]>>(
      '/api/user/storyboard/list',
      body
    )
    const data = unwrap(res)
    return Array.isArray(data) ? data : []
  })
}

/** 分镜工作台：查询分镜详情 POST /api/user/storyboard/detail */
export async function userStoryboardDetail(body: UserStoryboardDetailRequest): Promise<UserStoryboardListRow> {
  const res = await request.post<ApiEnvelope<UserStoryboardListRow>>('/api/user/storyboard/detail', body)
  return unwrap(res)
}

/** 分镜工作台：新增分镜 POST /api/user/storyboard/create */
export async function userStoryboardCreate(body: UserStoryboardCreateRequest): Promise<UserStoryboardCreateData> {
  const res = await request.post<ApiEnvelope<UserStoryboardCreateData>>('/api/user/storyboard/create', body)
  const data = unwrap(res)
  invalidateStoryboardListCache()
  return data
}

/** 分镜工作台：删除分镜 POST /api/user/storyboard/delete（返回实际软删除条数） */
export async function userStoryboardDelete(body: UserStoryboardDeleteRequest): Promise<number> {
  const res = await request.post<ApiEnvelope<number>>('/api/user/storyboard/delete', body)
  const data = unwrap(res)
  invalidateStoryboardListCache()
  return data
}

/** 分镜工作台：更新分镜配置 POST /api/user/storyboard/update */
export async function userStoryboardUpdate(body: UserStoryboardUpdateRequest): Promise<void> {
  await request.post<ApiEnvelope<unknown>>('/api/user/storyboard/update', body)
  invalidateStoryboardListCache()
}

/** @deprecated 请使用 userStoryboardUpdate */
export async function userStoryboardSave(body: UserStoryboardUpdateRequest): Promise<void> {
  return userStoryboardUpdate(body)
}

/** 分镜工作台：批量调整排序 POST /api/user/storyboard/sort */
export async function userStoryboardSort(body: UserStoryboardSortRequest): Promise<void> {
  await request.post<ApiEnvelope<unknown>>('/api/user/storyboard/sort', body)
  invalidateStoryboardListCache()
}

/** 分镜工作台：手动保存分镜视频提示词 POST /api/user/storyboard/save/video-prompt（v3.0） */
export async function userStoryboardSaveVideoPrompt(body: StoryboardSaveVideoPromptRequest): Promise<void> {
  await request.post<ApiEnvelope<unknown>>('/api/user/storyboard/save/video-prompt', body)
}

/** 分镜工作台：设置分镜最终产物 POST /api/user/storyboard/setFinal */
export async function userStoryboardSetFinal(body: StoryboardSetFinalRequest): Promise<void> {
  await request.post<ApiEnvelope<unknown>>('/api/user/storyboard/setFinal', body)
}

/** 分镜工作台：解析 image_prompt 中的 @图片N[name] POST /api/user/storyboard/image-prompt/resolve */
export async function userStoryboardImagePromptResolve(
  body: StoryboardImagePromptResolveRequest
): Promise<StoryboardImagePromptResolveData> {
  const key = stableRequestKey(body)
  return runListDedupe(key, imagePromptResolveInflight, imagePromptResolveBurst, async () => {
    const res = await request.post<ApiEnvelope<StoryboardImagePromptResolveData>>(
      '/api/user/storyboard/image-prompt/resolve',
      body
    )
    const data = unwrap(res)
    const references: StoryboardImagePromptReferenceItem[] = Array.isArray(data?.references)
      ? data.references
      : []
    const referenceImageIds = Array.isArray(data?.referenceImageIds)
      ? data.referenceImageIds
      : references
          .map((r) => Number(r?.imageId))
          .filter((id) => Number.isFinite(id) && id > 0)
    const referenceImageUrls = Array.isArray(data?.referenceImageUrls)
      ? data.referenceImageUrls
      : references.map((r) => String(r?.url || '').trim()).filter(Boolean)
    const audioReferences: StoryboardAudioReferenceItem[] = Array.isArray(data?.audioReferences)
      ? data.audioReferences
      : []
    return {
      referenceImageIds,
      referenceImageUrls,
      unresolvedNames: Array.isArray(data?.unresolvedNames) ? data.unresolvedNames : [],
      references,
      referenceAudioUrls: Array.isArray(data?.referenceAudioUrls) ? data.referenceAudioUrls : [],
      audioReferences,
      unresolvedAudioNames: Array.isArray(data?.unresolvedAudioNames) ? data.unresolvedAudioNames : [],
      unresolvedAudioRecordIds: Array.isArray(data?.unresolvedAudioRecordIds)
        ? data.unresolvedAudioRecordIds
        : [],
      unresolvedReferenceAudioIds: Array.isArray(data?.unresolvedReferenceAudioIds)
        ? data.unresolvedReferenceAudioIds
        : []
    }
  })
}

/** 分镜工作台：项目内生成内容列表 POST /api/user/storyboard/record/list-by-storyboard（v2.57.7） */
export async function userStoryboardRecordListByStoryboard(
  body: StoryboardRecordListByStoryboardRequest
): Promise<StoryboardRecordRow[]> {
  const res = await request.post<ApiEnvelope<StoryboardRecordRow[]>>(
    '/api/user/storyboard/record/list-by-storyboard',
    body
  )
  const data = unwrap(res)
  return Array.isArray(data) ? data : []
}

/** 分镜工作台：物理删除分镜生成记录 POST /api/user/storyboard/record/delete */
export async function userStoryboardRecordDelete(body: StoryboardRecordDeleteRequest): Promise<void> {
  await request.post<ApiEnvelope<unknown>>('/api/user/storyboard/record/delete', body)
}

/** 分镜工作台：设置分镜最终图片 POST /api/user/storyboard/setFinalImage（支持单个 / 批量） */
export async function userStoryboardSetFinalImage(
  body: StoryboardSetFinalImageRequest
): Promise<StoryboardSetFinalImageBatchData | null> {
  const res = await request.post<ApiEnvelope<StoryboardSetFinalImageBatchData>>(
    '/api/user/storyboard/setFinalImage',
    body
  )
  return unwrap(res) ?? null
}

/** 分镜工作台：取消分镜最终图片 POST /api/user/storyboard/unSetFinalImage（v2.58） */
export async function userStoryboardUnSetFinalImage(
  body: StoryboardUnSetFinalImageRequest
): Promise<StoryboardSetFinalImageBatchData | null> {
  const res = await request.post<ApiEnvelope<StoryboardSetFinalImageBatchData>>(
    '/api/user/storyboard/unSetFinalImage',
    body
  )
  return unwrap(res) ?? null
}

/** 分镜工作台：设置分镜最终视频 POST /api/user/storyboard/setFinalVideo（v2.62，支持单个 / 批量） */
export async function userStoryboardSetFinalVideo(
  body: StoryboardSetFinalVideoRequest
): Promise<StoryboardSetFinalImageBatchData | null> {
  const res = await request.post<ApiEnvelope<StoryboardSetFinalImageBatchData>>(
    '/api/user/storyboard/setFinalVideo',
    body
  )
  return unwrap(res) ?? null
}

/** 分镜工作台：取消分镜最终视频 POST /api/user/storyboard/unSetFinalVideo（v2.62） */
export async function userStoryboardUnSetFinalVideo(
  body: StoryboardUnSetFinalVideoRequest
): Promise<StoryboardSetFinalImageBatchData | null> {
  const res = await request.post<ApiEnvelope<StoryboardSetFinalImageBatchData>>(
    '/api/user/storyboard/unSetFinalVideo',
    body
  )
  return unwrap(res) ?? null
}

/** 分镜工作台：取消分镜最终配音 POST /api/user/storyboard/unSetFinalAudio（与 unSetFinalVideo 对称） */
export async function userStoryboardUnSetFinalAudio(body: StoryboardUnSetFinalAudioRequest): Promise<void> {
  await request.post<ApiEnvelope<unknown>>('/api/user/storyboard/unSetFinalAudio', body)
}

/** 分镜工作台：用户自行上传分镜媒体 POST /api/user/storyboard/upload（图片 / 视频） */
export async function userStoryboardUpload(
  body: StoryboardUploadRequest
): Promise<StoryboardUploadData> {
  const res = await request.post<ApiEnvelope<StoryboardUploadData>>(
    '/api/user/storyboard/upload',
    body
  )
  return unwrap(res)
}

/** @deprecated 请使用 userStoryboardUpload({ ..., mediaType: 'image', imageUrl }) */
export async function userStoryboardUploadImage(
  body: StoryboardUploadImageRequest
): Promise<StoryboardUploadImageData> {
  return userStoryboardUpload({
    projectId: body.projectId,
    episodeId: body.episodeId,
    storyboardId: body.storyboardId,
    imageUrl: body.imageUrl,
    mediaType: 'image'
  })
}

/** @deprecated 请使用 userStoryboardUpload({ ..., mediaType: 'video', imageUrl }) */
export async function userStoryboardUploadVideo(
  body: StoryboardUploadVideoRequest
): Promise<StoryboardUploadVideoData> {
  return userStoryboardUpload({
    projectId: body.projectId,
    episodeId: body.episodeId,
    storyboardId: body.storyboardId,
    imageUrl: String(body.imageUrl || body.videoUrl || ''),
    mediaType: 'video',
    ...(body.videoDuration != null ? { videoDuration: body.videoDuration } : {})
  })
}

/** 配音与音频域：AI 配音/批量配音、对口型、音画同步、语音试听、音色库、参考音频。 */
import type {
ApiEnvelope,
ReferenceAudioDeleteRequest,
ReferenceAudioUploadRequest,
ReferenceAudioVO,
StoryboardAudioBatchAcceptVO,
StoryboardAudioBatchRequest,
StoryboardAudioTaskVO,
StoryboardDubbingTaskVO,
StoryboardGenerateAudioRequest,
StoryboardGenerateDubbingRequest,
StoryboardLipSyncAcceptVO,
StoryboardLipSyncBatchAcceptVO,
StoryboardLipSyncBatchRequest,
StoryboardLipSyncRequest,
VoicePreviewRequest,
VoicePreviewResult,
VoiceTagBundleData
} from '~/types/business-api'
import { request } from '~/utils/api'
import { runListDedupe,unwrap,type ListBurstSlot } from '~/utils/business/shared'

/** 登记用户上传参考音频 POST /api/user/reference-audio/upload */
export async function userReferenceAudioUpload(
  body: ReferenceAudioUploadRequest
): Promise<ReferenceAudioVO> {
  const res = await request.post<ApiEnvelope<ReferenceAudioVO>>(
    '/api/user/reference-audio/upload',
    body
  )
  return unwrap(res) as ReferenceAudioVO
}

/** 逻辑删除参考音频 POST /api/user/reference-audio/delete */
export async function userReferenceAudioDelete(body: ReferenceAudioDeleteRequest): Promise<void> {
  await request.post<ApiEnvelope<unknown>>('/api/user/reference-audio/delete', body)
}

/**
 * 分镜工作台：发起 AI 配音 POST /api/user/storyboard/generate/audio
 * 正常同步返回 status=SUCCEEDED + audioUrl；仅兜底 PROCESSING 时需轮询 audio/{taskId}。
 * ttsText 出参为服务端清洗后的正文；另含 durationMs、lipSyncStatus。
 */
export async function userStoryboardGenerateAudio(
  body: StoryboardGenerateAudioRequest
): Promise<StoryboardAudioTaskVO> {
  const res = await request.post<ApiEnvelope<StoryboardAudioTaskVO>>(
    '/api/user/storyboard/generate/audio',
    body
  )
  return unwrap(res)
}

/** 分镜工作台：查询配音/对口型任务 GET /api/user/storyboard/audio/{taskId} */
export async function userStoryboardAudioTask(taskId: number): Promise<StoryboardAudioTaskVO> {
  const res = await request.get<ApiEnvelope<StoryboardAudioTaskVO>>(`/api/user/storyboard/audio/${taskId}`)
  return unwrap(res)
}

/**
 * 分镜工作台：发起对口型合成（受理型异步）POST /api/user/storyboard/lipSync
 * 返回 taskId，进度走 GET /api/user/task/stream/{taskId}（与批量对口型同通道）。
 */
export async function userStoryboardLipSync(
  body: StoryboardLipSyncRequest
): Promise<StoryboardLipSyncAcceptVO> {
  const res = await request.post<ApiEnvelope<StoryboardLipSyncAcceptVO>>(
    '/api/user/storyboard/lipSync',
    body
  )
  return unwrap(res)
}

/** 分镜工作台：批量对口型（受理型异步，SSE storyboard_lip_sync_generate）POST /api/user/storyboard/lipSync/batch */
export async function userStoryboardLipSyncBatch(
  body: StoryboardLipSyncBatchRequest
): Promise<StoryboardLipSyncBatchAcceptVO> {
  const res = await request.post<ApiEnvelope<StoryboardLipSyncBatchAcceptVO>>(
    '/api/user/storyboard/lipSync/batch',
    body
  )
  return unwrap(res)
}

/** 分镜工作台：批量配音（受理型异步，SSE storyboard_audio_generate）POST /api/user/storyboard/generate/audio/batch */
export async function userStoryboardGenerateAudioBatch(
  body: StoryboardAudioBatchRequest
): Promise<StoryboardAudioBatchAcceptVO> {
  const res = await request.post<ApiEnvelope<StoryboardAudioBatchAcceptVO>>(
    '/api/user/storyboard/generate/audio/batch',
    body
  )
  return unwrap(res)
}

/** 分镜工作台：发起音画同步（异步，SSE + dubbing/{taskId}）POST /api/user/storyboard/generate/dubbing */
export async function userStoryboardGenerateDubbing(
  body: StoryboardGenerateDubbingRequest
): Promise<StoryboardDubbingTaskVO> {
  const res = await request.post<ApiEnvelope<StoryboardDubbingTaskVO>>(
    '/api/user/storyboard/generate/dubbing',
    body
  )
  return unwrap(res)
}

/** 分镜工作台：查询音画同步任务 GET /api/user/storyboard/dubbing/{taskId} */
export async function userStoryboardDubbingTask(taskId: number): Promise<StoryboardDubbingTaskVO> {
  const res = await request.get<ApiEnvelope<StoryboardDubbingTaskVO>>(
    `/api/user/storyboard/dubbing/${taskId}`
  )
  return unwrap(res)
}

/** 文字转语音同步试听（字数上限见 /auth/public-config voicePreview）POST /api/user/voice/preview */
export async function userVoicePreview(body: VoicePreviewRequest): Promise<VoicePreviewResult> {
  const res = await request.post<ApiEnvelope<VoicePreviewResult>>('/api/user/voice/preview', body)
  return unwrap(res)
}

/** C 端一次性拉取音色筛选字典：POST /api/user/voice-library/tags（并发合并 + 短时缓存） */
const voiceLibraryTagsInflight = new Map<string, Promise<VoiceTagBundleData>>()
const voiceLibraryTagsBurst: ListBurstSlot<VoiceTagBundleData> = { current: null }

export async function userVoiceLibraryTags(): Promise<VoiceTagBundleData> {
  return runListDedupe(
    'voice-library-tags',
    voiceLibraryTagsInflight,
    voiceLibraryTagsBurst,
    async () => {
      const res = await request.post<ApiEnvelope<VoiceTagBundleData>>(
        '/api/user/voice-library/tags',
        {}
      )
      return unwrap(res)
    },
    30_000
  )
}

/** C 端查询音色列表：POST /api/user/voice-library/list */
export async function userVoiceLibraryList(body: {
  pageNum?: number
  pageSize?: number
  language?: string
  gender?: string
  ageRange?: string
  voiceName?: string
  characterType?: string
  voiceStyle?: string
  toneTag?: string
  emotionTag?: string
} = {}): Promise<{ data: any[]; total: number }> {
  const res = await request.post<{ code: number; msg: string; data: any[]; total: number }>(
    '/api/user/voice-library/list',
    body
  )
  return { data: Array.isArray(res.data) ? res.data : [], total: Number(res.total) || 0 }
}

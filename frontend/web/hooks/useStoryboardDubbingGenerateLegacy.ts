import { fetchComposeDubbingResultOnce,followStoryboardDubbingComposeJob,runStoryboardDubbingGenerateTask,type StoryboardDubbingGenerateParams,type StoryboardDubbingGenerateProgress,type StoryboardDubbingGenerateResult } from '~/hooks/useStoryboardDubbingGenerate'
/** @deprecated 请改用 runStoryboardDubbingGenerateTask */
export async function requestStoryboardDubbingGenerate(
  params: StoryboardDubbingGenerateParams
): Promise<{ videoUrl: string }> {
  const result = await runStoryboardDubbingGenerateTask({ params })
  if (result.ok === false) throw new Error(result.errorMessage)
  return { videoUrl: result.videoUrl }
}

/** @deprecated 请改用 followStoryboardDubbingComposeJob */
export async function followStoryboardDubbingGenerateTask(payload: {
  taskId: number
  sourceVideoUrl: string
  lipSync?: boolean
  storyboardId?: number
  onProgress?: (p: StoryboardDubbingGenerateProgress) => void
}): Promise<StoryboardDubbingGenerateResult> {
  return followStoryboardDubbingComposeJob({
    composeBatchId: '',
    audioRecordId: payload.taskId,
    lipSync: payload.lipSync ?? true,
    sourceVideoUrl: payload.sourceVideoUrl,
    storyboardId: payload.storyboardId,
    onProgress: payload.onProgress
  })
}

/** @deprecated 请改用 followStoryboardDubbingComposeJob（compose/status 轮询 + 单次 detail） */
export async function pollComposeVoiceoverResult(payload: {
  audioRecordId: number
  lipSync: boolean
  sourceVideoUrl: string
  onProgress?: (p: StoryboardDubbingGenerateProgress) => void
}): Promise<string> {
  payload.onProgress?.({
    audioRecordId: payload.audioRecordId,
    message: '配音生成中…',
    stepTitle: '配音生成中…'
  })
  return fetchComposeDubbingResultOnce(payload)
}

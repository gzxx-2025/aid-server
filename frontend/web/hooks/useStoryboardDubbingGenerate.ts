import { getStep4PlusMockGen } from '~/composables/useStep4PlusMockGen'
import { followStoryboardLipSyncSseJob } from '~/composables/useStoryboardLipSyncTask'
import { TASK_SSE_TIMEOUT_MS } from '~/composables/useTaskSseFollow'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import {
  userAssetCenterDetail,
  userComposeStatus,
  userComposeVoiceover,
  userModelList,
  userStoryboardLipSync
} from '~/utils/businessApi'
import { openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { htmlToPlainPreserveLineBreaks } from '~/utils/htmlPlain'
import { checkMiniMaxTtsTextLength } from '~/utils/ttsTextLimit'
import { useCreationStore } from '~/stores/creation'
import type { ComposeStatusResult, StoryboardLipSyncRequest, VoiceoverParam } from '~/types/business-api'
import {
  normalizeComposeStatus,
  resolveComposeProgressMessage,
  resolveDubbingDetailStatus,
  resolveDubbingOutputUrl
} from './storyboardDubbingComposeStatus'

export interface StoryboardDubbingGenerateParams {
  storyboardId?: number
  dialogue: string
  voiceName: string
  voiceLibraryId?: number
  voiceModelId?: number
  timbreCode?: string
  /** VoiceTag emotion code；界面仍保留中文 emotion 仅用于展示。 */
  emotionCode?: string
  emotion: string
  lipSync: boolean
  /** 当前分镜原视频地址，作为 mock / 兜底 */
  sourceVideoUrl: string
  projectId?: number
  episodeId?: number
}

export type StoryboardDubbingGenerateProgress = {
  message?: string
  percent?: number
  stepTitle?: string
  composeBatchId?: string
  audioRecordId?: number
  composeStatus?: string
  videoUrl?: string
  /** 对口型父任务 taskId（SSE） */
  taskId?: number
  /** 对口型配音阶段试听 URL（已拼域名） */
  audioUrl?: string
  durationMs?: number
}

export type StoryboardDubbingGenerateResult =
  | {
      ok: true
      composeBatchId: string
      audioRecordId: number
      videoUrl: string
      taskId?: number
      lipSyncVideoRecordId?: number
    }
  | { ok: false; errorMessage: string; deferred?: boolean }

export type StoryboardDubbingComposeJob = {
  composeBatchId: string
  audioRecordId: number
  lipSync: boolean
  sourceVideoUrl: string
  /** 对口型 SSE 父任务 ID；lipSync 恢复主 key */
  taskId?: number
}

const COMPOSE_STATUS_POLL_INTERVAL_MS = 10_000

function resolveDubbingMockDelayMs(): number {
  const env = process.env.NEXT_PUBLIC_MOCK_STEP4_PLUS_GEN
  if (env === '0' || env === 'false') {
    return 8000 + Math.floor(Math.random() * 4200)
  }
  if (env === '1' || env === 'true') {
    return 900 + Math.floor(Math.random() * 500)
  }
  return process.env.NODE_ENV === 'development'
    ? 900 + Math.floor(Math.random() * 500)
    : 8000 + Math.floor(Math.random() * 4200)
}

async function mockDubbingVideo(params: StoryboardDubbingGenerateParams): Promise<string> {
  await new Promise((r) => setTimeout(r, resolveDubbingMockDelayMs()))
  return params.sourceVideoUrl
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** SSE 终态后仅补查一次 asset/center/detail（不做轮询） */
export async function fetchComposeDubbingResultOnce(payload: {
  audioRecordId: number
  lipSync: boolean
  sourceVideoUrl: string
}): Promise<string> {
  const audioRecordId = Number(payload.audioRecordId)
  if (!Number.isFinite(audioRecordId) || audioRecordId <= 0) {
    throw new Error('配音记录ID无效')
  }
  const detail = await userAssetCenterDetail({
    categoryCode: 'dubbing',
    id: audioRecordId
  })
  const status = resolveDubbingDetailStatus(
    (detail.content as Record<string, unknown> | null | undefined) ?? null
  )
  if (status === 'FAILED') {
    throw new Error('配音生成失败')
  }
  const url = resolveDubbingOutputUrl(detail, payload.lipSync)
  if (url) return url
  const fallback = String(payload.sourceVideoUrl || '').trim()
  if (fallback) return fallback
  throw new Error('配音生成完成，但未返回可用地址')
}

async function pollComposeStatusUntilTerminal(payload: {
  composeBatchId: string
  audioRecordId: number
  onProgress?: (p: StoryboardDubbingGenerateProgress) => void
  timeoutMs?: number
}): Promise<ComposeStatusResult> {
  const composeBatchId = String(payload.composeBatchId || '').trim()
  const audioRecordId = Number(payload.audioRecordId)
  const deadline = Date.now() + (payload.timeoutMs ?? TASK_SSE_TIMEOUT_MS)

  while (Date.now() <= deadline) {
    const status = await userComposeStatus({ composeBatchId })
    const st = normalizeComposeStatus(status.status)
    const message = resolveComposeProgressMessage(status)

    payload.onProgress?.({
      composeBatchId,
      audioRecordId,
      composeStatus: st,
      message,
      stepTitle: message,
      ...(st === 'SUCCEEDED' && status.videoUrl
        ? { videoUrl: String(status.videoUrl).trim() }
        : {})
    })

    if (st === 'SUCCEEDED') return status
    if (st === 'FAILED') {
      const msg = String(status.errorMessage || '配音生成失败')
      openRechargeModalFromInsufficientBalance(msg)
      throw new Error(msg)
    }

    if (Date.now() + COMPOSE_STATUS_POLL_INTERVAL_MS > deadline) break
    await sleep(COMPOSE_STATUS_POLL_INTERVAL_MS)
  }

  throw new Error('配音生成超时，请稍后在生成记录中查看')
}

/**
 * 组装 compose/voiceover 的 voiceover 入参：
 * - 优先 voiceLibraryId（后端反查模型+音色编码）
 * - 无音色库 ID 时回退 voiceModelId + timbreCode（老入参）
 */
function buildComposeVoiceoverParam(
  params: StoryboardDubbingGenerateParams,
  ttsText: string
): VoiceoverParam {
  const voiceLibraryId = Number(params.voiceLibraryId)
  if (Number.isFinite(voiceLibraryId) && voiceLibraryId > 0) {
    return {
      voiceLibraryId,
      ttsTexts: [ttsText]
    }
  }

  const voiceModelId = Number(params.voiceModelId)
  const timbreCode = String(params.timbreCode || '').trim() || undefined
  if (Number.isFinite(voiceModelId) && voiceModelId > 0) {
    return {
      voiceModelId,
      ...(timbreCode ? { timbreCode } : {}),
      ttsTexts: [ttsText]
    }
  }

  throw new Error('请选择有效音色')
}

export function buildLipSyncRequest(
  params: StoryboardDubbingGenerateParams
): StoryboardLipSyncRequest {
  const storyboardId = Number(params.storyboardId)
  if (!Number.isFinite(storyboardId) || storyboardId <= 0) {
    throw new Error('分镜信息异常')
  }

  const body: StoryboardLipSyncRequest = { storyboardId }
  const voiceLibraryId = Number(params.voiceLibraryId)
  if (Number.isFinite(voiceLibraryId) && voiceLibraryId > 0) {
    body.voiceLibraryId = voiceLibraryId
  } else {
    const voiceModelId = Number(params.voiceModelId)
    const timbreCode = String(params.timbreCode || '').trim() || undefined
    if (Number.isFinite(voiceModelId) && voiceModelId > 0) {
      body.voiceModelId = voiceModelId
      if (timbreCode) body.timbreCode = timbreCode
    }
  }

  const emotionCode = String(params.emotionCode || '').trim()
  if (emotionCode && emotionCode !== 'neutral') {
    body.emotion = emotionCode
  }

  return body
}

export type SubmitStoryboardDubbingResult =
  | { mode: 'compose'; composeBatchId: string; audioRecordId: number }
  | { mode: 'lipSync'; taskId: number }
  | { mode: 'mock' }

export async function submitStoryboardDubbingGenerateTask(
  params: StoryboardDubbingGenerateParams
): Promise<SubmitStoryboardDubbingResult> {
  if (getStep4PlusMockGen().enabled) {
    return { mode: 'mock' }
  }

  const storyboardId = Number(params.storyboardId)
  if (!Number.isFinite(storyboardId) || storyboardId <= 0) {
    throw new Error('分镜信息异常')
  }

  const dialogue = htmlToPlainPreserveLineBreaks(params.dialogue).trim()
  if (!dialogue) {
    throw new Error('请输入配音台词')
  }

  // MiniMax 音色：单条配音文本最多 10000 字符
  {
    const hints: Array<string | null | undefined> = [params.timbreCode, params.voiceName]
    if (params.voiceModelId) {
      try {
        const models = await userModelList({ modelType: 'audio' })
        const hit = models.find((m) => Number(m.id) === Number(params.voiceModelId))
        if (hit) hints.push(hit.providerName, hit.modelCode, hit.modelName)
      } catch {
        /* ignore */
      }
    }
    const tooLong = checkMiniMaxTtsTextLength(dialogue, hints)
    if (tooLong) throw new Error(tooLong)
  }

  let projectId = params.projectId
  let episodeId = params.episodeId
  if (projectId == null || episodeId == null) {
    try {
      const store = useCreationStore.getState()
      const route = getRouteLikeSnapshot()
      const ctx = await resolveStoryScriptSaveContext(store, route)
      if (ctx) {
        projectId = projectId ?? ctx.projectId
        episodeId = episodeId ?? ctx.episodeId
      }
    } catch {
      /* ignore */
    }
  }

  if (params.lipSync) {
    const lipBody = buildLipSyncRequest(params)
    const accepted = await userStoryboardLipSync(lipBody)
    const taskId = Number(accepted?.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) {
      throw new Error('对口型任务创建失败')
    }
    return { mode: 'lipSync', taskId }
  }

  const voiceover = buildComposeVoiceoverParam(params, dialogue)

  const submitted = await userComposeVoiceover({
    storyboardIds: [storyboardId],
    voiceover,
    projectId,
    episodeId,
    resolution: 'FHD'
  })

  const composeBatchId = String(submitted?.composeBatchId || '').trim()
  const audioRecordId = Number(submitted?.audioRecordIds?.[0])
  if (!composeBatchId || !Number.isFinite(audioRecordId) || audioRecordId <= 0) {
    throw new Error('配音任务创建失败')
  }

  return { mode: 'compose', composeBatchId, audioRecordId }
}

/** 对口型 follow：SSE 主通道（taskId）；保留函数名供 restore / 弹窗调用 */
export async function followStoryboardLipSyncOnlyJob(payload: {
  params?: StoryboardDubbingGenerateParams
  taskId: number
  /** @deprecated 旧轮询路径字段，已忽略；进度里的 audioRecordId 由 SSE 下发 */
  audioRecordId?: number
  onProgress?: (p: StoryboardDubbingGenerateProgress) => void
}): Promise<StoryboardDubbingGenerateResult> {
  return followStoryboardLipSyncSseJob({
    taskId: payload.taskId,
    onProgress: payload.onProgress
  })
}

export async function followStoryboardDubbingComposeJob(payload: {
  composeBatchId: string
  audioRecordId: number
  lipSync: boolean
  sourceVideoUrl: string
  /** @deprecated 对口型已改为独立受理，不再在 compose 后串联 */
  storyboardId?: number
  onProgress?: (p: StoryboardDubbingGenerateProgress) => void
  /** @deprecated 旧版 SSE 恢复字段，已忽略 */
  taskId?: number
}): Promise<StoryboardDubbingGenerateResult> {
  const composeBatchId = String(payload.composeBatchId || '').trim()
  const audioRecordId = Number(payload.audioRecordId)
  if (!composeBatchId || !Number.isFinite(audioRecordId) || audioRecordId <= 0) {
    return { ok: false, errorMessage: '合成任务信息无效' }
  }

  try {
    payload.onProgress?.({
      composeBatchId,
      audioRecordId,
      message: '配音任务已提交…',
      stepTitle: '配音任务已提交…'
    })

    const terminal = await pollComposeStatusUntilTerminal({
      composeBatchId,
      audioRecordId,
      onProgress: payload.onProgress
    })

    const fromStatus = String(terminal.videoUrl || '').trim()
    const videoUrl =
      fromStatus ||
      (await fetchComposeDubbingResultOnce({
        audioRecordId,
        lipSync: false,
        sourceVideoUrl: payload.sourceVideoUrl
      }))

    return { ok: true, composeBatchId, audioRecordId, videoUrl }
  } catch (e: unknown) {
    const msg = String((e as Error)?.message || '配音生成失败')
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }
}

export async function runStoryboardDubbingGenerateTask(opts: {
  params: StoryboardDubbingGenerateParams
  resumeComposeJob?: StoryboardDubbingComposeJob
  onProgress?: (p: StoryboardDubbingGenerateProgress) => void
  onSubmitted?: (p: {
    composeBatchId: string
    audioRecordId: number
    taskId?: number
  }) => void
}): Promise<StoryboardDubbingGenerateResult> {
  const { params, resumeComposeJob, onProgress, onSubmitted } = opts

  if (resumeComposeJob) {
    if (resumeComposeJob.lipSync && !resumeComposeJob.composeBatchId) {
      const taskId = Number(resumeComposeJob.taskId)
      if (!Number.isFinite(taskId) || taskId <= 0) {
        return { ok: false, errorMessage: '对口型任务信息无效' }
      }
      return followStoryboardLipSyncOnlyJob({
        params,
        taskId,
        audioRecordId: resumeComposeJob.audioRecordId,
        onProgress
      })
    }
    return followStoryboardDubbingComposeJob({
      composeBatchId: resumeComposeJob.composeBatchId,
      audioRecordId: resumeComposeJob.audioRecordId,
      lipSync: false,
      sourceVideoUrl: resumeComposeJob.sourceVideoUrl || params.sourceVideoUrl,
      onProgress
    })
  }

  try {
    const submitted = await submitStoryboardDubbingGenerateTask(params)
    if (submitted.mode === 'mock') {
      const videoUrl = await mockDubbingVideo(params)
      return { ok: true, composeBatchId: '', audioRecordId: 0, videoUrl }
    }

    if (submitted.mode === 'lipSync') {
      onSubmitted?.({
        composeBatchId: '',
        audioRecordId: 0,
        taskId: submitted.taskId
      })
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }
      return followStoryboardLipSyncOnlyJob({
        params,
        taskId: submitted.taskId,
        onProgress
      })
    }

    onSubmitted?.({
      composeBatchId: submitted.composeBatchId,
      audioRecordId: submitted.audioRecordId
    })

    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
    }

    onProgress?.({
      composeBatchId: submitted.composeBatchId,
      audioRecordId: submitted.audioRecordId,
      message: '配音任务已提交…',
      stepTitle: '配音任务已提交…'
    })

    return followStoryboardDubbingComposeJob({
      composeBatchId: submitted.composeBatchId,
      audioRecordId: submitted.audioRecordId,
      lipSync: false,
      sourceVideoUrl: params.sourceVideoUrl,
      onProgress
    })
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    const msg = String(err?.msg || err?.message || '配音提交失败')
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }
}

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

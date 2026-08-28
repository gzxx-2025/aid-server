import type {
StoryboardAudioTaskVO,
StoryboardGenerateAudioRequest,
StoryboardLipSyncAcceptVO,
StoryboardLipSyncRequest
} from '~/types/business-api'
import {
userModelList,
userStoryboardAudioTask,
userStoryboardGenerateAudio,
userStoryboardLipSync
} from '~/utils/businessApi'
import { checkMiniMaxTtsTextLength } from '~/utils/ttsTextLimit'
/** Legacy polling interval for storyboard audio task APIs. */
export const STORYBOARD_AUDIO_POLL_INTERVAL_MS = 30_000

/** 单次轮询最长等待（约 10 分钟） */
export const STORYBOARD_AUDIO_POLL_TIMEOUT_MS = 10 * 60 * 1000

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Aborted', 'AbortError'))
      return
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      clearTimeout(timer)
      signal?.removeEventListener('abort', onAbort)
      reject(new DOMException('Aborted', 'AbortError'))
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

function normalizeStatus(raw: unknown): string {
  return String(raw ?? '')
    .trim()
    .toUpperCase()
}

export type StoryboardAudioPollMode = 'tts' | 'lipSync'

function isTerminalVo(vo: StoryboardAudioTaskVO, mode: StoryboardAudioPollMode): boolean {
  if (mode === 'lipSync') {
    const st = normalizeStatus(vo.lipSyncStatus)
    return st === 'SUCCEEDED' || st === 'FAILED'
  }
  const st = normalizeStatus(vo.status)
  return st === 'SUCCEEDED' || st === 'FAILED'
}

/**
 * 轮询 GET /api/user/storyboard/audio/{taskId}，间隔默认 30s，终态或超时后停止。
 * - tts：看 status（SUCCEEDED/FAILED）
 * - lipSync：看 lipSyncStatus（SUCCEEDED/FAILED）
 */
export async function pollStoryboardAudioTaskUntil(
  taskId: number,
  options: {
    mode: StoryboardAudioPollMode
    onProgress?: (vo: StoryboardAudioTaskVO) => void
    intervalMs?: number
    timeoutMs?: number
    signal?: AbortSignal
  }
): Promise<StoryboardAudioTaskVO> {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) {
    throw new Error('任务ID无效')
  }

  const intervalMs = options.intervalMs ?? STORYBOARD_AUDIO_POLL_INTERVAL_MS
  const timeoutMs = options.timeoutMs ?? STORYBOARD_AUDIO_POLL_TIMEOUT_MS
  const deadline = Date.now() + timeoutMs
  let last: StoryboardAudioTaskVO | null = null

  while (Date.now() <= deadline) {
    if (options.signal?.aborted) {
      throw new DOMException('Aborted', 'AbortError')
    }
    last = await userStoryboardAudioTask(id)
    options.onProgress?.(last)
    if (isTerminalVo(last, options.mode)) return last

    const wait = Math.min(intervalMs, Math.max(0, deadline - Date.now()))
    if (wait <= 0) break
    await sleep(wait, options.signal)
  }

  if (last && isTerminalVo(last, options.mode)) return last
  throw new Error(options.mode === 'lipSync' ? '对口型超时，请稍后在生成记录中查看' : '配音超时，请稍后在生成记录中查看')
}

/**
 * 发起配音：同步成功直接返回；仅 PROCESSING 时按 30s 间隔轮询终态。
 * MiniMax 音色：清洗后文本 >10000 字前端直接拦截（文案「文本过长」）。
 */
export async function resolveStoryboardGenerateAudio(
  body: StoryboardGenerateAudioRequest,
  options?: {
    onProgress?: (vo: StoryboardAudioTaskVO) => void
    signal?: AbortSignal
    /** 可选：已知的 provider / modelCode，用于 MiniMax 字数预检 */
    ttsProviderHints?: Array<string | null | undefined>
  }
): Promise<StoryboardAudioTaskVO> {
  const hints = [...(options?.ttsProviderHints || [])]
  if (body.voiceModelId && hints.length === 0) {
    try {
      const models = await userModelList({ modelType: 'audio' })
      const hit = models.find((m) => Number(m.id) === Number(body.voiceModelId))
      if (hit) hints.push(hit.providerName, hit.modelCode, hit.modelName)
    } catch {
      /* ignore lookup failure；仍交由后端校验 */
    }
  }
  const tooLong = checkMiniMaxTtsTextLength(body.ttsText, hints)
  if (tooLong) throw new Error(tooLong)

  const first = await userStoryboardGenerateAudio(body)
  options?.onProgress?.(first)

  const st = normalizeStatus(first.status)
  if (st === 'SUCCEEDED') return first
  if (st === 'FAILED') {
    throw new Error(String(first.errorMessage || '配音生成失败').trim() || '配音生成失败')
  }
  if (st !== 'PROCESSING') return first

  return pollStoryboardAudioTaskUntil(first.id, {
    mode: 'tts',
    onProgress: options?.onProgress,
    signal: options?.signal
  })
}

/**
 * 发起对口型并返回用户任务受理信息。新协议只返回 taskId/status；终态统一由任务 SSE 跟随，
 * 不能再把 taskId 当作 storyboard/audio 记录 id 去轮询。
 */
export async function resolveStoryboardLipSync(
  body: StoryboardLipSyncRequest,
  options?: {
    signal?: AbortSignal
  }
): Promise<StoryboardLipSyncAcceptVO> {
  const sid = Number(body.storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) {
    throw new Error('分镜ID无效')
  }

  if (options?.signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  return userStoryboardLipSync(body)
}

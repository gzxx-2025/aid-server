import {
  fetchUserTaskDetailOnce,
  normalizeTaskStatus,
  waitUserTaskSseTerminal
} from '~/composables/useTaskSseFollow'
import { userStoryboardImagePromptResolve, userTaskResume } from '~/utils/businessApi'
import { fetchUserStoryboardDetailOnce } from '~/utils/storyboardDetailOnce'
import { formatPartialFailedMessage, parseTaskPartialFailedData } from '~/utils/taskPartialFailed'
import type { StoryboardImagePromptResolveData, UserStoryboardListRow } from '~/types/business-api'
import { collectReferenceAudioIds } from '~/utils/referenceMediaItem'
import {
  buildPromptAssetsFromResolve,
  type PromptAssetItem
} from '~/utils/storyboardPromptAssetRef'

export type StoryboardPromptDetailField = 'imagePrompt' | 'videoPrompt' | 'videoPromptImage'

/** 模型类型枚举误写入 modelCode 时的脏值（如生成设置「生视频模型」误用 ModelTypeEnum） */
const INVALID_STORYBOARD_PROMPT_MODEL_CODES = new Set(['image', 'video', 'text', 'audio'])

/** 生成 image/video 提示词接口用的 modelCode；空或脏值表示不传，走后端智能体默认 */
export function sanitizeStoryboardPromptModelCode(raw: string | null | undefined): string {
  const code = String(raw ?? '').trim()
  if (!code) return ''
  if (INVALID_STORYBOARD_PROMPT_MODEL_CODES.has(code.toLowerCase())) return ''
  return code
}

export interface StoryboardPromptGenerateSettingsSlice {
  agentId?: string | null
  modelCode?: string | null
}

/** 生成设置里选了智能体才传 agentCode，否则走后端默认智能体 */
export function resolveStoryboardPromptAgentCode(
  settings: StoryboardPromptGenerateSettingsSlice
): string {
  return String(settings.agentId ?? '').trim()
}

/** 生成设置里选了文本模型才传 modelCode，否则走后端智能体默认模型 */
export function resolveStoryboardPromptModelCode(
  settings: StoryboardPromptGenerateSettingsSlice
): string {
  return sanitizeStoryboardPromptModelCode(settings.modelCode)
}

/** image-prompt / video-prompt 提交体：仅在有值时带上 agentCode、modelCode */
export function buildStoryboardPromptSubmitFields(agentCode: string, modelCode: string) {
  return {
    ...(agentCode ? { agentCode } : {}),
    ...(modelCode ? { modelCode } : {})
  }
}

/**
 * 自动/批量：不传 agentCode/modelCode，后端走 gen-config → aid_config 兜底。
 * 手动在弹窗切换智能体/模型时 manualPick=true 才传参。
 */
export function buildStoryboardLlmSubmitFields(
  manualPick: boolean,
  agentCode: string,
  modelCode: string
) {
  if (!manualPick) return {}
  return buildStoryboardPromptSubmitFields(agentCode, modelCode)
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 从分镜 detail 读取提示词字段（兼容 snake_case 与图生方向 videoPromptImage） */
export function readStoryboardDetailPromptField(
  row: UserStoryboardListRow | null | undefined,
  field: StoryboardPromptDetailField
): string {
  if (!row) return ''
  const raw = row as unknown as Record<string, unknown>
  if (field === 'videoPromptImage') {
    return String(row.videoPromptImage ?? raw.video_prompt_image ?? '').trim()
  }
  if (field === 'videoPrompt') {
    return String(row.videoPrompt ?? raw.video_prompt ?? '').trim()
  }
  return String(row.imagePrompt ?? raw.image_prompt ?? '').trim()
}

/** SSE 完成后从分镜详情拉取 imagePrompt / videoPrompt（后端落库可能有短暂延迟，带重试） */
export async function fetchStoryboardPromptPlainWithRetry(
  storyboardId: number,
  field: StoryboardPromptDetailField,
  options?: { maxAttempts?: number; intervalMs?: number }
): Promise<string> {
  const maxAttempts = options?.maxAttempts ?? 10
  const intervalMs = options?.intervalMs ?? 600

  // SSE 完成后必须强制拉最新 detail，避免短缓存返回生成前的旧提示词/推荐时长
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const row = await fetchUserStoryboardDetailOnce(storyboardId, { force: true })
    const text = readStoryboardDetailPromptField(row, field)
    if (text) return text
    if (attempt < maxAttempts - 1) await sleep(intervalMs)
  }
  return ''
}

const EMPTY_RESOLVE_DATA: StoryboardImagePromptResolveData = {
  referenceImageIds: [],
  referenceImageUrls: [],
  unresolvedNames: []
}

/** 解析提示词中的 @图片N[name] / @音频N[...] 占位（imagePrompt / videoPrompt 共用同一 resolve 接口） */
export async function resolveStoryboardPromptPlainForEditor(
  plain: string,
  ctx: { projectId: number; episodeId: number },
  options?: { referenceAudioIds?: number[] | null }
): Promise<StoryboardImagePromptResolveData> {
  const text = String(plain || '').trim()
  const referenceAudioIds = Array.isArray(options?.referenceAudioIds)
    ? options!.referenceAudioIds!.filter((id) => Number(id) > 0)
    : []
  if (!text || (!text.includes('@图片') && !text.includes('@音频') && !referenceAudioIds.length)) {
    return { ...EMPTY_RESOLVE_DATA }
  }
  try {
    return await userStoryboardImagePromptResolve({
      projectId: ctx.projectId,
      episodeId: ctx.episodeId,
      imagePrompt: text,
      ...(referenceAudioIds.length ? { referenceAudioIds } : {})
    })
  } catch {
    return { ...EMPTY_RESOLVE_DATA }
  }
}

export interface StoryboardPromptImageResolveOutcome {
  resolvedAssets: PromptAssetItem[]
  unresolvedNames: string[]
  unresolvedReferenceAudioIds?: number[]
}

/**
 * resolve 接口解析 @图片 / @音频 与显式 referenceAudioIds。
 * 失败或未命中时仍从纯文本占位生成可点击资产；构图/景别等由调用方走前端 paramGroups 解析。
 */
export async function resolveStoryboardImageAssetsFromPlain(
  plain: string,
  ctx: { projectId: number; episodeId: number } | null | undefined,
  options?: { referenceAudioIds?: number[] | null }
): Promise<StoryboardPromptImageResolveOutcome> {
  const text = String(plain || '').trim()
  const referenceAudioIds = Array.isArray(options?.referenceAudioIds)
    ? options!.referenceAudioIds!
    : []
  if (!text.includes('@图片') && !text.includes('@音频') && !referenceAudioIds.length) {
    return { resolvedAssets: [], unresolvedNames: [] }
  }
  const data = ctx
    ? await resolveStoryboardPromptPlainForEditor(text, ctx, { referenceAudioIds })
    : { ...EMPTY_RESOLVE_DATA }
  return {
    resolvedAssets: buildPromptAssetsFromResolve(text, data),
    unresolvedNames: data.unresolvedNames || [],
    unresolvedReferenceAudioIds: Array.isArray(data.unresolvedReferenceAudioIds)
      ? data.unresolvedReferenceAudioIds
      : []
  }
}

/** 从当前选用参考音频提取 resolve / 出片用 ID */
export function referenceAudioIdsFromMedia(
  audios:
    | Array<{ kind?: string; audioSource?: string; referenceAudioId?: number }>
    | null
    | undefined
): number[] {
  return collectReferenceAudioIds(
    (audios || []).map((a) => ({
      kind: (a.kind as 'audio') || 'audio',
      name: '',
      audioSource: a.audioSource as 'upload' | 'voice_sample' | undefined,
      referenceAudioId: a.referenceAudioId
    }))
  )
}

export type StoryboardPromptKind = 'image' | 'video'

export type StoryboardPromptGenerateTaskOutcome =
  | { ok: true }
  | { ok: true; partial: true; taskId: number; partialWarning: string }
  | { ok: false; deferred: true; errorMessage: string }
  | { ok: false; errorMessage: string }

function safeParseResultData(raw: unknown): unknown {
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  const s = String(raw).trim()
  if (!s) return null
  try {
    return JSON.parse(s)
  } catch {
    return null
  }
}

function outcomeFromPromptTaskDetail(
  taskId: number,
  detail: Awaited<ReturnType<typeof fetchUserTaskDetailOnce>>
): StoryboardPromptGenerateTaskOutcome {
  const status = normalizeTaskStatus(detail?.status ?? 'SUCCEEDED')
  if (status === 'FAILED') {
    return { ok: false as const, errorMessage: String(detail?.errorMessage || '生成提示词失败') }
  }
  if (status === 'PARTIAL_FAILED') {
    const parsed = parseTaskPartialFailedData(
      detail?.resultData ? safeParseResultData(detail.resultData) : null
    )
    return {
      ok: true as const,
      partial: true as const,
      taskId,
      partialWarning: formatPartialFailedMessage(
        parsed,
        String(detail?.errorMessage || '部分提示词生成失败，可续生')
      )
    }
  }
  return { ok: true as const }
}

/** 订阅提示词批量生成任务 SSE；仅在 SSE 终态（或超时）后最多补查一次 task/detail */
export async function awaitStoryboardPromptGenerateTask(
  taskId: number
): Promise<StoryboardPromptGenerateTaskOutcome> {
  const terminal = await waitUserTaskSseTerminal({ taskId })

  if (terminal.kind === 'timeout' || terminal.kind === 'superseded') {
    try {
      const detail = await fetchUserTaskDetailOnce(taskId)
      const status = normalizeTaskStatus(detail?.status)
      if (status === 'SUCCEEDED' || status === 'PARTIAL_FAILED') {
        return outcomeFromPromptTaskDetail(taskId, detail)
      }
      if (status === 'CANCELLED') {
        return { ok: false as const, errorMessage: '任务已取消' }
      }
      if (status === 'FAILED') {
        return {
          ok: false as const,
          errorMessage: String(detail?.errorMessage || '生成提示词失败')
        }
      }
    } catch {
      // 查询失败不能证明后台任务失败；保留快照，等待弹窗下次恢复。
    }
    return {
      ok: false as const,
      deferred: true as const,
      errorMessage: '任务仍在后台进行中'
    }
  }

  const res = terminal.event
  if (res.type === 'cancelled') {
    return { ok: false as const, errorMessage: res.message || '任务已取消' }
  }
  if (res.type === 'error') {
    return { ok: false as const, errorMessage: res.errorMessage || '生成提示词失败' }
  }
  if (res.type === 'partial_failed') {
    return {
      ok: true as const,
      partial: true as const,
      taskId,
      partialWarning: formatPartialFailedMessage(res.data, '部分提示词生成失败，可续生')
    }
  }

  // SSE complete 已代表终态，不再补查 task/detail
  return { ok: true as const }
}

/** PARTIAL_FAILED 后调用 POST /api/user/task/resume，并重新订阅 SSE */
export async function resumeStoryboardPromptGenerateTask(
  taskId: number,
  _kind: StoryboardPromptKind
): Promise<StoryboardPromptGenerateTaskOutcome> {
  await userTaskResume({ taskId })
  const outcome = await awaitStoryboardPromptGenerateTask(taskId)
  if ((typeof window !== 'undefined')) {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }
  return outcome
}

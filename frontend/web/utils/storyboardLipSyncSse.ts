import type { TaskSseProgressInput } from '~/utils/taskSseProgressText'

export type LipSyncCompleteItem = {
  storyboardId?: number
  audioRecordId?: number
  audioUrl?: string
  durationMs?: number
  lipSyncVideoRecordId?: number
  lipSyncVideoUrl?: string
  status?: string
  errorMessage?: string | null
}

export type LipSyncProgressPreview = {
  audioRecordId?: number
  audioUrl: string
  durationMs?: number
  message: string
  stepTitle: string
}

function finitePositiveInt(v: unknown): number | undefined {
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return undefined
  return Math.trunc(n)
}

function asTrimmedString(v: unknown): string {
  return typeof v === 'string' ? v.trim() : ''
}

function coerceItem(raw: Record<string, unknown>): LipSyncCompleteItem | null {
  const lipSyncVideoRecordId = finitePositiveInt(raw.lipSyncVideoRecordId)
  const lipSyncVideoUrl = asTrimmedString(raw.lipSyncVideoUrl)
  const audioRecordId = finitePositiveInt(raw.audioRecordId)
  const storyboardId = finitePositiveInt(raw.storyboardId)
  const status = asTrimmedString(raw.status).toUpperCase() || undefined
  const audioUrl = asTrimmedString(raw.audioUrl) || undefined
  const durationMsRaw = Number(raw.durationMs)
  const durationMs =
    Number.isFinite(durationMsRaw) && durationMsRaw >= 0 ? Math.trunc(durationMsRaw) : undefined
  const errorMessage =
    raw.errorMessage == null ? null : asTrimmedString(raw.errorMessage) || null

  if (!lipSyncVideoRecordId && !lipSyncVideoUrl && !audioRecordId && !status) {
    return null
  }

  return {
    ...(storyboardId != null ? { storyboardId } : {}),
    ...(audioRecordId != null ? { audioRecordId } : {}),
    ...(audioUrl ? { audioUrl } : {}),
    ...(durationMs != null ? { durationMs } : {}),
    ...(lipSyncVideoRecordId != null ? { lipSyncVideoRecordId } : {}),
    ...(lipSyncVideoUrl ? { lipSyncVideoUrl } : {}),
    ...(status ? { status } : {}),
    errorMessage
  }
}

/** 解析对口型 SSE complete / task.resultData */
export function parseLipSyncCompleteItem(raw: unknown): LipSyncCompleteItem | null {
  if (raw == null) return null

  let obj: Record<string, unknown>
  if (typeof raw === 'string') {
    const text = raw.trim()
    if (!text) return null
    try {
      const parsed = JSON.parse(text)
      if (typeof parsed !== 'object' || parsed == null || Array.isArray(parsed)) return null
      obj = parsed as Record<string, unknown>
    } catch {
      return null
    }
  } else if (typeof raw === 'object' && !Array.isArray(raw)) {
    obj = raw as Record<string, unknown>
  } else {
    return null
  }

  if (Array.isArray(obj.items) && obj.items.length > 0) {
    const first = obj.items[0]
    if (first && typeof first === 'object' && !Array.isArray(first)) {
      return coerceItem(first as Record<string, unknown>)
    }
  }

  return coerceItem(obj)
}

/** complete 里的视频地址：完整 URL 与相对路径均原样返回（列表刷新会补绝对地址） */
export function resolveLipSyncVideoDisplayUrl(url: unknown): string {
  return asTrimmedString(url)
}

/**
 * 从 SSE progress 推导试听预览。
 * progress 帧 audioUrl 通常已拼域名；无可用 URL 时返回 null。
 */
export function resolveLipSyncProgressPreview(
  p: Pick<TaskSseProgressInput, 'stepId' | 'audioUrl' | 'audioRecordId' | 'durationMs' | 'stepTitle' | 'message' | 'progress'>
): LipSyncProgressPreview | null {
  const audioUrl = asTrimmedString(p.audioUrl)
  if (!audioUrl) return null

  const stepId = asTrimmedString(p.stepId)
  const isDub = !stepId || stepId === 'dub'
  const message = isDub
    ? '配音已生成，可试听'
    : asTrimmedString(p.stepTitle) || asTrimmedString(p.message) || '对口型合成中…'
  const stepTitle = isDub ? message : asTrimmedString(p.stepTitle) || '对口型合成中…'

  return {
    audioUrl,
    ...(finitePositiveInt(p.audioRecordId) != null
      ? { audioRecordId: finitePositiveInt(p.audioRecordId) }
      : {}),
    ...(p.durationMs != null && Number.isFinite(Number(p.durationMs))
      ? { durationMs: Math.trunc(Number(p.durationMs)) }
      : {}),
    message,
    stepTitle
  }
}

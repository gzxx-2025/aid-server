import type { DubbingPanel } from '~/types';
import type { StoryboardRecordRow } from '~/types/business-api';
import { isOriginalStoryboardVideoRecord } from '~/utils/storyboardRecordRow';

/** 从分镜视频条目解析服务端 recordId（用于 unSetFinalVideo） */
export function resolveStoryboardVideoRecordId(
  video: { id?: string; _serverRow?: { id?: number }; _fromServer?: boolean } | null | undefined
): number | null {
  const fromRow = video?._serverRow?.id
  if (fromRow != null && Number.isFinite(Number(fromRow)) && Number(fromRow) > 0) {
    return Number(fromRow)
  }
  const id = Number(video?.id)
  if (video?._fromServer && Number.isFinite(id) && id > 0) return id
  return null
}

/** 从生成记录列表回退解析 recordId（列表快照缺 id 时用于取消设置） */
export function resolveStoryboardVideoRecordIdFromRows(
  video: { id?: string; url?: string; _serverRow?: { id?: number }; _fromServer?: boolean } | null | undefined,
  rows: StoryboardRecordRow[]
): number | null {
  const direct = resolveStoryboardVideoRecordId(video)
  if (direct) return direct

  const selected = rows.find(
    (r) => r.isSelected === 1 && isOriginalStoryboardVideoRecord(r)
  )
  if (selected?.id != null && Number(selected.id) > 0) return Number(selected.id)

  const url = String(video?.url || '').trim()
  if (!url) return null
  const byUrl = rows.find(
    (r) => isOriginalStoryboardVideoRecord(r) && String(r.fileUrl || '').trim() === url
  )
  if (byUrl?.id != null && Number(byUrl.id) > 0) return Number(byUrl.id)
  return null
}

/** 从配音面板或服务端 finalAudioId 解析 recordId（用于 unSetFinalAudio） */
export function resolveStoryboardAudioRecordId(
  panel: DubbingPanel | null | undefined,
  serverFinalAudioId?: number | null
): number | null {
  if (serverFinalAudioId != null && Number.isFinite(Number(serverFinalAudioId)) && serverFinalAudioId > 0) {
    return Number(serverFinalAudioId)
  }
  const key = panel?.dubbingLipSyncKey
  if (!key || key === '__source__' || String(key).startsWith('batch-')) return null
  const n = Number(key)
  return Number.isFinite(n) && n > 0 ? n : null
}

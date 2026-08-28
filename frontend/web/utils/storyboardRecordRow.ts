import type { StoryboardRecordRow } from '~/types/business-api'

/** 原视频轨 genType（不含 compose 配音合成视频） */
export const STORYBOARD_ORIGINAL_VIDEO_GEN_TYPES = [
  'i2v',
  'multi',
  'edge',
  'upload_video'
] as const

export function normalizeStoryboardRecordGenType(genType: unknown): string {
  return String(genType ?? '')
    .trim()
    .toLowerCase()
}

export function isComposeStoryboardVideoRecord(
  row: { genType?: string | null } | null | undefined
): boolean {
  return normalizeStoryboardRecordGenType(row?.genType) === 'compose'
}

export function isOriginalStoryboardVideoRecord(
  row: { genType?: string | null } | null | undefined
): boolean {
  const gt = normalizeStoryboardRecordGenType(row?.genType)
  return (STORYBOARD_ORIGINAL_VIDEO_GEN_TYPES as readonly string[]).includes(gt)
}

/** list-by-storyboard v2.57.7 服务端 displayName，缺失时回落 fallback */
export function resolveStoryboardRecordDisplayName(
  row: Pick<StoryboardRecordRow, 'displayName'> | null | undefined,
  fallback?: string
): string {
  const fromServer = String(row?.displayName ?? '').trim()
  if (fromServer) return fromServer
  const fb = String(fallback ?? '').trim()
  return fb
}

export function isStoryboardRecordSelected(
  row: { isSelected?: number | boolean | null } | null | undefined
): boolean {
  if (row?.isSelected === true || row?.isSelected === 1) return true
  return false
}

/** 分镜视频卡片「来源」文案：按 genType 区分上传与 AI 生成，避免误标为「本地上传」 */
export function resolveStoryboardVideoSourceLabel(video: {
  source?: string | null
  _fromServer?: boolean
  _serverRow?: { genType?: string | null } | null
  genType?: string | null
} | null | undefined): string {
  const existing = String(video?.source ?? '').trim()
  if (existing) return existing

  const gt = normalizeStoryboardRecordGenType(
    video?._serverRow?.genType ?? video?.genType
  )
  if (gt === 'upload_video') return '本地上传'
  if (gt === 'i2v' || gt === 'multi' || gt === 'edge' || gt === 'grid') return '生成记录'
  if (video?._fromServer) return '生成记录'
  return '生成记录'
}

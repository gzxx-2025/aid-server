import type { ComposeStatusResult } from '~/types/business-api'

export function normalizeComposeStatus(status: string | null | undefined): string {
  return String(status || '').trim().toUpperCase()
}

export function resolveDubbingDetailStatus(
  content: Record<string, unknown> | null | undefined
): string {
  return String(content?.status || '').trim().toUpperCase()
}

export function resolveDubbingOutputUrl(
  detail: { videoUrl?: string | null; audioUrl?: string | null },
  lipSync: boolean
): string {
  const videoUrl = String(detail.videoUrl || '').trim()
  const audioUrl = String(detail.audioUrl || '').trim()
  if (lipSync && videoUrl) return videoUrl
  if (videoUrl) return videoUrl
  if (audioUrl) return audioUrl
  return ''
}

export function resolveComposeProgressMessage(status: ComposeStatusResult): string {
  const normalizedStatus = normalizeComposeStatus(status.status)
  if (normalizedStatus === 'VOICING') {
    const total = Number(status.audioTotal) || 0
    const done = Number(status.audioSucceeded) || 0
    return total > 0 ? `配音生成中（${done}/${total}）…` : '配音生成中…'
  }
  if (normalizedStatus === 'COMPOSING') return '视频合成中…'
  if (normalizedStatus === 'SUCCEEDED') return '合成完成'
  if (normalizedStatus === 'FAILED') return String(status.errorMessage || '配音生成失败')
  return '配音任务处理中…'
}

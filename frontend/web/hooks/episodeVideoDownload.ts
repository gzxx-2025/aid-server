import { triggerBrowserBlobDownload,userEpisodeExportDownload } from '~/utils/businessApi'
import { downloadMediaFile } from '~/utils/mediaFileDownload'
function normalizeMediaUrl(url: unknown): string {
  const raw = String(url || '').trim()
  if (!raw || /^(blob:|data:)/i.test(raw) || /\/blob:/i.test(raw)) return ''
  return raw
}

function guessExportFilename(url: string): string {
  try {
    const path = new URL(url, typeof window !== 'undefined' ? window.location.href : undefined)
      .pathname
    const base = path.split('/').pop() || ''
    if (/\.(mp4|mov|webm|mkv|m4v)(\?|$)/i.test(base)) {
      return decodeURIComponent(base.split('?')[0] || base)
    }
  } catch {
    /* ignore */
  }
  return `完整视频_${Date.now()}.mp4`
}

/**
 * 将导出成片保存到本地（触发浏览器下载，不跳转打开 CDN 播放页）。
 * @deprecated 优先使用 downloadExportedFinalVideo（/episode/export/download blob）
 */
export async function openExportedVideo(videoUrl: string): Promise<void> {
  const url = normalizeMediaUrl(videoUrl)
  if (!url) throw new Error('暂无可保存的视频地址')
  if (typeof window === 'undefined') throw new Error('仅支持在浏览器中下载')

  const filename = guessExportFilename(url)
  await downloadMediaFile(url, filename)
}

/**
 * 成片 mp4 附件流下载：POST /api/user/episode/export/download（blob）
 * 优先 episodeEditorId；否则 projectId + episodeId（电影 episodeId=0）
 */
export async function downloadExportedFinalVideo(payload: {
  episodeEditorId?: number | null
  projectId?: number | null
  episodeId?: number | null
}): Promise<void> {
  if (typeof window === 'undefined') throw new Error('仅支持在浏览器中下载')
  const { blob, filename } = await userEpisodeExportDownload({
    episodeEditorId: payload.episodeEditorId,
    projectId: payload.projectId ?? undefined,
    episodeId: payload.episodeId ?? undefined
  })
  triggerBrowserBlobDownload(blob, filename)
}

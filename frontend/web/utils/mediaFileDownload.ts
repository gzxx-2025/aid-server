'use client'

import { message } from 'antd'
import { triggerBrowserBlobDownload } from '~/utils/business/compose'

type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

const MIME_EXTENSION: Record<string, string> = {
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
  'image/gif': '.gif',
  'image/svg+xml': '.svg',
  'video/mp4': '.mp4',
  'video/webm': '.webm',
  'video/quicktime': '.mov',
  'audio/mpeg': '.mp3',
  'audio/wav': '.wav',
  'audio/ogg': '.ogg'
}

let downloadMessageSequence = 0

function extensionFromUrl(url: string): string {
  try {
    const pathname = new URL(url, typeof window === 'undefined' ? 'http://localhost' : window.location.href)
      .pathname
    const match = /\.(jpe?g|png|webp|gif|svg|mp4|webm|mov|m4v|avi|mkv|mp3|wav|ogg)$/i.exec(pathname)
    return match ? `.${match[1]!.toLowerCase().replace('jpeg', 'jpg')}` : ''
  } catch {
    return ''
  }
}

function filenameFromContentDisposition(header: string | null): string {
  if (!header) return ''
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (utf8?.[1]) {
    try {
      return decodeURIComponent(utf8[1].trim().replace(/^"|"$/g, ''))
    } catch {
      return utf8[1].trim().replace(/^"|"$/g, '')
    }
  }
  return /filename="?([^";]+)"?/i.exec(header)?.[1]?.trim() || ''
}

export function resolveMediaDownloadFilename(input: {
  requestedName?: string | null
  responseName?: string | null
  url: string
  mimeType?: string | null
}): string {
  const original = String(input.requestedName || input.responseName || '下载文件').trim() || '下载文件'
  const safeName = original.replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_').replace(/[. ]+$/g, '') || '下载文件'
  if (/\.(jpe?g|png|webp|gif|svg|mp4|webm|mov|m4v|avi|mkv|mp3|wav|ogg)$/i.test(safeName)) {
    return safeName
  }
  const mime = String(input.mimeType || '').split(';')[0]!.trim().toLowerCase()
  return `${safeName}${extensionFromUrl(input.url) || MIME_EXTENSION[mime] || ''}`
}

export async function fetchRemoteMediaBlob(
  url: string,
  fetcher: FetchLike = fetch
): Promise<{ blob: Blob; responseName: string }> {
  const normalizedUrl = String(url || '').trim()
  if (!normalizedUrl) throw new Error('暂无可下载的文件地址')

  const response = await fetcher(normalizedUrl, {
    credentials: 'same-origin'
  })
  if (!response.ok) throw new Error(`文件下载失败（HTTP ${response.status}）`)

  const blob = await response.blob()
  if (!blob.size) throw new Error('文件内容为空，无法下载')
  return {
    blob,
    responseName: filenameFromContentDisposition(response.headers.get('content-disposition'))
  }
}

/** 获取远程媒体后通过 Blob URL 触发浏览器本地保存，避免跨域 URL 打开新标签页。 */
export async function downloadMediaFile(url: string, requestedName?: string | null): Promise<string> {
  if (typeof document === 'undefined') throw new Error('仅支持在浏览器中下载')
  const { blob, responseName } = await fetchRemoteMediaBlob(url)
  const filename = resolveMediaDownloadFilename({
    requestedName,
    responseName,
    url,
    mimeType: blob.type
  })
  triggerBrowserBlobDownload(blob, filename)
  return filename
}

/** 带统一状态提示的媒体下载入口；失败时不会回退到会打开新页面的跨域直链。 */
export async function downloadMediaFileWithToast(
  url: string,
  requestedName?: string | null,
  mediaLabel = '文件'
): Promise<boolean> {
  const key = `media-download-${Date.now()}-${downloadMessageSequence++}`
  message.loading({ content: `正在下载${mediaLabel}...`, key, duration: 0 })
  try {
    await downloadMediaFile(url, requestedName)
    message.success({ content: `${mediaLabel}下载成功`, key, duration: 2 })
    return true
  } catch (error: unknown) {
    const cause = error as { message?: string }
    message.error({ content: cause?.message || `${mediaLabel}下载失败，请重试`, key, duration: 3 })
    return false
  }
}

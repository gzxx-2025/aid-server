import { fetchMediaBlob } from '~/utils/mediaFetch'
import { acquireMediaLoadSlot } from '~/utils/mediaLoadGate'
export type CaptureVideoPosterOptions = {
  maxWidth?: number
  quality?: number
  timeoutMs?: number
  fetchBlob?: (url: string) => Promise<Blob | null>
}

const DEFAULT_MAX_WIDTH = 160
const DEFAULT_QUALITY = 0.82
const DEFAULT_TIMEOUT_MS = 12_000

function canvasToJpegBlob(
  canvas: HTMLCanvasElement,
  quality: number
): Promise<Blob | null> {
  return new Promise((resolve) => {
    try {
      canvas.toBlob(
        (blob) => resolve(blob),
        'image/jpeg',
        quality
      )
    } catch {
      try {
        const dataUrl = canvas.toDataURL('image/jpeg', quality)
        const comma = dataUrl.indexOf(',')
        if (comma < 0) {
          resolve(null)
          return
        }
        const bin = atob(dataUrl.slice(comma + 1))
        const arr = new Uint8Array(bin.length)
        for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i)
        resolve(new Blob([arr], { type: 'image/jpeg' }))
      } catch {
        resolve(null)
      }
    }
  })
}

/**
 * 经同源可读 blob 截取视频首帧小图（避免裸 CDN crossOrigin canvas 线上失败）。
 */
export async function captureVideoPosterBlob(
  videoUrl: string,
  options: CaptureVideoPosterOptions = {}
): Promise<Blob | null> {
  const url = String(videoUrl || '').trim()
  if (!url || typeof document === 'undefined') return null

  const maxWidth = options.maxWidth ?? DEFAULT_MAX_WIDTH
  const quality = options.quality ?? DEFAULT_QUALITY
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const fetchBlob = options.fetchBlob ?? fetchMediaBlob

  const releaseSlot = await acquireMediaLoadSlot(2)
  try {
    const mediaBlob = await fetchBlob(url)
    if (!mediaBlob || !mediaBlob.size) return null

    const objectUrl = URL.createObjectURL(mediaBlob)
    try {
      return await new Promise<Blob | null>((resolve) => {
        const video = document.createElement('video')
        video.muted = true
        video.playsInline = true
        video.preload = 'auto'

        let settled = false
        const timeout = window.setTimeout(() => finish(null), timeoutMs)

        const cleanup = () => {
          window.clearTimeout(timeout)
          video.removeEventListener('error', onError)
          video.removeEventListener('loadeddata', onLoadedData)
          video.removeEventListener('seeked', onSeeked)
          try {
            video.pause()
            video.removeAttribute('src')
            video.load()
          } catch {
            /* ignore */
          }
        }

        const finish = (blob: Blob | null) => {
          if (settled) return
          settled = true
          cleanup()
          resolve(blob)
        }

        const onError = () => finish(null)

        const paint = async () => {
          try {
            const vw = video.videoWidth || 0
            const vh = video.videoHeight || 0
            if (vw <= 0 || vh <= 0) {
              finish(null)
              return
            }
            const scale = Math.min(1, maxWidth / vw)
            const canvas = document.createElement('canvas')
            canvas.width = Math.max(1, Math.round(vw * scale))
            canvas.height = Math.max(1, Math.round(vh * scale))
            const ctx = canvas.getContext('2d')
            if (!ctx) {
              finish(null)
              return
            }
            ctx.drawImage(video, 0, 0, canvas.width, canvas.height)
            const blob = await canvasToJpegBlob(canvas, quality)
            canvas.width = 0
            canvas.height = 0
            finish(blob)
          } catch {
            finish(null)
          }
        }

        const onSeeked = () => {
          void paint()
        }

        const onLoadedData = () => {
          const duration = Number.isFinite(video.duration) ? video.duration : 0
          const seekTime = duration > 0 ? Math.min(0.1, duration * 0.01) : 0
          if (Math.abs(video.currentTime - seekTime) < 0.001) {
            void paint()
          } else {
            try {
              video.currentTime = seekTime
            } catch {
              void paint()
            }
          }
        }

        video.addEventListener('error', onError, { once: true })
        video.addEventListener('loadeddata', onLoadedData, { once: true })
        video.addEventListener('seeked', onSeeked, { once: true })
        video.src = objectUrl
        video.load()
      })
    } finally {
      try {
        URL.revokeObjectURL(objectUrl)
      } catch {
        /* ignore */
      }
    }
  } finally {
    releaseSlot()
  }
}

/** 失败自动重试 1 次 */
export async function captureVideoPosterBlobWithRetry(
  videoUrl: string,
  options?: CaptureVideoPosterOptions
): Promise<Blob | null> {
  const first = await captureVideoPosterBlob(videoUrl, options)
  if (first) return first
  return captureVideoPosterBlob(videoUrl, options)
}

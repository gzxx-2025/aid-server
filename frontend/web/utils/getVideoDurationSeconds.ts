/**
 * 读取视频时长（秒）。支持本地 File（object URL）或远程/相对 URL。
 * 仅 preload metadata，不解码整段视频。
 */
export function getVideoDurationSeconds(source: File | string): Promise<number> {
  return new Promise((resolve, reject) => {
    let objectUrl: string | null = null
    let videoSrc = ''

    if (typeof source === 'string') {
      videoSrc = source.trim()
      if (!videoSrc) {
        reject(new Error('视频地址无效'))
        return
      }
    } else if (source instanceof File) {
      objectUrl = URL.createObjectURL(source)
      videoSrc = objectUrl
    } else {
      reject(new Error('视频来源无效'))
      return
    }

    const video = document.createElement('video')
    video.preload = 'metadata'
    video.muted = true
    video.playsInline = true

    let settled = false

    const cleanup = () => {
      video.removeEventListener('loadedmetadata', onLoaded)
      video.removeEventListener('error', onError)
      try {
        video.pause()
        video.removeAttribute('src')
        video.load()
      } catch {
        /* ignore */
      }
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl)
        objectUrl = null
      }
    }

    const finish = (fn: () => void) => {
      if (settled) return
      settled = true
      cleanup()
      fn()
    }

    const onLoaded = () => {
      const duration = Number(video.duration)
      if (Number.isFinite(duration) && duration > 0) {
        finish(() => resolve(duration))
        return
      }
      finish(() => reject(new Error('无法读取视频时长')))
    }

    const onError = () => {
      finish(() => reject(new Error('无法读取视频时长，请更换文件后重试')))
    }

    video.addEventListener('loadedmetadata', onLoaded, { once: true })
    video.addEventListener('error', onError, { once: true })
    video.src = videoSrc
    video.load()
  })
}

/** 转为接口约定的正整数秒；无效时返回 null */
export function toVideoDurationSecondsInt(raw: unknown): number | null {
  const n = Number(raw)
  if (!Number.isFinite(n) || n <= 0) return null
  return Math.max(1, Math.round(n))
}

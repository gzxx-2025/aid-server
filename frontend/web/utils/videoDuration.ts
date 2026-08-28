const VIDEO_METADATA_TIMEOUT_MS = 15_000

/** 将浏览器读取到的视频时长统一转换为后端使用的正整数秒。 */
export function normalizeVideoDurationSeconds(duration: unknown): number {
  const seconds = Number(duration)
  if (!Number.isFinite(seconds) || seconds <= 0) {
    throw new Error('视频时长读取失败')
  }
  return Math.ceil(seconds)
}

/**
 * 从本地文件或已上传视频 URL 读取媒体元数据；不下载整段视频内容。
 * 调用方必须在登记 upload_video 记录前取得时长，避免后端产生无时长视频。
 */
export function readVideoDurationSeconds(source: File | string): Promise<number> {
  return new Promise((resolve, reject) => {
    const sourceUrl = typeof source === 'string' ? source.trim() : URL.createObjectURL(source)
    const objectUrl = typeof source === 'string' ? null : sourceUrl
    if (!sourceUrl) {
      reject(new Error('视频时长读取失败'))
      return
    }

    const video = document.createElement('video')
    video.preload = 'metadata'
    video.muted = true
    video.playsInline = true
    let settled = false

    const cleanup = () => {
      window.clearTimeout(timeoutId)
      video.onloadedmetadata = null
      video.onerror = null
      try {
        video.removeAttribute('src')
        video.load()
      } catch {
        // 浏览器清理媒体元素失败不影响时长结果。
      }
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
    const finish = (callback: () => void) => {
      if (settled) return
      settled = true
      cleanup()
      callback()
    }
    const fail = () => finish(() => reject(new Error('视频时长读取失败')))
    const timeoutId = window.setTimeout(fail, VIDEO_METADATA_TIMEOUT_MS)

    video.onloadedmetadata = () => {
      try {
        const duration = normalizeVideoDurationSeconds(video.duration)
        finish(() => resolve(duration))
      } catch {
        fail()
      }
    }
    video.onerror = fail
    video.src = sourceUrl
    video.load()
  })
}

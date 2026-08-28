/**
 * 限制同时发起的媒体（尤其 mp4 metadata）请求数，避免弹窗打开时十几路并发把页面打卡死。
 */
const DEFAULT_MAX = 2

let active = 0
const waiters: Array<() => void> = []

export function acquireMediaLoadSlot(maxConcurrent = DEFAULT_MAX): Promise<() => void> {
  const limit = Math.max(1, maxConcurrent)
  return new Promise((resolve) => {
    const grant = () => {
      active += 1
      let released = false
      resolve(() => {
        if (released) return
        released = true
        active = Math.max(0, active - 1)
        const next = waiters.shift()
        if (next) next()
      })
    }
    if (active < limit) grant()
    else waiters.push(grant)
  })
}

export function isProbablyVideoUrl(url: string): boolean {
  const raw = String(url || '').trim()
  if (!raw) return false
  const path = raw.split('?')[0].split('#')[0].toLowerCase()
  if (/\.(mp4|webm|mov|m4v|mkv)(\b|$)/.test(path)) return true
  // 常见对象存储视频路径
  if (/\/(?:video|videos|mp4)\//i.test(path)) return true
  return false
}

export function isProbablyImageUrl(url: string): boolean {
  const raw = String(url || '').trim()
  if (!raw) return false
  const path = raw.split('?')[0]?.split('#')[0]?.toLowerCase() || ''
  return /\.(png|jpe?g|webp|gif|bmp|svg)(\b|$)/.test(path)
}

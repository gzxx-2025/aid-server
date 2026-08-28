function getViewportCompactScale(): number {
  if (!(typeof window !== 'undefined')) return 1
  return document.documentElement.getAttribute('data-viewport-compact-scale') === '1' ? 0.75 : 1
}

function appendQuery(url: string, query: string): string {
  const sep = url.includes('?') ? '&' : '?'
  return `${url}${sep}${query}`
}

function supportsAliyunOssProcess(url: string): boolean {
  return (
    /\.aliyuncs\.com/i.test(url) ||
    /cdn\.continueai\.cn/i.test(url) ||
    /\/(aid|ai_pet|oss)\//i.test(url)
  )
}

function supportsQiniuImageView(url: string): boolean {
  return /(?:clouddn|qiniucdn|qbox)\.com/i.test(url)
}

/**
 * 列表缩略图按展示尺寸 × DPR（并补偿视口 compact zoom）请求更清晰资源。
 * 非 OSS/CDN 或不支持处理参数的 URL 原样返回。
 */
export function buildRetinaDisplayImageUrl(
  url: string,
  displayCssWidthPx: number,
  options?: { quality?: number; maxDpr?: number }
): string {
  const raw = String(url || '').trim()
  if (!raw || !displayCssWidthPx) return raw
  if (/^data:|^blob:/i.test(raw)) return raw
  if (/x-oss-process=|imageView2\/|imageMogr2\//i.test(raw)) return raw

  const compact = getViewportCompactScale()
  const dpr = (typeof window !== 'undefined')
    ? Math.min(options?.maxDpr ?? 3, window.devicePixelRatio || 1)
    : 2
  const targetW = Math.max(1, Math.round((displayCssWidthPx / compact) * dpr))
  const quality = options?.quality ?? 92

  if (supportsAliyunOssProcess(raw)) {
    return appendQuery(
      raw,
      `x-oss-process=image/resize,w_${targetW}/quality,q_${quality}/format,webp`
    )
  }

  if (supportsQiniuImageView(raw)) {
    return appendQuery(raw, `imageView2/2/w/${targetW}/q/${quality}`)
  }

  return raw
}

export interface AspectRatioOption<T extends string = string> {
  value: T
  label: string
}

export interface AspectRatioSize {
  width: number
  height: number
}

export function parseAspectRatioParts(value: string): AspectRatioSize | null {
  const match = String(value || '').trim().match(/^(\d+(?:\.\d+)?)\s*:\s*(\d+(?:\.\d+)?)$/)
  if (!match) return null
  const width = Number(match[1])
  const height = Number(match[2])
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return null
  }
  return { width, height }
}

export function fitAspectRatioIconSize(value: string, maxSize = 18): AspectRatioSize {
  const parts = parseAspectRatioParts(value)
  if (!parts) return { width: maxSize, height: maxSize }
  const scale = maxSize / Math.max(parts.width, parts.height)
  return {
    width: Math.max(6, Math.round(parts.width * scale)),
    height: Math.max(6, Math.round(parts.height * scale))
  }
}

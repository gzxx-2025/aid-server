/**
 * 成对管理 ObjectURL，避免预览/下载后泄漏。
 */
export function createTrackedObjectUrl(blob: Blob): string {
  return URL.createObjectURL(blob)
}

export function revokeObjectUrl(url: string | null | undefined): void {
  if (!url) return
  try {
    URL.revokeObjectURL(url)
  } catch {
    /* ignore */
  }
}

/** 在同步使用 ObjectURL 后立即释放（如下载链接 click） */
export function withObjectUrl(blob: Blob, consume: (url: string) => void): void {
  const url = createTrackedObjectUrl(blob)
  try {
    consume(url)
  } finally {
    revokeObjectUrl(url)
  }
}

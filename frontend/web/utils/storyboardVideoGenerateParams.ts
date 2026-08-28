/**
 * 分镜视频出片公共入参：清晰度等字段格式与透传。
 * 供编辑弹窗 / 批量弹窗共用，避免各处手写不一致。
 */

/** 清晰度入参：对齐 capability.sizeOptions 常见写法（1K / 720P） */
export function formatVideoResolutionForApi(raw?: string | null): string | undefined {
  const q = String(raw || '').trim()
  if (!q) return undefined
  if (/^\d+k$/i.test(q)) return q.toUpperCase()
  if (/^\d+p$/i.test(q)) return q.toUpperCase()
  return q
}

export function buildStoryboardVideoResolutionField(
  quality?: string | null
): { resolution: string } | Record<string, never> {
  const resolution = formatVideoResolutionForApi(quality)
  return resolution ? { resolution } : {}
}

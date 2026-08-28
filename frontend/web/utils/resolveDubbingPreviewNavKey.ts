/**
 * 配音弹窗中间预览：有生成记录时跟配音结果，无记录时回退源视频
 */

/** 用户已点选的有效项：reconcile 时保持，避免被「使用中」主视频强制盖回 */
export function shouldPreserveDubbingNavSelection(input: {
  currentKey?: string | null
  hist: Array<{ id: string }>
  navKeySource: string
  navKeyLoading: string
}): boolean {
  const cur = String(input.currentKey || '').trim()
  if (!cur || cur === input.navKeyLoading) return false
  if (cur === input.navKeySource) return true
  return input.hist.some((h) => h.id === cur)
}

export function resolveDubbingPreviewNavKey(input: {
  hist: Array<{ id: string }>
  currentKey?: string | null
  navKeySource: string
  navKeyLoading: string
  hasSourceVideo: boolean
}): string {
  const cur = String(input.currentKey || '').trim()
  if (cur && cur !== input.navKeyLoading && input.hist.some((h) => h.id === cur)) {
    return cur
  }
  if (input.hist.length > 0) {
    return input.hist[input.hist.length - 1]!.id
  }
  if (input.hasSourceVideo) return input.navKeySource
  return cur && cur !== input.navKeyLoading ? cur : input.navKeySource
}

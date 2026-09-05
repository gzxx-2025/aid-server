/** 导出成功后的可播放地址：存在新生成成片时优先预览新成片。 */
export function resolveExportPlaybackUrl(payload: {
  finalVideoUrl?: string | null
  pendingVideoUrl?: string | null
}): string {
  const pending = String(payload.pendingVideoUrl || '').trim()
  const finalUrl = String(payload.finalVideoUrl || '').trim()
  return finalUrl || pending
}

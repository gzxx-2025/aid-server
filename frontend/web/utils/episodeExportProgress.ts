/**
 * 成片导出进度展示规则：
 * - exportStatus===2（成功）固定 100
 * - 接口 exportProgress 可为 100（合成中也可能回 100），进度条需原样展示，不再封顶 99
 */

/** 合成成功展示 100% 后，再等待多久才进入下载（给用户看满进度） */
export const EPISODE_EXPORT_COMPLETE_HOLD_MS = 1000

export function clampEpisodeExportProgressPercent(
  progress: unknown,
  exportStatus?: unknown
): number | undefined {
  if (Number(exportStatus) === 2) return 100
  const raw = Number(progress)
  if (!Number.isFinite(raw)) return undefined
  return Math.max(0, Math.min(100, raw))
}

/** UI 展示用：单调不回退（同一次导出任务内） */
export function resolveEpisodeExportProgressDisplay(input: {
  progress?: unknown
  exportStatus?: unknown
  previousPercent?: number
}): number {
  const status = Number(input.exportStatus)
  if (status === 2) return 100
  const prev = Number(input.previousPercent)
  const fallback = Number.isFinite(prev) && prev >= 0 ? (prev >= 100 ? 0 : prev) : 0
  const clamped = clampEpisodeExportProgressPercent(input.progress, input.exportStatus)
  const value = clamped != null ? clamped : fallback
  return Math.max(fallback, value)
}

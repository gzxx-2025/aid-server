/**
 * 对口型计费时长：max(视频秒数, 配音秒数)，再向上取整到 5 秒一档。
 * 与接口文档 POST /api/user/storyboard/lipSync 口径一致。
 */
export function calcLipSyncBillableSeconds(
  videoSeconds: number | null | undefined,
  audioSeconds: number | null | undefined
): number {
  const v = Number(videoSeconds)
  const a = Number(audioSeconds)
  const maxSec = Math.max(
    Number.isFinite(v) && v > 0 ? v : 0,
    Number.isFinite(a) && a > 0 ? a : 0
  )
  if (maxSec <= 0) return 0
  return Math.ceil(maxSec / 5) * 5
}

/**
 * POST /api/user/asset/rps/delete 批量删除：分片（≤50）与结果汇总。
 */

/** 文档：单批最多 50 个主资产 */
export const RPS_DELETE_BATCH_MAX = 50

export type RpsDeleteBatchFailure = { id?: number | null; reason?: string }

export type RpsDeleteBatchPart = {
  total?: number
  successCount?: number
  failCount?: number
  successIds?: number[]
  failures?: RpsDeleteBatchFailure[]
}

export type RpsDeleteBatchSummary = {
  successIds: number[]
  failCount: number
  failures: RpsDeleteBatchFailure[]
}

export function normalizeRpsDeleteIds(ids: number[]): number[] {
  return [...new Set(ids.map(Number).filter((n) => Number.isFinite(n) && n > 0))]
}

export function chunkRpsDeleteIds(ids: number[], max = RPS_DELETE_BATCH_MAX): number[][] {
  const unique = normalizeRpsDeleteIds(ids)
  const chunks: number[][] = []
  for (let i = 0; i < unique.length; i += max) {
    chunks.push(unique.slice(i, i + max))
  }
  return chunks
}

export function mergeRpsDeleteBatchResults(
  parts: Array<RpsDeleteBatchPart | null | undefined>
): RpsDeleteBatchSummary {
  const successIds: number[] = []
  const failures: RpsDeleteBatchFailure[] = []
  let failCount = 0

  for (const part of parts) {
    if (!part) continue
    for (const id of part.successIds ?? []) {
      const n = Number(id)
      if (Number.isFinite(n) && n > 0) successIds.push(n)
    }
    for (const f of part.failures ?? []) {
      failures.push(f)
    }
    const chunkFail = Number(part.failCount)
    if (Number.isFinite(chunkFail)) {
      failCount += chunkFail
    } else {
      const ok = (part.successIds ?? []).length
      const total = Number(part.total)
      failCount += Number.isFinite(total) ? Math.max(0, total - ok) : 0
    }
  }

  return {
    successIds: [...new Set(successIds)],
    failCount,
    failures
  }
}

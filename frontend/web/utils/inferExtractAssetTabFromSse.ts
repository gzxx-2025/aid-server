export type ExtractAssetTabKey = 'scene' | 'character' | 'prop'

/**
 * 根据任务 SSE 的 stage / 文案推断当前资产提取落在场景、角色还是道具 Tab。
 */
export function inferExtractAssetTabFromSse(p: {
  stage?: string | null
  stepTitle?: string | null
  message?: string | null
}): ExtractAssetTabKey | null {
  const rawStage = String(p.stage || '').trim().toLowerCase()
  if (rawStage) {
    if (rawStage === 'scene' || rawStage.includes('scene')) return 'scene'
    if (rawStage === 'character' || rawStage.includes('character') || rawStage.includes('role'))
      return 'character'
    if (rawStage === 'prop' || rawStage.includes('prop')) return 'prop'
  }
  const text = `${String(p.stepTitle || '')} ${String(p.message || '')}`.toLowerCase()
  if (!text.trim()) return null
  if (text.includes('场景') || text.includes('scene')) return 'scene'
  if (text.includes('角色') || text.includes('character')) return 'character'
  if (text.includes('道具') || text.includes('prop')) return 'prop'
  return null
}

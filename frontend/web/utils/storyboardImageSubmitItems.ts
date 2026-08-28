import type { StoryboardImageGenerateItemResult } from '~/types/business-api'

/** 提交阶段 items 中未受理镜头的跳过原因（accepted=false） */
export function formatStoryboardImageSubmitRejections(
  items?: StoryboardImageGenerateItemResult[] | null
): string | null {
  if (!items?.length) return null
  const rejected = items.filter((item) => item && item.accepted === false)
  if (!rejected.length) return null
  const reasons = rejected
    .map((item) => {
      const sid = Number(item.storyboardId)
      const reason = String(item.reason || '').trim()
      if (!Number.isFinite(sid) || sid <= 0) return reason
      return reason ? `分镜${sid}：${reason}` : `分镜${sid}未受理`
    })
    .filter(Boolean)
  return reasons.length ? reasons.join('；') : null
}

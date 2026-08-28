import { touchSubtitleItems,touchVoiceItems } from './timelineOps'
import { MIN_DURATION,type VideoPreviewCtx } from './types'
export function constrainLinkedItemsToVideo(ctx: VideoPreviewCtx, videoClipId: string) {
  const state = ctx.state
  const clip = state.videoClips.get().find((item) => item.id === videoClipId)
  if (!clip) return

  const clipEnd = clip.start + clip.duration
  state.subtitleItems.get().forEach((item) => {
    if (item.videoClipId !== videoClipId) return
    if (item.start < clip.start) item.start = clip.start
    if (item.start + item.duration > clipEnd) {
      item.duration = Math.max(MIN_DURATION, Number((clipEnd - item.start).toFixed(2)))
    }
  })
  state.voiceItems.get().forEach((item) => {
    if (item.videoClipId !== videoClipId) return
    item.start = clip.start
    item.duration = Math.max(MIN_DURATION, Number(clip.duration.toFixed(2)))
  })
  touchSubtitleItems(ctx)
  touchVoiceItems(ctx)
}

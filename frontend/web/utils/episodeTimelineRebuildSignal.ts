/** 批量配音/对口型或手动设配音主视频后，通知成品预览页重建时间轴（rebuild: true） */
export const EPISODE_TIMELINE_REBUILD_EVENT = 'create-flow-episode-timeline-rebuild'

export function notifyEpisodeTimelineRebuildRequested(): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(EPISODE_TIMELINE_REBUILD_EVENT))
}

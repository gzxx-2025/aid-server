import { message } from 'antd'
import { loadEpisodeTimeline } from '~/hooks/useEpisodeTimeline'
import { useCreationStore } from '~/stores/creation'
import {
resolvePreviewTimelineVideoUrl
} from '~/utils/storyboardVideoCover'
import { resetPlayheadToStart } from './playbackOps'
import { applyServerTimelineUi,buildTimelineFromProps,getInitialClipDuration,hydrateVideoDurationsFromSource,projectScopeKeyOf } from './timelineDataOps'
import {
type TimelineVideoClip,
type VideoPreviewCtx
} from './types'
export async function reloadEpisodeTimelineFromServer(
  ctx: VideoPreviewCtx,
  opts?: { rebuild?: boolean; showMessage?: boolean }
) {
  const S = ctx.state
  S.timelineLoading.set(true)
  try {
    const { result, ui } = await loadEpisodeTimeline({
      store: useCreationStore.getState(),
      route: ctx.getRoute(),
      rebuild: Boolean(opts?.rebuild)
    })
    S.serverTimelineBaseline.set(result.timeline)
    applyServerTimelineUi(ctx, ui)
    ctx.runtime.lastHydratedScopeKey = projectScopeKeyOf()
    if (opts?.showMessage) {
      message.success('已按最新分镜数据重置时间轴')
    }
    resetPlayheadToStart(ctx)
  } catch (e: unknown) {
    if (opts?.showMessage) {
      const msg = String((e as Error)?.message || (e as { msg?: string })?.msg || '')
      if (!buildTimelineFromProps(ctx, { showSuccessMessage: true })) {
        message.warning(msg || '暂无分镜/配音数据，请先完成前面步骤')
      } else {
        resetPlayheadToStart(ctx)
      }
    }
  } finally {
    S.timelineLoading.set(false)
    setTimeout(() => resetPlayheadToStart(ctx), 0)
  }
}

export function syncFromPreviousSteps(ctx: VideoPreviewCtx) {
  void reloadEpisodeTimelineFromServer(ctx, { rebuild: true, showMessage: true })
}

export function onEpisodeTimelineRebuildRequested(ctx: VideoPreviewCtx) {
  void reloadEpisodeTimelineFromServer(ctx, { rebuild: true })
}

export function autoInitPlaceholderClipsFromProps(ctx: VideoPreviewCtx) {
  const S = ctx.state
  const props = ctx.getProps()
  if (S.videoClips.get().length) return
  const n = Math.max(props.dubbingPanels?.length || 0, props.storyboardVideoPanels?.length || 0)
  if (!n) return

  const list: TimelineVideoClip[] = []
  let cursor = 0
  for (let i = 0; i < n; i++) {
    const dub = props.dubbingPanels?.[i]
    const vp = props.storyboardVideoPanels?.[i]
    const name = dub?.title || vp?.title || `分镜${i + 1}`
    const url = resolvePreviewTimelineVideoUrl(dub, vp)
    const dur = getInitialClipDuration(url)
    list.push({
      id: dub?.id || vp?.id || `placeholder-${i}-${Date.now()}`,
      kind: 'video',
      name,
      url,
      start: cursor,
      duration: dur,
      sourceDuration: dur,
      trimStart: 0,
      trimEnd: dur
    })
    cursor += dur
  }
  S.videoClips.set(list)
  S.currentTime.set(0)
  S.playing.set(false)
  S.selectedClip.set({ track: 'video', id: list[0]!.id })
  void hydrateVideoDurationsFromSource(ctx)
}

export function hydrateTimelineForCurrentProject(ctx: VideoPreviewCtx) {
  const S = ctx.state
  const scope = projectScopeKeyOf()
  if (!scope || scope === 'null:null' || scope === ':') return
  if (ctx.runtime.lastHydratedScopeKey === scope && S.videoClips.get().length) return

  void (async () => {
    S.timelineLoading.set(true)
    try {
      const { result, ui } = await loadEpisodeTimeline({
        store: useCreationStore.getState(),
        route: ctx.getRoute(),
        rebuild: false
      })
      if (projectScopeKeyOf() !== scope) return
      S.serverTimelineBaseline.set(result.timeline)
      applyServerTimelineUi(ctx, ui)
      ctx.runtime.lastHydratedScopeKey = scope
    } catch {
      if (projectScopeKeyOf() !== scope) return
      if (buildTimelineFromProps(ctx)) {
        ctx.runtime.lastHydratedScopeKey = scope
        S.selectedClip.set(
          S.videoClips.get()[0] ? { track: 'video', id: S.videoClips.get()[0]!.id } : null
        )
        return
      }
      autoInitPlaceholderClipsFromProps(ctx)
      if (S.videoClips.get().length) ctx.runtime.lastHydratedScopeKey = scope
    } finally {
      S.timelineLoading.set(false)
    }
  })()
}

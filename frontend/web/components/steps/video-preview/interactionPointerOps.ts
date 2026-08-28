import type * as React from 'react'
import { ensurePreviewAtCurrentTime,scheduleRebuild } from './canvasOps'
import { constrainLinkedItemsToVideo } from './interactionLinkedOps'
import {
findItem,
getSnapDistanceSec,
getTotalDuration,
getTrackItems,
getVideoClipAtTime,
getVideoVolume,
layoutPxToSec,
secToLayoutPx
} from './layoutOps'
import {
pauseAutoFollow,
scheduleAutoFollowResume,
scrollPlayheadIntoView,
seekToTime,
setTimelineScrollLeft,
stopPlayback,
syncNativePreviewVideoTime,
syncPreviewAudios
} from './playbackOps'
import {
  applyVideoSceneReorder,
  decideVideoSceneReorder,
  resolveInsertIndicatorSec
} from '~/utils/videoPreviewSceneReorder'
import { dragPointerDeltaSec } from '~/utils/videoPreviewDragScroll'
import {
relayoutVideoTrackWithLinkedByOrder,
scheduleTimelinePersist,
touchSubtitleItems,
touchVideoClips,
touchVoiceItems
} from './timelineOps'
import {
MIN_DURATION,
SCALE_PX_PER_SEC,
type ResizeSide,
type TrackType,
type VideoPreviewCtx
} from './types'
function touchTrack(ctx: VideoPreviewCtx, track: TrackType) {
  const S = ctx.state
  if (track === 'video') S.videoClips.set([...S.videoClips.get()])
  else if (track === 'voice') S.voiceItems.set([...S.voiceItems.get()])
  else if (track === 'subtitle') S.subtitleItems.set([...S.subtitleItems.get()])
  else S.musicItems.set([...S.musicItems.get()])
}

// --- volume bar ---

export function isVolumeBarActive(ctx: VideoPreviewCtx, clipId: string) {
  return (
    ctx.state.volumeHoverClipId.get() === clipId || ctx.state.volumeDrag.get()?.clipId === clipId
  )
}

export function onVolumeBarMouseEnter(ctx: VideoPreviewCtx, clipId: string) {
  ctx.state.volumeHoverClipId.set(clipId)
}

export function setVideoVolume(ctx: VideoPreviewCtx, clipId: string, volume: number) {
  const S = ctx.state
  const v = Math.max(0, Math.min(2, Number(volume.toFixed(2))))
  const voice = S.voiceItems.get().find((item) => item.videoClipId === clipId)
  if (voice) {
    voice.volume = v
    voice.volumeCurve = [v, v, v]
    touchVoiceItems(ctx)
    if (S.playing.get()) {
      syncPreviewAudios(ctx)
    } else {
      scheduleRebuild(ctx, 'audio')
    }
    return
  }
  S.videoVolumePreset.set({ ...S.videoVolumePreset.get(), [clipId]: v })
  const activeClip = getVideoClipAtTime(ctx, S.currentTime.get())
  if (S.playing.get() && activeClip?.id === clipId) {
    syncNativePreviewVideoTime(ctx)
  }
}

export function onVolumeBarPointerDown(ctx: VideoPreviewCtx, e: React.PointerEvent, clipId: string) {
  const shell = e.currentTarget as HTMLElement
  const rect = shell.getBoundingClientRect()
  const barHeight = rect.height || 38
  const ratio = 1 - Math.max(0, Math.min(1, (e.clientY - rect.top) / barHeight))
  setVideoVolume(ctx, clipId, ratio * 2)
  ctx.state.volumeDrag.set({
    clipId,
    startY: e.clientY,
    startVolume: getVideoVolume(ctx, clipId),
    barHeight
  })
  ctx.state.volumeHoverClipId.set(clipId)
  pauseAutoFollow(ctx)
  try {
    shell.setPointerCapture(e.pointerId)
  } catch {}
}

export function onVolumeBarMouseLeave(ctx: VideoPreviewCtx, clipId: string) {
  if (ctx.state.volumeDrag.get()?.clipId === clipId) return
  if (ctx.state.volumeHoverClipId.get() === clipId) ctx.state.volumeHoverClipId.set(null)
}

function updateVolumeFromPointer(ctx: VideoPreviewCtx, e: PointerEvent) {
  const drag = ctx.state.volumeDrag.get()
  if (!drag) return
  const wrap = ctx.dom.timelineWrapRef.current
  const shell = wrap?.querySelector(
    `[data-volume-clip="${drag.clipId}"] .volume-bar-shell`
  ) as HTMLElement | null
  if (shell) {
    const rect = shell.getBoundingClientRect()
    const barHeight = rect.height || drag.barHeight
    const ratio = 1 - Math.max(0, Math.min(1, (e.clientY - rect.top) / barHeight))
    setVideoVolume(ctx, drag.clipId, ratio * 2)
    return
  }
  const deltaY = drag.startY - e.clientY
  const deltaVol = (deltaY / drag.barHeight) * 2
  setVideoVolume(ctx, drag.clipId, drag.startVolume + deltaVol)
}

function stopVolumeDrag(ctx: VideoPreviewCtx) {
  if (!ctx.state.volumeDrag.get()) return
  ctx.state.volumeDrag.set(null)
  scheduleTimelinePersist(ctx)
  if (ctx.state.playing.get()) scheduleAutoFollowResume(ctx, 600)
}

// --- pointer drag (move/resize) ---

export function pxToSec(ctx: VideoPreviewCtx, px: number) {
  return layoutPxToSec(ctx, Math.max(0, px))
}

function getStripLeftPx(ctx: VideoPreviewCtx) {
  const wrap = ctx.dom.timelineWrapRef.current
  if (!wrap) return 0
  const rect = wrap.getBoundingClientRect()
  return rect.left + wrap.clientLeft + ctx.state.trackLabelWidth.get() - wrap.scrollLeft
}

export function selectClip(ctx: VideoPreviewCtx, track: TrackType, id: string) {
  ctx.state.selectedClip.set({ track, id })
  const item = findItem(ctx, track, id)
  if (!item) return

  stopPlayback(ctx)
  seekToTime(ctx, item.start)
  void ensurePreviewAtCurrentTime(ctx)
  setTimeout(() => scrollPlayheadIntoView(ctx), 0)
}

function markSwapping(ctx: VideoPreviewCtx, ids: string[]) {
  if (!ids.length) return
  const next = new Set(ctx.state.swappingClipIds.get())
  ids.forEach((id) => next.add(id))
  ctx.state.swappingClipIds.set(next)
  window.setTimeout(() => {
    const cleared = new Set(ctx.state.swappingClipIds.get())
    ids.forEach((id) => cleared.delete(id))
    ctx.state.swappingClipIds.set(cleared)
  }, 260)
}

function snapStart(
  ctx: VideoPreviewCtx,
  track: TrackType,
  id: string,
  start: number,
  duration: number,
  mode: 'move' | 'resize-start' | 'resize-end'
) {
  const S = ctx.state
  if (!S.snapEnabled.get()) {
    S.snapIndicatorPx.set(null)
    return Math.max(0, Number(start.toFixed(2)))
  }
  const candidates: number[] = [0]
  if (S.snapSourceMode.get() === 'edges-playhead') {
    candidates.push(S.currentTime.get())
  }
  if (S.snapSourceMode.get() === 'edges-grid') {
    const gridStepSec = 1
    const maxT = Math.ceil(Math.max(getTotalDuration(ctx), start + duration + 5))
    for (let t = 0; t <= maxT; t += gridStepSec) candidates.push(t)
  }
  const list = getTrackItems(ctx, track)
  for (const it of list) {
    if ((it as any).id === id) continue
    candidates.push(it.start, it.start + it.duration)
  }
  let snappedStart = start
  let nearest: number | null = null
  let bestDist = Infinity
  const targetHead = start
  const targetTail = start + duration
  for (const c of candidates) {
    const distHead = Math.abs(c - targetHead)
    if (distHead < bestDist && distHead <= getSnapDistanceSec(ctx)) {
      bestDist = distHead
      snappedStart = c
      nearest = c
    }
    const distTail = Math.abs(c - targetTail)
    if (distTail < bestDist && distTail <= getSnapDistanceSec(ctx)) {
      bestDist = distTail
      snappedStart = c - duration
      nearest = c
    }
  }
  S.snapIndicatorPx.set(nearest === null ? null : secToLayoutPx(ctx, nearest))
  if (mode === 'resize-end') return start
  return Math.max(0, Number(snappedStart.toFixed(2)))
}

export function resolveOverlap(ctx: VideoPreviewCtx, track: TrackType, movingId: string) {
  const list = getTrackItems(ctx, track) as Array<any>
  list.sort((a, b) => a.start - b.start)
  const changedIds = new Set<string>()
  for (let i = 1; i < list.length; i++) {
    const prev = list[i - 1]
    const cur = list[i]
    const prevEnd = prev.start + prev.duration
    if (cur.start < prevEnd) {
      changedIds.add(cur.id)
      cur.start = Number(prevEnd.toFixed(2))
    }
  }
  // 交换策略：移动项大幅跨过中点时和临近项交换优先顺序
  const moving = list.find((x) => x.id === movingId)
  if (!moving) {
    touchTrack(ctx, track)
    return
  }
  for (const it of list) {
    if (it.id === moving.id) continue
    const overlap =
      Math.min(moving.start + moving.duration, it.start + it.duration) -
      Math.max(moving.start, it.start)
    if (overlap > Math.min(moving.duration, it.duration) * 0.6) {
      const temp = moving.start
      moving.start = it.start
      it.start = temp
      changedIds.add(moving.id)
      changedIds.add(it.id)
      break
    }
  }
  touchTrack(ctx, track)
  if (track === 'video') {
    relayoutVideoTrackWithLinkedByOrder(ctx)
  }
  markSwapping(ctx, Array.from(changedIds))
}

export function onClipPointerDown(
  ctx: VideoPreviewCtx,
  e: React.PointerEvent,
  track: TrackType,
  id: string
) {
  const item = findItem(ctx, track, id)
  if (!item) return
  const wrap = ctx.dom.timelineWrapRef.current
  ctx.state.dragState.set({
    kind: 'move',
    track,
    id,
    startX: e.clientX,
    originStart: item.start,
    originScrollLeft: wrap?.scrollLeft ?? 0,
    lastClientX: e.clientX
  })
  selectClip(ctx, track, id)
}

export function onResizePointerDown(
  ctx: VideoPreviewCtx,
  e: React.PointerEvent,
  track: TrackType,
  id: string,
  side: ResizeSide
) {
  const item = findItem(ctx, track, id)
  if (!item) return
  const wrap = ctx.dom.timelineWrapRef.current
  ctx.state.dragState.set({
    kind: 'resize',
    track,
    id,
    side,
    startX: e.clientX,
    originStart: item.start,
    originDuration: item.duration,
    originScrollLeft: wrap?.scrollLeft ?? 0,
    lastClientX: e.clientX
  })
  selectClip(ctx, track, id)
}

export function onTimelinePointerDown(ctx: VideoPreviewCtx, e: React.PointerEvent) {
  const target = e.target as HTMLElement
  if (target.closest('.track-strip-music') || target.closest('.track-clip-music')) return
  startTimelineScrub(ctx, e.clientX)
}

export function setCurrentTimeFromClientX(ctx: VideoPreviewCtx, clientX: number) {
  const stripLeft = getStripLeftPx(ctx)
  const x = clientX - stripLeft
  const sec = Math.max(0, Math.min(getTotalDuration(ctx), pxToSec(ctx, x)))
  seekToTime(ctx, sec)
}

function startTimelineScrub(ctx: VideoPreviewCtx, clientX: number) {
  ctx.state.scrubbing.set(true)
  ctx.state.scrubClientX.set(clientX)
  pauseAutoFollow(ctx)
  setCurrentTimeFromClientX(ctx, clientX)
}

function stopTimelineScrub(ctx: VideoPreviewCtx) {
  ctx.state.scrubbing.set(false)
  ctx.state.scrubClientX.set(null)
  if (ctx.state.playing.get()) scheduleAutoFollowResume(ctx, 800)
}

export function autoScrollTimelineWhileScrub(ctx: VideoPreviewCtx, clientX: number) {
  autoScrollTimelineNearEdge(ctx, clientX, { edgePx: 36, maxStep: 24 })
}

/** 拖拽分镜时边缘区更大，长分镜也能持续滚出可视区 */
export function autoScrollTimelineNearEdge(
  ctx: VideoPreviewCtx,
  clientX: number,
  opts?: { edgePx?: number; maxStep?: number }
) {
  const wrap = ctx.dom.timelineWrapRef.current
  if (!wrap) return 0
  const rect = wrap.getBoundingClientRect()
  const edgePx = opts?.edgePx ?? 56
  const maxStep = opts?.maxStep ?? 32
  let nextScrollLeft = wrap.scrollLeft
  if (clientX < rect.left + edgePx) {
    const ratio = (rect.left + edgePx - clientX) / edgePx
    const step = Math.min(maxStep, Math.max(2, Math.round(maxStep * ratio)))
    nextScrollLeft = Math.max(0, wrap.scrollLeft - step)
  } else if (clientX > rect.right - edgePx) {
    const ratio = (clientX - (rect.right - edgePx)) / edgePx
    const step = Math.min(maxStep, Math.max(2, Math.round(maxStep * ratio)))
    const maxScroll = Math.max(0, wrap.scrollWidth - wrap.clientWidth)
    nextScrollLeft = Math.min(maxScroll, wrap.scrollLeft + step)
  }
  if (nextScrollLeft === wrap.scrollLeft) return 0
  const delta = nextScrollLeft - wrap.scrollLeft
  setTimelineScrollLeft(ctx, wrap, nextScrollLeft)
  return delta
}

function readDragScrollLeft(ctx: VideoPreviewCtx) {
  return ctx.dom.timelineWrapRef.current?.scrollLeft ?? 0
}

function applyMoveDragFrame(ctx: VideoPreviewCtx, clientX: number) {
  const S = ctx.state
  const st = S.dragState.get()
  if (!st || st.kind !== 'move') return
  st.lastClientX = clientX
  if (st.track === 'video') {
    autoScrollTimelineNearEdge(ctx, clientX, { edgePx: 72, maxStep: 36 })
  }
  const dSec = dragPointerDeltaSec({
    clientX,
    startX: st.startX,
    scrollLeft: readDragScrollLeft(ctx),
    originScrollLeft: st.originScrollLeft,
    pxPerSec: SCALE_PX_PER_SEC
  })
  const item = findItem(ctx, st.track, st.id)
  if (!item) return

  const oldStart = item.start
  const raw = Math.max(0, Number((st.originStart + dSec).toFixed(2)))
  item.start = snapStart(ctx, st.track, st.id, raw, item.duration, 'move')
  if (st.track === 'video') {
    touchTrack(ctx, 'video')
    const snapshot = S.videoClips
      .get()
      .map((c) => ({ id: c.id, start: c.start, duration: c.duration }))
    const decision = decideVideoSceneReorder(snapshot, st.id)
    const indicatorSec = resolveInsertIndicatorSec(snapshot, st.id, decision)
    S.snapIndicatorPx.set(indicatorSec == null ? null : secToLayoutPx(ctx, indicatorSec))
    const delta = item.start - oldStart
    if (Math.abs(delta) > 0.0001) {
      S.subtitleItems.get().forEach((s) => {
        if (s.videoClipId === item.id) s.start = Math.max(0, Number((s.start + delta).toFixed(2)))
      })
      S.voiceItems.get().forEach((v) => {
        if (v.videoClipId === item.id) v.start = Math.max(0, Number((v.start + delta).toFixed(2)))
      })
      touchSubtitleItems(ctx)
      touchVoiceItems(ctx)
    }
    return
  }
  resolveOverlap(ctx, st.track, st.id)
}

/** 供 rAF：指针停在边缘时持续滚动并刷新分镜落点 */
export function tickVideoClipDragAutoScroll(ctx: VideoPreviewCtx) {
  const st = ctx.state.dragState.get()
  if (!st || st.kind !== 'move' || st.track !== 'video') return
  applyMoveDragFrame(ctx, st.lastClientX)
}

export function onPointerMove(ctx: VideoPreviewCtx, e: PointerEvent) {
  const S = ctx.state
  if (S.volumeDrag.get()) {
    updateVolumeFromPointer(ctx, e)
  }
  if (S.scrubbing.get()) {
    S.scrubClientX.set(e.clientX)
    autoScrollTimelineWhileScrub(ctx, e.clientX)
    setCurrentTimeFromClientX(ctx, e.clientX)
  }
  const st = S.dragState.get()
  if (!st) return

  if (st.kind === 'move') {
    applyMoveDragFrame(ctx, e.clientX)
    return
  }

  st.lastClientX = e.clientX
  const dSec = dragPointerDeltaSec({
    clientX: e.clientX,
    startX: st.startX,
    scrollLeft: readDragScrollLeft(ctx),
    originScrollLeft: st.originScrollLeft,
    pxPerSec: SCALE_PX_PER_SEC
  })
  const item = findItem(ctx, st.track, st.id)
  if (!item) return

  if (st.side === 'start') {
    const end = st.originStart + st.originDuration
    const newStart = Math.max(0, Number((st.originStart + dSec).toFixed(2)))
    const snapped = snapStart(
      ctx,
      st.track,
      st.id,
      Math.min(newStart, end - MIN_DURATION),
      end - Math.min(newStart, end - MIN_DURATION),
      'resize-start'
    )
    item.start = Math.min(snapped, end - MIN_DURATION)
    item.duration = Math.max(MIN_DURATION, Number((end - item.start).toFixed(2)))
    touchTrack(ctx, st.track)
    if (st.track === 'video') constrainLinkedItemsToVideo(ctx, item.id)
  } else {
    item.duration = Math.max(MIN_DURATION, Number((st.originDuration + dSec).toFixed(2)))
    resolveOverlap(ctx, st.track, st.id)
    if (st.track === 'video') constrainLinkedItemsToVideo(ctx, item.id)
  }
}

export function onPointerUp(ctx: VideoPreviewCtx) {
  const S = ctx.state
  stopVolumeDrag(ctx)
  stopTimelineScrub(ctx)
  const st = S.dragState.get()
  if (!st) return
  if (st.track === 'video') {
    if (st.kind === 'move') {
      finalizeVideoSceneReorder(ctx, st.id, st.originStart)
    } else {
      relayoutVideoTrackWithLinkedByOrder(ctx)
    }
    scheduleTimelinePersist(ctx)
  } else if (st.track === 'voice') {
    // 配音拖拽/缩放后仍强制对齐所属分镜，避免出现「只有一点」的短条
    const voice = S.voiceItems.get().find((v) => v.id === st.id)
    const clip = voice?.videoClipId
      ? S.videoClips.get().find((c) => c.id === voice.videoClipId)
      : null
    if (voice && clip) {
      voice.start = clip.start
      voice.duration = Math.max(MIN_DURATION, Number(clip.duration.toFixed(2)))
      touchVoiceItems(ctx)
    }
    scheduleTimelinePersist(ctx)
  } else if (st.track === 'subtitle' || st.track === 'music') {
    scheduleTimelinePersist(ctx)
  }
  S.dragState.set(null)
  S.snapIndicatorPx.set(null)
  scheduleRebuild(ctx, 'all')
}

function finalizeVideoSceneReorder(
  ctx: VideoPreviewCtx,
  movingId: string,
  movingOriginStart: number
) {
  const S = ctx.state
  const snapshot = S.videoClips
    .get()
    .map((c) => ({ id: c.id, start: c.start, duration: c.duration }))
  const decision = decideVideoSceneReorder(snapshot, movingId)
  const packed = applyVideoSceneReorder(snapshot, movingId, decision, movingOriginStart)
  const byId = new Map(packed.map((c) => [c.id, c.start]))
  const nextClips = S.videoClips.get().map((clip) => {
    const start = byId.get(clip.id)
    if (start != null) clip.start = start
    return clip
  })
  // 按新 start 排序写回，保证列表顺序与成片顺序一致
  nextClips.sort((a, b) => a.start - b.start || a.id.localeCompare(b.id))
  S.videoClips.set(nextClips)
  touchVideoClips(ctx)
  relayoutVideoTrackWithLinkedByOrder(ctx)

  const changedIds = packed.map((c) => c.id)
  if (decision.kind === 'swap') {
    markSwapping(ctx, [movingId, decision.targetId])
  } else if (decision.kind === 'insert') {
    markSwapping(ctx, changedIds.slice(0, 3))
  }
}

// --- click to add ---

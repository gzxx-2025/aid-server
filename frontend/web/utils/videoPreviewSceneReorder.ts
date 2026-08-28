/**
 * 成品预览时间轴：分镜整组换序判定（纯函数，无 DOM / store 依赖）。
 * - 深重叠 → 对调（基于拖动前主序）
 * - 否则按落点中心插入
 */

export type SceneClipLike = {
  id: string
  start: number
  duration: number
}

export type VideoSceneReorderDecision =
  | { kind: 'noop' }
  | { kind: 'swap'; targetId: string }
  | { kind: 'insert'; toIndex: number }

export const VIDEO_SCENE_SWAP_OVERLAP_RATIO = 0.6

function clipCenter(clip: SceneClipLike): number {
  return clip.start + clip.duration / 2
}

function overlapSeconds(a: SceneClipLike, b: SceneClipLike): number {
  return (
    Math.min(a.start + a.duration, b.start + b.duration) - Math.max(a.start, b.start)
  )
}

function sortByStart(clips: SceneClipLike[]): SceneClipLike[] {
  return clips.slice().sort((a, b) => a.start - b.start || a.id.localeCompare(b.id))
}

/** 判定用：moving 用落点 start；其它 clip 保持主序 start（拖动中不应被改） */
export function decideVideoSceneReorder(
  clips: SceneClipLike[],
  movingId: string,
  overlapRatioThreshold = VIDEO_SCENE_SWAP_OVERLAP_RATIO
): VideoSceneReorderDecision {
  if (clips.length <= 1) return { kind: 'noop' }
  const moving = clips.find((c) => c.id === movingId)
  if (!moving) return { kind: 'noop' }

  let bestTarget: SceneClipLike | null = null
  let bestOverlap = 0
  for (const other of clips) {
    if (other.id === moving.id) continue
    const overlap = overlapSeconds(moving, other)
    if (overlap <= 0) continue
    const ratio = overlap / Math.min(moving.duration, other.duration)
    if (ratio > overlapRatioThreshold && overlap > bestOverlap) {
      bestOverlap = overlap
      bestTarget = other
    }
  }
  if (bestTarget) {
    return { kind: 'swap', targetId: bestTarget.id }
  }

  const others = sortByStart(clips.filter((c) => c.id !== movingId))
  const center = clipCenter(moving)
  let toIndex = others.length
  for (let i = 0; i < others.length; i++) {
    if (center < clipCenter(others[i]!)) {
      toIndex = i
      break
    }
  }
  return { kind: 'insert', toIndex }
}

/**
 * @param movingOriginStart 拖动前 moving 的 start，用于对调时恢复主序（缺省则用 clips 内 start）
 */
export function buildReorderedSceneClips(
  clips: SceneClipLike[],
  movingId: string,
  decision: VideoSceneReorderDecision,
  movingOriginStart?: number
): SceneClipLike[] {
  if (decision.kind === 'noop' || clips.length <= 1) {
    return sortByStart(clips)
  }

  const moving = clips.find((c) => c.id === movingId)
  if (!moving) return sortByStart(clips)

  if (decision.kind === 'swap') {
    const home = sortByStart(
      clips.map((c) =>
        c.id === movingId && movingOriginStart != null
          ? { ...c, start: movingOriginStart }
          : c
      )
    )
    const i = home.findIndex((c) => c.id === movingId)
    const j = home.findIndex((c) => c.id === decision.targetId)
    if (i < 0 || j < 0) return home
    const swapped = home.slice()
    ;[swapped[i], swapped[j]] = [swapped[j]!, swapped[i]!]
    return swapped
  }

  const others = sortByStart(
    clips
      .filter((c) => c.id !== movingId)
      .map((c) =>
        // 其它轨保持主序；若误带上 moving 的临时值，这里已过滤
        c
      )
  )
  const toIndex = Math.max(0, Math.min(decision.toIndex, others.length))
  return [...others.slice(0, toIndex), moving, ...others.slice(toIndex)]
}

export function packSceneClipStarts(ordered: SceneClipLike[]): SceneClipLike[] {
  let cursor = 0
  return ordered.map((clip) => {
    const start = Number(cursor.toFixed(2))
    cursor = Number((cursor + clip.duration).toFixed(2))
    return { ...clip, start }
  })
}

export function applyVideoSceneReorder(
  clips: SceneClipLike[],
  movingId: string,
  decision: VideoSceneReorderDecision,
  movingOriginStart?: number
): SceneClipLike[] {
  return packSceneClipStarts(
    buildReorderedSceneClips(clips, movingId, decision, movingOriginStart)
  )
}

/** 插入指示线时间（秒） */
export function resolveInsertIndicatorSec(
  clips: SceneClipLike[],
  movingId: string,
  decision: VideoSceneReorderDecision
): number | null {
  if (decision.kind === 'noop') return null
  if (decision.kind === 'swap') {
    const target = clips.find((c) => c.id === decision.targetId)
    return target ? target.start : null
  }
  const others = sortByStart(clips.filter((c) => c.id !== movingId))
  if (decision.toIndex <= 0) return 0
  if (decision.toIndex >= others.length) {
    const last = others[others.length - 1]
    return last ? last.start + last.duration : 0
  }
  return others[decision.toIndex]?.start ?? null
}

import type { StudioCanvasNode } from '@/types/studio'
import { studioNodeDimensions } from '@/utils/studio/studioNodeLod'

export interface StudioNodeRect {
  x: number
  y: number
  width: number
  height: number
}

export const STUDIO_NODE_PLACEMENT_GAP = 28

export function studioNodeRectsOverlap(
  a: StudioNodeRect,
  b: StudioNodeRect,
  gap = STUDIO_NODE_PLACEMENT_GAP
): boolean {
  return !(
    a.x + a.width + gap <= b.x ||
    b.x + b.width + gap <= a.x ||
    a.y + a.height + gap <= b.y ||
    b.y + b.height + gap <= a.y
  )
}

/** 在目标点附近寻找不与已有节点重叠的位置（先下再右螺旋扫描）。 */
export function placeStudioNodeWithoutOverlap(
  desired: { x: number; y: number },
  size: { width: number; height: number },
  occupied: StudioNodeRect[],
  gap = STUDIO_NODE_PLACEMENT_GAP
): { x: number; y: number } {
  const candidate = { x: desired.x, y: desired.y, width: size.width, height: size.height }
  if (!occupied.some((rect) => studioNodeRectsOverlap(candidate, rect, gap))) {
    return { x: desired.x, y: desired.y }
  }

  const stepY = Math.max(48, Math.round(size.height * 0.35))
  const stepX = Math.max(48, Math.round(size.width * 0.35))
  const maxRing = 24
  for (let ring = 1; ring <= maxRing; ring += 1) {
    for (let dy = 0; dy <= ring; dy += 1) {
      const offsets = [
        { x: 0, y: dy * stepY },
        { x: ring * stepX, y: dy * stepY },
        { x: -ring * stepX, y: dy * stepY },
        { x: dy * stepX, y: ring * stepY },
        { x: -dy * stepX, y: ring * stepY }
      ]
      for (const offset of offsets) {
        const next = {
          x: desired.x + offset.x,
          y: desired.y + offset.y,
          width: size.width,
          height: size.height
        }
        if (!occupied.some((rect) => studioNodeRectsOverlap(next, rect, gap))) {
          return { x: next.x, y: next.y }
        }
      }
    }
  }
  return {
    x: desired.x,
    y: desired.y + occupied.length * (size.height + gap)
  }
}

export function toStudioNodeRect(node: Pick<StudioCanvasNode, 'position' | 'data'>): StudioNodeRect {
  const size = studioNodeDimensions(node)
  return {
    x: node.position.x,
    y: node.position.y,
    width: size.width,
    height: size.height
  }
}

/** 仅调整「新增」节点，已有布局节点保持不动。 */
export function placeNewStudioNodesWithoutOverlap(
  nodes: StudioCanvasNode[],
  isNew: (node: StudioCanvasNode) => boolean,
  gap = STUDIO_NODE_PLACEMENT_GAP
): StudioCanvasNode[] {
  const occupied: StudioNodeRect[] = nodes
    .filter((node) => !isNew(node))
    .map((node) => toStudioNodeRect(node))
  return nodes.map((node) => {
    if (!isNew(node)) return node
    const size = studioNodeDimensions(node)
    const position = placeStudioNodeWithoutOverlap(node.position, size, occupied, gap)
    occupied.push({ ...position, width: size.width, height: size.height })
    if (position.x === node.position.x && position.y === node.position.y) return node
    return { ...node, position }
  })
}

/** 默认构图：按从左到右、从上到下顺序消解重叠（仅用于服务端默认落点）。 */
export function packStudioNodesWithoutOverlap(
  nodes: StudioCanvasNode[],
  gap = STUDIO_NODE_PLACEMENT_GAP
): StudioCanvasNode[] {
  const order = nodes
    .map((node, index) => ({ index, x: node.position.x, y: node.position.y, id: node.id }))
    .sort((a, b) => a.x - b.x || a.y - b.y || a.id.localeCompare(b.id))
  const occupied: StudioNodeRect[] = []
  const next = nodes.slice()
  for (const item of order) {
    const node = next[item.index]
    const size = studioNodeDimensions(node)
    const position = placeStudioNodeWithoutOverlap(node.position, size, occupied, gap)
    occupied.push({ ...position, width: size.width, height: size.height })
    if (position.x !== node.position.x || position.y !== node.position.y) {
      next[item.index] = { ...node, position }
    }
  }
  return next
}

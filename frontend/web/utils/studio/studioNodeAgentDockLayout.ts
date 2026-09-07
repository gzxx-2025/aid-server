export const STUDIO_NODE_AGENT_DOCK_WIDTH_PX = 640
export const STUDIO_NODE_AGENT_DOCK_MAX_HEIGHT_PX = 400
export const STUDIO_NODE_AGENT_DOCK_GAP_PX = 12

export interface StudioAgentDockRect {
  left: number
  top: number
  right: number
  bottom: number
  width: number
  height: number
}

export interface StudioAgentDockPosition {
  left: number
  top: number
  width: number
  height: number
  placement: 'bottom'
}

const VIEWPORT_MARGIN = 16

export function resolveStudioNodeAgentDockSize(
  measured: Pick<StudioAgentDockRect, 'width' | 'height'>
): { width: number; height: number } {
  return {
    width: STUDIO_NODE_AGENT_DOCK_WIDTH_PX,
    height: Math.min(STUDIO_NODE_AGENT_DOCK_MAX_HEIGHT_PX, Math.max(measured.height, 140))
  }
}

/** 流程节点输入框始终跟随当前节点，并落在节点下方。 */
export function placeStudioNodeAgentDock(
  selection: StudioAgentDockRect,
  viewport: StudioAgentDockRect,
  size: { width: number; height: number }
): StudioAgentDockPosition {
  const width = Math.min(size.width, Math.max(1, viewport.width - VIEWPORT_MARGIN * 2))
  const centeredLeft = selection.left + selection.width / 2 - width / 2
  const left = clamp(
    centeredLeft,
    viewport.left + VIEWPORT_MARGIN,
    viewport.right - width - VIEWPORT_MARGIN
  )

  return {
    left,
    top: selection.bottom + STUDIO_NODE_AGENT_DOCK_GAP_PX,
    width,
    height: size.height,
    placement: 'bottom'
  }
}

/** 使用真实节点 DOM 测量，媒体加载导致尺寸变化时仍能保持贴合。 */
export function measureStudioNodesInCanvas(
  canvas: HTMLElement,
  nodeIds: string[]
): StudioAgentDockRect | null {
  if (!nodeIds.length) return null
  const canvasRect = canvas.getBoundingClientRect()
  let left = Number.POSITIVE_INFINITY
  let top = Number.POSITIVE_INFINITY
  let right = Number.NEGATIVE_INFINITY
  let bottom = Number.NEGATIVE_INFINITY
  let found = false

  for (const id of nodeIds) {
    const element = canvas.querySelector(`.react-flow__node[data-id="${escapeCssValue(id)}"]`)
    if (!(element instanceof HTMLElement)) continue
    const rect = element.getBoundingClientRect()
    found = true
    left = Math.min(left, rect.left - canvasRect.left)
    top = Math.min(top, rect.top - canvasRect.top)
    right = Math.max(right, rect.right - canvasRect.left)
    bottom = Math.max(bottom, rect.bottom - canvasRect.top)
  }

  if (!found) return null
  return { left, top, right, bottom, width: right - left, height: bottom - top }
}

export function escapeStudioCanvasNodeId(id: string): string {
  return escapeCssValue(id)
}

function escapeCssValue(value: string): string {
  return typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
    ? CSS.escape(value)
    : value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, Math.max(min, max)))
}

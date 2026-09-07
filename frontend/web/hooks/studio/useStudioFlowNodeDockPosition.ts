'use client'

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  type RefObject
} from 'react'
import { useReactFlow } from '@xyflow/react'
import type { StudioCanvasNode } from '@/types/studio'
import type { StudioFlowNodeComposerDockHandle } from '@/components/studio/canvas/StudioFlowNodeComposerDock'
import {
  escapeStudioCanvasNodeId,
  measureStudioNodesInCanvas,
  type StudioAgentDockRect
} from '@/utils/studio/studioNodeAgentDockLayout'
import { studioNodeDimensions } from '@/utils/studio/studioNodeLod'

export function useStudioFlowNodeDockPosition({
  canvasRef,
  dockRef,
  node
}: {
  canvasRef: RefObject<HTMLDivElement | null>
  dockRef: RefObject<StudioFlowNodeComposerDockHandle | null>
  node?: StudioCanvasNode
}) {
  const { getViewport } = useReactFlow()
  const frameRef = useRef<number | null>(null)

  const update = useCallback(() => {
    const canvas = canvasRef.current
    if (!canvas || !node || !dockRef.current) return
    const canvasRect = canvas.getBoundingClientRect()
    const viewportRect: StudioAgentDockRect = {
      left: 0,
      top: 0,
      right: canvasRect.width,
      bottom: canvasRect.height,
      width: canvasRect.width,
      height: canvasRect.height
    }
    const measured = measureStudioNodesInCanvas(canvas, [node.id])
    const selection = measured ?? fallbackNodeRect(node, getViewport())
    dockRef.current.reposition(selection, viewportRect)
  }, [canvasRef, dockRef, getViewport, node])

  const schedule = useCallback(() => {
    if (frameRef.current != null) cancelAnimationFrame(frameRef.current)
    frameRef.current = requestAnimationFrame(() => {
      frameRef.current = null
      update()
    })
  }, [update])

  useLayoutEffect(() => update(), [update])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || !node) return
    const refresh = () => schedule()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(refresh)
    observer?.observe(canvas)
    const nodeElement = canvas.querySelector(
      `.react-flow__node[data-id="${escapeStudioCanvasNodeId(node.id)}"]`
    )
    if (nodeElement instanceof HTMLElement) observer?.observe(nodeElement)
    window.addEventListener('resize', refresh)
    schedule()
    return () => {
      observer?.disconnect()
      window.removeEventListener('resize', refresh)
    }
  }, [canvasRef, node, schedule])

  useEffect(() => () => {
    if (frameRef.current != null) cancelAnimationFrame(frameRef.current)
  }, [])

  return schedule
}

function fallbackNodeRect(
  node: StudioCanvasNode,
  viewport: { x: number; y: number; zoom: number }
): StudioAgentDockRect {
  const size = studioNodeDimensions(node)
  const left = viewport.x + node.position.x * viewport.zoom
  const top = viewport.y + node.position.y * viewport.zoom
  const width = size.width * viewport.zoom
  const height = size.height * viewport.zoom
  return {
    left,
    top,
    right: left + width,
    bottom: top + height,
    width,
    height
  }
}

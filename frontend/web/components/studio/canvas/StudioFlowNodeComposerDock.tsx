'use client'

import {
  forwardRef,
  memo,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef
} from 'react'
import { CloseOutlined } from '@ant-design/icons'
import type { StudioCanvasNode } from '@/types/studio'
import {
  placeStudioNodeAgentDock,
  resolveStudioNodeAgentDockSize,
  type StudioAgentDockRect
} from '@/utils/studio/studioNodeAgentDockLayout'
import { StudioFlowComposerRouter } from '../flow/composer/StudioFlowComposerRouter'

export interface StudioFlowNodeComposerDockHandle {
  reposition: (selectionRect: StudioAgentDockRect, viewportRect: StudioAgentDockRect) => void
}

export const StudioFlowNodeComposerDock = memo(forwardRef<
  StudioFlowNodeComposerDockHandle,
  { node: StudioCanvasNode; onClose: () => void }
>(function StudioFlowNodeComposerDock({ node, onClose }, ref) {
  const elementRef = useRef<HTMLElement>(null)
  const lastRectsRef = useRef<{
    selection: StudioAgentDockRect
    viewport: StudioAgentDockRect
  } | null>(null)

  const applyPosition = useCallback((
    selectionRect: StudioAgentDockRect,
    viewportRect: StudioAgentDockRect
  ) => {
    const element = elementRef.current
    if (!element) return
    lastRectsRef.current = { selection: selectionRect, viewport: viewportRect }
    const position = placeStudioNodeAgentDock(
      selectionRect,
      viewportRect,
      resolveStudioNodeAgentDockSize(element.getBoundingClientRect())
    )
    element.style.left = `${position.left}px`
    element.style.top = `${position.top}px`
    element.style.width = `${position.width}px`
    element.dataset.placement = position.placement
  }, [])

  useImperativeHandle(ref, () => ({ reposition: applyPosition }), [applyPosition])

  useEffect(() => {
    const element = elementRef.current
    if (!element || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      const last = lastRectsRef.current
      if (last) applyPosition(last.selection, last.viewport)
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [applyPosition, node.id])

  return (
    <aside
      ref={elementRef}
      className="studio-flow-node-inspector nodrag nowheel"
      role="form"
      aria-label="流程节点编辑器"
      style={{ left: -9999, top: 0 }}
      onPointerDown={(event) => event.stopPropagation()}
      onKeyDown={(event) => event.stopPropagation()}
    >
      <button
        type="button"
        className="studio-flow-node-inspector__close"
        aria-label="关闭节点编辑器"
        onClick={onClose}
      >
        <CloseOutlined />
      </button>
      <StudioFlowComposerRouter node={node} />
    </aside>
  )
}))

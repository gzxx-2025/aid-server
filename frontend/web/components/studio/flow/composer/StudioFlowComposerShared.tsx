'use client'

import type { StudioCanvasNode } from '@/types/studio'

export function StudioFlowComposerHeader({
  node,
  subtitle
}: {
  node: StudioCanvasNode
  subtitle?: string
}) {
  const binding = node.data.flowBinding
  return (
    <div className="studio-flow-composer__heading">
      <span>{node.data.title}</span>
      <small>{subtitle ?? binding?.step ?? '流程节点'}</small>
    </div>
  )
}

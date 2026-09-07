'use client'

import { BaseEdge, getBezierPath, type EdgeProps } from '@xyflow/react'
import type { StudioFlowEdge } from '@/types/studio'

export function StudioEnergyEdge(props: EdgeProps<StudioFlowEdge>) {
  const [path] = getBezierPath({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    sourcePosition: props.sourcePosition,
    targetX: props.targetX,
    targetY: props.targetY,
    targetPosition: props.targetPosition
  })
  return (
    <g className={props.data?.energy ? 'studio-energy-edge is-active' : 'studio-energy-edge'}>
      <BaseEdge id={props.id} path={path} markerEnd={props.markerEnd} style={props.style} />
      {props.data?.energy ? <path className="studio-energy-edge__pulse" d={path} fill="none" /> : null}
    </g>
  )
}

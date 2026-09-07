'use client'

import { Skeleton } from 'antd'
import type { ReactNode } from 'react'

export type StudioFlowRuntimePhase = 'loading' | 'select-episode' | 'ready' | 'error'

export function isStudioFlowRuntimeScopeReady(
  workspaceScopeReady: boolean,
  readyScopeKey: string | null,
  expectedScopeKey: string
): boolean {
  return workspaceScopeReady && readyScopeKey === expectedScopeKey
}

/** URL scope 改变后的首个 render 即失败关闭，避免 effect 执行前短暂展示上一作品。 */
export function StudioFlowRuntimeScopeBoundary({
  workspaceScopeReady,
  phase,
  children
}: {
  workspaceScopeReady: boolean
  phase: StudioFlowRuntimePhase
  children?: ReactNode
}) {
  if (!workspaceScopeReady || phase === 'loading') {
    return (
      <div className="studio-loading-screen" data-testid="studio-flow-runtime-loading">
        <div className="studio-loading-screen__brand">AI·D <span>FLOW</span></div>
        <Skeleton active paragraph={{ rows: 6 }} title={{ width: 280 }} />
      </div>
    )
  }
  return children
}

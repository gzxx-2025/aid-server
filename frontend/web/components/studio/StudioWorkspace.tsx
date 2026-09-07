'use client'

import { Button, Result, Skeleton } from 'antd'
import { useStudioWorkspace } from '@/hooks/studio/useStudioWorkspace'
import { useStudioUiStore } from '@/stores/studioUi'
import { StudioCanvas } from './canvas/StudioCanvas'
import { StudioFlowLeftPanel } from './shell/StudioFlowLeftPanel'
import { StudioFlowRightPanel } from './shell/StudioFlowRightPanel'
import { StudioTopbar } from './shell/StudioTopbar'
import { StudioRechargeHost } from './shell/StudioRechargeHost'
import { StudioFlowRuntimeProvider } from './flow/StudioFlowRuntimeProvider'
import { useStudioPanelPresence } from '@/hooks/studio/useStudioPanelPresence'
import { StudioStoryScriptAgentProvider } from './StudioStoryScriptAgentProvider'

export function StudioWorkspace({
  scopeKey,
  projectId,
  episodeId,
  readOnly = false
}: {
  scopeKey: string
  projectId: number
  episodeId: number | null
  readOnly?: boolean
}) {
  const loading = useStudioUiStore((state) => state.loading)
  const loadError = useStudioUiStore((state) => state.loadError)
  const rightCollapsed = useStudioUiStore((state) => state.rightCollapsed)
  const { saveNow } = useStudioWorkspace(scopeKey, { readOnly })
  const { rendered: rightPanelRendered } = useStudioPanelPresence(!rightCollapsed)

  if (loading) {
    return (
      <div className="studio-loading-screen">
        <div className="studio-loading-screen__brand">AI·D <span>FLOW</span></div>
        <Skeleton active paragraph={{ rows: 5 }} title={{ width: 260 }} />
      </div>
    )
  }

  if (loadError) {
    return (
      <div className="studio-loading-screen">
        <Result
          status="error"
          title="流程画布加载失败"
          subTitle={loadError}
          extra={<Button type="primary" onClick={() => window.location.reload()}>重新加载</Button>}
        />
      </div>
    )
  }

  return (
    <StudioFlowRuntimeProvider
      projectId={projectId}
      episodeId={episodeId}
      workspaceScopeKey={scopeKey}
      readOnly={readOnly}
    >
      <StudioStoryScriptAgentProvider>
        <div
          className={`studio-workspace studio-workspace--flow${rightPanelRendered ? ' studio-workspace--right-panel-open' : ''}${readOnly ? ' studio-workspace--public-readonly' : ''} h-dvh w-full max-w-full overflow-hidden`}
          data-readonly-project-preview={readOnly ? 'true' : undefined}
        >
          <StudioTopbar onSave={saveNow} readOnly={readOnly} />
          <div className="studio-workspace__body">
            <StudioFlowLeftPanel />
            <StudioCanvas onSave={saveNow} readOnly={readOnly} />
            <StudioFlowRightPanel />
          </div>
          {!readOnly ? <StudioRechargeHost /> : null}
        </div>
      </StudioStoryScriptAgentProvider>
    </StudioFlowRuntimeProvider>
  )
}

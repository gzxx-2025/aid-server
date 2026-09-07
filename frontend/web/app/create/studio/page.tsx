'use client'

import '@xyflow/react/dist/style.css'
import '@/components/studio/studio-base.css'
import '@/components/studio/studio-panels.css'
import '@/components/studio/studio-canvas.css'
import { StudioWorkspace } from '@/components/studio/StudioWorkspace'
import { Alert, Button } from 'antd'
import { useRouter, useSearchParams } from 'next/navigation'
import { Suspense } from 'react'
import {
  parsePositiveStudioId,
  parseStudioEpisodeId,
  studioWorkspaceScopeKey
} from '@/utils/studio/studioFlowEntry'

export default function StudioPage() {
  return (
    <Suspense fallback={null}>
      <StudioFlowPageContent />
    </Suspense>
  )
}

function StudioFlowPageContent() {
  const router = useRouter()
  const query = useSearchParams()
  const rawProjectId = query.get('projectId')?.trim() || ''
  const rawEpisodeId = query.get('episodeId')?.trim() || '0'
  const projectId = parsePositiveStudioId(rawProjectId)
  const episodeId = parseStudioEpisodeId(rawEpisodeId)

  if (!projectId) {
    return (
      <main className="studio-loading-screen">
        <Alert
          showIcon
          type="warning"
          title="请先选择作品"
          description="流程画布必须绑定已有作品，请从作品的创作流程进入。"
          action={<Button onClick={() => router.replace('/works')}>返回我的作品</Button>}
        />
      </main>
    )
  }

  const scopeKey = studioWorkspaceScopeKey(rawProjectId, rawEpisodeId)

  return (
    <StudioWorkspace
      scopeKey={scopeKey}
      projectId={projectId}
      episodeId={episodeId}
    />
  )
}

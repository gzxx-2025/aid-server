'use client'

/**
 * 原 pages/create/preview.vue（definePageMeta layout:'create'）：成品预览步骤薄页面。
 * :key="previewScopeKey" → React key 属性，切作品/集时整树重建；
 * VideoPreview 挂载时经 createFlowShellContext.registerPreviewExportBridge 向壳层注册导出能力。
 */

import { Suspense } from 'react'
import { VideoPreview } from '~/components/steps/VideoPreview'
import { useCreationStore } from '~/stores/creation'
import type { DubbingPanel,StoryboardVideoPanel } from '~/types'
function PreviewStepClient() {
  const storyboardVideoPanels = useCreationStore(
    (s) => s.formData.storyboardVideo.panels as StoryboardVideoPanel[]
  )
  const dubbingPanels = useCreationStore((s) => s.formData.dubbing.panels as DubbingPanel[])
  const bgm = useCreationStore((s) => s.formData.dubbing.bgm)
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)

  const previewScopeKey = `${currentProjectId ?? 'na'}:${currentEpisodeId ?? 'na'}`

  return (
    <VideoPreview
      key={previewScopeKey}
      storyboardVideoPanels={storyboardVideoPanels}
      dubbingPanels={dubbingPanels}
      bgm={bgm}
    />
  )
}

export default function PreviewStepPage() {
  return (
    <Suspense fallback={null}>
      <PreviewStepClient />
    </Suspense>
  )
}

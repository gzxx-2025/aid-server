'use client'

import {
  createContext,
  useContext,
  useMemo,
  type ReactNode
} from 'react'
import dynamic from 'next/dynamic'
import type { CreateFlowGlobalSettingContext, CreateFlowShellContext } from '@/utils/createFlowInjection'
import { createFlowShellContext } from '@/utils/createFlowInjection'
import { EmbeddedRouteLikeProvider } from '@/hooks/useRouteLike'
import { useCreateFlowExport } from '@/components/create/create-flow-shell/useCreateFlowExport'
import { useCreationStore } from '@/stores/creation'
import { useStudioFlowRuntime } from './StudioFlowRuntimeProvider'

const VideoPreview = dynamic(
  () => import('@/components/steps/VideoPreview').then((module) => ({
    default: module.VideoPreview
  })),
  { ssr: false }
)

export type StudioFlowExportValue = ReturnType<typeof useCreateFlowExport>

const StudioFlowExportContext = createContext<StudioFlowExportValue | null>(null)

export function useStudioFlowExportContext(): StudioFlowExportValue | null {
  return useContext(StudioFlowExportContext)
}

const noopGlobalSettingContext = {
  confirmLoading: false,
  titleDraft: '',
  projectTypeDraft: 'movie' as const,
  draft: {} as CreateFlowGlobalSettingContext['draft'],
  projectTypeLocked: true,
  showModal: false,
  syncFromStore: () => {},
  openModal: () => {},
  setTitleDraft: () => {},
  setProjectTypeDraft: () => {},
  updateField: () => {},
  patchStyle: () => {},
  save: async () => {}
} satisfies CreateFlowGlobalSettingContext

function StudioFlowPreviewExportHost({
  flowExport
}: {
  flowExport: StudioFlowExportValue
}) {
  const runtime = useStudioFlowRuntime()
  const currentProjectId = useCreationStore((state) => state.currentProjectId)
  const currentEpisodeId = useCreationStore((state) => state.currentEpisodeId)
  const storyboardVideoPanels = useCreationStore((state) => state.formData.storyboardVideo.panels)
  const dubbingPanels = useCreationStore((state) => state.formData.dubbing.panels)
  const bgm = useCreationStore((state) => state.formData.dubbing.bgm)

  const previewScopeKey = `${currentProjectId ?? 'na'}:${currentEpisodeId ?? 'na'}`
  const previewRoutePath = useMemo(() => {
    const params = new URLSearchParams()
    if (currentProjectId) params.set('projectId', String(currentProjectId))
    if (currentEpisodeId != null) params.set('episodeId', String(currentEpisodeId))
    const query = params.toString()
    return query ? `/create/preview?${query}` : '/create/preview'
  }, [currentEpisodeId, currentProjectId])

  const shellContext = useMemo<CreateFlowShellContext>(() => ({
    goToStep: () => {},
    stopExtractAssets: () => {},
    openExtractModalFromScp: () => {},
    openContinueExtractModal: () => {},
    dismissScriptChangeLightBanner: () => {},
    jumpToStoryboardScriptFromVideo: () => {},
    clearStoryboardScriptJumpTooltip: () => {},
    storyboardScriptTooltipTargetIndex: null,
    storyboardScriptTooltipKey: 0,
    syncVideoAndDubbingFromScriptPanels: () => {},
    setDubbingGenerating: () => {},
    storyboardListLoading: false,
    storyboardListSyncReady: true,
    globalSetting: noopGlobalSettingContext,
    openProjectGenConfig: () => {},
    registerPreviewExportBridge: flowExport.registerPreviewExportBridge,
    notifyPreviewExportSuccess: flowExport.handlePreviewExportSuccess,
    embeddedEpisodeId: runtime?.episodeId ?? null
  }), [
    flowExport.handlePreviewExportSuccess,
    flowExport.registerPreviewExportBridge,
    runtime?.episodeId
  ])

  return (
    <EmbeddedRouteLikeProvider path={previewRoutePath}>
      <createFlowShellContext.Provider value={shellContext}>
        <div className="studio-flow-preview-export-host" aria-hidden="true">
          <VideoPreview
            key={previewScopeKey}
            storyboardVideoPanels={storyboardVideoPanels}
            dubbingPanels={dubbingPanels}
            bgm={bgm}
          />
        </div>
      </createFlowShellContext.Provider>
    </EmbeddedRouteLikeProvider>
  )
}

export function StudioFlowExportProvider({
  children,
  pageReady
}: {
  children: ReactNode
  pageReady: boolean
}) {
  const flowExport = useCreateFlowExport()

  return (
    <StudioFlowExportContext.Provider value={flowExport}>
      {children}
      {pageReady ? <StudioFlowPreviewExportHost flowExport={flowExport} /> : null}
    </StudioFlowExportContext.Provider>
  )
}

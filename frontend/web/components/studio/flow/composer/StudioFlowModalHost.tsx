'use client'

import { Suspense, useCallback, useEffect, useMemo, useState } from 'react'
import { Modal } from 'antd'
import dynamic from 'next/dynamic'
import { AsyncModalLoading } from '@/components/common/AsyncModalLoading'
import { CreateFirstStepModal } from '@/components/steps/CreateFirstStepModal'
import { ProjectGenConfigModal } from '@/components/steps/ProjectGenConfigModal'
import { useCreateFlowGlobalSettingModal } from '@/hooks/useCreateFlowGlobalSettingModal'
import { useCreateFlowStepModalIntent } from '@/hooks/useCreateFlowStepModalIntent'
import { useStudioFlowRuntime } from '@/components/studio/flow/StudioFlowRuntimeProvider'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StoryboardPanel } from '@/types'
import type { UserAssetRpsRow } from '@/types/business-api'
import { userAssetRpsList } from '@/utils/businessApi'
import {
  consumeCreateFlowStepModalIntent,
  type CreateFlowStepModalKind
} from '@/utils/createFlowStepModalIntent'
import {
  hasImmediateProjectConfigAssetLock,
  hasProjectSceneCharacterPropAssets
} from '@/utils/projectConfigAssetGuard'
import {
  STUDIO_FLOW_ADVANCED_MODAL_EVENT,
  type StudioFlowAdvancedModalDetail
} from '@/utils/studio/studioFlowAdvancedModal'
import {
  buildSceneModalScenesFromAssets,
  buildStoryboardModalScenes,
  resolveStoryboardPanelIndex
} from '@/utils/studio/studioFlowModalScenes'
import { EditStoryboardImageModalLazy } from '@/components/steps/storyboard-script/storyboardScriptImageModalLoader'
import { EditSceneImageModalLazy } from '@/components/steps/scene-character-prop/editSceneImageModalLoader'

const EditStoryboardVideoModal = dynamic(
  () => import('@/components/steps/EditStoryboardVideoModal'),
  { loading: () => <AsyncModalLoading /> }
)
const EditStoryboardDubbingModal = dynamic(
  () => import('@/components/steps/EditStoryboardDubbingModal'),
  { loading: () => <AsyncModalLoading /> }
)

type SceneModalState = {
  open: boolean
  assetType: 'scene' | 'character' | 'prop'
  sceneIndex: number
  scenes: Array<{ name: string; images: unknown[]; setting?: string }>
  rpsAssetIdsByIndex: Record<number, number>
}

export function StudioFlowModalHost() {
  const runtime = useStudioFlowRuntime()
  const stepIntent = useCreateFlowStepModalIntent()
  const panels = useCreationStore((state) => state.formData.storyboardScript.panels)
  const dubbingPanels = useCreationStore((state) => state.formData.dubbing.panels)
  const videoPanels = useCreationStore((state) => state.formData.storyboardVideo.panels)
  const projectId = useCreationStore((state) => state.currentProjectId)
  const episodeId = useCreationStore((state) => state.currentEpisodeId)

  const [storyboardImageOpen, setStoryboardImageOpen] = useState(false)
  const [storyboardImageIndex, setStoryboardImageIndex] = useState(-1)
  const [storyboardVideoOpen, setStoryboardVideoOpen] = useState(false)
  const [storyboardVideoIndex, setStoryboardVideoIndex] = useState(-1)
  const [dubbingOpen, setDubbingOpen] = useState(false)
  const [dubbingIndex, setDubbingIndex] = useState(-1)
  const [sceneModal, setSceneModal] = useState<SceneModalState | null>(null)
  const [projectGenOpen, setProjectGenOpen] = useState(false)
  const [storyScriptOpen, setStoryScriptOpen] = useState(false)
  const [storyScriptDraft, setStoryScriptDraft] = useState('')

  const globalSettingModal = useCreateFlowGlobalSettingModal()
  const [contentConfigLocked, setContentConfigLocked] = useState(false)
  const storyboardScenes = useMemo(() => buildStoryboardModalScenes(panels), [panels])

  const refreshProjectConfigLock = useCallback(() => {
    const creation = useCreationStore.getState()
    const nodes = useStudioUiStore.getState().nodes
    const immediate = hasImmediateProjectConfigAssetLock({
      styleLocked: creation.formData.globalSetting.styleLocked === true,
      sceneCharacter: creation.formData.sceneCharacter,
      nodes
    })
    setContentConfigLocked(immediate)
    const pid = Number(projectId)
    const eid = Number(episodeId)
    if (!Number.isFinite(pid) || pid <= 0) return
    void hasProjectSceneCharacterPropAssets(
      pid,
      Number.isFinite(eid) && eid >= 0 ? eid : 0,
      {
        styleLocked: creation.formData.globalSetting.styleLocked === true,
        sceneCharacter: creation.formData.sceneCharacter,
        nodes
      }
    ).then(setContentConfigLocked)
  }, [episodeId, projectId])

  const openStoryboardModal = useCallback((kind: CreateFlowStepModalKind, panelIndex: number) => {
    if (panelIndex < 0) return
    if (kind === 'storyboard-image') {
      setStoryboardImageIndex(panelIndex)
      setStoryboardImageOpen(true)
      return
    }
    if (kind === 'storyboard-video') {
      setStoryboardVideoIndex(panelIndex)
      setStoryboardVideoOpen(true)
      return
    }
    if (kind === 'storyboard-dubbing') {
      setDubbingIndex(panelIndex)
      setDubbingOpen(true)
    }
  }, [])

  useEffect(() => {
    if (!stepIntent) return
    let active = true
    queueMicrotask(() => {
      if (active) openStoryboardModal(stepIntent.kind, stepIntent.panelIndex)
    })
    return () => { active = false }
  }, [openStoryboardModal, stepIntent])

  useEffect(() => {
    const openAdvanced = (event: Event) => {
      const detail = (event as CustomEvent<StudioFlowAdvancedModalDetail>).detail
      if (!detail) return
      if (detail.kind === 'project-gen-config') {
        setProjectGenOpen(true)
        return
      }
      if (detail.kind === 'global-setting') {
        refreshProjectConfigLock()
        globalSettingModal.openGlobalSettingModal()
        return
      }
      if (detail.kind === 'story-script') {
        setStoryScriptDraft(useCreationStore.getState().formData.storyScript.content)
        setStoryScriptOpen(true)
        return
      }
      if (detail.kind === 'scene-image') {
        void (async () => {
          const pid = Number(projectId)
          const eid = Number(episodeId)
          if (!Number.isFinite(pid) || pid <= 0) return
          const { rows } = await userAssetRpsList({
            projectId: pid,
            episodeId: Number.isFinite(eid) && eid >= 0 ? eid : 0,
            assetType: detail.assetType
          })
          const built = buildSceneModalScenesFromAssets(detail.assetType, rows as UserAssetRpsRow[], detail.assetId)
          setSceneModal({
            open: true,
            assetType: detail.assetType,
            sceneIndex: built.sceneIndex,
            scenes: built.scenes,
            rpsAssetIdsByIndex: built.rpsAssetIdsByIndex
          })
        })()
        return
      }
      openStoryboardModal(detail.kind, detail.panelIndex)
    }
    window.addEventListener(STUDIO_FLOW_ADVANCED_MODAL_EVENT, openAdvanced)
    return () => window.removeEventListener(STUDIO_FLOW_ADVANCED_MODAL_EVENT, openAdvanced)
  }, [episodeId, globalSettingModal, openStoryboardModal, projectId, refreshProjectConfigLock])

  const patchStoryboardPanel = useCallback((index: number, patch: Partial<StoryboardPanel>) => {
    const store = useCreationStore.getState()
    const nextPanels = store.formData.storyboardScript.panels.map((panel, panelIndex) =>
      panelIndex === index ? { ...panel, ...patch } : panel
    )
    store.updateFormData({ storyboardScript: { ...store.formData.storyboardScript, panels: nextPanels } })
  }, [])

  const closeStoryboardImage = useCallback((open: boolean) => {
    setStoryboardImageOpen(open)
    if (!open) consumeCreateFlowStepModalIntent('storyboard-image')
    if (!open) void runtime?.refresh({ taskIntent: 'read' })
  }, [runtime])

  return (
    <>
      {storyboardImageOpen && storyboardImageIndex >= 0 ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditStoryboardImageModalLazy
            open={storyboardImageOpen}
            sceneIndex={storyboardImageIndex}
            scenes={storyboardScenes}
            editorScopeKey={`studio-flow-storyboard-${panels[storyboardImageIndex]?.id ?? storyboardImageIndex}`}
            onOpenChange={closeStoryboardImage}
            onUpdate={(sceneIndex, data) => patchStoryboardPanel(sceneIndex, data)}
          />
        </Suspense>
      ) : null}

      {storyboardVideoOpen && storyboardVideoIndex >= 0 ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditStoryboardVideoModal
            open={storyboardVideoOpen}
            sceneIndex={storyboardVideoIndex}
            scenes={storyboardScenes.map((scene) => ({
              ...scene,
              videos: videoPanels[storyboardVideoIndex]?.videos,
              storyboardImages: panels[storyboardVideoIndex]?.images
            }))}
            editorScopeKey={`studio-flow-video-${panels[storyboardVideoIndex]?.id ?? storyboardVideoIndex}`}
            onOpenChange={(open) => {
              setStoryboardVideoOpen(open)
              if (!open) consumeCreateFlowStepModalIntent('storyboard-video')
              if (!open) void runtime?.refresh({ taskIntent: 'read' })
            }}
            onUpdate={(sceneIndex, data) => patchStoryboardPanel(sceneIndex, data)}
          />
        </Suspense>
      ) : null}

      {dubbingOpen && dubbingIndex >= 0 ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditStoryboardDubbingModal
            open={dubbingOpen}
            sceneIndex={dubbingIndex}
            dubbingPanels={dubbingPanels}
            storyboardVideoPanels={videoPanels}
            storyboardScriptPanels={panels}
            editorScopeKey={`studio-flow-dubbing-${panels[dubbingIndex]?.id ?? dubbingIndex}`}
            onOpenChange={(open) => {
              setDubbingOpen(open)
              if (!open) consumeCreateFlowStepModalIntent('storyboard-dubbing')
              if (!open) void runtime?.refresh({ taskIntent: 'read' })
            }}
            onPanelsChange={(nextPanels) => {
              useCreationStore.getState().updateFormData({
                dubbing: { ...useCreationStore.getState().formData.dubbing, panels: nextPanels }
              })
            }}
          />
        </Suspense>
      ) : null}

      {sceneModal?.open ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditSceneImageModalLazy
            open={sceneModal.open}
            sceneIndex={sceneModal.sceneIndex}
            imageType={sceneModal.assetType}
            scenes={sceneModal.scenes}
            rpsAssetId={sceneModal.rpsAssetIdsByIndex[sceneModal.sceneIndex] ?? null}
            rpsAssetIdsByIndex={sceneModal.rpsAssetIdsByIndex}
            editorScopeKey={`studio-flow-scene-${sceneModal.assetType}-${sceneModal.sceneIndex}`}
            onOpenChange={(open) => {
              if (!open) setSceneModal(null)
              if (!open) void runtime?.refresh({ taskIntent: 'read' })
            }}
            onUpdate={() => undefined}
          />
        </Suspense>
      ) : null}

      <CreateFirstStepModal
        open={globalSettingModal.showGlobalSettingModal}
        flowEditMode
        projectTypeLocked
        contentConfigLocked={contentConfigLocked}
        contentConfigLockReason={contentConfigLocked ? 'assets' : undefined}
        confirmLoading={globalSettingModal.globalSettingConfirmLoading}
        title={globalSettingModal.creationTitleDraft}
        projectType={globalSettingModal.globalSettingProjectTypeDraft}
        aspectRatio={globalSettingModal.creationGlobalSettingDraft.aspectRatio}
        scriptType={globalSettingModal.creationGlobalSettingDraft.scriptType}
        modelStrategy={globalSettingModal.creationGlobalSettingDraft.modelStrategy}
        creationMode={globalSettingModal.creationGlobalSettingDraft.creationMode}
        modelValue={globalSettingModal.creationGlobalSettingDraft}
        onOpenChange={globalSettingModal.setShowGlobalSettingModal}
        onTitleChange={globalSettingModal.setCreationTitleDraft}
        onProjectTypeChange={globalSettingModal.setGlobalSettingProjectTypeDraft}
        onAspectRatioChange={(value) => globalSettingModal.updateGlobalSettingDraftField('aspectRatio', value)}
        onScriptTypeChange={(value) => globalSettingModal.updateGlobalSettingDraftField('scriptType', value)}
        onModelStrategyChange={(value) => globalSettingModal.updateGlobalSettingDraftField('modelStrategy', value)}
        onCreationModeChange={(value) => globalSettingModal.updateGlobalSettingDraftField('creationMode', value)}
        onModelValueChange={globalSettingModal.patchGlobalSettingDraftStyle}
        onConfirm={() => void globalSettingModal.handleGlobalSettingConfirm({
          navigateAfterSave: false,
          contentConfigLocked
        }).then(() => runtime?.refresh({ taskIntent: 'read' }))}
      />

      <ProjectGenConfigModal
        open={projectGenOpen}
        projectId={projectId}
        episodeId={episodeId}
        onOpenChange={setProjectGenOpen}
        onSaved={() => void runtime?.refresh({ taskIntent: 'read' })}
      />

      <Modal
        open={storyScriptOpen}
        title="剧本高级编辑"
        width="min(960px, calc(100vw - 48px))"
        className="create-flow-modal"
        okText="保存"
        cancelText="取消"
        onCancel={() => setStoryScriptOpen(false)}
        onOk={() => {
          useCreationStore.getState().updateFormData({
            storyScript: { ...useCreationStore.getState().formData.storyScript, content: storyScriptDraft }
          })
          setStoryScriptOpen(false)
          void runtime?.refresh({ taskIntent: 'read' })
        }}
      >
        <textarea
          className="studio-flow-composer__story-script-modal"
          value={storyScriptDraft}
          rows={16}
          onChange={(event) => setStoryScriptDraft(event.target.value)}
        />
      </Modal>
    </>
  )
}

export function openStudioFlowModalForBinding(
  serverId: number | undefined,
  kind: CreateFlowStepModalKind,
  panels: StoryboardPanel[]
) {
  const panelIndex = resolveStoryboardPanelIndex(serverId, panels)
  if (panelIndex < 0) return false
  window.dispatchEvent(new CustomEvent(STUDIO_FLOW_ADVANCED_MODAL_EVENT, {
    detail: { kind, panelIndex }
  }))
  return true
}

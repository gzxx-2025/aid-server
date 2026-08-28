'use client'

import { Suspense, useContext, useEffect, useRef, useState } from 'react'
import { Button, message } from 'antd'
import { DeleteOutlined, LoadingOutlined, AudioOutlined } from '@ant-design/icons'
import listNorRaw from '~/assets/img/icon/list-nor.svg'
import listSelRaw from '~/assets/img/icon/list-sel.svg'
import cardNorRaw from '~/assets/img/icon/card-nor.svg'
import cardSelRaw from '~/assets/img/icon/card-sel.svg'
import emptyFjRaw from '~/assets/img/icon/empty-fj.svg'
import { assetUrl } from '~/utils/assetUrl'
import { GENERATING_CENTER_ICON_URL as generatingCenterIcon } from '~/utils/generatingCenterIcon'
import type { DubbingPanel, StoryboardVideoPanel, StoryboardPanel } from '~/types'
import { openVideoPreviewModal } from '~/utils/openVideoPreviewModal'
import { confirmDubbingGeneration } from '~/utils/confirmDubbingGeneration'
import { useCreationStore } from '~/stores/creation'
import StoryboardToolbarOpsDropdown, {
  type StoryboardOpsMenuItem
} from './StoryboardToolbarOpsDropdown'
import { resolveStoryboardListDisplayTitle } from '~/utils/storyboardPanelTitle'
import { getPanelStoryboardVideo } from '~/utils/storyboardVideoCover'
import { createFlowShellContext } from '~/utils/createFlowInjection'
import {
  consumeCreateFlowStepModalIntent,
  peekCreateFlowStepModalIntent,
} from '~/utils/createFlowStepModalIntent'
import { useCreateFlowStepModalIntent } from '~/hooks/useCreateFlowStepModalIntent'
import {
  hasPersistedStoryboardScriptBatchGenWork,
  hasPersistedStoryboardVideoBatchGenWork
} from '~/utils/storyboardListBootstrap'
import { isDubbingFlowStepGenerating } from '~/utils/storyboardFlowStepLoading'
import { resolveStoryboardDesignProgressCounts } from '~/utils/creationModeUiRules'
import { useRouteLike, getRouteLikeSnapshot } from '~/composables/useRouteLike'
import BatchRegenerateDubbingModal from './BatchRegenerateDubbingModal'
import BatchGenerateStoryboardModal from './BatchGenerateStoryboardModal'
import { AsyncModalLoading } from '~/components/common/AsyncModalLoading'
import { EditStoryboardDubbingModalLazy, preloadStoryboardDubbingEditorWhenIdle } from './heavyEditorModalLoaders'
import DubbingListView from './dubbing/DubbingListView'
import DubbingCardView from './dubbing/DubbingCardView'
import { useDubbingStepRestore } from './dubbing/useDubbingStepRestore'
import { useDubbingPanelOps } from './dubbing/useDubbingPanelOps'
import './dubbing/dubbing.css'

const listNorIcon = assetUrl(listNorRaw)
const listSelIcon = assetUrl(listSelRaw)
const cardNorIcon = assetUrl(cardNorRaw)
const cardSelIcon = assetUrl(cardSelRaw)
const emptyFjIcon = assetUrl(emptyFjRaw)

export interface DubbingProps {
  description: string
  value: DubbingPanel[]
  /** 分镜视频列表，用于判断是否有视频、编辑分镜配音前校验 */
  storyboardVideoPanels?: StoryboardVideoPanel[]
  /** 分镜脚本：台词自动取自对应分镜的脚本内容 */
  storyboardScriptPanels?: StoryboardPanel[]
  /** 素材准备中的角色列表，用于「替换发言角色」弹窗 */
  sceneCharacters?: string[]
  onChange: (value: DubbingPanel[]) => void
  onGoStep: (stepIndex: number) => void
  onStoryboardVideoPanelsChange: (value: StoryboardVideoPanel[]) => void
  onGenerating: (value: boolean) => void
}

export function Dubbing({
  description: _description,
  value,
  storyboardVideoPanels,
  storyboardScriptPanels,
  sceneCharacters,
  onChange,
  onGoStep,
  onStoryboardVideoPanelsChange,
  onGenerating
}: DubbingProps) {
  useEffect(() => preloadStoryboardDubbingEditorWhenIdle(), [])
  const route = useRouteLike()
  const createFlowShell = useContext(createFlowShellContext)

  const storyboardListLoading = createFlowShell?.storyboardListLoading ?? false
  const storyboardListSyncReady = createFlowShell?.storyboardListSyncReady ?? true

  // 订阅 store：与原 Pinia computed 依赖对齐（scope 桶更新需重算 bootstrap 蒙层 / 生成中判定）
  const isSyncGeneratingStoryboard = useCreationStore((s) => s.isGeneratingStoryboard)
  const storyboardGenerationError = useCreationStore((s) => s.storyboardGenerationError)
  const storyboardGenerationProgress = useCreationStore((s) => s.storyboardGenerationProgress)
  const isHydrated = useCreationStore((s) => s.isHydrated)
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const creationMode = useCreationStore((s) => s.formData.globalSetting?.creationMode)
  const dubbingBatchGeneratingIndices = useCreationStore((s) => s.dubbingBatchGeneratingIndices)
  const step4PlusLiveGenByScope = useCreationStore((s) => s.step4PlusLiveGenByScope)
  void step4PlusLiveGenByScope

  function hasOngoingDubbingGenWork(): boolean {
    const store = useCreationStore.getState()
    const routeSnap = getRouteLikeSnapshot()
    if (!storyboardListSyncReady) {
      if (hasPersistedStoryboardScriptBatchGenWork(store, routeSnap)) return true
      if (hasPersistedStoryboardVideoBatchGenWork(store, routeSnap)) return true
      return false
    }
    return isDubbingFlowStepGenerating(store, routeSnap)
  }

  const showDubbingBootstrapMask =
    (storyboardListLoading || !storyboardListSyncReady) && !hasOngoingDubbingGenWork()

  const [viewMode, setViewMode] = useState<'list' | 'card'>('list')
  const [hoverIndex, setHoverIndex] = useState<number | null>(null)
  const [dubbingEditModalOpen, setDubbingEditModalOpen] = useState(false)
  const [dubbingEditSceneIndex, setDubbingEditSceneIndex] = useState(0)
  const dubbingEditSceneIndexRef = useRef(dubbingEditSceneIndex)
  dubbingEditSceneIndexRef.current = dubbingEditSceneIndex

  const panels = value || []
  const scriptPanels = (storyboardScriptPanels || []) as StoryboardPanel[]
  const videoPanels = storyboardVideoPanels || []
  const sceneCharactersArr = sceneCharacters || []

  // 异步恢复/确认弹窗回调中读取最新面板与回调（原 Vue computed 调用时求值）
  const panelsRef = useRef(panels)
  panelsRef.current = panels
  const scriptPanelsRef = useRef(scriptPanels)
  scriptPanelsRef.current = scriptPanels
  const videoPanelsRef = useRef(videoPanels)
  videoPanelsRef.current = videoPanels
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  const onGeneratingRef = useRef(onGenerating)
  onGeneratingRef.current = onGenerating

  /** 原 listInteractive ref：仅在 onBeforeUnmount 置 false 以拆除拖拽；React 卸载即销毁，恒为 true */
  const listInteractive = true
  const batchRegenerateOverwriteRef = useRef(false)

  // 后台任务恢复编排（弹窗单镜 + 批量配音；含 scope 切换交接与生命周期清理）
  const { storyboardAudioBatchGen, pageDisposedRef } =
    useDubbingStepRestore({
      panelsRef,
      scriptPanelsRef,
      videoPanelsRef,
      onChangeRef,
      onGeneratingRef,
      isHydrated,
      currentProjectId,
      currentEpisodeId,
      routeProjectId: route.query.projectId,
      routeEpisodeId: route.query.episodeId,
      panelsLength: panels.length
    })

  // 分镜行操作（标题编辑 / 拖拽排序 / 批量删除 / 复制 / 删除 / 弹窗回写）
  const panelOps = useDubbingPanelOps({
    panelsRef,
    onChangeRef,
    onStoryboardVideoPanelsChange,
    dubbingEditSceneIndexRef
  })

  const [toolbarOpsOpen, setToolbarOpsOpen] = useState(false)

  // 挂载时清理残留报错
  useEffect(() => {
    if (panelsRef.current.length === 0 && useCreationStore.getState().storyboardGenerationError) {
      useCreationStore.getState().clearStoryboardScriptGenerationOutcome()
    }
  }, [])

  const [batchRegenerateModalOpen, setBatchRegenerateModalOpen] = useState(false)
  const [batchDeleteModalOpen, setBatchDeleteModalOpen] = useState(false)
  const [batchRegenerateModalTitle, setBatchRegenerateModalTitle] = useState('批量生成分镜配音')
  const [batchRegeneratePreselectAll, setBatchRegeneratePreselectAll] = useState(false)
  const [isDubbingDragging, setIsDubbingDragging] = useState(false)
  /** 转为数组供编辑弹窗使用，用于弹窗内头部 tab 与右侧列表的 loading（按作品存在 Pinia） */
  const batchGeneratingIndicesArray = [...dubbingBatchGeneratingIndices]

  function isDubbingBatchGenerating(index: number) {
    return dubbingBatchGeneratingIndices.includes(index)
  }

  function displayPanelTitle(panel: DubbingPanel, index: number): string {
    return resolveStoryboardListDisplayTitle(panel.title, index, 'dubbing')
  }

  function onDubbingDragStart() {
    setIsDubbingDragging(true)
  }

  function onDubbingDragEnd() {
    setIsDubbingDragging(false)
  }

  const showScriptSyncGeneratingView =
    (isSyncGeneratingStoryboard && panels.length === 0 && !storyboardGenerationError) ||
    (!storyboardListSyncReady &&
      hasPersistedStoryboardScriptBatchGenWork(useCreationStore.getState(), getRouteLikeSnapshot()))
  const showDubbingEmptyState =
    !showScriptSyncGeneratingView && !showDubbingBootstrapMask && panels.length === 0
  const syncGenerationPercent = (() => {
    const total = Number(storyboardGenerationProgress.total || 0)
    const completed = Number(storyboardGenerationProgress.completed || 0)
    if (total <= 0) return 0
    const percent = Math.round((completed / total) * 100)
    return Math.min(100, Math.max(0, percent))
  })()

  /** 分镜脚本 / 配音台词常为富文本 HTML（含复制分镜后的副本），列表区只展示纯文本 */
  function stripDialogueHtmlForDisplay(raw: string): string {
    const t = (raw || '').trim()
    if (!t) return ''
    if (typeof window !== 'undefined' && typeof DOMParser !== 'undefined') {
      try {
        const doc = new DOMParser().parseFromString(t, 'text/html')
        const text = doc.body.textContent ?? ''
        const normalized = text
          .replace(/\u00a0/g, ' ')
          .replace(/\s+/g, ' ')
          .trim()
        if (normalized) return normalized
      } catch {
        /* 回退到正则 */
      }
    }
    return t
      .replace(/<[^>]*>/g, ' ')
      .replace(/&nbsp;/gi, ' ')
      .replace(/&lt;/gi, '<')
      .replace(/&gt;/gi, '>')
      .replace(/&amp;/gi, '&')
      .replace(/\s+/g, ' ')
      .trim()
  }

  /** 台词：自动渲染（分镜脚本内容 / 自动配音文案），非可编辑文本域 */
  function getRenderedDialogue(index: number): string {
    const script = scriptPanels[index]?.scriptContent?.trim()
    if (script) return stripDialogueHtmlForDisplay(script)
    const d = panels[index]?.dialogue?.trim()
    if (d) return stripDialogueHtmlForDisplay(d)
    return '将在完善分镜设计或「批量生成分镜配音」后展示台词。'
  }

  const progressText = (() => {
    const done = panels.filter((p) => p.status === 'done').length
    const progress = resolveStoryboardDesignProgressCounts(creationMode, panels.length, done)
    return `${progress.completed}/${progress.total}`
  })()

  const dubbingCompletedCount = panels.filter(
    (p) => !!(p.dubbingLipSyncVideoUrl && String(p.dubbingLipSyncVideoUrl).trim())
  ).length

  const dubbingBatchGenerateLabel =
    dubbingCompletedCount > 0 ? '重新批量生成分镜配音' : '批量生成分镜配音'

  const dubbingToolbarOpsItems: StoryboardOpsMenuItem[] = [
    {
      key: 'batch-dubbing',
      label: dubbingBatchGenerateLabel,
      icon: AudioOutlined,
      disabled: panels.length === 0 || batchGeneratingIndicesArray.length > 0,
      disabledTooltip:
        panels.length === 0 ? '暂无分镜，请先在「分镜设计」中生成分镜' : undefined
    },
    {
      key: 'batch-delete',
      label: '批量删除分镜配音',
      icon: DeleteOutlined,
      danger: true,
      disabled: panels.length === 0 || panelOps.batchDeleteSubmitting
    }
  ]

  function handleDubbingToolbarOpsSelect(key: string) {
    if (key === 'batch-dubbing') {
      openBatchDubbingModal()
      return
    }
    if (key === 'batch-delete') {
      setBatchDeleteModalOpen(true)
    }
  }

  function openBatchDubbingModal() {
    if (panels.length === 0) {
      message.warning('暂无分镜，请先在「分镜设计」中生成分镜')
      return
    }
    setBatchRegenerateModalTitle(dubbingBatchGenerateLabel)
    batchRegenerateOverwriteRef.current = dubbingCompletedCount > 0
    setBatchRegeneratePreselectAll(false)
    confirmDubbingGeneration(() => setBatchRegenerateModalOpen(true))
  }

  /** 对应分镜视频是否已设置主视频（含已生成的音画同步视频） */
  function getVideoForIndex(index: number): { url?: string | null; title?: string } | null {
    const dub = panels[index]
    if (dub?.dubbingLipSyncVideoUrl) {
      return { url: dub.dubbingLipSyncVideoUrl, title: '音画同步' }
    }
    const panel = videoPanels[index]
    const mainVideo = getPanelStoryboardVideo(panel)
    return mainVideo?.url ? mainVideo : null
  }

  function hasVideoForIndex(index: number): boolean {
    const v = getVideoForIndex(index)
    return !!v?.url
  }

  function getVideoUrlForIndex(index: number): string {
    const v = getVideoForIndex(index)
    return v?.url || ''
  }

  function goToStoryboardVideo() {
    onGoStep(4)
  }

  /** 音画同步：弹窗预览视频（与图片预览同壳层，适配各分辨率） */
  function handlePreviewDubbingVideo(index: number) {
    const v = getVideoForIndex(index)
    const url = v?.url
    if (!url) {
      message.warning('暂无视频可预览')
      return
    }
    const title = v?.title || panels[index]?.title || `音画同步 ${index + 1}`
    openVideoPreviewModal({ url, title })
  }

  /** 编辑分镜配音：无分镜视频时先提示 */
  function handleEditDubbing(index: number) {
    if (!hasVideoForIndex(index)) {
      message.warning('请先生成该分镜的视频片段，再进行音画同步。请先到「视频生成」步骤添加视频。')
      onGoStep(4)
      return
    }
    setDubbingEditSceneIndex(index)
    setDubbingEditModalOpen(true)
  }

  /** 消费跨步骤意图：打开对应分镜音画同步弹窗 */
  function tryConsumeStepModalIntent() {
    if (pageDisposedRef.current) return
    const pending = peekCreateFlowStepModalIntent()
    if (!pending || pending.kind !== 'storyboard-dubbing') return
    if (pending.panelIndex < 0 || pending.panelIndex >= panelsRef.current.length) return
    const index = consumeCreateFlowStepModalIntent('storyboard-dubbing')
    if (index == null) return
    setViewMode('list')
    setTimeout(() => {
      if (pageDisposedRef.current) return
      handleEditDubbing(index)
    }, 0)
  }

  // 原 watch([panels.length, intent.token], { immediate: true })
  const modalIntentToken = useCreateFlowStepModalIntent()?.token ?? null
  useEffect(() => {
    queueMicrotask(tryConsumeStepModalIntent)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [panels.length, modalIntentToken])

  async function onBatchGenerate(
    selectedPanelIds: string[],
    options: {
      overwrite?: boolean
    }
  ) {
    if (selectedPanelIds.length === 0) return

    const panelIndices = selectedPanelIds
      .map((id) => panelsRef.current.findIndex((p) => p.id === id))
      .filter((i) => i >= 0)

    if (!panelIndices.length) return

    const overwrite = options.overwrite ?? batchRegenerateOverwriteRef.current
    const result = await storyboardAudioBatchGen.runBatchForIndices({
      panelIndices,
      scriptPanels: scriptPanelsRef.current,
      panels: panelsRef.current,
      overwrite,
      onPanelsUpdate: (next) => {
        if (pageDisposedRef.current) return
        onChangeRef.current(next)
      },
      onGenerating: (v) => {
        if (pageDisposedRef.current) return
        onGeneratingRef.current(v)
      }
    })
    if (pageDisposedRef.current || !result.ok) return
  }

  const sharedViewProps = {
    panels,
    hoverIndex,
    onHoverIndexChange: setHoverIndex,
    displayPanelTitle,
    isDubbingBatchGenerating,
    hasVideoForIndex,
    getVideoUrlForIndex,
    getRenderedDialogue,
    onGoStep,
    onEditDubbing: handleEditDubbing,
    onCopyPanel: (index: number) => void panelOps.handleCopyPanel(index),
    onRemovePanel: panelOps.removePanel,
    onPreviewDubbingVideo: handlePreviewDubbingVideo,
    goToStoryboardVideo
  }

  return (
    <div className="dubbing-step create-step-dubbing storyboard-step">
      <div className="storyboard-toolbar">
        <div className="storyboard-toolbar-left">
          <div className="storyboard-view-toggle">
            <Button
              size="small"
              type={viewMode === 'list' ? 'primary' : 'default'}
              onClick={() => setViewMode('list')}
              icon={
                <img
                  src={viewMode === 'list' ? listSelIcon : listNorIcon}
                  alt=""
                  className="storyboard-view-icon"
                />
              }
            >
              列表
            </Button>
            <Button
              size="small"
              type={viewMode === 'card' ? 'primary' : 'default'}
              onClick={() => setViewMode('card')}
              icon={
                <img
                  src={viewMode === 'card' ? cardSelIcon : cardNorIcon}
                  alt=""
                  className="storyboard-view-icon"
                />
              }
            >
              卡片
            </Button>
          </div>
          <div className="storyboard-progress">
            <span>配音完成进度：{progressText}</span>
            {isSyncGeneratingStoryboard || batchGeneratingIndicesArray.length > 0 ? (
              <LoadingOutlined
                className="storyboard-progress-icon storyboard-progress-loading"
                spin
              />
            ) : null}
          </div>
        </div>
        <div className="storyboard-toolbar-right">
          {!isSyncGeneratingStoryboard ? (
            <StoryboardToolbarOpsDropdown
              open={toolbarOpsOpen}
              onOpenChange={setToolbarOpsOpen}
              items={dubbingToolbarOpsItems}
              loading={panelOps.batchDeleteSubmitting || batchGeneratingIndicesArray.length > 0}
              disabled={panels.length === 0}
              onSelect={handleDubbingToolbarOpsSelect}
            />
          ) : null}
        </div>
      </div>

      <div
        className={`storyboard-step-shell dubbing-wrap${
          panels.length > 0 ? ' storyboard-step-shell--has-list dubbing-wrap--has-list' : ''
        }${showDubbingBootstrapMask ? ' storyboard-step-shell--bootstrap-pending' : ''}`}
      >
        {showDubbingBootstrapMask ? (
          <div
            className="storyboard-list-bootstrap-mask"
            role="status"
            aria-live="polite"
            aria-busy="true"
          >
            <LoadingOutlined spin className="storyboard-list-bootstrap-mask__icon" />
            <p className="storyboard-list-bootstrap-mask__text">正在同步音画同步列表…</p>
          </div>
        ) : null}
        {showScriptSyncGeneratingView ? (
          <div className="storyboard-generating-view">
            <div className="storyboard-generating-center">
              <img src={generatingCenterIcon} alt="" className="storyboard-generating-center-icon" />
              <div className="storyboard-generating-center-title">
                正在把剧本进行一格一格分镜拆解...
              </div>
              <div className="storyboard-generating-center-progress">
                提取中（{syncGenerationPercent}%）
              </div>
            </div>
          </div>
        ) : showDubbingEmptyState ? (
          <div className="storyboard-empty-content">
            <div className="storyboard-empty-inner">
              <div className="storyboard-empty-icon-wrap">
                <img src={emptyFjIcon} alt="" />
              </div>
              <p className="storyboard-empty-title">暂无音画同步</p>
              <p className="dubbing-empty-desc">
                请先在「分镜设计」中生成分镜，音画同步列表将自动生成。
              </p>
            </div>
          </div>
        ) : viewMode === 'list' ? (
          /* 列表视图 */
          <DubbingListView
            {...sharedViewProps}
            listInteractive={listInteractive}
            isDubbingDragging={isDubbingDragging}
            editingId={panelOps.editingId}
            editingTitle={panelOps.editingTitle}
            onEditingTitleChange={panelOps.setEditingTitle}
            onStartEditTitle={panelOps.startEditTitle}
            onFinishEditTitle={(panel) => void panelOps.finishEditTitle(panel)}
            onCancelEditTitle={panelOps.cancelEditTitle}
            onDragStart={onDubbingDragStart}
            onDragEnd={onDubbingDragEnd}
            onListDragChange={(from, to) => void panelOps.onDubbingListDragChange(from, to)}
          />
        ) : (
          /* 卡片视图：点击「卡片」按钮后以网格卡片形式展示音画同步列表 */
          <DubbingCardView {...sharedViewProps} />
        )}
      </div>

      {dubbingEditModalOpen ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditStoryboardDubbingModalLazy
          key={`storyboard-dubbing-${panels[dubbingEditSceneIndex]?.id ?? dubbingEditSceneIndex}`}
          editorScopeKey={`storyboard-dubbing-${panels[dubbingEditSceneIndex]?.id ?? dubbingEditSceneIndex}`}
          open={dubbingEditModalOpen}
          onOpenChange={setDubbingEditModalOpen}
          sceneIndex={dubbingEditSceneIndex}
          dubbingPanels={panels}
          storyboardVideoPanels={videoPanels}
          storyboardScriptPanels={scriptPanels}
          batchGeneratingIndices={batchGeneratingIndicesArray}
          onPanelsChange={panelOps.onDubbingPanelsSave}
          onStoryboardVideoPanelsChange={onStoryboardVideoPanelsChange}
          />
        </Suspense>
      ) : null}

      {batchRegenerateModalOpen ? (
        <BatchRegenerateDubbingModal
          open={batchRegenerateModalOpen}
          onOpenChange={setBatchRegenerateModalOpen}
          title={batchRegenerateModalTitle}
          preselectAll={batchRegeneratePreselectAll}
          overwrite={batchRegenerateOverwriteRef.current}
          panels={panels}
          scriptPanels={scriptPanels}
          videoPanels={videoPanels}
          sceneCharacters={sceneCharactersArr}
          onBatchGenerate={(ids, options) => void onBatchGenerate(ids, options)}
        />
      ) : null}
      {batchDeleteModalOpen ? (
        <BatchGenerateStoryboardModal
          open={batchDeleteModalOpen}
          action="delete"
          mode="video"
          title="批量删除分镜配音"
          panels={scriptPanels}
          videoPanels={videoPanels}
          onOpenChange={setBatchDeleteModalOpen}
          onConfirm={(payload) =>
            panelOps.handleBatchDeleteDubbingPanels(payload.selectedStoryboardIds)
          }
        />
      ) : null}
    </div>
  )
}

export default Dubbing

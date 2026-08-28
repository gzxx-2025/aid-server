'use client'

/**
 * 分镜脚本/分镜图步骤主面板（原 aid-pc/components/steps/StoryboardScript.vue，3157 行拆分迁移）。
 *
 * 对外 props 契约（原 defineProps / defineEmits）：
 * - value: StoryboardPanel[]                    —— 原 v-model / modelValue（分镜脚本 panels）
 * - editScriptTooltipTargetIndex?: number|null  —— 壳层「修改分镜脚本」引导 tooltip 目标下标
 * - editScriptTooltipKey?: number               —— 引导 tooltip 触发 key（变化即重放）
 * - onChange(value)                             —— 原 emit('update:modelValue')
 * - onGoStep(stepIndex)                         —— 原 emit('go-step')（跳视频生成传 4）
 * - onGenerationComplete(panels)                —— 原 emit('generation-complete')
 *
 * 原调用点（后续创作壳批次接线，本批次暂不接）：
 * - pages/create/storyboard-script.vue：
 *   v-model 绑定 creationStore.formData.storyboardScript.panels，
 *   editScriptTooltip* 来自壳层 useCreateFlowShell（React 版为 createFlowShellContext），
 *   go-step → shell.goToStep，generation-complete → shell.syncVideoAndDubbingFromScriptPanels。
 *
 * 拆分结构见 ./storyboard-script/ 子目录（列表/卡片视图 + 行操作 + 生成/恢复编排 + 任务命令 + 弹窗桥接）。
 */

import { Suspense, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { Button, message } from 'antd'
import {
  DeleteOutlined,
  LoadingOutlined,
  PictureOutlined,
  PlusOutlined,
  StopOutlined,
  ThunderboltOutlined
} from '@ant-design/icons'
import listNorRaw from '~/assets/img/icon/list-nor.svg'
import listSelRaw from '~/assets/img/icon/list-sel.svg'
import cardNorRaw from '~/assets/img/icon/card-nor.svg'
import cardSelRaw from '~/assets/img/icon/card-sel.svg'
import emptyFjRaw from '~/assets/img/icon/empty-fj.svg'
import { assetUrl } from '~/utils/assetUrl'
import { GENERATING_CENTER_ICON_URL as generatingCenterIcon } from '~/utils/generatingCenterIcon'
import { useCreationStore } from '~/stores/creation'
import { buildTaskResumeBillingRequest } from '~/utils/taskPartialFailed'
import { createFlowShellContext } from '~/utils/createFlowInjection'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import {
  resolveStoryboardDesignProgressCounts,
  skipsStoryboardImageGeneration
} from '~/utils/creationModeUiRules'
import {
  formatTaskSseLiveText,
  formatTaskSseLiveTextWithCounts
} from '~/utils/taskSseProgressText'
import { getPersistedStoryboardScriptPanels } from '~/utils/storyboardPanelMap'
import {
  hasPersistedStoryboardImageBatchGenWork,
  hasPersistedStoryboardScriptBatchGenWork
} from '~/utils/storyboardListBootstrap'
import { getActiveImageBatchTargetIds } from '~/composables/useStoryboardImageBatchGenerate'
import { clearModalImageGenUserDismissed } from '~/utils/storyboardImageModalGenSession'
import {
  consumeCreateFlowStepModalIntent,
  peekCreateFlowStepModalIntent,
  requestCreateFlowStepModal,
} from '~/utils/createFlowStepModalIntent'
import { useCreateFlowStepModalIntent } from '~/hooks/useCreateFlowStepModalIntent'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import StoryboardToolbarOpsDropdown, {
  type StoryboardOpsMenuItem
} from './StoryboardToolbarOpsDropdown'
import { AsyncModalLoading } from '~/components/common/AsyncModalLoading'
import { StoryboardScriptModal } from './StoryboardScriptModal'
import { StoryboardGenerateModal } from './StoryboardGenerateModal'
import { BatchGenerateStoryboardModal } from './BatchGenerateStoryboardModal'
import { StoryboardScriptCardView } from './storyboard-script/StoryboardScriptCardView'
import { StoryboardScriptListView } from './storyboard-script/StoryboardScriptListView'
import { useStoryboardScriptPanelOps } from './storyboard-script/useStoryboardScriptPanelOps'
import { useStoryboardScriptGeneration } from './storyboard-script/useStoryboardScriptGeneration'
import {
  EditStoryboardImageModalLazy,
  preloadStoryboardImageModalWhenIdle
} from './storyboard-script/storyboardScriptImageModalLoader'
import {
  getPanelCoverImage,
  type StoryboardFailedPanelItem,
  type StoryboardPanel,
  type StoryboardScriptViewSharedProps
} from './storyboard-script/storyboardScriptShared'
import './storyboard-script/storyboard-script.css'

const listNorIcon = assetUrl(listNorRaw)
const listSelIcon = assetUrl(listSelRaw)
const cardNorIcon = assetUrl(cardNorRaw)
const cardSelIcon = assetUrl(cardSelRaw)
const emptyFjIcon = assetUrl(emptyFjRaw)

export interface StoryboardScriptProps {
  value: StoryboardPanel[]
  editScriptTooltipTargetIndex?: number | null
  editScriptTooltipKey?: number
  onChange: (value: StoryboardPanel[]) => void
  onGoStep: (stepIndex: number) => void
  onGenerationComplete: (panels: StoryboardPanel[]) => void
}

export function StoryboardScript({
  value,
  editScriptTooltipTargetIndex = null,
  editScriptTooltipKey = 0,
  onChange,
  onGoStep,
  onGenerationComplete
}: StoryboardScriptProps) {
  const createFlowShell = useContext(createFlowShellContext)
  const getStore = () => useCreationStore.getState()

  const storyboardListLoading = createFlowShell?.storyboardListLoading ?? false
  const storyboardListSyncReady = createFlowShell?.storyboardListSyncReady ?? true
  const storyboardListSyncReadyRef = useRef(storyboardListSyncReady)
  storyboardListSyncReadyRef.current = storyboardListSyncReady

  // 订阅 store：与原 Pinia computed 依赖对齐（scope 桶更新需重算 bootstrap 蒙层 / 生成中判定）
  const isGenerating = useCreationStore((s) => s.isGeneratingStoryboard)
  const isGeneratingImageBatch = useCreationStore((s) => s.isGeneratingStoryboardImageBatch)
  const generationError = useCreationStore((s) => s.storyboardGenerationError)
  const progress = useCreationStore((s) => s.storyboardGenerationProgress)
  const imageBatchProgress = useCreationStore((s) => s.storyboardImageBatchProgress)
  const storyboardScriptPartialFailedData = useCreationStore(
    (s) => s.storyboardScriptPartialFailedData
  )
  const storyboardScriptActiveTaskId = useCreationStore((s) => s.storyboardScriptActiveTaskId)
  const storyboardPanelImageGenStatusByStoryboardId = useCreationStore(
    (s) => s.storyboardPanelImageGenStatusByStoryboardId
  )
  const storyboardImageBatchTargetStoryboardIds = useCreationStore(
    (s) => s.storyboardImageBatchTargetStoryboardIds
  )
  const step4PlusLiveGenByScope = useCreationStore((s) => s.step4PlusLiveGenByScope)
  const sceneCharacterScenes = useCreationStore((s) => s.formData.sceneCharacter?.scenes)

  useEffect(() => preloadStoryboardImageModalWhenIdle(), [])
  const creationMode = useCreationStore((s) => s.formData.globalSetting?.creationMode)
  const storyboardAgent = useCreationStore((s) => s.storyboardAgent)
  const storyboardGenerateSettings = useCreationStore((s) => s.storyboardGenerateSettings)

  /** 专业版 / 多参数：分镜步骤仅脚本，无分镜图 / 批量图操作 */
  const isProMode = skipsStoryboardImageGeneration(creationMode)

  const panels = useMemo(() => value || [], [value])
  const persistedPanels = getPersistedStoryboardScriptPanels(panels)

  // 异步恢复/弹窗回调中读取最新面板与回调（原 Vue computed 调用时求值）
  const panelsRef = useRef(panels)
  panelsRef.current = panels
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  const onGenerationCompleteRef = useRef(onGenerationComplete)
  onGenerationCompleteRef.current = onGenerationComplete

  const [viewMode, setViewMode] = useState<'list' | 'card'>('list')
  const [showAutoGenerateModal, setShowAutoGenerateModal] = useState(false)
  const [batchGenerateImageModalOpen, setBatchGenerateImageModalOpen] = useState(false)
  const [batchDeleteModalOpen, setBatchDeleteModalOpen] = useState(false)
  const scriptManualAgentModelPickRef = useRef(false)
  const [toolbarOpsOpen, setToolbarOpsOpen] = useState(false)
  const [isImageModalOpen, setIsImageModalOpen] = useState(false)
  const isImageModalOpenRef = useRef(isImageModalOpen)
  isImageModalOpenRef.current = isImageModalOpen
  const [currentPanelIndex, setCurrentPanelIndex] = useState(-1)
  const currentPanelIndexRef = useRef(currentPanelIndex)
  currentPanelIndexRef.current = currentPanelIndex
  const [showStoryboardScriptModal, setShowStoryboardScriptModal] = useState(false)
  const [currentScriptPanelIndex, setCurrentScriptPanelIndex] = useState(-1)
  const currentScriptPanelIndexRef = useRef(currentScriptPanelIndex)
  currentScriptPanelIndexRef.current = currentScriptPanelIndex
  const [editScriptTooltipVisibleIndex, setEditScriptTooltipVisibleIndex] = useState<
    number | null
  >(null)
  const editScriptTooltipVisibleIndexRef = useRef(editScriptTooltipVisibleIndex)
  editScriptTooltipVisibleIndexRef.current = editScriptTooltipVisibleIndex
  /** 防止 Tooltip 与受控 open 在同一 tick 内反复开关 */
  const editScriptTooltipConsumedKeyRef = useRef(0)
  const editScriptTooltipTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const editScriptTooltipKeyRef = useRef(editScriptTooltipKey)
  editScriptTooltipKeyRef.current = editScriptTooltipKey

  const stepRootRef = useRef<HTMLDivElement | null>(null)
  const listRef = useRef<HTMLDivElement | null>(null)
  const bottomAddBarRef = useRef<HTMLDivElement | null>(null)

  /** 原 listInteractive ref：仅在 onBeforeUnmount 置 false 拆除拖拽；React 卸载即销毁，恒为 true */
  const listInteractive = true

  function clearEditScriptTooltipState() {
    setEditScriptTooltipVisibleIndex(null)
    editScriptTooltipConsumedKeyRef.current = editScriptTooltipKeyRef.current ?? 0
    if (editScriptTooltipTimerRef.current) {
      clearTimeout(editScriptTooltipTimerRef.current)
      editScriptTooltipTimerRef.current = null
    }
    createFlowShell?.clearStoryboardScriptJumpTooltip()
  }

  const clearEditScriptTooltipStateRef = useRef(clearEditScriptTooltipState)
  clearEditScriptTooltipStateRef.current = clearEditScriptTooltipState

  // 生成链路与后台任务恢复编排（含全局任务命令 / 弹窗自动重开 / 批量分镜图恢复）
  const gen = useStoryboardScriptGeneration({
    panelsRef,
    onChangeRef,
    onGenerationCompleteRef,
    isImageModalOpenRef,
    currentPanelIndexRef,
    setCurrentPanelIndex,
    setIsImageModalOpen,
    setToolbarOpsOpen,
    storyboardListSyncReadyRef,
    storyboardListSyncReady,
    scriptManualAgentModelPickRef
  })

  // 分镜行操作（增删改/复制/标题编辑/拖拽排序/插入空白/滚动定位/图片操作）
  const ops = useStoryboardScriptPanelOps({
    panelsRef,
    onChangeRef,
    pageDisposedRef: gen.pageDisposedRef,
    stepRootRef,
    listRef,
    bottomAddBarRef,
    currentScriptPanelIndexRef,
    setShowStoryboardScriptModal,
    clearEditScriptTooltipStateRef
  })

  // 原 onUnmounted：清理引导 tooltip 状态与定时器
  useEffect(() => {
    return () => {
      clearEditScriptTooltipStateRef.current()
    }
     
  }, [])

  /* ---------- 派生展示状态（原 computed） ---------- */

  const showStoryboardBootstrapMask =
    (storyboardListLoading || !storyboardListSyncReady) &&
    !gen.hasOngoingStoryboardScriptGenWork()

  const showStoryboardScriptGeneratingView =
    isGenerating ||
    (!storyboardListSyncReady &&
      hasPersistedStoryboardScriptBatchGenWork(getStore(), getRouteLikeSnapshot()))

  const showStoryboardEmptyState =
    !showStoryboardScriptGeneratingView &&
    !isGeneratingImageBatch &&
    !showStoryboardBootstrapMask &&
    persistedPanels.length === 0

  const isPartialStoryboardError = (() => {
    const msg = String(generationError || '')
    if (!msg) return false
    if (msg.includes('部分') || msg.includes('续生')) return true
    return !!storyboardScriptPartialFailedData?.failedItems?.length
  })()
  const showStoryboardPartialBanner =
    isPartialStoryboardError && !!generationError && persistedPanels.length > 0

  const storyboardScriptProgressMessage = formatTaskSseLiveText(
    progress,
    '正在按场次生成分镜脚本…'
  )

  const storyboardImageCompletedCount = panels.filter(
    (p) => !!getPanelCoverImage(p)?.url
  ).length
  const storyboardDesignProgress = resolveStoryboardDesignProgressCounts(
    creationMode,
    persistedPanels.length,
    storyboardImageCompletedCount
  )

  const progressText = (() => {
    if (isGenerating) {
      const live = formatTaskSseLiveText(progress, '')
      if (live) return live
      return `第 ${progress.completed}/${progress.total} 场次`
    }
    if (isGeneratingImageBatch) {
      return formatTaskSseLiveTextWithCounts(imageBatchProgress, '分镜图生成中')
    }
    return `${storyboardDesignProgress.completed}/${storyboardDesignProgress.total}`
  })()

  const imageBatchGeneratingTargetSet = useMemo(() => {
    const store = getStore()
    const routeSnap = getRouteLikeSnapshot()
    const batchActive =
      isGeneratingImageBatch || hasPersistedStoryboardImageBatchGenWork(store, routeSnap)
    if (!batchActive) return new Set<number>()
    return new Set(getActiveImageBatchTargetIds(store, routeSnap))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    isGeneratingImageBatch,
    storyboardImageBatchTargetStoryboardIds,
    storyboardPanelImageGenStatusByStoryboardId,
    step4PlusLiveGenByScope
  ])

  function isPanelImageGenerating(panel: StoryboardPanel): boolean {
    const sid = parseServerStoryboardId(panel.id)
    if (sid == null) return false
    if (storyboardPanelImageGenStatusByStoryboardId[String(sid)] === 'generating') {
      return true
    }
    return imageBatchGeneratingTargetSet.has(sid)
  }

  const scriptAutoGenerateLabel = panels.length > 0 ? '重新生成分镜' : '自动生成分镜'

  const scriptToolbarOpsItems: StoryboardOpsMenuItem[] = [
    {
      key: 'auto-script',
      label: scriptAutoGenerateLabel,
      icon: ThunderboltOutlined,
    },
    {
      key: 'batch-image',
      label: '批量生成分镜图',
      icon: PictureOutlined,
      disabled: panels.length === 0 || isGeneratingImageBatch || isGenerating,
      disabledTooltip: panels.length === 0 ? '暂无分镜，请先添加或自动生成分镜' : undefined
    },
    {
      key: 'batch-delete',
      label: '批量删除分镜脚本',
      icon: DeleteOutlined,
      danger: true,
      disabled: panels.length === 0 || ops.batchDeleteSubmitting
    }
  ]

  const failedPanels: StoryboardFailedPanelItem[] = useMemo(() => {
    if (!generationError) return []

    const failedItems = storyboardScriptPartialFailedData?.failedItems
    if (Array.isArray(failedItems) && failedItems.length > 0) {
      const scenes = sceneCharacterScenes || []
      return failedItems.map((item, idx) => {
        const batchIndex = Number(item.batchIndex ?? idx)
        const sceneAtIndex = scenes[Number.isFinite(batchIndex) ? batchIndex : idx]
        const sceneName = String(item.sceneName ?? sceneAtIndex ?? '').trim()
        const failMessage = String(item.message ?? item.reason ?? '生成失败，请重试').trim()
        return {
          id: `failed-${String(item.sceneId ?? item.scene_id ?? item.batchId ?? batchIndex)}`,
          title: sceneName || `场次${(Number.isFinite(batchIndex) ? batchIndex : idx) + 1}`,
          message: failMessage
        }
      })
    }

    return []
  }, [generationError, storyboardScriptPartialFailedData, sceneCharacterScenes])

  const canResumePartialFailed =
    !!generationError &&
    Number.isFinite(Number(storyboardScriptActiveTaskId)) &&
    Number(storyboardScriptActiveTaskId) > 0
  const resumeBillingRequest = buildTaskResumeBillingRequest(storyboardScriptActiveTaskId)

  const currentScriptPanelTitle =
    currentScriptPanelIndex >= 0 && currentScriptPanelIndex < panels.length
      ? panels[currentScriptPanelIndex]?.title ?? ''
      : ''

  const currentScriptPanelContent =
    currentScriptPanelIndex >= 0 && currentScriptPanelIndex < panels.length
      ? panels[currentScriptPanelIndex]?.scriptContent ?? ''
      : ''

  const storyboardScenes = useMemo(
    () =>
      panels.map((panel) => ({
        name: panel.title,
        images: Array.isArray(panel.images) ? panel.images.map((img) => ({ ...img })) : [],
        scriptContent: panel.scriptContent ?? '',
        storyboardId: Number.isFinite(Number(panel.id)) ? Number(panel.id) : undefined
      })),
    [panels]
  )

  /* ---------- 工具栏 / 弹窗交互 ---------- */

  function openAutoGenerateModal() {
    setShowAutoGenerateModal(true)
  }

  function handleScriptToolbarOpsSelect(key: string) {
    if (key === 'auto-script') {
      openAutoGenerateModal()
      return
    }
    if (key === 'batch-image') {
      openBatchGenerateImageModal()
      return
    }
    if (key === 'batch-delete') {
      setBatchDeleteModalOpen(true)
    }
  }

  function openBatchGenerateImageModal() {
    if (panels.length === 0) {
      message.warning('暂无分镜，请先添加或自动生成分镜')
      return
    }
    setBatchGenerateImageModalOpen(true)
  }

  async function handleBatchGenerateImageConfirm(payload: {
    mode: 'image' | 'video'
    selectedStoryboardIds: number[]
    agent?: string
    model?: string
  }) {
    if (payload.mode !== 'image') return
    setBatchGenerateImageModalOpen(false)
    // 原 computed storyboardImageCompletedCount > 0：调用时按最新面板求值
    const overwrite =
      panelsRef.current.filter((p) => !!getPanelCoverImage(p)?.url).length > 0
    await gen.handleBatchGenerateStoryboardImages({
      selectedStoryboardIds: payload.selectedStoryboardIds,
      manualAgentModelPick: true,
      agentCode: payload.agent,
      modelCode: payload.model,
      overwrite
    })
  }

  function handleConfirmAutoGenerate(settings: {
    agentId: string
    shotDensity: string
    modelCode?: string
    manualAgentModelPick?: boolean
  }) {
    scriptManualAgentModelPickRef.current = settings.manualAgentModelPick === true
    getStore().setStoryboardGenerateSettings({
      shotDensity: settings.shotDensity,
      ...(scriptManualAgentModelPickRef.current
        ? { agentId: settings.agentId, modelCode: settings.modelCode }
        : { agentId: '', modelCode: '' })
    })
    void gen.startGeneration()
  }

  function openStoryboardImage(index: number) {
    // 原 editStoryboardImageModalLoader.preload()：React 侧弹窗为静态 import，无需预热
    clearModalImageGenUserDismissed()
    setCurrentPanelIndex(index)
    setIsImageModalOpen(true)
  }

  /** 跳转视频生成并打开对应分镜视频弹窗 */
  function jumpToVideoWithModal(index: number) {
    requestCreateFlowStepModal('storyboard-video', index)
    onGoStep(4)
  }

  const openStoryboardScriptModal = (index: number) => {
    clearEditScriptTooltipState()
    setCurrentScriptPanelIndex(index)
    setShowStoryboardScriptModal(true)
  }

  /* ---------- 跨步骤意图 / 弹窗关闭 / 引导 tooltip（原 watch） ---------- */

  /** 消费跨步骤意图：打开编辑分镜图（视频页点「分镜设计」） */
  function tryConsumeStepModalIntent() {
    if (gen.pageDisposedRef.current) return
    const pending = peekCreateFlowStepModalIntent()
    if (!pending || pending.kind !== 'storyboard-image') return
    if (pending.panelIndex < 0 || pending.panelIndex >= panelsRef.current.length) return
    const index = consumeCreateFlowStepModalIntent('storyboard-image')
    if (index == null) return
    setViewMode('list')
    setTimeout(() => {
      if (gen.pageDisposedRef.current) return
      ops.scrollToPanelIndex(index)
      openStoryboardImage(index)
    }, 0)
  }

  // 原 watch([panels.length, intent.token], { immediate: true })
  const modalIntentToken = useCreateFlowStepModalIntent()?.token ?? null
  useEffect(() => {
    queueMicrotask(tryConsumeStepModalIntent)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [panels.length, modalIntentToken])

  // 原 watch(isImageModalOpen)：关闭时挂起弹窗 SSE、刷新列表并恢复批量任务
  const prevImageModalOpenRef = useRef(isImageModalOpen)
  useEffect(() => {
    const wasOpen = prevImageModalOpenRef.current
    prevImageModalOpenRef.current = isImageModalOpen
    if (wasOpen && !isImageModalOpen) {
      gen.handleImageModalClosed()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isImageModalOpen])

  function onEditScriptTooltipOpenChange(open: boolean, index: number) {
    if (!open && editScriptTooltipVisibleIndexRef.current === index) {
      clearEditScriptTooltipState()
    }
  }

  function showEditScriptTooltipForIndex(targetIndex: number) {
    if (targetIndex < 0 || targetIndex >= panelsRef.current.length) return
    setViewMode('list')
    setEditScriptTooltipVisibleIndex(targetIndex)
    ops.scrollToPanelIndex(targetIndex)
    if (editScriptTooltipTimerRef.current) clearTimeout(editScriptTooltipTimerRef.current)
    editScriptTooltipTimerRef.current = setTimeout(() => {
      clearEditScriptTooltipStateRef.current()
    }, 6000)
  }

  // 原 watch([props.editScriptTooltipKey, panels.length], { immediate: true })
  useEffect(() => {
    const key = editScriptTooltipKey
    if (!key || key === editScriptTooltipConsumedKeyRef.current) return
    const targetIndex = editScriptTooltipTargetIndex
    if (targetIndex === null || targetIndex === undefined) return
    if (targetIndex < 0 || targetIndex >= panels.length) return
    if (editScriptTooltipVisibleIndexRef.current === targetIndex) return
    setTimeout(() => showEditScriptTooltipForIndex(targetIndex), 0)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editScriptTooltipKey, panels.length])

  /* ---------- 视图共享 props ---------- */

  const sharedViewProps: StoryboardScriptViewSharedProps = {
    panels,
    isProMode,
    editingId: ops.editingId,
    editingTitle: ops.editingTitle,
    onEditingTitleChange: ops.setEditingTitle,
    onStartEditTitle: ops.startEditTitle,
    onFinishEditTitle: (panel) => void ops.finishEditTitle(panel),
    onCancelEditTitle: ops.cancelEditTitle,
    isPanelImageGenerating,
    onOpenStoryboardScriptModal: openStoryboardScriptModal,
    onOpenStoryboardImage: openStoryboardImage,
    onCopyPanel: (index) => void ops.handleCopyPanel(index),
    onRemovePanel: ops.removePanel,
    onJumpToVideoWithModal: jumpToVideoWithModal,
    onPreviewStoryboardImage: ops.handlePreviewStoryboardImage,
    onDownloadStoryboardImage: ops.handleDownloadStoryboardImage,
    onDeleteStoryboardImage: ops.handleDeleteStoryboardImage,
    showStoryboardPartialBanner,
    generationError,
    canResumePartialFailed,
    isResumingPartialFailed: gen.isResumingPartialFailed,
    resumeBillingRequest,
    onResumePartialFailed: () => void gen.handleResumePartialFailed(),
    failedPanels
  }

  return (
    <div
      ref={stepRootRef}
      className="storyboard-script create-step-storyboard-script storyboard-step"
    >
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
            <span>分镜完成进度：{progressText}</span>
            {isGenerating || isGeneratingImageBatch ? (
              <LoadingOutlined className="storyboard-progress-loading" spin />
            ) : null}
          </div>
        </div>
        <div className="storyboard-toolbar-right">
          <Button
            size="small"
            className="storyboard-action-btn"
            onClick={() => void ops.addPanel()}
            icon={<PlusOutlined />}
          >
            添加分镜
          </Button>
          {/* 专业版 / 多参数：隐藏批量操作，仅保留「自动生成分镜」入口 */}
          {isProMode && !isGenerating && !isGeneratingImageBatch ? (
            <Button
              size="small"
              className="storyboard-action-btn"
              onClick={openAutoGenerateModal}
              icon={<ThunderboltOutlined />}
            >
              {scriptAutoGenerateLabel}
            </Button>
          ) : !isProMode &&
            !isGenerating && !isGeneratingImageBatch ? (
            <StoryboardToolbarOpsDropdown
              open={toolbarOpsOpen}
              onOpenChange={setToolbarOpsOpen}
              items={scriptToolbarOpsItems}
              loading={ops.batchDeleteSubmitting}
              onSelect={handleScriptToolbarOpsSelect}
            />
          ) : isGeneratingImageBatch ? (
            <Button
              size="small"
              danger
              className="storyboard-action-btn"
              onClick={() => void gen.stopImageBatchGeneration()}
              icon={<StopOutlined />}
            >
              停止生成
            </Button>
          ) : (
            <Button
              size="small"
              danger
              className="storyboard-action-btn"
              onClick={() => void gen.stopGeneration()}
              icon={<StopOutlined />}
            >
              停止生成
            </Button>
          )}
        </div>
      </div>

      <div
        className={`storyboard-step-shell storyboard-script-empty${
          persistedPanels.length > 0 && (!isGenerating || isGeneratingImageBatch)
            ? ' storyboard-step-shell--has-list storyboard-script-empty--has-list'
            : ''
        }${showStoryboardBootstrapMask ? ' storyboard-step-shell--bootstrap-pending' : ''}`}
      >
        {showStoryboardBootstrapMask ? (
          <div
            className="storyboard-list-bootstrap-mask"
            role="status"
            aria-live="polite"
            aria-busy="true"
          >
            <LoadingOutlined spin className="storyboard-list-bootstrap-mask__icon" />
            <p className="storyboard-list-bootstrap-mask__text">正在同步分镜列表…</p>
          </div>
        ) : null}
        {/* 生成中：骨架屏 + 执行任务中 */}
        {showStoryboardScriptGeneratingView ? (
          <div className="storyboard-generating-view">
            <div className="storyboard-generating-center">
              <img src={generatingCenterIcon} alt="" className="storyboard-generating-center-icon" />
              <div className="storyboard-generating-center-title">
                {storyboardScriptProgressMessage || '正在按场次生成分镜脚本…'}
              </div>
              <div className="storyboard-generating-center-progress">
                第 {progress.completed}/{progress.total} 场次
              </div>
            </div>
          </div>
        ) : showStoryboardEmptyState ? (
          <div className="storyboard-empty-content">
            <div className="storyboard-empty-inner">
              <div className="storyboard-empty-icon-wrap">
                <img src={emptyFjIcon} alt="" />
              </div>
              <p className="storyboard-empty-title">暂无分镜</p>
              <Button
                className="storyboard-empty-add-btn"
                size="small"
                onClick={() => void ops.addPanel()}
              >
                <div className="text-gradient">添加分镜</div>
              </Button>
            </div>
          </div>
        ) : viewMode === 'card' ? (
          /* 卡片视图 */
          <StoryboardScriptCardView {...sharedViewProps} />
        ) : (
          /* 列表视图 */
          <StoryboardScriptListView
            {...sharedViewProps}
            listInteractive={listInteractive}
            isShotDragging={ops.isShotDragging}
            onDragStart={ops.onShotDragStart}
            onDragEnd={ops.onShotDragEnd}
            onListDragChange={(from, to) => void ops.onShotListDragChange(from, to)}
            activeInsertSlot={ops.activeInsertSlot}
            onInsertSlotEnter={ops.onInsertSlotEnter}
            onInsertSlotLeave={ops.onInsertSlotLeave}
            onClearInsertSlotImmediate={ops.clearInsertSlotImmediate}
            onInsertBlankPanelAt={(idx) => void ops.insertBlankPanelAt(idx)}
            onAddPanel={() => void ops.addPanel()}
            onPreviewReferenceImages={ops.handlePreviewReferenceImages}
            editScriptTooltipVisibleIndex={editScriptTooltipVisibleIndex}
            onEditScriptTooltipOpenChange={onEditScriptTooltipOpenChange}
            listRef={listRef}
            bottomAddBarRef={bottomAddBarRef}
          />
        )}
      </div>

      {isImageModalOpen && currentPanelIndex >= 0 ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditStoryboardImageModalLazy
            key={`storyboard-image-${panels[currentPanelIndex]?.id ?? currentPanelIndex}`}
            open={isImageModalOpen}
            sceneIndex={currentPanelIndex}
            editorScopeKey={`storyboard-image-${panels[currentPanelIndex]?.id ?? currentPanelIndex}`}
            scenes={storyboardScenes}
            onOpenChange={setIsImageModalOpen}
            onUpdate={(sceneIndex, data) => void ops.handleStoryboardUpdate(sceneIndex, data)}
          />
        </Suspense>
      ) : null}
      {showStoryboardScriptModal ? (
        <StoryboardScriptModal
          open={showStoryboardScriptModal}
          panelTitle={currentScriptPanelTitle}
          initialContent={currentScriptPanelContent}
          onOpenChange={setShowStoryboardScriptModal}
          onSave={(payload) => void ops.handleSaveStoryboardScript(payload)}
        />
      ) : null}
      {/* 自动生成分镜弹窗：确定后开始生成 */}
      {showAutoGenerateModal ? (
        <StoryboardGenerateModal
          open={showAutoGenerateModal}
          mode="generate"
          agent={storyboardAgent}
          shotDensity={storyboardGenerateSettings.shotDensity}
          quoteOverwrite={panels.some((panel) => parseServerStoryboardId(panel.id) != null)}
          onOpenChange={setShowAutoGenerateModal}
          onConfirm={(settings) =>
            handleConfirmAutoGenerate(
              settings as {
                agentId: string
                shotDensity: string
                modelCode?: string
                manualAgentModelPick?: boolean
              }
            )
          }
        />
      ) : null}
      {batchGenerateImageModalOpen ? (
        <BatchGenerateStoryboardModal
          open={batchGenerateImageModalOpen}
          mode="image"
          title="批量生成分镜图"
          panels={panels}
          onOpenChange={setBatchGenerateImageModalOpen}
          onConfirm={(payload) => void handleBatchGenerateImageConfirm(payload)}
        />
      ) : null}
      {batchDeleteModalOpen ? (
        <BatchGenerateStoryboardModal
          open={batchDeleteModalOpen}
          action="delete"
          mode="image"
          title="批量删除分镜图"
          panels={panels}
          onOpenChange={setBatchDeleteModalOpen}
          onConfirm={(payload) => ops.handleBatchDeleteStoryboardPanels(payload.selectedStoryboardIds)}
        />
      ) : null}
    </div>
  )
}

export default StoryboardScript

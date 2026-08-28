'use client'

/**
 * 分镜视频步骤主面板（原 components/steps/StoryboardVideo.vue，2798 行）。
 *
 * 对外 props 契约（对照原 defineProps / defineEmits）：
 * - description: string                          —— 步骤描述（原样保留，模板未直接使用）
 * - value: StoryboardVideoPanel[]                —— 原 v-model:modelValue 读侧
 * - storyboardScriptPanels?: StoryboardPanel[]   —— 分镜脚本 panels，用于进入第五步时同步生成视频 panels
 * - onChange(value)                              —— 原 emit('update:modelValue', value)
 * - onGoStep(stepIndex)                          —— 原 emit('go-step', stepIndex)
 * - onJumpToStoryboardScript(panelIndex)         —— 原 emit('jump-to-storyboard-script', panelIndex)
 *
 * 原 Vue 调用点（后续批次接线）：aid-pc/pages/create/storyboard-video.vue（创作壳步骤页）。
 * 本批次暂不接线，由创作壳步骤页批次统一挂载。
 *
 * 拆分：列表/卡片视图、面板行操作、播放控制、后台任务恢复编排均在 storyboard-video/ 子目录；
 * 批量生成主体在 hooks/useStoryboardVideoBatchGenerate.ts（含 utils/storyboardVideoBatch* 拆分）。
 */

import { Suspense, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { Button, Tooltip, message } from 'antd'
import {
  DeleteOutlined,
  LoadingOutlined,
  PlusOutlined,
  StopOutlined,
  VideoCameraOutlined
} from '@ant-design/icons'
import { assetUrl } from '~/utils/assetUrl'
import listNorRaw from '~/assets/img/icon/list-nor.svg'
import listSelRaw from '~/assets/img/icon/list-sel.svg'
import cardNorRaw from '~/assets/img/icon/card-nor.svg'
import cardSelRaw from '~/assets/img/icon/card-sel.svg'
import emptyFjRaw from '~/assets/img/icon/empty-fj.svg'
import { GENERATING_CENTER_ICON_URL as generatingCenterIcon } from '~/utils/generatingCenterIcon'
import type { StoryboardVideoPanel, StoryboardPanel } from '~/types'
import { useCreationStore } from '~/stores/creation'
import { useRouteLike, getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { createFlowShellContext } from '~/utils/createFlowInjection'
import { useStoryboardWorkbenchMutations } from '~/composables/useStoryboardWorkbenchMutations'
import {
  isStoryboardVideoModalRestoreFollowing,
  activeStoryboardVideoModalOwnedFollowIds
} from '~/composables/useStoryboardVideoBatchGenerate'
import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import { findStoryboardVideoGenTaskInScopes } from '~/composables/useCreationStoreHydration'
import { modalGenSessionScopeFromStore } from '~/utils/modalGenSessionScope'
import {
  markStoryboardVideoModalUserDismissed,
  readStoryboardVideoModalGenSession
} from '~/utils/storyboardVideoModalGenSession'
import {
  consumeCreateFlowStepModalIntent,
  peekCreateFlowStepModalIntent,
  requestCreateFlowStepModal,
} from '~/utils/createFlowStepModalIntent'
import { useCreateFlowStepModalIntent } from '~/hooks/useCreateFlowStepModalIntent'
import {
  hasPersistedStoryboardScriptBatchGenWork,
  hasPersistedStoryboardVideoBatchGenWork
} from '~/utils/storyboardListBootstrap'
import { createDefaultVideoPanel } from '~/composables/useCreateFlowStoryboardSync'
import { resolveStoryboardListDisplayTitle } from '~/utils/storyboardPanelTitle'
import {
  resolveStoryboardDesignProgressCounts,
  skipsStoryboardImageGeneration
} from '~/utils/creationModeUiRules'
import StoryboardToolbarOpsDropdown, {
  type StoryboardOpsMenuItem
} from './StoryboardToolbarOpsDropdown'
import type { EditStoryboardVideoModalScene } from './EditStoryboardVideoModal'
import { AsyncModalLoading } from '~/components/common/AsyncModalLoading'
import { EditStoryboardVideoModalLazy, preloadStoryboardVideoEditorWhenIdle } from './heavyEditorModalLoaders'
import BatchGenerateStoryboardModal from './BatchGenerateStoryboardModal'
import { getPanelStoryboardVideo } from './storyboard-video/storyboardVideoViewShared'
import StoryboardVideoCardView from './storyboard-video/StoryboardVideoCardView'
import StoryboardVideoListView from './storyboard-video/StoryboardVideoListView'
import { useStoryboardVideoPanelOps } from './storyboard-video/useStoryboardVideoPanelOps'
import { useStoryboardVideoPlayback } from './storyboard-video/useStoryboardVideoPlayback'
import { useStoryboardVideoStepRestore } from './storyboard-video/useStoryboardVideoStepRestore'
import { useStoryboardVideoBatchActions } from './storyboard-video/useStoryboardVideoBatchActions'
import './storyboard-video/storyboard-video.css'

const listNorIcon = assetUrl(listNorRaw)
const listSelIcon = assetUrl(listSelRaw)
const cardNorIcon = assetUrl(cardNorRaw)
const cardSelIcon = assetUrl(cardSelRaw)
const emptyFjIcon = assetUrl(emptyFjRaw)

export interface StoryboardVideoProps {
  description: string
  value: StoryboardVideoPanel[]
  /** 分镜脚本 panels，用于进入第五步时同步生成视频 panels */
  storyboardScriptPanels?: Array<{ id: string; title: string; [key: string]: any }>
  onChange: (value: StoryboardVideoPanel[]) => void
  onGoStep: (stepIndex: number) => void
  onJumpToStoryboardScript: (panelIndex: number) => void
}

export function StoryboardVideo({
  description: _description,
  value,
  storyboardScriptPanels,
  onChange,
  onGoStep,
  onJumpToStoryboardScript
}: StoryboardVideoProps) {
  useEffect(() => preloadStoryboardVideoEditorWhenIdle(), [])
  const route = useRouteLike()
  const createFlowShell = useContext(createFlowShellContext)
  const wb = useStoryboardWorkbenchMutations()

  const storyboardListLoading = createFlowShell?.storyboardListLoading ?? false
  const storyboardListSyncReady = createFlowShell?.storyboardListSyncReady ?? true

  // 订阅 store：与原 Pinia computed 依赖对齐（scope 桶更新需重算 bootstrap 蒙层 / 卡片 loading）
  const isGeneratingVideo = useCreationStore((s) => s.isGeneratingStoryboardVideo)
  const isSyncGeneratingStoryboard = useCreationStore((s) => s.isGeneratingStoryboard)
  const storyboardGenerationError = useCreationStore((s) => s.storyboardGenerationError)
  const storyboardGenerationProgress = useCreationStore((s) => s.storyboardGenerationProgress)
  const videoBatchProgress = useCreationStore((s) => s.storyboardVideoBatchProgress)
  // 订阅任务 id：isPanelVideoGenerating/panelVideoGenerateError 读 getState()，靠订阅触发重渲
  const promptTid = useCreationStore((s) => s.storyboardVideoBatchActivePromptTaskId)
  void promptTid
  const videoTid = useCreationStore((s) => s.storyboardVideoBatchActiveVideoTaskId)
  void videoTid
  const batchTargetIds = useCreationStore((s) => s.storyboardVideoBatchTargetStoryboardIds)
  void batchTargetIds
  const panelVideoGenStatusById = useCreationStore(
    (s) => s.storyboardPanelVideoGenStatusByStoryboardId
  )
  void panelVideoGenStatusById
  const panelVideoGenErrorById = useCreationStore(
    (s) => s.storyboardPanelVideoGenErrorByStoryboardId
  )
  void panelVideoGenErrorById
  const step4PlusLiveGenByScope = useCreationStore((s) => s.step4PlusLiveGenByScope)
  void step4PlusLiveGenByScope
  const isHydrated = useCreationStore((s) => s.isHydrated)
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const creationMode = useCreationStore((s) => s.formData.globalSetting?.creationMode)

  const [viewMode, setViewMode] = useState<'list' | 'card'>('list')
  const [batchGenerateVideoModalOpen, setBatchGenerateVideoModalOpen] = useState(false)
  const [batchDeleteModalOpen, setBatchDeleteModalOpen] = useState(false)
  const [toolbarOpsOpen, setToolbarOpsOpen] = useState(false)
  const [isVideoModalOpen, setIsVideoModalOpen] = useState(false)
  const [currentPanelIndex, setCurrentPanelIndex] = useState(-1)
  const [isVideoDragging, setIsVideoDragging] = useState(false)
  const [activeInsertSlot, setActiveInsertSlot] = useState<number | null>(null)

  const rootRef = useRef<HTMLDivElement | null>(null)
  const listRef = useRef<HTMLDivElement | null>(null)
  const bottomAddBarRef = useRef<HTMLDivElement | null>(null)
  const insertLeaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const batchVideoSubmittingRef = useRef(false)
  const suppressEmptyResyncFromScriptRef = useRef(false)
  const isVideoModalOpenRef = useRef(isVideoModalOpen)
  isVideoModalOpenRef.current = isVideoModalOpen

  const panels = useMemo(() => value || [], [value])
  const scriptPanels = useMemo(
    () => (storyboardScriptPanels || []) as StoryboardPanel[],
    [storyboardScriptPanels]
  )

  // 异步恢复/确认弹窗回调中读取最新面板与回调（原 Vue computed 调用时求值）
  const panelsRef = useRef(panels)
  panelsRef.current = panels
  const scriptPanelsRef = useRef(scriptPanels)
  scriptPanelsRef.current = scriptPanels
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  /** 原 listInteractive ref：仅在 onBeforeUnmount 置 false 以拆除拖拽；React 卸载即销毁，恒为 true */
  const listInteractive = true

  function resolvePanelStoryboardId(index: number): number | null {
    const sp = scriptPanelsRef.current[index]
    if (sp) {
      const sid = wb.parseServerStoryboardId(sp.id)
      if (sid != null) return sid
    }
    const storePanels = useCreationStore.getState().formData.storyboardScript
      .panels as StoryboardPanel[]
    const storeSp = storePanels[index]
    return storeSp ? wb.parseServerStoryboardId(storeSp.id) : null
  }

  const panelOps = useStoryboardVideoPanelOps({
    panelsRef,
    scriptPanelsRef,
    onChangeRef,
    resolvePanelStoryboardId,
    suppressEmptyResyncFromScriptRef,
    rootRef,
    listRef,
    bottomAddBarRef
  })

  const playback = useStoryboardVideoPlayback({ panelsRef })

  function openEditVideoModal(index: number) {
    setCurrentPanelIndex(index)
    setIsVideoModalOpen(true)
  }

  const { videoBatchGen, pageDisposedRef, restoreStoryboardVideoBatchIfNeeded, mergeStoryboardVideoPanelUiFromStore } =
    useStoryboardVideoStepRestore({
      panelsRef,
      scriptPanelsRef,
      onChangeRef,
      batchVideoSubmittingRef,
      isVideoModalOpenRef,
      resolvePanelStoryboardId,
      openEditVideoModalAt: openEditVideoModal,
      reopenVideoModalAt: (index) => {
        setCurrentPanelIndex(index)
        setIsVideoModalOpen(true)
      },
      storyboardListSyncReady,
      isHydrated,
      currentProjectId,
      currentEpisodeId,
      routeProjectId: route.query.projectId,
      routeEpisodeId: route.query.episodeId,
      panelsLength: panels.length,
      scriptPanelsLength: scriptPanels.length
    })

  function hasOngoingStoryboardVideoGenWork(): boolean {
    const store = useCreationStore.getState()
    const routeSnap = getRouteLikeSnapshot()
    if (!storyboardListSyncReady) {
      if (hasPersistedStoryboardScriptBatchGenWork(store, routeSnap)) return true
      if (hasPersistedStoryboardVideoBatchGenWork(store, routeSnap)) return true
      return false
    }
    if (store.isGeneratingStoryboard) return true
    if (store.isGeneratingStoryboardVideo) return true
    const promptTaskId = Number(store.storyboardVideoBatchActivePromptTaskId)
    if (Number.isFinite(promptTaskId) && promptTaskId > 0) return true
    const videoTaskId = Number(store.storyboardVideoBatchActiveVideoTaskId)
    return Number.isFinite(videoTaskId) && videoTaskId > 0
  }

  const showStoryboardVideoBootstrapMask =
    (storyboardListLoading || !storyboardListSyncReady) && !hasOngoingStoryboardVideoGenWork()

  function displayPanelTitle(panel: StoryboardVideoPanel, index: number): string {
    return resolveStoryboardListDisplayTitle(panel.title, index, 'video')
  }

  // 规则：1）生成了分镜脚本且列表中至少有一条有分镜图或参考图（场景道具角色设置的场景图）可点击；
  // 2）分镜是「添加分镜」生成的，只要有一条设置了分镜图也可点击。参考图与分镜视频列表同步，由大模型返回不一定每条都有。
  // 3）专业版 / 多参数不出分镜图，有分镜脚本即可批量生成分镜视频，不校验分镜图/参考图。
  const canAutoGenerateVideo = (() => {
    const list = storyboardScriptPanels || []
    if (list.length === 0) return false
    if (skipsStoryboardImageGeneration(creationMode)) {
      return true
    }
    const hasImageOrRef = (p: any) => {
      const hasImage = p.images && Array.isArray(p.images) && p.images.length > 0
      const hasRef =
        (p.referenceImage && (p.referenceImage.url || p.referenceImage.thumbnail)) ||
        (p.referenceImages && Array.isArray(p.referenceImages) && p.referenceImages.length > 0)
      return hasImage || hasRef
    }
    return list.some(hasImageOrRef)
  })()

  /** 批量生成分镜视频不可用时的提示（专业版/多参数不提示「需先有分镜图」） */
  const batchVideoDisabledTooltip = (() => {
    if (canAutoGenerateVideo) {
      return panels.length === 0 ? '暂无分镜视频' : ''
    }
    if (skipsStoryboardImageGeneration(creationMode)) {
      return '暂无分镜视频'
    }
    return '需先有分镜图或参考图（至少一条）'
  })()

  // 异步动作中读取最新派生值（原 Vue computed 调用时求值）
  const canAutoGenerateVideoRef = useRef(canAutoGenerateVideo)
  canAutoGenerateVideoRef.current = canAutoGenerateVideo
  const batchVideoDisabledTooltipRef = useRef(batchVideoDisabledTooltip)
  batchVideoDisabledTooltipRef.current = batchVideoDisabledTooltip

  const batchActions = useStoryboardVideoBatchActions({
    videoBatchGen,
    pageDisposedRef,
    panelsRef,
    scriptPanelsRef,
    onChangeRef,
    batchVideoSubmittingRef,
    canAutoGenerateVideoRef,
    batchVideoDisabledTooltipRef,
    mergeStoryboardVideoPanelUiFromStore
  })
  const batchVideoSubmitting = batchActions.batchVideoSubmitting

  const showScriptSyncGeneratingView =
    (isSyncGeneratingStoryboard && panels.length === 0 && !storyboardGenerationError) ||
    (!storyboardListSyncReady &&
      hasPersistedStoryboardScriptBatchGenWork(useCreationStore.getState(), getRouteLikeSnapshot()))
  const showStoryboardVideoEmptyState =
    !showScriptSyncGeneratingView && !showStoryboardVideoBootstrapMask && panels.length === 0
  const syncGenerationPercent = (() => {
    const total = Number(storyboardGenerationProgress.total || 0)
    const completed = Number(storyboardGenerationProgress.completed || 0)
    if (total <= 0) return 0
    const percent = Math.round((completed / total) * 100)
    return Math.min(100, Math.max(0, percent))
  })()

  // 从分镜脚本同步：当脚本有数据且视频 panels 为空时，按脚本生成视频项（首次进入等）
  const scriptSig = scriptPanels.map((p) => p.id).join('\u0000')
  useEffect(() => {
    if (scriptPanels.length <= 0 || panels.length > 0) return
    if (suppressEmptyResyncFromScriptRef.current) return
    const next: StoryboardVideoPanel[] = scriptPanelsRef.current.map((p, i) =>
      createDefaultVideoPanel(p as StoryboardPanel, i)
    )
    onChangeRef.current(next)
     
  }, [scriptPanels.length, scriptSig, panels.length])

  const videoScenes = useMemo((): EditStoryboardVideoModalScene[] => {
    const list = scriptPanels
    return panels.map((panel, i) => {
      const byIndex = list[i]
      const sid = Number(byIndex?.id)
      const sp =
        Number.isFinite(sid) && sid > 0
          ? list.find((s) => Number(s.id) === sid) || byIndex
          : byIndex
      return {
        name: panel.title,
        videos: Array.isArray(panel.videos) ? panel.videos.map((v) => ({ ...v })) : [],
        scriptContent: sp?.scriptContent ?? '',
        scriptPanelTitle: sp?.title ?? panel.title,
        storyboardId: Number.isFinite(Number(sp?.id)) ? Number(sp?.id) : undefined,
        /** 第四步该分镜的分镜图，供导入弹窗「当前分镜」Tab */
        storyboardImages: Array.isArray(sp?.images) ? sp.images.map((img: any) => ({ ...img })) : []
      }
    })
     
  }, [panels, scriptPanels])

  const videoCompletedCount = panels.filter((p) => getPanelStoryboardVideo(p)).length
  const videoProgress = resolveStoryboardDesignProgressCounts(
    creationMode,
    panels.length,
    videoCompletedCount
  )
  const progressText = (() => {
    if (isGeneratingVideo && videoBatchProgress.total > 0) {
      return `${videoBatchProgress.completed}/${videoBatchProgress.total}`
    }
    return `${videoProgress.completed}/${videoProgress.total}`
  })()

  /** 卡片失败：仅以 store 持久化状态为准，避免 panel 本地脏数据污染；已设置主视频则不再展示失败 */
  function panelVideoGenerateError(
    panel: StoryboardVideoPanel,
    index: number
  ): string | undefined {
    const mainVideo = getPanelStoryboardVideo(panel)
    if (mainVideo && String(mainVideo.url ?? '').trim()) return undefined

    const sid = resolvePanelStoryboardId(index)
    if (sid == null) return undefined
    const key = String(sid)
    const store = useCreationStore.getState()
    const batchTargets = store.storyboardVideoBatchTargetStoryboardIds
    const isBatchActive =
      store.isGeneratingStoryboardVideo ||
      store.storyboardVideoBatchActivePromptTaskId != null ||
      store.storyboardVideoBatchActiveVideoTaskId != null
    if (batchTargets.length > 0 && isBatchActive && !store.isStoryboardVideoBatchTarget(sid)) {
      return undefined
    }
    const storeErr = String(store.storyboardPanelVideoGenErrorByStoryboardId[key] ?? '').trim()
    if (storeErr) return storeErr
    if (store.storyboardPanelVideoGenStatusByStoryboardId[key] === 'failed') {
      return '视频生成失败'
    }
    return undefined
  }

  /** 卡片 loading：与分镜脚本 isPanelImageGenerating 一致，直接读 store */
  function isPanelVideoGenerating(_panel: StoryboardVideoPanel, index: number): boolean {
    const sid = resolvePanelStoryboardId(index)
    if (sid == null) return false

    const store = useCreationStore.getState()
    const scopeKey = store.step3GenVisualScopeKey()
    const modalTask =
      store.step4PlusLiveGenByScope[scopeKey]?.storyboardVideoGenTasksByStoryboardId?.[String(sid)]
    const isBatchActive =
      store.isGeneratingStoryboardVideo ||
      store.storyboardVideoBatchActivePromptTaskId != null ||
      store.storyboardVideoBatchActiveVideoTaskId != null

    // 弹窗单条生视频：外层列表不展示 loading（与分镜图弹窗一致）
    if ((modalTask || isStoryboardVideoModalRestoreFollowing(sid)) && !isBatchActive) {
      return false
    }

    if (
      store.storyboardVideoBatchTargetStoryboardIds.length > 0 &&
      isBatchActive &&
      !store.isStoryboardVideoBatchTarget(sid)
    ) {
      return false
    }

    return store.storyboardPanelVideoGenStatusByStoryboardId[String(sid)] === 'generating'
  }

  const batchVideoGenerateLabel = videoCompletedCount > 0 ? '批量生成分镜视频' : '批量生成分镜视频'

  const videoToolbarOpsItems: StoryboardOpsMenuItem[] = [
    {
      key: 'batch-video',
      label: batchVideoGenerateLabel,
      icon: VideoCameraOutlined,
      disabled: !canAutoGenerateVideo || panels.length === 0 || isGeneratingVideo,
      disabledTooltip: batchVideoDisabledTooltip || undefined
    },
    {
      key: 'batch-delete',
      label: '批量删除分镜视频',
      icon: DeleteOutlined,
      danger: true,
      disabled: panels.length === 0 || panelOps.batchDeleteSubmitting
    }
  ]

  function handleVideoToolbarOpsSelect(key: string) {
    if (key === 'batch-video') {
      openBatchGenerateVideoModal()
      return
    }
    if (key === 'batch-delete') {
      setBatchDeleteModalOpen(true)
    }
  }

  function openBatchGenerateVideoModal() {
    if (!canAutoGenerateVideo || panels.length === 0) {
      message.warning(batchVideoDisabledTooltip || '暂无分镜视频')
      return
    }
    setBatchGenerateVideoModalOpen(true)
  }

  /** 跳转分镜设计并打开「编辑分镜图」 */
  function jumpToScriptWithImageModal(index: number) {
    requestCreateFlowStepModal('storyboard-image', index)
    onGoStep(3)
  }

  /** 跳转音画同步并打开对应分镜配音弹窗（仅已设主视频时展示入口） */
  function jumpToDubbingWithModal(index: number) {
    requestCreateFlowStepModal('storyboard-dubbing', index)
    onGoStep(5)
  }

  /** 消费跨步骤意图：打开编辑分镜视频（从分镜设计进入视频生成） */
  function tryConsumeStepModalIntent() {
    if (pageDisposedRef.current) return
    const pending = peekCreateFlowStepModalIntent()
    if (!pending || pending.kind !== 'storyboard-video') return
    if (pending.panelIndex < 0 || pending.panelIndex >= panelsRef.current.length) return
    const index = consumeCreateFlowStepModalIntent('storyboard-video')
    if (index == null) return
    setViewMode('list')
    setTimeout(() => {
      if (pageDisposedRef.current) return
      openEditVideoModal(index)
    }, 0)
  }

  // 原 watch([panels.length, intent.token], { immediate: true })
  const modalIntentToken = useCreateFlowStepModalIntent()?.token ?? null
  useEffect(() => {
    queueMicrotask(tryConsumeStepModalIntent)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [panels.length, modalIntentToken])

  // 原 watch(isVideoModalOpen, (open, wasOpen))：仅在关窗时处理
  const prevVideoModalOpenRef = useRef(isVideoModalOpen)
  useEffect(() => {
    const wasOpen = prevVideoModalOpenRef.current
    prevVideoModalOpenRef.current = isVideoModalOpen
    if (!wasOpen || isVideoModalOpen) return
    const store = useCreationStore.getState()
    const sessionScope = modalGenSessionScopeFromStore(store)
    const session = readStoryboardVideoModalGenSession(sessionScope)
    const sid =
      session?.storyboardId ??
      (currentPanelIndex >= 0 ? resolvePanelStoryboardId(currentPanelIndex) : null)
    if (sid != null && Number(sid) > 0) {
      const n = Number(sid)
      markStoryboardVideoModalUserDismissed(n, sessionScope)
      activeStoryboardVideoModalOwnedFollowIds.delete(n)
      const snap = findStoryboardVideoGenTaskInScopes(store, n, getRouteLikeSnapshot())
      const tid = Number(snap?.taskId)
      if (Number.isFinite(tid) && tid > 0) suspendTaskSseFollow(tid)
    }
    // 关窗后仅恢复列表自身的批量任务；弹窗 taskId 保留快照，重新打开弹窗时再续跟。
    setTimeout(() => {
      void restoreStoryboardVideoBatchIfNeeded()
    }, 0)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isVideoModalOpen])

  function handleVideoUpdate(sceneIndex: number, data: any) {
    panelOps.handleVideoUpdate(sceneIndex, data)
  }

  function handleJumpToStoryboardScript(panelIndex: number) {
    setIsVideoModalOpen(false)
    requestCreateFlowStepModal('storyboard-image', panelIndex)
    onJumpToStoryboardScript(panelIndex)
    onGoStep(3)
  }

  function onInsertSlotEnter(idx: number) {
    if (insertLeaveTimerRef.current) {
      clearTimeout(insertLeaveTimerRef.current)
      insertLeaveTimerRef.current = null
    }
    setActiveInsertSlot(idx)
  }

  function onInsertSlotLeave() {
    insertLeaveTimerRef.current = setTimeout(() => {
      setActiveInsertSlot(null)
      insertLeaveTimerRef.current = null
    }, 180)
  }

  function clearInsertSlotImmediate() {
    if (insertLeaveTimerRef.current) {
      clearTimeout(insertLeaveTimerRef.current)
      insertLeaveTimerRef.current = null
    }
    setActiveInsertSlot(null)
  }

  async function insertBlankPanelAt(atIndex: number) {
    const inserted = await panelOps.insertBlankPanelAt(atIndex)
    if (inserted) clearInsertSlotImmediate()
  }

  // 卸载时清理插入槽计时器
  useEffect(() => {
    return () => {
      if (insertLeaveTimerRef.current) {
        clearTimeout(insertLeaveTimerRef.current)
        insertLeaveTimerRef.current = null
      }
    }
     
  }, [])

  const sharedViewProps = {
    panels,
    displayPanelTitle,
    isPanelVideoGenerating,
    panelVideoGenerateError,
    editingId: panelOps.editingId,
    editingTitle: panelOps.editingTitle,
    onEditingTitleChange: panelOps.setEditingTitle,
    onStartEditTitle: panelOps.startEditTitle,
    onFinishEditTitle: (panel: StoryboardVideoPanel) =>
      void panelOps.finishEditTitle(panel, panelOps.editingTitle),
    onCancelEditTitle: panelOps.cancelEditTitle,
    onOpenEditVideoModal: openEditVideoModal,
    onCopyPanel: (index: number) => void panelOps.handleCopyPanel(index),
    onRemovePanel: panelOps.removePanel,
    onJumpToScriptWithImageModal: jumpToScriptWithImageModal,
    onJumpToDubbingWithModal: jumpToDubbingWithModal,
    onRegeneratePanel: (index: number) => void batchActions.regeneratePanel(index),
    resolveRegeneratePanelBillingRequest: batchActions.resolveRegeneratePanelBillingRequest,
    onCancelStoryboardVideo: (index: number) => void panelOps.handleCancelStoryboardVideo(index),
    onPreviewStoryboardVideo: panelOps.handlePreviewStoryboardVideo,
    onDownloadStoryboardVideo: panelOps.handleDownloadStoryboardVideo,
    playingPanelIndex: playback.playingPanelIndex,
    panelVideoMediaReady: playback.panelVideoMediaReady,
    setPanelVideoRef: playback.setPanelVideoRef,
    onMarkPanelVideoMediaReady: playback.markPanelVideoMediaReady,
    onPanelVideoEnded: playback.onPanelVideoEnded,
    onPanelVideoPause: playback.onPanelVideoPause,
    onPlayPanelVideo: playback.handlePlayPanelVideo,
    onFullscreenPanelVideo: (index: number) => void playback.handleFullscreenPanelVideo(index)
  }

  return (
    <div
      ref={rootRef}
      className="storyboard-video create-step-storyboard-video storyboard-step"
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
            <span>视频完成进度：{progressText}</span>
            {isGeneratingVideo || isSyncGeneratingStoryboard ? (
              <LoadingOutlined className="storyboard-progress-loading" spin />
            ) : null}
          </div>
        </div>
        <div className="storyboard-toolbar-right">
          <Button
            size="small"
            className="storyboard-action-btn"
            disabled={isGeneratingVideo}
            onClick={() => void panelOps.addPanel()}
            icon={<PlusOutlined />}
          >
            添加分镜视频
          </Button>
          <Tooltip title={batchVideoDisabledTooltip}>
            <span className="storyboard-tooltip-wrap">
              {!isGeneratingVideo ? (
                <StoryboardToolbarOpsDropdown
                  open={toolbarOpsOpen}
                  onOpenChange={setToolbarOpsOpen}
                  items={videoToolbarOpsItems}
                  loading={batchVideoSubmitting || panelOps.batchDeleteSubmitting}
                  disabled={!canAutoGenerateVideo && panels.length === 0}
                  onSelect={handleVideoToolbarOpsSelect}
                />
              ) : (
                <Button
                  size="small"
                  danger
                  className="storyboard-action-btn"
                  onClick={() => void batchActions.stopVideoGeneration()}
                  icon={<StopOutlined />}
                >
                  停止生成
                </Button>
              )}
            </span>
          </Tooltip>
        </div>
      </div>

      <div
        className={`storyboard-step-shell storyboard-video-empty${
          panels.length > 0 ? ' storyboard-step-shell--has-list' : ''
        }${showStoryboardVideoBootstrapMask ? ' storyboard-step-shell--bootstrap-pending' : ''}`}
      >
        {showStoryboardVideoBootstrapMask ? (
          <div
            className="storyboard-list-bootstrap-mask"
            role="status"
            aria-live="polite"
            aria-busy="true"
          >
            <LoadingOutlined spin className="storyboard-list-bootstrap-mask__icon" />
            <p className="storyboard-list-bootstrap-mask__text">正在同步分镜视频列表…</p>
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
        ) : showStoryboardVideoEmptyState ? (
          <div className="storyboard-empty-content">
            <div className="storyboard-empty-inner">
              <div className="storyboard-empty-icon-wrap">
                <img src={emptyFjIcon} alt="" />
              </div>
              <p className="storyboard-empty-title">暂无分镜视频</p>
              <Button
                className="storyboard-empty-add-btn"
                size="small"
                onClick={() => void panelOps.addPanel()}
              >
                <div className="text-gradient">添加分镜视频</div>
              </Button>
            </div>
          </div>
        ) : viewMode === 'card' ? (
          /* 卡片视图 */
          <StoryboardVideoCardView {...sharedViewProps} />
        ) : (
          /* 列表视图 */
          <StoryboardVideoListView
            {...sharedViewProps}
            listInteractive={listInteractive}
            isVideoDragging={isVideoDragging}
            onDragStart={() => setIsVideoDragging(true)}
            onDragEnd={() => setIsVideoDragging(false)}
            onListDragChange={(from, to) => void panelOps.onVideoListDragChange(from, to)}
            activeInsertSlot={activeInsertSlot}
            onInsertSlotEnter={onInsertSlotEnter}
            onInsertSlotLeave={onInsertSlotLeave}
            onClearInsertSlotImmediate={clearInsertSlotImmediate}
            onInsertBlankPanelAt={(atIndex) => void insertBlankPanelAt(atIndex)}
            onAddPanel={() => void panelOps.addPanel()}
            listRef={listRef}
            bottomAddBarRef={bottomAddBarRef}
          />
        )}
      </div>

      {isVideoModalOpen && currentPanelIndex >= 0 ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditStoryboardVideoModalLazy
          key={`storyboard-video-${panels[currentPanelIndex]?.id ?? currentPanelIndex}`}
          open={isVideoModalOpen}
          sceneIndex={currentPanelIndex}
          editorScopeKey={`storyboard-video-${panels[currentPanelIndex]?.id ?? currentPanelIndex}`}
          scenes={videoScenes}
          onOpenChange={setIsVideoModalOpen}
          onUpdate={handleVideoUpdate}
          onJumpToStoryboardScript={handleJumpToStoryboardScript}
          />
        </Suspense>
      ) : null}
      {batchGenerateVideoModalOpen ? (
        <BatchGenerateStoryboardModal
          open={batchGenerateVideoModalOpen}
          onOpenChange={setBatchGenerateVideoModalOpen}
          mode="video"
          title={batchVideoGenerateLabel}
          panels={scriptPanels}
          videoPanels={panels}
          onConfirm={(payload) => void batchActions.handleBatchGenerateVideoConfirm(payload)}
        />
      ) : null}
      {batchDeleteModalOpen ? (
        <BatchGenerateStoryboardModal
          open={batchDeleteModalOpen}
          action="delete"
          mode="video"
          title="批量删除分镜视频"
          panels={scriptPanels}
          videoPanels={panels}
          onOpenChange={setBatchDeleteModalOpen}
          onConfirm={(payload) =>
            panelOps.handleBatchDeleteVideoPanels(payload.selectedStoryboardIds)
          }
        />
      ) : null}
    </div>
  )
}

export default StoryboardVideo

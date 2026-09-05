'use client'

/**
 * 创作流程壳层：侧栏、流程条、工具栏、弹窗与 createFlowShellContext Provider。
 * 业务逻辑及工具栏/流程条/发布等已拆到 hooks 与 ./create-flow-shell/。
 * 切步时 route.path 或作品 scope 变化会先挂起 SSE，再由新步骤恢复跟随；
 * 卸载时挂起 SSE 并把 generating 状态落盘到作品 scope。
 */

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { message } from 'antd'
import { suspendAllTaskSseFollows } from '~/composables/useTaskSseFollow'
import { useRouteLike, useRouteLikeNavigator } from '~/composables/useRouteLike'
import HomeNewSidebar from '~/components/layout/HomeNewSidebar'
import { CREATE_FLOW_STEP_ORDER, isSeriesEpisodeListPath, isSeriesFlowChromePath, isSeriesScriptUploadPath, routePathToCreationStep } from '~/utils/createFlowRoutes'
import { createFlowPageKey } from '~/utils/createFlowNavSerialize'
import { getCreateFlowDisplaySteps } from '~/utils/createFlowStepMeta'
import { shouldSkipFlowProjectScopedApis } from '~/utils/createFlowProjectContext'
import { fetchFlowUserTaskList, invalidateFlowUserTaskListCache } from '~/utils/userTaskListFlowOnce'
import { resolveRouteProjectId } from '~/utils/hydrateCreationStoreFromProjectDetail'
import { createFlowShellContext, type CreateFlowGlobalSettingContext, type CreateFlowShellContext } from '~/utils/createFlowInjection'
import { useCreationStore } from '~/stores/creation'
import { useCreateFlowStoryboardSync } from '~/composables/useCreateFlowStoryboardSync'
import { useScriptChangeExtractGate } from '~/composables/useScriptChangeExtractGate'
import { useCreateFlowExtractAgents } from '~/composables/useCreateFlowExtractAgents'
import { useCreateFlowGlobalSettingModal } from '~/composables/useCreateFlowGlobalSettingModal'
import { useCreateFlowRouteAndSteps } from '~/composables/useCreateFlowRouteAndSteps'
import { useCreateFlowSidebarChrome } from '~/composables/useCreateFlowSidebarChrome'
import { useCreateFlowShellLiveGenBootstrap } from '~/composables/useCreateFlowShellLiveGenBootstrap'
import { useCreateFlowStepNavigationFeedback } from '~/hooks/useCreateFlowStepNavigationFeedback'
import { useCreateFlowStepPreload } from '~/hooks/useCreateFlowStepPreload'
import { useGlobalSettingProjectHydrate } from '~/composables/useGlobalSettingProjectHydrate'
import { useCreateFlowTitleMeasure } from '~/composables/useCreateFlowTitleMeasure'
import { htmlPlainTextLength } from '~/utils/htmlPlain'
import { userEpisodeList, userProjectUpdate } from '~/utils/businessApi'
import { checkSeriesProjectConfigStoryboardGuard, resolveSeriesProjectConfigAccess } from '~/utils/seriesProjectConfigGuard'
import { isDubbingFlowStepGenerating, isStoryboardScriptFlowStepGenerating, isStoryboardVideoFlowStepGenerating as checkStoryboardVideoFlowStepGenerating } from '~/utils/storyboardFlowStepLoading'
import { isStep3FlowStepGenerating } from '~/utils/step3LiveGenRestore'
import type { CreationStep } from '~/types'
import { useRouter } from 'next/navigation'
import { CreateFlowToolbar } from './create-flow-shell/CreateFlowToolbar'
import { CreateFlowStepStrip } from './create-flow-shell/CreateFlowStepStrip'
import { CreateFlowShellOverlays } from './create-flow-shell/CreateFlowShellOverlays'
import { CreateFlowShellSkeleton } from './create-flow-shell/CreateFlowShellSkeleton'
import { CreateFlowStepLoading } from './create-flow-shell/CreateFlowStepLoading'
import { useCreateFlowExport } from './create-flow-shell/useCreateFlowExport'
import { useCreateFlowGlobalTasks } from './create-flow-shell/useCreateFlowGlobalTasks'
import './create-flow-shell/create-flow-shell.css'
import './create-flow-shell/create-flow-shell-steps.css'

const CREATE_FLOW_ENTRY_GUIDE_QUERY_KEY = 'flowGuide' as const
const CREATE_FLOW_ENTRY_GUIDE_VALUE = 'project-entry' as const

export function CreateFlowShell({ children }: { children: ReactNode }) {
  const route = useRouteLike()
  const routeRef = useRef(route)
  useLayoutEffect(() => {
    routeRef.current = route
  }, [route])
  const router = useRouter()
  const navigator = useRouteLikeNavigator()

  const isSeriesScriptUpload = isSeriesScriptUploadPath(route.path)
  const isSeriesEpisodeList = isSeriesEpisodeListPath(route.path)
  const isSeriesFlowChrome = isSeriesFlowChromePath(route.path)

  const currentProjectType = useCreationStore((s) => s.currentProjectType)
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const seriesEpisodeListTotal = useCreationStore((s) => s.seriesEpisodeListTotal)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const isExtractingAssets = useCreationStore((s) => s.isExtractingAssets)
  const showExtractAgentModal = useCreationStore((s) => s.showExtractAgentModal)
  const extractAgents = useCreationStore((s) => s.extractAgents)
  const extractModelCodes = useCreationStore((s) => s.extractModelCodes)
  const extractModalActionMode = useCreationStore((s) => s.extractModalActionMode)

  const seriesEpisodeCountLabel = seriesEpisodeListTotal != null && seriesEpisodeListTotal >= 0 ? String(seriesEpisodeListTotal) : '—'

  const [pageReady, setPageReady] = useState(false)
  const [isDubbingGenerating, setIsDubbingGenerating] = useState(false)
  const dubbingFlowGenerating = useCreationStore((s) => isDubbingFlowStepGenerating(s, route))
  const isDubbingStepGenerating = isDubbingGenerating || dubbingFlowGenerating

  const isStoryboardVideoFlowStepGenerating = useCreationStore((s) =>
    checkStoryboardVideoFlowStepGenerating(s, route)
  )
  const isStoryboardScriptStepGenerating = useCreationStore((s) =>
    isStoryboardScriptFlowStepGenerating(s, route)
  )
  const isStep3VisualStepGenerating = useCreationStore((s) => isStep3FlowStepGenerating(s, route))

  const storyboardSync = useCreateFlowStoryboardSync()
  const extract = useCreateFlowExtractAgents()
  const scriptChangeGate = useScriptChangeExtractGate()
  const stepNavigation = useCreateFlowStepNavigationFeedback(route.path)
  const routeSteps = useCreateFlowRouteAndSteps(
    extract.openExtractAgentModalIfNeeded,
    stepNavigation.beginStepNavigation
  )
  const globalSetting = useCreateFlowGlobalSettingModal()
  const {
    showRechargeModal,
    setShowRechargeModal,
    showUserMenuCard,
    userMenuDropdownRef,
    userMenuCardStyle,
    goLogin,
    goHomeFromCreate,
    openWorksPanel,
    openAssetsPanel,
    openInvite,
    openTutorial,
    toggleUserMenu,
    openFaq,
    openBilling,
    openRechargeFromMenu,
    handleLogout,
    handleDocumentClick,
    updateUserMenuPosition,
    handleRechargePaid,
    handleOpenRechargeByEvent,
    setUserMenuTriggerElement
  } = useCreateFlowSidebarChrome()
  const titleMeasure = useCreateFlowTitleMeasure(pageReady)

  const {
    steps,
    flowStepIndex,
    currentStep,
    previewContentStepClass,
    stepStatus,
    unlockedStepIndex,
    stepApiLoading,
    handleStepClick,
    isStepPillDisabled,
    handleNextStep: runNextStep,
    nextStepSubmitting,
    toolbarPrimaryLabel,
    toolbarPrimaryDisabled,
    toolbarPrimaryTooltip,
    goBack,
    saveDraft,
    saveDraftSubmitting,
    syncProjectContextFromRoute,
    fetchCreationStepStatus,
    isConnectorTrailDone,
    goToCreateStep,
    storyboardListLoading,
    storyboardListSyncReady
  } = routeSteps

  /** 剧集流程条隐藏「项目配置」，仅展示后 6 步 */
  const displaySteps = useMemo(
    () => getCreateFlowDisplaySteps(currentProjectType),
    [currentProjectType]
  )
  const displayStepKeys = useMemo(() => displaySteps.map((step) => step.key), [displaySteps])
  const preloadCreateStepRoute = useCreateFlowStepPreload({
    enabled: pageReady && !isSeriesFlowChrome,
    currentStep,
    stepKeys: displayStepKeys,
    query: route.query
  })
  const displayStepSkeletonCount = displaySteps.length

  const stepRealIndex = useCallback((stepKey: CreationStep): number => {
    const i = CREATE_FLOW_STEP_ORDER.indexOf(stepKey)
    return i >= 0 ? i : 0
  }, [])
  const optimisticFlowStepIndex = stepNavigation.pendingStep
    ? stepRealIndex(stepNavigation.pendingStep)
    : flowStepIndex
  const pendingStepTitle = stepNavigation.pendingStep
    ? steps.find((step) => step.key === stepNavigation.pendingStep)?.title
    : null

  const [nextStepDelayLoading, setNextStepDelayLoading] = useState(false)
  const [showProjectGenConfigModal, setShowProjectGenConfigModal] = useState(false)

  const isPreviewStep = flowStepIndex >= steps.length - 1
  const highlightEntryFlowTabs =
    String(route.query[CREATE_FLOW_ENTRY_GUIDE_QUERY_KEY] ?? '') === CREATE_FLOW_ENTRY_GUIDE_VALUE
  const toolbarPrimaryLoading = nextStepDelayLoading || nextStepSubmitting

  const activeProjectId = currentProjectId && currentProjectId > 0
    ? currentProjectId
    : resolveRouteProjectId(route.query as Record<string, unknown>)
  const activeProjectIdRef = useRef(activeProjectId)
  useLayoutEffect(() => {
    activeProjectIdRef.current = activeProjectId
  }, [activeProjectId])

  const flowExport = useCreateFlowExport()

  const openProjectGenConfig = useCallback(() => {
    if (!activeProjectIdRef.current) {
      message.warning('请先选择作品后再配置生成参数')
      return
    }
    setShowProjectGenConfigModal(true)
  }, [setShowProjectGenConfigModal])

  /** 进入创作流程时 bootstrap 权威 task/list，切换步骤与各弹窗恢复均复用缓存 */
  const prevActivePidRef = useRef<number | null | undefined>(undefined)
  useEffect(() => {
    const prevPid = prevActivePidRef.current
    prevActivePidRef.current = activeProjectId
    if (shouldSkipFlowProjectScopedApis(routeRef.current)) return
    if (prevPid && activeProjectId !== prevPid) invalidateFlowUserTaskListCache(prevPid)
    if (!activeProjectId) {
      if (prevPid) invalidateFlowUserTaskListCache(prevPid)
      return
    }
    void fetchFlowUserTaskList(activeProjectId, { intent: 'bootstrap' })
  }, [activeProjectId])

  // ---- 剧集「项目配置」入口 ----
  const [seriesProjectConfigChecking, setSeriesProjectConfigChecking] = useState(false)
  const seriesProjectConfigCheckingRef = useRef(false)
  const [seriesProjectConfigContentLocked, setSeriesProjectConfigContentLocked] = useState(false)

  const { hydrateFromProjectApi } = useGlobalSettingProjectHydrate()

  const onSeriesProjectConfigClick = useCallback(async () => {
    if (seriesProjectConfigCheckingRef.current) return
    const pid = activeProjectIdRef.current
    if (!pid) {
      message.warning('缺少项目信息')
      return
    }
    seriesProjectConfigCheckingRef.current = true
    setSeriesProjectConfigChecking(true)
    try {
      const rows = await userEpisodeList({ projectId: pid })
      if (activeProjectIdRef.current !== pid) return
      const episodeIds = rows
        .map((ep) => ep.id)
        .filter((id): id is number => typeof id === 'number' && id > 0)
      const guard = await checkSeriesProjectConfigStoryboardGuard(pid, episodeIds)
      if (activeProjectIdRef.current !== pid) return
      const access = resolveSeriesProjectConfigAccess(guard)
      if (access.mode === 'blocked') {
        message.error(access.message || '无法确认分镜状态，暂不可修改项目配置')
        return
      }
      const hydrated = await hydrateFromProjectApi(routeRef.current, { force: true })
      if (!hydrated || activeProjectIdRef.current !== pid) return
      setSeriesProjectConfigContentLocked(access.mode === 'content-locked')
      globalSetting.openGlobalSettingModal()
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '打开项目配置失败')
    } finally {
      seriesProjectConfigCheckingRef.current = false
      setSeriesProjectConfigChecking(false)
    }
  }, [globalSetting, hydrateFromProjectApi])

  const globalTasks = useCreateFlowGlobalTasks({ goToCreateStep })

  const handleNextStepWithDelay = useCallback(async () => {
    if (toolbarPrimaryDisabled || toolbarPrimaryLoading) return
    setNextStepDelayLoading(true)
    try {
      await new Promise((resolve) => window.setTimeout(resolve, 500))
      await runNextStep()
    } finally {
      setNextStepDelayLoading(false)
    }
  }, [runNextStep, toolbarPrimaryDisabled, toolbarPrimaryLoading])

  const onFlowStepPillClick = useCallback(
    (index: number) => {
      void handleStepClick(index)
    },
    [handleStepClick]
  )

  // ---- 项目配置弹窗（CreateFirstStepModal）----
  const {
    showGlobalSettingModal,
    setShowGlobalSettingModal,
    globalSettingConfirmLoading,
    creationTitleDraft,
    setCreationTitleDraft,
    globalSettingProjectTypeDraft,
    setGlobalSettingProjectTypeDraft,
    creationGlobalSettingDraft,
    patchGlobalSettingDraftStyle,
    updateGlobalSettingDraftField,
    handleGlobalSettingConfirm,
    syncGlobalSettingDraftFromStore
  } = globalSetting

  /** 流程内编辑已有作品时锁定作品类型（电影 / 剧集均不可改） */
  const globalSettingProjectTypeLocked =
    currentProjectType === 'movie' || currentProjectType === 'series'

  // 原 watch(showGlobalSettingModal)：关闭后解除内容锁
  const prevShowGlobalSettingRef = useRef(showGlobalSettingModal)
  useEffect(() => {
    const prev = prevShowGlobalSettingRef.current
    prevShowGlobalSettingRef.current = showGlobalSettingModal
    if (prev && !showGlobalSettingModal) setSeriesProjectConfigContentLocked(false)
  }, [showGlobalSettingModal])

  // 原 watch(activeProjectId)：切作品时关闭项目配置弹窗
  const prevPidForModalRef = useRef<number | null | undefined>(undefined)
  useEffect(() => {
    const previousPid = prevPidForModalRef.current
    prevPidForModalRef.current = activeProjectId
    if (previousPid !== undefined && previousPid != null && activeProjectId !== previousPid) {
      setShowGlobalSettingModal(false)
      setSeriesProjectConfigContentLocked(false)
    }
  }, [activeProjectId, setShowGlobalSettingModal])

  const globalSettingContext: CreateFlowGlobalSettingContext = {
    confirmLoading: globalSettingConfirmLoading,
    titleDraft: creationTitleDraft,
    projectTypeDraft: globalSettingProjectTypeDraft,
    draft: creationGlobalSettingDraft,
    projectTypeLocked: globalSettingProjectTypeLocked,
    showModal: showGlobalSettingModal,
    syncFromStore: syncGlobalSettingDraftFromStore,
    openModal: globalSetting.openGlobalSettingModal,
    setTitleDraft: setCreationTitleDraft,
    setProjectTypeDraft: setGlobalSettingProjectTypeDraft,
    updateField: updateGlobalSettingDraftField,
    patchStyle: patchGlobalSettingDraftStyle,
    save: () => handleGlobalSettingConfirm({ navigateAfterSave: false })
  }

  useCreateFlowShellLiveGenBootstrap({
    route,
    syncProjectContextFromRoute,
    enabled: true
  })

  const { titleMeasureRef, titleMeasureText, titleInputWrapStyle, syncTitleInputWidth } =
    titleMeasure

  // ---- 作品标题保存 ----
  const workTitleSaveBaselineRef = useRef('')

  const syncWorkTitleSaveBaseline = useCallback(() => {
    workTitleSaveBaselineRef.current =
      (useCreationStore.getState().workTitle || '').trim() || '未命名作品'
  }, [])

  const onSeriesWorkTitleBlur = useCallback(async () => {
    const store = useCreationStore.getState()
    const trimmed = (store.workTitle || '').trim() || '未命名作品'
    store.setWorkTitle(trimmed)
    if (trimmed === workTitleSaveBaselineRef.current) {
      setTimeout(() => syncTitleInputWidth(), 0)
      return
    }
    const r = routeRef.current
    const routePid = Number(r.query.projectId ?? r.query.id ?? r.query.workId)
    const pid =
      store.currentProjectId ?? (Number.isFinite(routePid) && routePid > 0 ? routePid : null)
    if (!pid) {
      workTitleSaveBaselineRef.current = trimmed
      store.updateFormData({
        globalSetting: { ...store.formData.globalSetting, title: trimmed }
      })
      setTimeout(() => syncTitleInputWidth(), 0)
      return
    }
    try {
      await userProjectUpdate({ id: pid, projectName: trimmed })
      workTitleSaveBaselineRef.current = trimmed
      const storeNow = useCreationStore.getState()
      storeNow.updateFormData({
        globalSetting: { ...storeNow.formData.globalSetting, title: trimmed }
      })
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '保存标题失败')
      useCreationStore.getState().setWorkTitle(workTitleSaveBaselineRef.current)
    } finally {
      setTimeout(() => syncTitleInputWidth(), 0)
    }
  }, [syncTitleInputWidth])

  // ---- provide(createFlowShellKey) → Context Provider ----
  const shellContextValue: CreateFlowShellContext = {
    goToStep: (stepIndex: number) => {
      void goToCreateStep(stepIndex)
    },
    stopExtractAssets: extract.stopExtractAssets,
    openExtractModalFromScp: extract.handleOpenExtractModalFromScp,
    openContinueExtractModal: () => {
      extract.setExtractModalScope('all')
      scriptChangeGate.openContinueExtractModal()
    },
    dismissScriptChangeLightBanner: scriptChangeGate.dismissLightBanner,
    jumpToStoryboardScriptFromVideo: storyboardSync.handleJumpToStoryboardScriptFromVideo,
    clearStoryboardScriptJumpTooltip: storyboardSync.clearStoryboardScriptJumpTooltip,
    storyboardScriptTooltipTargetIndex: storyboardSync.storyboardScriptTooltipTargetIndex,
    storyboardScriptTooltipKey: storyboardSync.storyboardScriptTooltipKey,
    syncVideoAndDubbingFromScriptPanels: storyboardSync.syncVideoAndDubbingFromScriptPanels,
    storyboardListLoading,
    storyboardListSyncReady,
    setDubbingGenerating: (v: boolean) => {
      setIsDubbingGenerating(v)
    },
    globalSetting: globalSettingContext,
    openProjectGenConfig,
    registerPreviewExportBridge: flowExport.registerPreviewExportBridge,
    notifyPreviewExportSuccess: flowExport.handlePreviewExportSuccess
  }

  // ---- onMounted ----
  useEffect(() => {
    const r = routeRef.current
    const panel = String(r.query.panel ?? '').toLowerCase()
    if (panel === 'works') {
      router.replace('/works')
      return
    }
    if (panel === 'assets') {
      router.replace('/assets')
      return
    }

    document.addEventListener('click', handleDocumentClick)
    window.addEventListener('resize', updateUserMenuPosition)
    window.addEventListener('scroll', updateUserMenuPosition, true)
    window.addEventListener(
      'open-recharge-modal',
      handleOpenRechargeByEvent as EventListener
    )
    const t = setTimeout(() => {
      const finishPageReady = () => {
        const store = useCreationStore.getState()
        if (store.currentStepIndex < 0 || store.currentStepIndex >= steps.length) {
          store.setCurrentStepIndex(0)
        }
        setPageReady(true)
      }

      if (!shouldSkipFlowProjectScopedApis(routeRef.current)) {
        syncProjectContextFromRoute()
        // 刷新后必须以 project/detail 回填创作模式等项目配置。
        // 不能因 persist 已有同 projectId 就跳过：否则专业版 UI 限制会在批量出片后刷新失效。
        // 等 hydrate 完成再放开 pageReady，避免专业版分镜列表先闪成「需生图」的普通版布局。
        void hydrateFromProjectApi(routeRef.current)
          .then(() => {
            syncGlobalSettingDraftFromStore()
          })
          .finally(() => {
            finishPageReady()
          })
        void fetchCreationStepStatus()
      } else {
        finishPageReady()
      }
    }, 0)
    return () => {
      clearTimeout(t)
      document.removeEventListener('click', handleDocumentClick)
      window.removeEventListener('resize', updateUserMenuPosition)
      window.removeEventListener('scroll', updateUserMenuPosition, true)
      window.removeEventListener(
        'open-recharge-modal',
        handleOpenRechargeByEvent as EventListener
      )
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 原 watch(route.query project ids)：切作品/集时同步上下文并按需 hydrate
  const routeQueryProjectId = route.query.projectId
  const routeQueryId = route.query.id
  const routeQueryWorkId = route.query.workId
  const routeQueryEpisodeId = route.query.episodeId
  const prevProjectQueryRef = useRef<string | undefined>(undefined)
  useEffect(() => {
    const sig = `${String(routeQueryProjectId ?? '')}|${String(routeQueryId ?? '')}|${String(routeQueryWorkId ?? '')}|${String(routeQueryEpisodeId ?? '')}`
    const prev = prevProjectQueryRef.current
    prevProjectQueryRef.current = sig
    if (prev === undefined || prev === sig) return
    if (shouldSkipFlowProjectScopedApis(routeRef.current)) return
    syncProjectContextFromRoute()
    const routePid = resolveRouteProjectId(routeRef.current.query as Record<string, unknown>)
    if (
      routePid != null &&
      routePid > 0 &&
      useCreationStore.getState().currentProjectId === routePid
    ) {
      return
    }
    void hydrateFromProjectApi(routeRef.current).then((hydrated) => {
      if (hydrated) syncGlobalSettingDraftFromStore()
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeQueryProjectId, routeQueryId, routeQueryWorkId, routeQueryEpisodeId])

  /**
   * 第三步 bootstrap + 剧本就绪后再尝试自动弹窗。
   * 须等 step/status（含 stepInitAdvance）同步结束：生成剧集可能先短暂落到素材准备再回退剧本，
   * 若在回退前弹窗会造成「闪一下又关」。
   */
  const step3AssetListSyncReady = useCreationStore((s) => s.step3AssetListSyncReady)
  const hasScript = useCreationStore(
    (s) => htmlPlainTextLength(s.formData.storyScript.content || '') > 0
  )
  const pendingOpenContinueExtractModal = useCreationStore(
    (s) => s.pendingOpenContinueExtractModal
  )
  const stepInitAdvanceQuery = String(route.query.stepInitAdvance ?? '')
  useEffect(() => {
    if (stepApiLoading || !step3AssetListSyncReady || !hasScript) return
    if (stepInitAdvanceQuery === '1' || stepInitAdvanceQuery === 'true') return
    if (routePathToCreationStep(route.path) !== 'scene-character') return
    const t = setTimeout(() => {
      if (
        pendingOpenContinueExtractModal ||
        useCreationStore.getState().pendingOpenContinueExtractModal
      ) {
        extract.setExtractModalScope('all')
        scriptChangeGate.consumePendingOpenExtractModal()
        return
      }
      extract.openExtractAgentModalIfNeeded('current-route')
      void scriptChangeGate.refreshLightBannerOnPreparePage({ skipIfPendingOpen: true })
    }, 0)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    step3AssetListSyncReady,
    route.path,
    hasScript,
    stepApiLoading,
    stepInitAdvanceQuery,
    pendingOpenContinueExtractModal
  ])

  // 原 watch([pageReady, query ids], immediate)：就绪后同步标题保存基线
  useEffect(() => {
    if (pageReady) setTimeout(() => syncWorkTitleSaveBaseline(), 0)
  }, [pageReady, routeQueryProjectId, routeQueryId, routeQueryWorkId, syncWorkTitleSaveBaseline])

  /**
   * 原 watch(route.path, flush:'sync')：切步先挂起全部任务 SSE，再由新步骤页恢复。
   * useLayoutEffect 保证先于新步骤页的 useEffect（恢复跟随）执行。
   */
  const prevPathRef = useRef<string | undefined>(undefined)
  useLayoutEffect(() => {
    const previousPath = prevPathRef.current
    prevPathRef.current = route.path
    if (previousPath !== undefined && route.path !== previousPath) {
      // 第三步组件会在资产列表重新同步完成后置回 true；路由切换期间禁止复用上次的 ready 状态。
      useCreationStore.getState().setStep3AssetListSyncReady(false)
      suspendAllTaskSseFollows()
    }
    setTimeout(() => syncTitleInputWidth(), 0)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [route.path])

  /**
   * 剧集隔离（原 watch(step3GenVisualScopeKey, flush:'sync')）：
   * 同一路由下切换作品/集数（scope 变化）也必须挂起全部任务 SSE。
   * 仅靠 route.path 监听覆盖不了「剧集列表 → 第 2 集」这类只改 episodeId query 的切换，
   * 旧集 SSE 回调会把进度/终态写进新集的扁平 store（跨集污染）。
   * 挂起只断浏览器连接，持久化任务快照保留，切回原集由 scope bootstrap 恢复。
   */
  const step3ScopeKey = useCreationStore((s) => s.step3GenVisualScopeKey())
  const prevScopeKeyRef = useRef<string | undefined>(undefined)
  useLayoutEffect(() => {
    const prevScopeKey = prevScopeKeyRef.current
    prevScopeKeyRef.current = step3ScopeKey
    if (prevScopeKey !== undefined && prevScopeKey && step3ScopeKey !== prevScopeKey) {
      suspendAllTaskSseFollows()
    }
  }, [step3ScopeKey])

  // 原 onBeforeUnmount：离开创作壳层挂起 SSE，并把当前扁平 generating 落盘到作品 scope
  useEffect(() => {
    return () => {
      suspendAllTaskSseFollows()
      try {
        const store = useCreationStore.getState()
        store.syncStep3GenVisualToCurrentScope()
        store.syncStep4PlusLiveGenToCurrentScope()
      } catch {
        /* ignore */
      }
    }
  }, [])

  void currentEpisodeId
  void navigator

  /** 步骤 / 作品 / 剧集变化时重挂内容区并播进场动画；无关 query 不换 key */
  const stepPageKey = createFlowPageKey({
    path: route.path,
    fullPath: route.path,
    query: route.query
  })

  // ---- 视图 ----
  const skeletonView = (
    <CreateFlowShellSkeleton
      seriesChrome={isSeriesFlowChrome}
      seriesScriptUpload={isSeriesScriptUpload}
      seriesEpisodeList={isSeriesEpisodeList}
      stepCount={displayStepSkeletonCount}
    />
  )

  const mainView = (
    <div className="main-layout">
      <HomeNewSidebar
        onBrand={goHomeFromCreate}
        onGallery={goHomeFromCreate}
        onWorks={openWorksPanel}
        onAssets={openAssetsPanel}
        onTutorial={() => void openTutorial()}
        onInvite={openInvite}
        onLogin={goLogin}
        onToggleUserMenu={toggleUserMenu}
        onUserMenuTriggerChange={setUserMenuTriggerElement}
      />

      <div className="create-main">
        <div className="create-main__flow-stack">
          <CreateFlowToolbar
            isSeriesScriptUpload={isSeriesScriptUpload}
            isSeriesEpisodeList={isSeriesEpisodeList}
            titleMeasureRef={titleMeasureRef}
            titleMeasureText={titleMeasureText}
            titleInputWrapStyle={titleInputWrapStyle}
            syncTitleInputWidth={syncTitleInputWidth}
            onSeriesWorkTitleBlur={() => void onSeriesWorkTitleBlur()}
            goBack={goBack}
            seriesEpisodeCountLabel={seriesEpisodeCountLabel}
            seriesProjectConfigChecking={seriesProjectConfigChecking}
            onSeriesProjectConfigClick={() => void onSeriesProjectConfigClick()}
            activeProjectId={activeProjectId}
            onGlobalTaskStop={(task) => void globalTasks.handleGlobalTaskStop(task)}
            onGlobalTaskRestart={(task) => void globalTasks.handleGlobalTaskRestart(task)}
            resolveGlobalTaskRestartBillingRequest={
              globalTasks.resolveGlobalTaskRestartBillingRequest
            }
            onGlobalTaskResume={(task) => void globalTasks.handleGlobalTaskResume(task)}
            resolveGlobalTaskResumeBillingRequest={
              globalTasks.resolveGlobalTaskResumeBillingRequest
            }
            openProjectGenConfig={openProjectGenConfig}
            flowStepIndex={flowStepIndex}
            globalSettingConfirmLoading={globalSettingConfirmLoading}
            onGlobalSettingSave={() => void globalSettingContext.save()}
            saveDraftSubmitting={saveDraftSubmitting}
            toolbarPrimaryLoading={toolbarPrimaryLoading}
            saveDraft={() => void saveDraft()}
            isPreviewStep={isPreviewStep}
            exportMenuOpen={flowExport.exportMenuOpen}
            onExportMenuOpenChange={flowExport.onExportMenuOpenChange}
            previewExportBusy={flowExport.previewExportBusy}
            onExportFullVideo={() => void flowExport.onExportFullVideo()}
            onExportSegments={() => void flowExport.onExportSegments()}
            toolbarPrimaryDisabled={toolbarPrimaryDisabled}
            toolbarPrimaryTooltip={toolbarPrimaryTooltip}
            nextStepDelayLoading={nextStepDelayLoading}
            toolbarPrimaryLabel={toolbarPrimaryLabel}
            onNextStepWithDelay={() => void handleNextStepWithDelay()}
          />
          <div className="preview_bg_box">
            {!isSeriesFlowChrome ? (
              <CreateFlowStepStrip
                displaySteps={displaySteps}
                flowStepIndex={optimisticFlowStepIndex}
                stepStatus={stepStatus}
                unlockedStepIndex={Math.max(unlockedStepIndex, flowStepIndex)}
                stepRealIndex={stepRealIndex}
                isConnectorTrailDone={isConnectorTrailDone}
                isPillDisabled={(index) =>
                  stepNavigation.pendingStep != null || toolbarPrimaryLoading || isStepPillDisabled(index)
                }
                highlightAll={highlightEntryFlowTabs}
                sceneCharacterGenerating={isExtractingAssets || isStep3VisualStepGenerating}
                storyboardScriptGenerating={isStoryboardScriptStepGenerating}
                storyboardVideoGenerating={isStoryboardVideoFlowStepGenerating}
                dubbingGenerating={isDubbingStepGenerating}
                onPillClick={onFlowStepPillClick}
                onPillIntent={preloadCreateStepRoute}
              />
            ) : null}

            <div className="preview-panel">
              <div
                key={stepPageKey}
                className={`preview-content ${previewContentStepClass} create-step-route`}
                aria-busy={stepNavigation.pendingStep != null}
              >
                {stepNavigation.pendingStep ? (
                  <CreateFlowStepLoading label={`正在打开${pendingStepTitle ?? '流程页面'}…`} />
                ) : null}
                {children}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )

  return (
    <createFlowShellContext.Provider value={shellContextValue}>
      <div className={`create-page${isSeriesFlowChrome ? ' create-page--series-upload' : ''}`}>
        {!pageReady ? skeletonView : mainView}

        <CreateFlowShellOverlays
          extractModal={{
            open: showExtractAgentModal,
            agents: extractAgents,
            modelCodes: extractModelCodes,
            scope: extract.extractModalScope,
            actionMode: extractModalActionMode,
            onOpenChange: (value) =>
              useCreationStore.setState({ showExtractAgentModal: value }),
            onAgentsChange: extract.updateExtractAgents,
            onModelCodesChange: extract.updateExtractModelCodes,
            onStart: extract.startExtractAssets
          }}
          rechargeModal={{
            open: showRechargeModal,
            onOpenChange: setShowRechargeModal,
            onPaid: handleRechargePaid
          }}
          globalSettingModal={{
            open: showGlobalSettingModal,
            flowEditMode: true,
            projectTypeLocked: globalSettingProjectTypeLocked,
            contentConfigLocked: seriesProjectConfigContentLocked,
            confirmLoading: globalSettingConfirmLoading,
            title: creationTitleDraft,
            projectType: globalSettingProjectTypeDraft,
            aspectRatio: creationGlobalSettingDraft.aspectRatio,
            scriptType: creationGlobalSettingDraft.scriptType,
            modelStrategy: creationGlobalSettingDraft.modelStrategy,
            creationMode: creationGlobalSettingDraft.creationMode,
            modelValue: creationGlobalSettingDraft,
            onOpenChange: setShowGlobalSettingModal,
            onTitleChange: setCreationTitleDraft,
            onProjectTypeChange: setGlobalSettingProjectTypeDraft,
            onAspectRatioChange: (value) => updateGlobalSettingDraftField('aspectRatio', value),
            onScriptTypeChange: (value) => updateGlobalSettingDraftField('scriptType', value),
            onModelStrategyChange: (value) => updateGlobalSettingDraftField('modelStrategy', value),
            onCreationModeChange: (value) => updateGlobalSettingDraftField('creationMode', value),
            onModelValueChange: patchGlobalSettingDraftStyle,
            onConfirm: () =>
              void handleGlobalSettingConfirm({
                navigateAfterSave: false,
                contentConfigLocked: seriesProjectConfigContentLocked
              })
          }}
          projectConfigModal={{
            open: showProjectGenConfigModal,
            projectId: activeProjectId,
            episodeId: currentEpisodeId,
            onOpenChange: setShowProjectGenConfigModal
          }}
          userMenu={{
            ref: userMenuDropdownRef,
            open: showUserMenuCard,
            floatingStyle: userMenuCardStyle,
            onFaq: openFaq,
            onBilling: openBilling,
            onRecharge: openRechargeFromMenu,
            onLogout: handleLogout
          }}
        />
      </div>
    </createFlowShellContext.Provider>
  )
}

export default CreateFlowShell

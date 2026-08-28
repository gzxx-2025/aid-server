'use client'

/**
 * 路由同步、剧本拉取、服务端步骤、流程条与工具栏「下一步」
 * （原 composables/useCreateFlowRouteAndSteps.ts；执行侧拆分见 ./createFlowRouteSteps/）
 */

import { useCallback, useEffect, useReducer, useRef } from 'react'
import { useRouter } from 'next/navigation'
import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import { useRouteLike, useRouteLikeNavigator } from '~/composables/useRouteLike'
import { useScriptChangeExtractGate } from '~/composables/useScriptChangeExtractGate'
import {
  CREATE_FLOW_STEP_ORDER,
  CREATE_SERIES_EPISODE_LIST_PATH,
  creationStepToRoutePath,
  routePathToCreationStep,
  isSeriesScriptUploadPath,
  isSeriesEpisodeListPath,
} from '~/utils/createFlowRoutes'
import { CREATION_FLOW_STEPS } from '~/utils/createFlowStepMeta'
import type { CreationStep, WorkData } from '~/types'
import type { RouteLikeLocation } from '~/types/routeLike'
import type { ExtractModalAutoOpenContext } from '~/hooks/useCreateFlowExtractAgents'
import {
  getFlowStepIndex,
  parseRouteEpisodeId as ctxParseRouteEpisodeId,
  setBox,
  type Box,
  type FlowStepStatusValue,
  type RouteLikeQuery,
  type RouteStepsCtx
} from './createFlowRouteSteps/types'
import {
  isStoryScriptContentFilled,
  loadStoryScriptFromApi,
  loadStoryboardListFromApi,
  saveStoryScriptToServer,
} from './createFlowRouteSteps/stepData'
import {
  ensureRouteWithinUnlockedSteps,
  fetchCreationStepStatus as runFetchCreationStepStatus,
} from './createFlowRouteSteps/stepStatus'
import { runFlowSubmit } from './createFlowRouteSteps/stepSubmit'
import { useCreateFlowRouteStepEffects } from './createFlowRouteSteps/useCreateFlowRouteStepEffects'
import { runCreateFlowGoBack, runCreateFlowNextStep } from './createFlowRouteSteps/navigationActions'
import { getCreateFlowStepPillDisabled, runCreateFlowStepClick } from './createFlowRouteSteps/stepClick'
import {
  applyStoryboardFlowStepStatusGate,
  isStoryboardFlowSuccessorLocked,
  isStoryboardFlowSuccessorStep,
  isStoryboardScriptAdvanceBlocked,
  STORYBOARD_SCRIPT_ADVANCE_BLOCKED_TOOLTIP
} from '~/utils/storyboardFlowNavigationGate'

function box<T>(value: T): Box<T> {
  return { value }
}

function buildHref(path: string, query?: RouteLikeQuery): string {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(query ?? {})) {
    if (v === null || v === undefined) continue
    if (Array.isArray(v)) {
      for (const item of v) {
        if (item !== null && item !== undefined) qs.append(k, String(item))
      }
    } else {
      qs.set(k, String(v))
    }
  }
  const s = qs.toString()
  return s ? `${path}?${s}` : path
}

/** 步骤完成判定（原 isStepCompleted；formData 由调用方传入以保证渲染期响应） */
function computeIsStepCompleted(
  steps: typeof CREATION_FLOW_STEPS,
  index: number,
  data: WorkData
): boolean {
  const step = steps[index]!
  switch (step.key) {
    case 'global-setting':
      // 与接口文档一致：画面比例、剧本类型、视频风格（selectedStyle）
      return !!(
        data.globalSetting.aspectRatio &&
        data.globalSetting.scriptType &&
        data.globalSetting.selectedStyle != null
      )
    case 'story-script':
      return isStoryScriptContentFilled(data.storyScript.content)
    case 'scene-character':
      return data.sceneCharacter.characters.length > 0 && data.sceneCharacter.scenes.length > 0
    case 'storyboard-script':
    case 'storyboard-video':
    case 'dubbing':
      // 与接口文档 v1.8.0 一致：步骤 4/5/6 仅校验总分镜数 > 0
      return data.storyboardScript.panels.length > 0
    case 'preview':
      return true
    default:
      return false
  }
}

/**
 * 是否允许提交审核。
 * - 剧集无「项目配置」页，不因该步本地表单未填而拦截
 * - 有服务端步骤态时以其为准（成品预览页 formData 可能未完整回填）
 */
function computeCanSubmit(
  steps: typeof CREATION_FLOW_STEPS,
  serverStepStatus: FlowStepStatusValue[] | null,
  data: WorkData,
  projectType: string | null
): boolean {
  const isSeriesSkip = (key: string | undefined) =>
    projectType === 'series' && key === 'global-setting'

  if (serverStepStatus) {
    return serverStepStatus.every((status, index) => {
      const key = steps[index]?.key
      if (isSeriesSkip(key)) return true
      // 成品预览可为 current/active，其余步骤须 completed
      if (key === 'preview') return status === 'completed' || status === 'active'
      return status === 'completed'
    })
  }

  return steps.every((step, index) => {
    if (isSeriesSkip(step.key)) return true
    return computeIsStepCompleted(steps, index, data)
  })
}

export function useCreateFlowRouteAndSteps(
  openExtractAgentModalIfNeeded: (context?: ExtractModalAutoOpenContext) => void,
  onStepNavigationStart?: (step: CreationStep) => void
) {
  const router = useRouter()
  const navigator = useRouteLikeNavigator()
  const route = useRouteLike()
  const routeRef = useRef<RouteLikeLocation>(route)
  routeRef.current = route
  const scriptChangeGate = useScriptChangeExtractGate()
  const [, forceRender] = useReducer((x: number) => x + 1, 0)

  const openExtractModalRef = useRef(openExtractAgentModalIfNeeded)
  openExtractModalRef.current = openExtractAgentModalIfNeeded
  const stepNavigationStartRef = useRef(onStepNavigationStart)
  useEffect(() => {
    stepNavigationStartRef.current = onStepNavigationStart
  }, [onStepNavigationStart])

  const steps = CREATION_FLOW_STEPS

  /** 当前在剧本页、目标为素材准备时：有效变更则强提示；取消则拦截跳转 */
  const confirmIfLeavingScriptToPrepare = useCallback(
    async (targetKey: string): Promise<boolean> => {
      if (targetKey !== 'scene-character') return true
      if (routePathToCreationStep(routeRef.current.path) !== 'story-script') return true
      return scriptChangeGate.confirmLeaveScriptToPrepare()
    },
    [scriptChangeGate]
  )

  const ctxRef = useRef<RouteStepsCtx | null>(null)
  if (!ctxRef.current) {
    const ctx: RouteStepsCtx = {
      steps,
      getRoute: () => routeRef.current,
      replace: async (loc) => {
        try {
          router.replace(buildHref(loc.path, loc.query))
          return true
        } catch (e: unknown) {
          if (process.env.NODE_ENV === 'development') {
            console.warn('[create-flow] router.replace skipped during concurrent navigation', e)
          }
          return false
        }
      },
      push: async (loc) => {
        router.push(buildHref(loc.path, loc.query))
      },
      back: () => router.back(),
      requestRender: () => forceRender(),
      openExtractAgentModalIfNeeded: (context) => openExtractModalRef.current(context),
      confirmIfLeavingScriptToPrepare: (targetKey) => confirmRef.current(targetKey),
      serverStepStatus: box<FlowStepStatusValue[] | null>(null),
      unlockedStepIndex: box(0),
      stepApiLoading: box(false),
      nextStepSubmitting: box(false),
      serverReportedCurrentStep: box(1),
      stepStatusLoadGeneration: box(0),
      stepStatusPendingRerun: box(false),
      pendingStepStatusAfterEmbeddedPanel: box(false),
      storyScriptDetailFetchedKey: box<string | null>(null),
      storyScriptDetailLoading: box(false),
      storyScriptDetailInFlightKey: box<string | null>(null),
      storyScriptLoadGeneration: box(0),
      storyboardListFetchedKey: box<string | null>(null),
      storyboardListLoading: box(false),
      storyboardListSyncReady: box(false),
      storyboardListInFlightKey: box<string | null>(null),
      storyboardListLoadGeneration: box(0),
      saveDraftSubmitting: box(false),
      createStepSwapPlaceholder: box(false)
    }
    ctxRef.current = ctx
  }
  const ctx = ctxRef.current
  const confirmRef = useRef(confirmIfLeavingScriptToPrepare)
  confirmRef.current = confirmIfLeavingScriptToPrepare

  // ---- 渲染期派生（原 computed）----
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const currentProjectType = useCreationStore((s) => s.currentProjectType)
  const formData = useCreationStore((s) => s.formData)
  const currentStepIndexStore = useCreationStore((s) => s.currentStepIndex)

  const flowStepIndex = getFlowStepIndex(ctx)

  const currentStep: CreationStep = (() => {
    const fromRoute = routePathToCreationStep(route.path)
    if (fromRoute) return fromRoute
    return steps[Math.min(Math.max(currentStepIndexStore, 0), steps.length - 1)]!.key
  })()

  const previewContentStepClass = (() => {
    if (isSeriesScriptUploadPath(route.path)) return 'step-series-script-upload'
    if (isSeriesEpisodeListPath(route.path)) return 'step-series-episode-list'
    return `step-${currentStep}`
  })()

  const storyboardGatePayload = {
    storyboardListSyncReady: ctx.storyboardListSyncReady.value,
    panels: formData.storyboardScript.panels
  }
  const storyboardSuccessorsLocked = isStoryboardFlowSuccessorLocked(storyboardGatePayload)
  const storyboardAdvanceBlocked = isStoryboardScriptAdvanceBlocked({
    currentStep,
    ...storyboardGatePayload
  })

  const stepStatus: Array<'completed' | 'pending' | 'disabled' | 'active'> = (() => {
    const baseStatus = ctx.serverStepStatus.value
      ? [...ctx.serverStepStatus.value]
      : steps.map((_, index) => {
          if (index < flowStepIndex) {
            return computeIsStepCompleted(steps, index, formData) ? 'completed' : 'pending'
          }
          if (index === flowStepIndex) return 'active'
          return 'pending'
        })
    return applyStoryboardFlowStepStatusGate(baseStatus, storyboardSuccessorsLocked)
  })()

  const canSubmit = computeCanSubmit(
    steps,
    ctx.serverStepStatus.value,
    formData,
    currentProjectType
  )

  const getCanSubmit = useCallback((): boolean => {
    const store = useCreationStore.getState()
    return computeCanSubmit(
      steps,
      ctx.serverStepStatus.value,
      store.formData,
      store.currentProjectType
    )
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const toolbarPrimaryLabel = flowStepIndex >= steps.length - 1 ? '提交审核' : '下一步'
  const toolbarPrimaryDisabled =
    storyboardAdvanceBlocked || (flowStepIndex >= steps.length - 1 && !canSubmit)
  const toolbarPrimaryTooltip = storyboardAdvanceBlocked
    ? STORYBOARD_SCRIPT_ADVANCE_BLOCKED_TOOLTIP
    : ''

  /**
   * 步骤 index 与 index+1 之间的箭头：仅当「当前路由步骤已严格越过 index」时高亮。
   * 不能用 isStepCompleted(index)，否则第 4/5/6 步共用 panels 判断会导致尚未到达时箭头已全部变蓝。
   */
  const isConnectorTrailDone = useCallback(
    (index: number): boolean => getFlowStepIndex(ctx) > index,
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  )

  // ---- 导航 ----
  const pushCreateStepRoute = useCallback(
    async (stepKey: CreationStep) => {
      stepNavigationStartRef.current?.(stepKey)
      await new Promise<void>((resolve) => setTimeout(resolve, 0))
      // 先提交乐观步骤与加载层，再导航；冷 chunk 场景也能在首帧给出反馈。
      if (typeof window !== 'undefined') {
        await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()))
      }
      await ctx.push({
        path: creationStepToRoutePath(stepKey),
        query: { ...routeRef.current.query }
      })
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  )

  const goToCreateStep = useCallback(
    async (stepIndex: number) => {
      const key = steps[stepIndex]?.key
      if (key) {
        await pushCreateStepRoute(key)
      }
    },
    [pushCreateStepRoute, steps]
  )

  const syncProjectContextFromRoute = useCallback(() => {
    const r = routeRef.current
    const store = useCreationStore.getState()
    const routeProjectIdRaw = Number(r.query.projectId ?? r.query.id ?? r.query.workId)
    const routeProjectId =
      Number.isFinite(routeProjectIdRaw) && routeProjectIdRaw > 0 ? routeProjectIdRaw : null
    const pt = store.currentProjectType
    const routeEp = ctxParseRouteEpisodeId(ctx)
    const payload: { projectId?: number; episodeId?: number | null } = {}
    if (routeProjectId) payload.projectId = routeProjectId
    if (pt === 'movie') {
      payload.episodeId = 0
    } else if (routeEp !== null && routeEp > 0) {
      payload.episodeId = routeEp
    } else if (
      routeProjectId != null &&
      routeProjectId !== store.currentProjectId &&
      pt === 'series'
    ) {
      payload.episodeId = null
    }
    if (payload.projectId !== undefined || payload.episodeId !== undefined) {
      store.setCurrentProjectContext(payload)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const fetchCreationStepStatus = useCallback(async () => {
    await runFetchCreationStepStatus(ctx, navigator)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [navigator])

  const goBack = useCallback(() => {
    runCreateFlowGoBack(ctx)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleSubmit = useCallback(
    async (opts?: {
      alsoPublish?: boolean
      coverUrl?: string
      projectDesc?: string
    }): Promise<boolean> => {
      return runFlowSubmit(
        {
          getCanSubmit,
          navigateToWorks: () => router.push('/works')
        },
        opts
      )
    },
    [getCanSubmit, router]
  )

  const handleNextStep = useCallback(async () => {
    const store = useCreationStore.getState()
    const routeStep = routePathToCreationStep(routeRef.current.path)
    if (
      routeStep &&
      isStoryboardScriptAdvanceBlocked({
        currentStep: routeStep,
        storyboardListSyncReady: ctx.storyboardListSyncReady.value,
        panels: store.formData.storyboardScript.panels
      })
    ) {
      message.warning(STORYBOARD_SCRIPT_ADVANCE_BLOCKED_TOOLTIP)
      return
    }
    await runCreateFlowNextStep({
      ctx,
      steps,
      pushCreateStepRoute,
      getCanSubmit,
      handleSubmit: () => handleSubmit(),
      confirmLeave: (targetKey) => confirmRef.current(targetKey),
      openExtractModal: (context) => openExtractModalRef.current(context)
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [getCanSubmit, handleSubmit, pushCreateStepRoute, steps])

  const handleStepClick = useCallback(
    async (index: number) => {
      const store = useCreationStore.getState()
      const routeStep = routePathToCreationStep(routeRef.current.path)
      if (
        routeStep &&
        isStoryboardFlowSuccessorStep(index) &&
        isStoryboardFlowSuccessorLocked({
          storyboardListSyncReady: ctx.storyboardListSyncReady.value,
          panels: store.formData.storyboardScript.panels
        })
      ) {
        message.warning(STORYBOARD_SCRIPT_ADVANCE_BLOCKED_TOOLTIP)
        return
      }
      await runCreateFlowStepClick({
        ctx,
        index,
        currentProjectType: useCreationStore.getState().currentProjectType,
        confirmLeave: (targetKey) => confirmRef.current(targetKey),
        pushCreateStepRoute,
        handleNextStep,
        openSceneCharacterExtractModal: () =>
          openExtractModalRef.current('step-click-scene-character')
      })
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [handleNextStep, pushCreateStepRoute, steps]
  )

  const isStepPillDisabled = (index: number): boolean =>
    (storyboardSuccessorsLocked && isStoryboardFlowSuccessorStep(index)) ||
    getCreateFlowStepPillDisabled(ctx, index)

  const prevStep = useCallback(() => {
    const store = useCreationStore.getState()
    // 剧集：剧本创作为展示上的第一步 → 回剧集管理（与顶栏返回一致）
    if (
      store.currentProjectType === 'series' &&
      getFlowStepIndex(ctx) <= CREATE_FLOW_STEP_ORDER.indexOf('story-script')
    ) {
      const q: RouteLikeQuery = { ...routeRef.current.query }
      delete q.episodeId
      delete q.stepInitAdvance
      void ctx.replace({ path: CREATE_SERIES_EPISODE_LIST_PATH, query: q })
      return
    }
    if (getFlowStepIndex(ctx) > 0) {
      const prev = steps[getFlowStepIndex(ctx) - 1]
      if (prev) {
        void pushCreateStepRoute(prev.key)
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pushCreateStepRoute, steps])

  const saveDraft = useCallback(async () => {
    if (ctx.saveDraftSubmitting.value) return
    if (routePathToCreationStep(routeRef.current.path) !== 'story-script') return
    setBox(ctx, ctx.saveDraftSubmitting, true)
    try {
      const ok = await saveStoryScriptToServer(ctx)
      if (ok) message.success('草稿已保存')
    } finally {
      setBox(ctx, ctx.saveDraftSubmitting, false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const addCharacter = useCallback(() => {
    const store = useCreationStore.getState()
    store.updateSceneCharacterData({
      characters: [
        ...store.formData.sceneCharacter.characters,
        `新角色${store.formData.sceneCharacter.characters.length + 1}`
      ]
    })
  }, [])

  useCreateFlowRouteStepEffects({
    ctx,
    route,
    currentProjectId,
    currentEpisodeId,
    currentProjectType,
    fetchCreationStepStatus
  })

  return {
    steps,
    flowStepIndex,
    currentStep,
    previewContentStepClass,
    goToCreateStep,
    serverStepStatus: ctx.serverStepStatus.value,
    unlockedStepIndex: ctx.unlockedStepIndex.value,
    stepApiLoading: ctx.stepApiLoading.value,
    nextStepSubmitting: ctx.nextStepSubmitting.value,
    parseRouteEpisodeId: () => ctxParseRouteEpisodeId(ctx),
    syncProjectContextFromRoute,
    loadStoryScriptFromApi: () => loadStoryScriptFromApi(ctx),
    storyScriptDetailLoading: ctx.storyScriptDetailLoading.value,
    loadStoryboardListFromApi: () => loadStoryboardListFromApi(ctx),
    storyboardListLoading: ctx.storyboardListLoading.value,
    storyboardListSyncReady: ctx.storyboardListSyncReady.value,
    fetchCreationStepStatus,
    isConnectorTrailDone,
    stepStatus,
    canSubmit,
    toolbarPrimaryLabel,
    toolbarPrimaryDisabled,
    toolbarPrimaryTooltip,
    goBack,
    handleStepClick,
    isStepPillDisabled,
    handleNextStep,
    prevStep,
    handleSubmit,
    saveDraft,
    saveDraftSubmitting: ctx.saveDraftSubmitting.value,
    addCharacter,
    ensureRouteWithinUnlockedSteps: () => ensureRouteWithinUnlockedSteps(ctx)
  }
}

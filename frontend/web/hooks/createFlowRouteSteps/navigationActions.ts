import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import {
  CREATE_FLOW_FROM_PANEL_WORKS,
  CREATE_FLOW_FROM_WORKS,
  CREATE_SERIES_EPISODE_LIST_PATH,
  CREATE_SERIES_SCRIPT_UPLOAD_PATH,
  isSeriesEpisodeListPath,
  isSeriesScriptUploadPath,
  resolveCreateFlowBackTarget,
  routePathToCreationStep
} from '~/utils/createFlowRoutes'
import { fetchCreationStepStatusOnce } from '~/utils/creationStepStatusOnce'
import { creationStepAdvance } from '~/utils/businessApi'
import { writeCreationStepSyncCache } from '~/utils/creationStepSyncCache'
import type { CreationStep } from '~/types'
import type { ExtractModalAutoOpenContext } from '~/hooks/useCreateFlowExtractAgents'
import { getFlowStepIndex, getStepRequestParams, setBox, type RouteLikeQuery, type RouteStepsCtx } from './types'
import { persistCurrentStepBeforeNext } from './stepData'
import { applyServerStepState, reconcileAdvanceLoop } from './stepStatus'

export function runCreateFlowGoBack(ctx: RouteStepsCtx) {
  const route = ctx.getRoute()
  const path = route.path
  const from = String(route.query.from ?? '')
  const store = useCreationStore.getState()
  const backOptions = { projectType: store.currentProjectType }
  const navigateBack = (target: ReturnType<typeof resolveCreateFlowBackTarget>, replace = true) => {
    const location =
      target.type === 'path'
        ? { path: target.path }
        : { path: target.path, query: target.query }
    if (replace) void ctx.replace(location)
    else void ctx.push(location)
  }

  const inSeriesChrome = isSeriesScriptUploadPath(path) || isSeriesEpisodeListPath(path)
  const createStepKey = routePathToCreationStep(path)
  const isSeriesProject =
    store.currentProjectType === 'series' ||
    String(route.query.projectType ?? '').toLowerCase() === 'series' ||
    (Number(route.query.episodeId) > 0 && store.currentProjectType !== 'movie')
  if (isSeriesProject && createStepKey != null && !inSeriesChrome) {
    const query: RouteLikeQuery = { ...route.query }
    delete query.episodeId
    delete query.stepInitAdvance
    void ctx.replace({ path: CREATE_SERIES_EPISODE_LIST_PATH, query })
    return
  }
  if (
    inSeriesChrome &&
    (from === CREATE_FLOW_FROM_WORKS || from === CREATE_FLOW_FROM_PANEL_WORKS)
  ) {
    navigateBack(resolveCreateFlowBackTarget(route, backOptions))
    return
  }
  if (inSeriesChrome) {
    if (typeof window !== 'undefined' && window.history.length > 1) {
      ctx.back()
      return
    }
    if (isSeriesEpisodeListPath(path)) {
      void ctx.push({ path: CREATE_SERIES_SCRIPT_UPLOAD_PATH, query: { ...route.query } })
      return
    }
  }
  navigateBack(resolveCreateFlowBackTarget(route, backOptions))
}

export async function runCreateFlowNextStep(params: {
  ctx: RouteStepsCtx
  steps: typeof import('~/utils/createFlowStepMeta').CREATION_FLOW_STEPS
  pushCreateStepRoute: (step: CreationStep) => Promise<void>
  getCanSubmit: () => boolean
  handleSubmit: () => Promise<boolean>
  confirmLeave: (targetKey: string) => Promise<boolean>
  openExtractModal: (context?: ExtractModalAutoOpenContext) => void
}) {
  const { ctx, steps, pushCreateStepRoute, getCanSubmit, handleSubmit, confirmLeave, openExtractModal } =
    params
  if (ctx.stepApiLoading.value || ctx.nextStepSubmitting.value) return
  setBox(ctx, ctx.nextStepSubmitting, true)
  try {
    if (!(await persistCurrentStepBeforeNext(ctx))) return
    const leavingScript = routePathToCreationStep(ctx.getRoute().path) === 'story-script'
    if (leavingScript && !(await confirmLeave('scene-character'))) return

    const requestParams = getStepRequestParams(ctx)
    if (!requestParams) {
      if (getFlowStepIndex(ctx) < steps.length - 1) {
        const nextIndex = getFlowStepIndex(ctx) + 1
        await pushCreateStepRoute(steps[nextIndex]!.key)
        if (steps[nextIndex]!.key === 'scene-character') {
          openExtractModal('after-advance-to-scene-character')
        }
      } else if (getCanSubmit()) {
        void handleSubmit()
      } else {
        message.warning('请完成所有步骤后再提交')
      }
      return
    }

    const localRouteStep = Math.min(Math.max(getFlowStepIndex(ctx) + 1, 1), steps.length)
    if (localRouteStep < ctx.serverReportedCurrentStep.value) {
      if (getFlowStepIndex(ctx) < steps.length - 1) {
        const nextIndex = getFlowStepIndex(ctx) + 1
        await pushCreateStepRoute(steps[nextIndex]!.key)
        writeCreationStepSyncCache(
          requestParams.projectId,
          requestParams.episodeId,
          nextIndex + 1,
          ctx.serverReportedCurrentStep.value
        )
        if (steps[nextIndex]!.key === 'scene-character') {
          openExtractModal('after-advance-to-scene-character')
        }
      } else if (getCanSubmit()) {
        void handleSubmit()
      } else {
        message.warning('请完成所有步骤后再提交')
      }
      return
    }

    if (localRouteStep > ctx.serverReportedCurrentStep.value) {
      const status = await fetchCreationStepStatusOnce(requestParams)
      if (!status || !Number.isFinite(Number(status.currentStep))) {
        message.warning('步骤状态异常，请稍后重试或刷新页面')
        return
      }
      const reconciled = await reconcileAdvanceLoop(ctx, requestParams, status)
      applyServerStepState(
        ctx,
        reconciled && Number.isFinite(Number(reconciled.currentStep)) ? reconciled : status,
        { syncRoute: true }
      )
    }

    let status = await creationStepAdvance({ ...requestParams, completedStep: localRouteStep })
    if (!status || !Number.isFinite(Number(status.currentStep))) {
      try {
        status = await fetchCreationStepStatusOnce(requestParams, { force: true })
      } catch {
        // 统一走下方异常提示。
      }
    }
    if (!status || !Number.isFinite(Number(status.currentStep))) {
      message.warning('步骤同步异常，请稍后重试或刷新页面')
      return
    }
    applyServerStepState(ctx, status, { syncRoute: true })
    const nextIndex = Math.min(Math.max(Number(status.currentStep) - 1, 0), steps.length - 1)
    if (steps[nextIndex]?.key === 'scene-character') {
      openExtractModal('after-advance-to-scene-character')
    }
  } catch (error: any) {
    message.warning(error?.msg || error?.message || '当前步骤未完成，暂时无法推进')
  } finally {
    setBox(ctx, ctx.nextStepSubmitting, false)
  }
}

'use client'

/**
 * 服务端步骤状态拉取 / advance 对账 / 路由回落（原 useCreateFlowRouteAndSteps 的步骤状态侧拆分）。
 */

import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import type { CreationStepState } from '~/types/business-api'
import type { RouteLikeNavigator } from '~/types/routeLike'
import { creationStepAdvance } from '~/utils/businessApi'
import { shouldSkipCreateFlowSyncRoute } from '~/utils/createFlowNavSerialize'
import {
clearStaleCreateFlowProjectContext,
isProjectMissingApiError,
shouldSkipFlowProjectScopedApis
} from '~/utils/createFlowProjectContext'
import {
creationStepToRoutePath,
isSeriesEpisodeListPath,
isSeriesScriptUploadPath,
routePathToCreationStep
} from '~/utils/createFlowRoutes'
import { fetchCreationStepStatusOnce } from '~/utils/creationStepStatusOnce'
import { writeCreationStepSyncCache } from '~/utils/creationStepSyncCache'
import {
getFlowStepIndex,
getProjectContextSig,
getStepRequestParams,
isStepStatusFetchCurrent,
setBox,
type FlowStepStatusValue,
type RouteLikeQuery,
type RouteStepsCtx
} from './types'

export function applyServerStepState(
  ctx: RouteStepsCtx,
  payload:
    | { currentStep?: number; steps?: Array<{ step: number; status: string }> }
    | null
    | undefined,
  options?: { syncRoute?: boolean }
): void {
  const steps = ctx.steps
  const currentStepValue = Number(payload?.currentStep)
  if (!payload || !Number.isFinite(currentStepValue)) {
    return
  }
  const safeCurrentStep = Math.min(Math.max(currentStepValue, 1), steps.length)
  setBox(ctx, ctx.serverReportedCurrentStep, safeCurrentStep)
  useCreationStore.getState().setCurrentStepIndex(safeCurrentStep - 1)
  setBox(ctx, ctx.unlockedStepIndex, safeCurrentStep - 1)
  const mapped: FlowStepStatusValue[] = Array.from({ length: steps.length }, () => 'pending')
  for (const item of payload?.steps ?? []) {
    const idx = Number(item?.step) - 1
    if (idx < 0 || idx >= steps.length) continue
    if (item.status === 'completed') mapped[idx] = 'completed'
    else if (item.status === 'current') mapped[idx] = 'active'
    else mapped[idx] = 'pending'
  }
  if (!mapped.includes('active')) {
    mapped[safeCurrentStep - 1] = 'active'
  }
  setBox(ctx, ctx.serverStepStatus, mapped)
  if (options?.syncRoute) {
    let stepKey = steps[safeCurrentStep - 1]?.key
    // 剧集不落「项目配置」页，服务端仍停在步骤 1 时改落到剧本创作
    if (
      useCreationStore.getState().currentProjectType === 'series' &&
      stepKey === 'global-setting'
    ) {
      stepKey = 'story-script'
    }
    if (stepKey) {
      const targetPath = creationStepToRoutePath(stepKey)
      // 已在目标步骤时跳过，避免与进行中的 out-in 叠导航导致白屏
      if (
        !shouldSkipCreateFlowSyncRoute({
          currentPath: ctx.getRoute().path,
          targetPath
        })
      ) {
        void ctx.replace({ path: targetPath, query: { ...ctx.getRoute().query } })
      }
    }
  }
  const p = getStepRequestParams(ctx)
  if (p) {
    writeCreationStepSyncCache(p.projectId, p.episodeId, safeCurrentStep, safeCurrentStep)
  }
}

/**
 * 接口约定：仅当「当前路由对应步骤(1~7)」> 服务端 currentStep 时循环调 advance。
 * completedStep 取推进前服务端的 currentStep（即当前待完成的那一步）。
 */
export async function reconcileAdvanceLoop(
  ctx: RouteStepsCtx,
  params: { projectId: number; episodeId?: number },
  statusData: CreationStepState
): Promise<CreationStepState> {
  const steps = ctx.steps
  let last = statusData
  let serv = Number(last?.currentStep ?? 1)
  if (!Number.isFinite(serv)) serv = 1
  serv = Math.min(Math.max(Math.floor(serv), 1), steps.length)
  const localRouteStep = Math.min(Math.max(getFlowStepIndex(ctx) + 1, 1), steps.length)

  let guard = 0
  try {
    while (localRouteStep > serv && guard++ < steps.length) {
      last = await creationStepAdvance({ ...params, completedStep: serv })
      const next = Number(last?.currentStep ?? serv + 1)
      serv = Number.isFinite(next)
        ? Math.min(Math.max(Math.floor(next), 1), steps.length)
        : Math.min(serv + 1, steps.length)
    }
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    message.warning(err?.msg || err?.message || '步骤同步失败，请稍后重试')
    return statusData
  }
  if (last && Number.isFinite(Number(last.currentStep))) {
    return last
  }
  return statusData
}

/** 当前路由步骤超出作品解锁进度时（如内嵌作品库从 B 切回 A），回落到服务端允许的最高步骤 */
export async function ensureRouteWithinUnlockedSteps(
  ctx: RouteStepsCtx,
  query?: RouteLikeQuery,
  gen?: number,
  contextSig?: string
): Promise<boolean> {
  const steps = ctx.steps
  const route = ctx.getRoute()
  const resolvedQuery = query ?? { ...route.query }
  if (isSeriesScriptUploadPath(route.path) || isSeriesEpisodeListPath(route.path)) return false
  if (routePathToCreationStep(route.path) === null) return false
  if (getFlowStepIndex(ctx) <= ctx.unlockedStepIndex.value) return false

  let targetKey = steps[ctx.unlockedStepIndex.value]?.key
  if (useCreationStore.getState().currentProjectType === 'series' && targetKey === 'global-setting') {
    targetKey = 'story-script'
  }
  if (!targetKey) return false

  setBox(ctx, ctx.createStepSwapPlaceholder, true)
  try {
    // 原 nextTick + requestAnimationFrame：先渲染遮罩再触发路由切换
    await new Promise<void>((resolve) => setTimeout(resolve, 0))
    if (typeof window !== 'undefined') {
      await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()))
    }
    if (gen != null && contextSig != null && !isStepStatusFetchCurrent(ctx, gen, contextSig)) {
      return false
    }
    const ok = await ctx.replace({
      path: creationStepToRoutePath(targetKey),
      query: resolvedQuery
    })
    if (!ok) return false
  } finally {
    setBox(ctx, ctx.createStepSwapPlaceholder, false)
  }
  if (gen != null && contextSig != null && !isStepStatusFetchCurrent(ctx, gen, contextSig)) {
    return true
  }
  if (targetKey === 'scene-character') {
    await new Promise<void>((resolve) => setTimeout(resolve, 0))
    ctx.openExtractAgentModalIfNeeded('after-advance-to-scene-character')
  }
  return true
}

export async function fetchCreationStepStatus(
  ctx: RouteStepsCtx,
  navigator: RouteLikeNavigator
): Promise<void> {
  const steps = ctx.steps
  if (ctx.stepApiLoading.value) {
    ctx.stepStatusPendingRerun.value = true
    return
  }
  const route = ctx.getRoute()
  if (isSeriesScriptUploadPath(route.path) || isSeriesEpisodeListPath(route.path)) return
  if (shouldSkipFlowProjectScopedApis(route)) return
  const params = getStepRequestParams(ctx)
  if (!params) {
    setBox(ctx, ctx.serverStepStatus, null)
    setBox(ctx, ctx.unlockedStepIndex, useCreationStore.getState().currentStepIndex)
    return
  }
  // 剧集未选集时不请求 step/status，避免后端返回「请选择集数」并污染 UI
  if (
    useCreationStore.getState().currentProjectType === 'series' &&
    (params.episodeId == null || params.episodeId <= 0)
  ) {
    return
  }
  const needAdvanceBeforeStatus =
    route.query.stepInitAdvance === '1' || String(route.query.stepInitAdvance ?? '') === 'true'
  const initTarget = String(route.query.stepInitTarget ?? '')
  const cleanQuery: RouteLikeQuery = { ...route.query }
  delete cleanQuery.stepInitAdvance
  delete cleanQuery.stepInitTarget

  setBox(ctx, ctx.stepApiLoading, true)
  setBox(ctx, ctx.stepStatusLoadGeneration, ctx.stepStatusLoadGeneration.value + 1)
  const gen = ctx.stepStatusLoadGeneration.value
  const contextSig = getProjectContextSig(ctx)
  try {
    let data = await fetchCreationStepStatusOnce(params)
    if (!isStepStatusFetchCurrent(ctx, gen, contextSig)) return
    if (!data || !Number.isFinite(Number(data.currentStep))) {
      message.error('获取步骤状态失败，请稍后重试')
      return
    }

    const isValidStepState = (d: typeof data | null | undefined): d is typeof data =>
      !!d && Number.isFinite(Number(d.currentStep))
    /**
     * advance 返回体可能为空（接口成功但无 data）。服务端 status 是步骤状态的唯一权威数据源：
     * 返回体有效则直接采用，否则重拉一次 status；两者都拿不到时保留 advance 前的有效状态。
     */
    const resolveStepStateAfterAdvance = async (
      adv: typeof data | null | undefined
    ): Promise<typeof data | null> => {
      if (isValidStepState(adv)) return adv
      try {
        // force：advance 已改变服务端状态，4s 内的 status 缓存已失效
        const refreshed = await fetchCreationStepStatusOnce(params, { force: true })
        return isValidStepState(refreshed) ? refreshed : null
      } catch {
        return null
      }
    }

    // 初始化标记只补齐尚未完成的步骤，避免重复进入时把已有进度回写或重复推进。
    if (needAdvanceBeforeStatus && Number(data.currentStep) <= 1) {
      try {
        const next = await resolveStepStateAfterAdvance(
          await creationStepAdvance({ ...params, completedStep: 1 })
        )
        if (next) {
          data = next
          setBox(
            ctx,
            ctx.serverReportedCurrentStep,
            Math.min(Math.max(Math.floor(Number(next.currentStep)), 1), steps.length)
          )
        }
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.warning(err?.msg || err?.message || '初始化步骤推进失败，将按服务端状态展示')
      }
    }

    // “生成剧集”已在列表页确认存在剧本，可安全补齐剧本步骤并直接进入素材准备。
    if (
      needAdvanceBeforeStatus &&
      initTarget === 'scene-character' &&
      Number(data.currentStep) === steps.findIndex((s) => s.key === 'story-script') + 1
    ) {
      try {
        const next = await resolveStepStateAfterAdvance(
          await creationStepAdvance({
            ...params,
            completedStep: steps.findIndex((s) => s.key === 'story-script') + 1
          })
        )
        if (next) {
          data = next
        }
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.warning(err?.msg || err?.message || '剧本步骤推进失败，将返回剧本创作')
      }
    }

    if (!isStepStatusFetchCurrent(ctx, gen, contextSig)) return

    const serverStep = Math.min(Math.max(Math.floor(Number(data.currentStep)), 1), steps.length)
    const localRouteStep = Math.min(Math.max(getFlowStepIndex(ctx) + 1, 1), steps.length)
    // 路由步骤高于服务端解锁进度时（切作品残留路由），禁止 reconcile 循环 advance，避免污染新作品步骤
    const merged =
      localRouteStep <= serverStep ? await reconcileAdvanceLoop(ctx, params, data) : data
    if (!isStepStatusFetchCurrent(ctx, gen, contextSig)) return
    const toApply = merged && Number.isFinite(Number(merged.currentStep)) ? merged : data
    applyServerStepState(ctx, toApply)
    const routeReplaced = await ensureRouteWithinUnlockedSteps(ctx, cleanQuery, gen, contextSig)

    if (needAdvanceBeforeStatus && !routeReplaced && isStepStatusFetchCurrent(ctx, gen, contextSig)) {
      // 仅清理一次性初始化 query；路径不变时也用安全 replace，避免并发导航白屏
      const routeNow = ctx.getRoute()
      const hasInitQuery =
        routeNow.query.stepInitAdvance != null || routeNow.query.stepInitTarget != null
      if (hasInitQuery) {
        await ctx.replace({ path: routeNow.path, query: cleanQuery })
      }
    }
  } catch (error: unknown) {
    if (!isStepStatusFetchCurrent(ctx, gen, contextSig)) return
    if (isProjectMissingApiError(error)) {
      setBox(ctx, ctx.serverStepStatus, null)
      await clearStaleCreateFlowProjectContext({
        router: navigator,
        route: ctx.getRoute(),
        store: useCreationStore.getState()
      })
      return
    }
    const err = error as { msg?: string; message?: string }
    message.error(err?.msg || err?.message || '获取步骤状态失败')
  } finally {
    setBox(ctx, ctx.stepApiLoading, false)
    if (ctx.stepStatusPendingRerun.value) {
      ctx.stepStatusPendingRerun.value = false
      void fetchCreationStepStatus(ctx, navigator)
    }
  }
}

'use client'

/**
 * useCreateFlowRouteAndSteps 拆分模块的共享运行时（原 composables/useCreateFlowRouteAndSteps.ts）。
 * Vue ref → Box（callback 即时读写）+ requestRender（驱动 React 重渲染）。
 */

import type { ExtractModalAutoOpenContext } from '~/hooks/useCreateFlowExtractAgents'
import { useCreationStore } from '~/stores/creation'
import type { RouteLikeLocation } from '~/types/routeLike'
import {
buildFlowStepRequestParams,
resolveFlowEpisodeIdFromRoute
} from '~/utils/createFlowProjectContext'
import {
creationStepIndexFromPath,
isSeriesEpisodeListPath,
isSeriesScriptUploadPath
} from '~/utils/createFlowRoutes'
import type { CreationFlowStepMeta } from '~/utils/createFlowStepMeta'

export interface Box<T> {
  value: T
}

export type FlowStepStatusValue = 'completed' | 'active' | 'pending'

export type RouteLikeQuery = Record<
  string,
  string | string[] | (string | null)[] | null | undefined
>

export interface RouteStepsCtx {
  steps: CreationFlowStepMeta[]
  getRoute(): RouteLikeLocation
  /** 并发导航时 Next 内部可能抛错；吞掉避免整页白屏（原 safeRouterReplace） */
  replace(loc: { path: string; query?: RouteLikeQuery }): Promise<boolean>
  push(loc: { path: string; query?: RouteLikeQuery }): Promise<void>
  back(): void
  requestRender(): void
  openExtractAgentModalIfNeeded(context?: ExtractModalAutoOpenContext): void
  confirmIfLeavingScriptToPrepare(targetKey: string): Promise<boolean>

  serverStepStatus: Box<FlowStepStatusValue[] | null>
  unlockedStepIndex: Box<number>
  stepApiLoading: Box<boolean>
  nextStepSubmitting: Box<boolean>
  serverReportedCurrentStep: Box<number>
  /**
   * 步骤状态拉取世代：快速切换剧集时作废旧请求的 router.replace，
   * 避免与新导航叠加造成白屏。
   */
  stepStatusLoadGeneration: Box<number>
  /** loading 期间又有新触发时，结束后用最新上下文补拉一次 */
  stepStatusPendingRerun: Box<boolean>
  pendingStepStatusAfterEmbeddedPanel: Box<boolean>

  storyScriptDetailFetchedKey: Box<string | null>
  storyScriptDetailLoading: Box<boolean>
  storyScriptDetailInFlightKey: Box<string | null>
  /** 防止快速切换作品时旧请求晚到覆盖新作品的剧本正文 */
  storyScriptLoadGeneration: Box<number>
  storyboardListFetchedKey: Box<string | null>
  storyboardListLoading: Box<boolean>
  /** 当前作品/剧集分镜列表是否已完成首次同步（刷新/切作品前为 false，避免空态闪烁） */
  storyboardListSyncReady: Box<boolean>
  storyboardListInFlightKey: Box<string | null>
  storyboardListLoadGeneration: Box<number>

  saveDraftSubmitting: Box<boolean>
  /** 原 Nuxt useState('create-flow-step-swap-placeholder')：切步遮罩（模板占位已注释，仅保时序） */
  createStepSwapPlaceholder: Box<boolean>
}

/** 写 Box 并触发重渲染（等价 Vue ref 赋值） */
export function setBox<T>(ctx: RouteStepsCtx, box: Box<T>, value: T): void {
  box.value = value
  ctx.requestRender()
}

export function getFlowStepIndex(ctx: RouteStepsCtx): number {
  return creationStepIndexFromPath(ctx.getRoute().path)
}

export function parseRouteEpisodeId(ctx: RouteStepsCtx): number | null {
  return resolveFlowEpisodeIdFromRoute(
    ctx.getRoute(),
    useCreationStore.getState().currentProjectType
  )
}

/** 原 computed(stepRequestParams) */
export function getStepRequestParams(
  ctx: RouteStepsCtx
): { projectId: number; episodeId?: number } | null {
  const route = ctx.getRoute()
  const store = useCreationStore.getState()
  const routeProjectIdRaw = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
  const routeProjectId =
    Number.isFinite(routeProjectIdRaw) && routeProjectIdRaw > 0 ? routeProjectIdRaw : null
  const storePid =
    store.currentProjectId != null && store.currentProjectId > 0 ? store.currentProjectId : null
  // 内嵌「我的作品」切作品时 store 已更新、路由 query 可能仍为旧 id，优先 store 避免串号
  const projectId = storePid ?? routeProjectId
  if (!projectId) return null

  return buildFlowStepRequestParams({
    projectId,
    projectType: store.currentProjectType,
    storeEpisodeId: store.currentEpisodeId,
    routeEpisodeId: parseRouteEpisodeId(ctx)
  })
}

/** 原 computed(projectContextSig) */
export function getProjectContextSig(ctx: RouteStepsCtx): string {
  const p = getStepRequestParams(ctx)
  return p ? `${p.projectId}:${p.episodeId ?? 'na'}` : ''
}

/** 步骤状态拉取结果是否仍然有效（世代 + 上下文 + 非剧集 chrome 页） */
export function isStepStatusFetchCurrent(
  ctx: RouteStepsCtx,
  gen: number,
  contextSig: string
): boolean {
  const path = ctx.getRoute().path
  return (
    gen === ctx.stepStatusLoadGeneration.value &&
    contextSig === getProjectContextSig(ctx) &&
    !isSeriesScriptUploadPath(path) &&
    !isSeriesEpisodeListPath(path)
  )
}

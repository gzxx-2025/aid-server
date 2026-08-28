'use client'

import { useEffect,useRef } from 'react'
import { useCreationStore } from '~/stores/creation'
import type { RouteLikeLocation } from '~/types/routeLike'
import { shouldSkipFlowProjectScopedApis } from '~/utils/createFlowProjectContext'
import {
CREATE_FLOW_STEP_ORDER,
CREATE_SERIES_EPISODE_LIST_PATH,
creationStepToRoutePath,
isSeriesEpisodeListPath,
isSeriesScriptUploadPath,
routePathToCreationStep
} from '~/utils/createFlowRoutes'
import {
loadStoryScriptFromApi,
loadStoryboardListFromApi,
shouldLoadStoryScriptForRoute,
shouldLoadStoryboardListForRoute,
storyScriptFetchKeyFromDeps
} from './stepData'
import {
getFlowStepIndex,
getProjectContextSig,
parseRouteEpisodeId,
setBox,
type RouteLikeQuery,
type RouteStepsCtx
} from './types'
interface RouteEffectOptions {
  ctx: RouteStepsCtx
  route: RouteLikeLocation
  currentProjectId: number | null
  currentEpisodeId: number | null
  currentProjectType: string | null
  fetchCreationStepStatus: () => Promise<void>
}

/** 路由与项目 scope 变化产生的数据加载/状态失效副作用。 */
export function useCreateFlowRouteEffects({
  ctx,
  route,
  currentProjectId,
  currentEpisodeId,
  currentProjectType,
  fetchCreationStepStatus
}: RouteEffectOptions): void {
  useEffect(() => {
    const step = routePathToCreationStep(route.path)
    if (step === null) return
    const index = CREATE_FLOW_STEP_ORDER.indexOf(step)
    if (index >= 0 && useCreationStore.getState().currentStepIndex !== index) {
      useCreationStore.getState().setCurrentStepIndex(index)
    }
  }, [route.path])

  const routeQueryEpisodeId = route.query.episodeId
  const routeQueryProjectId = route.query.projectId
  useEffect(() => {
    if (currentProjectType !== 'series') return
    if (routePathToCreationStep(route.path) !== 'global-setting') return
    const query: RouteLikeQuery = { ...ctx.getRoute().query }
    const episodeId = parseRouteEpisodeId(ctx)
    void ctx.replace({
      path:
        episodeId != null && episodeId > 0
          ? creationStepToRoutePath('story-script')
          : CREATE_SERIES_EPISODE_LIST_PATH,
      query
    })
  }, [ctx, currentEpisodeId, currentProjectType, route.path, routeQueryEpisodeId, routeQueryProjectId])

  const loadDependencies = [
    route.path,
    currentProjectId,
    currentEpisodeId,
    currentProjectType,
    routeQueryProjectId,
    route.query.id,
    route.query.workId,
    routeQueryEpisodeId
  ] as const
  const previousLoadDependenciesRef = useRef<readonly unknown[] | undefined>(undefined)
  useEffect(() => {
    const previous = previousLoadDependenciesRef.current
    previousLoadDependenciesRef.current = loadDependencies
    const path = String(loadDependencies[0] ?? '')
    const nextKey = storyScriptFetchKeyFromDeps(loadDependencies)
    const previousKey = previous ? storyScriptFetchKeyFromDeps(previous) : null
    if (previous && nextKey !== previousKey) setBox(ctx, ctx.storyScriptDetailFetchedKey, null)
    if (shouldLoadStoryScriptForRoute(path)) void loadStoryScriptFromApi(ctx)
    if (shouldLoadStoryboardListForRoute(path)) void loadStoryboardListFromApi(ctx)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, loadDependencies)

  const projectContextSignature = getProjectContextSig(ctx)
  const previousContextSignatureRef = useRef<string | undefined>(undefined)
  useEffect(() => {
    const previous = previousContextSignatureRef.current
    previousContextSignatureRef.current = projectContextSignature
    if (previous === undefined || !projectContextSignature || projectContextSignature === previous) return
    setBox(ctx, ctx.stepStatusLoadGeneration, ctx.stepStatusLoadGeneration.value + 1)
    if (previous && projectContextSignature.split(':')[0] !== previous.split(':')[0]) {
      setBox(ctx, ctx.storyScriptDetailFetchedKey, null)
    }
    setBox(ctx, ctx.storyboardListFetchedKey, null)
    setBox(ctx, ctx.storyboardListInFlightKey, null)
    setBox(ctx, ctx.storyboardListSyncReady, false)
    setBox(ctx, ctx.storyboardListLoadGeneration, ctx.storyboardListLoadGeneration.value + 1)
    if (shouldSkipFlowProjectScopedApis(ctx.getRoute())) {
      ctx.pendingStepStatusAfterEmbeddedPanel.value = true
      setBox(ctx, ctx.serverStepStatus, null)
      setBox(ctx, ctx.unlockedStepIndex, getFlowStepIndex(ctx))
      return
    }
    setBox(ctx, ctx.serverStepStatus, null)
    void fetchCreationStepStatus()
  }, [ctx, fetchCreationStepStatus, projectContextSignature])

  const skipsProjectApis = shouldSkipFlowProjectScopedApis(route)
  const previousSkipRef = useRef<boolean | undefined>(undefined)
  useEffect(() => {
    const previous = previousSkipRef.current
    previousSkipRef.current = skipsProjectApis
    if (skipsProjectApis || previous !== true) return
    ctx.pendingStepStatusAfterEmbeddedPanel.value = false
    setBox(ctx, ctx.serverStepStatus, null)
    void fetchCreationStepStatus()
  }, [ctx, fetchCreationStepStatus, skipsProjectApis])

  const previousPathRef = useRef<string | undefined>(undefined)
  useEffect(() => {
    const previousPath = previousPathRef.current
    previousPathRef.current = route.path
    if (previousPath === undefined) return
    if (isSeriesScriptUploadPath(route.path) || isSeriesEpisodeListPath(route.path)) {
      setBox(ctx, ctx.stepStatusLoadGeneration, ctx.stepStatusLoadGeneration.value + 1)
      ctx.stepStatusPendingRerun.value = false
    }
    const leftSeriesChrome =
      (isSeriesScriptUploadPath(previousPath) || isSeriesEpisodeListPath(previousPath)) &&
      !isSeriesScriptUploadPath(route.path) &&
      !isSeriesEpisodeListPath(route.path)
    if (leftSeriesChrome) void fetchCreationStepStatus()
  }, [ctx, fetchCreationStepStatus, route.path])
}

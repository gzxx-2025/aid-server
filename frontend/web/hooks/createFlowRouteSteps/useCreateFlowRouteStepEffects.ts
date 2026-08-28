'use client'

import { useEffect, useRef } from 'react'
import { useCreationStore } from '~/stores/creation'
import {
  CREATE_FLOW_STEP_ORDER,
  CREATE_SERIES_EPISODE_LIST_PATH,
  creationStepToRoutePath,
  isSeriesEpisodeListPath,
  isSeriesScriptUploadPath,
  routePathToCreationStep
} from '~/utils/createFlowRoutes'
import { shouldSkipFlowProjectScopedApis } from '~/utils/createFlowProjectContext'
import type { RouteLikeLocation } from '~/types/routeLike'
import {
  getFlowStepIndex,
  getProjectContextSig,
  parseRouteEpisodeId,
  setBox,
  type RouteLikeQuery,
  type RouteStepsCtx
} from './types'
import {
  loadStoryScriptFromApi,
  loadStoryboardListFromApi,
  shouldLoadStoryScriptForRoute,
  shouldLoadStoryboardListForRoute,
  storyScriptFetchKeyFromDeps
} from './stepData'

export function useCreateFlowRouteStepEffects(params: {
  ctx: RouteStepsCtx
  route: RouteLikeLocation
  currentProjectId: number | null
  currentEpisodeId: number | null
  currentProjectType: string | null
  fetchCreationStepStatus: () => Promise<void>
}) {
  const {
    ctx,
    route,
    currentProjectId,
    currentEpisodeId,
    currentProjectType,
    fetchCreationStepStatus
  } = params

  useEffect(() => {
    const step = routePathToCreationStep(route.path)
    if (step == null) return
    const index = CREATE_FLOW_STEP_ORDER.indexOf(step)
    if (index >= 0 && useCreationStore.getState().currentStepIndex !== index) {
      useCreationStore.getState().setCurrentStepIndex(index)
    }
  }, [route.path])

  const routeEpisodeId = route.query.episodeId
  const routeProjectId = route.query.projectId
  useEffect(() => {
    if (currentProjectType !== 'series' || routePathToCreationStep(route.path) !== 'global-setting') {
      return
    }
    const episodeId = parseRouteEpisodeId(ctx)
    const query: RouteLikeQuery = { ...ctx.getRoute().query }
    if (episodeId != null && episodeId > 0) {
      void ctx.replace({ path: creationStepToRoutePath('story-script'), query })
    } else {
      void ctx.replace({ path: CREATE_SERIES_EPISODE_LIST_PATH, query })
    }
  }, [ctx, route.path, currentProjectType, currentEpisodeId, routeEpisodeId, routeProjectId])

  const routeId = route.query.id
  const routeWorkId = route.query.workId
  const loadDependencies = [
    route.path,
    currentProjectId,
    currentEpisodeId,
    currentProjectType,
    routeProjectId,
    routeId,
    routeWorkId,
    routeEpisodeId
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
  }, loadDependencies) // eslint-disable-line react-hooks/exhaustive-deps

  const projectContextSignature = getProjectContextSig(ctx)
  const previousContextSignatureRef = useRef<string | undefined>(undefined)
  useEffect(() => {
    const previous = previousContextSignatureRef.current
    previousContextSignatureRef.current = projectContextSignature
    if (previous === undefined || !projectContextSignature || projectContextSignature === previous) {
      return
    }
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

  const skipFlowApis = shouldSkipFlowProjectScopedApis(route)
  const previousSkipRef = useRef<boolean | undefined>(undefined)
  useEffect(() => {
    const previous = previousSkipRef.current
    previousSkipRef.current = skipFlowApis
    if (skipFlowApis || previous !== true) return
    ctx.pendingStepStatusAfterEmbeddedPanel.value = false
    setBox(ctx, ctx.serverStepStatus, null)
    void fetchCreationStepStatus()
  }, [ctx, fetchCreationStepStatus, skipFlowApis])

  const previousPathRef = useRef<string | undefined>(undefined)
  useEffect(() => {
    const previous = previousPathRef.current
    previousPathRef.current = route.path
    if (previous === undefined) return
    if (isSeriesScriptUploadPath(route.path) || isSeriesEpisodeListPath(route.path)) {
      setBox(ctx, ctx.stepStatusLoadGeneration, ctx.stepStatusLoadGeneration.value + 1)
      ctx.stepStatusPendingRerun.value = false
    }
    const leftSeriesChrome =
      (isSeriesScriptUploadPath(previous) || isSeriesEpisodeListPath(previous)) &&
      !isSeriesScriptUploadPath(route.path) &&
      !isSeriesEpisodeListPath(route.path)
    if (leftSeriesChrome) void fetchCreationStepStatus()
  }, [ctx, fetchCreationStepStatus, route.path])
}

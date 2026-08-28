import { useCreationStore } from '~/stores/creation'
import type { CreationStep,WorkData } from '~/types'
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
import { CREATION_FLOW_STEPS } from '~/utils/createFlowStepMeta'
import { isStoryScriptContentFilled } from './stepData'
import {
parseRouteEpisodeId,
type Box,
type FlowStepStatusValue,
type RouteLikeQuery,
type RouteStepsCtx
} from './types'
export function box<T>(value: T): Box<T> {
  return { value }
}

export function buildCreateFlowHref(path: string, query?: RouteLikeQuery): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value == null) continue
    if (Array.isArray(value)) {
      value.forEach((item) => item != null && search.append(key, String(item)))
    } else {
      search.set(key, String(value))
    }
  }
  const queryString = search.toString()
  return queryString ? `${path}?${queryString}` : path
}

export function isCreateFlowStepCompleted(
  steps: typeof CREATION_FLOW_STEPS,
  index: number,
  data: WorkData
): boolean {
  const step = steps[index]!
  switch (step.key) {
    case 'global-setting':
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
      return data.storyboardScript.panels.length > 0
    case 'preview':
      return true
    default:
      return false
  }
}

export function canSubmitCreateFlow(
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
      return key === 'preview'
        ? status === 'completed' || status === 'active'
        : status === 'completed'
    })
  }

  return steps.every(
    (step, index) => isSeriesSkip(step.key) || isCreateFlowStepCompleted(steps, index, data)
  )
}

export function syncProjectContextFromCreateRoute(ctx: RouteStepsCtx): void {
  const route = ctx.getRoute()
  const store = useCreationStore.getState()
  const routeProjectIdRaw = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
  const routeProjectId =
    Number.isFinite(routeProjectIdRaw) && routeProjectIdRaw > 0 ? routeProjectIdRaw : null
  const routeEpisodeId = parseRouteEpisodeId(ctx)
  const payload: { projectId?: number; episodeId?: number | null } = {}
  if (routeProjectId) payload.projectId = routeProjectId
  if (store.currentProjectType === 'movie') payload.episodeId = 0
  else if (routeEpisodeId !== null && routeEpisodeId > 0) payload.episodeId = routeEpisodeId
  else if (
    routeProjectId != null &&
    routeProjectId !== store.currentProjectId &&
    store.currentProjectType === 'series'
  ) {
    payload.episodeId = null
  }
  if (payload.projectId !== undefined || payload.episodeId !== undefined) {
    store.setCurrentProjectContext(payload)
  }
}

export function navigateBackFromCreateFlow(ctx: RouteStepsCtx): void {
  const route = ctx.getRoute()
  const path = route.path
  const from = String(route.query.from ?? '')
  const store = useCreationStore.getState()
  const backOptions = { projectType: store.currentProjectType }
  const navigate = (target: ReturnType<typeof resolveCreateFlowBackTarget>, replace = true) => {
    const location =
      target.type === 'path'
        ? { path: target.path }
        : { path: target.path, query: target.query }
    if (replace) void ctx.replace(location)
    else void ctx.push(location)
  }

  const inSeriesChrome = isSeriesScriptUploadPath(path) || isSeriesEpisodeListPath(path)
  const createStep = routePathToCreationStep(path)
  const isSeriesProject =
    store.currentProjectType === 'series' ||
    String(route.query.projectType ?? '').toLowerCase() === 'series' ||
    (Number(route.query.episodeId) > 0 && store.currentProjectType !== 'movie')

  if (isSeriesProject && createStep != null && !inSeriesChrome) {
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
    navigate(resolveCreateFlowBackTarget(route, backOptions))
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
  navigate(resolveCreateFlowBackTarget(route, backOptions))
}

export type CreateFlowStep = CreationStep

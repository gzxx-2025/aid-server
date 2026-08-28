import type { CreationStep } from '~/types'
import type { RouteLikeLocation } from '~/types/routeLike'
import { creationStepToRoutePath } from './createFlowRoutes'

const clientModuleLoaders: Record<CreationStep, () => Promise<unknown>> = {
  'global-setting': () => import('~/components/steps/GlobalSettingPagePanel'),
  'story-script': () => import('~/components/steps/StoryScript'),
  'scene-character': () => import('~/components/steps/SceneCharacterProp'),
  'storyboard-script': () => import('~/components/steps/StoryboardScript'),
  'storyboard-video': () => import('~/components/steps/StoryboardVideo'),
  dubbing: () => import('~/components/steps/Dubbing'),
  preview: () => import('~/components/steps/VideoPreview')
}

const clientModuleCache = new Map<CreationStep, Promise<void>>()

export function buildCreateFlowStepPrefetchHref(
  step: CreationStep,
  query: RouteLikeLocation['query']
): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value == null) continue
    if (Array.isArray(value)) {
      value.forEach((item) => item != null && search.append(key, String(item)))
    } else {
      search.set(key, String(value))
    }
  }
  const queryString = search.toString()
  const path = creationStepToRoutePath(step)
  return queryString ? `${path}?${queryString}` : path
}

export function resolveCreateFlowStepPreloadOrder(
  stepKeys: CreationStep[],
  currentStep: CreationStep
): CreationStep[] {
  const currentIndex = Math.max(0, stepKeys.indexOf(currentStep))
  return stepKeys
    .filter((step) => step !== currentStep)
    .sort((left, right) => {
      const distance = Math.abs(stepKeys.indexOf(left) - currentIndex) -
        Math.abs(stepKeys.indexOf(right) - currentIndex)
      return distance || stepKeys.indexOf(left) - stepKeys.indexOf(right)
    })
}

export function preloadCreateFlowStepClient(step: CreationStep): Promise<void> {
  const cached = clientModuleCache.get(step)
  if (cached) return cached
  const promise = clientModuleLoaders[step]().then(
    () => undefined,
    () => {
      clientModuleCache.delete(step)
    }
  )
  clientModuleCache.set(step, promise)
  return promise
}

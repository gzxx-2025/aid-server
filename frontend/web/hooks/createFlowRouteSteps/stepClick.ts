import type { CreationStep } from '~/types'
import {
  isCreateFlowStepPillDisabled,
  resolveCreateFlowStepClickAction
} from '~/utils/createFlowStepNavigation'
import { getFlowStepIndex, type RouteStepsCtx } from './types'

export function getCreateFlowStepPillDisabled(ctx: RouteStepsCtx, index: number): boolean {
  return isCreateFlowStepPillDisabled({
    targetIndex: index,
    currentRouteIndex: getFlowStepIndex(ctx),
    unlockedStepIndex: ctx.unlockedStepIndex.value,
    stepStatusLoading: ctx.stepApiLoading.value,
    stepSubmitting: ctx.nextStepSubmitting.value
  })
}

interface RunCreateFlowStepClickOptions {
  ctx: RouteStepsCtx
  index: number
  currentProjectType: string | null
  confirmLeave: (targetKey: string) => Promise<boolean>
  pushCreateStepRoute: (stepKey: CreationStep) => Promise<void>
  handleNextStep: () => Promise<void>
  openSceneCharacterExtractModal: () => void
}

export async function runCreateFlowStepClick({
  ctx,
  index,
  currentProjectType,
  confirmLeave,
  pushCreateStepRoute,
  handleNextStep,
  openSceneCharacterExtractModal
}: RunCreateFlowStepClickOptions): Promise<void> {
  const clickState = {
    targetIndex: index,
    currentRouteIndex: getFlowStepIndex(ctx),
    unlockedStepIndex: ctx.unlockedStepIndex.value
  }
  const action = resolveCreateFlowStepClickAction(clickState)
  if (
    isCreateFlowStepPillDisabled({
      ...clickState,
      stepStatusLoading: ctx.stepApiLoading.value,
      stepSubmitting: ctx.nextStepSubmitting.value
    })
  ) return

  const targetKey = ctx.steps[index]?.key
  if (currentProjectType === 'series' && targetKey === 'global-setting') return
  if (action === 'stay') return

  if (action === 'navigate') {
    if (!targetKey || !(await confirmLeave(targetKey))) return
    await pushCreateStepRoute(targetKey)
    if (targetKey === 'scene-character') openSceneCharacterExtractModal()
    return
  }

  await handleNextStep()
}

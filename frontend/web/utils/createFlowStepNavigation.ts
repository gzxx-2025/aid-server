export type CreateFlowStepClickAction = 'stay' | 'navigate' | 'advance'

export interface CreateFlowStepClickState {
  targetIndex: number
  currentRouteIndex: number
  unlockedStepIndex: number
}

/**
 * A restored/deep-linked route is proof that its preceding steps are history,
 * even while the server status request is still pending or its cached unlocked
 * index is stale. This only widens backwards navigation; it never directly
 * unlocks a step after the current route.
 */
export function resolveCreateFlowStepClickAction({
  targetIndex,
  currentRouteIndex,
  unlockedStepIndex
}: CreateFlowStepClickState): CreateFlowStepClickAction {
  if (targetIndex < 0 || targetIndex === currentRouteIndex) return 'stay'
  const directNavigationLimit = Math.max(currentRouteIndex, unlockedStepIndex)
  return targetIndex <= directNavigationLimit ? 'navigate' : 'advance'
}

export function isCreateFlowStepPillDisabled(
  state: CreateFlowStepClickState & {
    stepStatusLoading: boolean
    stepSubmitting: boolean
  }
): boolean {
  if (state.stepSubmitting) return true
  if (!state.stepStatusLoading) return false
  return !(
    state.targetIndex < state.currentRouteIndex &&
    resolveCreateFlowStepClickAction(state) === 'navigate'
  )
}

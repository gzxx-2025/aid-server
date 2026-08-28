'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import type { CreationStep } from '~/types'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'

const NAVIGATION_FEEDBACK_TIMEOUT_MS = 20_000

export function useCreateFlowStepNavigationFeedback(routePath: string) {
  const [pendingStep, setPendingStep] = useState<CreationStep | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const currentStep = routePathToCreationStep(routePath)

  const clearTimer = useCallback(() => {
    if (!timerRef.current) return
    clearTimeout(timerRef.current)
    timerRef.current = null
  }, [])

  const beginStepNavigation = useCallback((step: CreationStep) => {
    if (routePathToCreationStep(routePath) === step) return
    clearTimer()
    setPendingStep(step)
    timerRef.current = setTimeout(() => {
      timerRef.current = null
      setPendingStep(null)
    }, NAVIGATION_FEEDBACK_TIMEOUT_MS)
  }, [clearTimer, routePath])

  useEffect(() => {
    if (pendingStep !== currentStep) return
    clearTimer()
    const completedStep = pendingStep
    const clearPendingTimer = setTimeout(() => {
      setPendingStep((value) => value === completedStep ? null : value)
    }, 0)
    return () => clearTimeout(clearPendingTimer)
  }, [clearTimer, currentStep, pendingStep])

  useEffect(() => clearTimer, [clearTimer])

  return {
    pendingStep: pendingStep === currentStep ? null : pendingStep,
    beginStepNavigation
  }
}

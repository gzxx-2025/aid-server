'use client'

import { useCallback, useEffect, useMemo } from 'react'
import { useRouter } from 'next/navigation'
import type { CreationStep } from '~/types'
import type { RouteLikeLocation } from '~/types/routeLike'
import {
  buildCreateFlowStepPrefetchHref,
  preloadCreateFlowStepClient,
  resolveCreateFlowStepPreloadOrder
} from '~/utils/createFlowStepPreload'

interface UseCreateFlowStepPreloadOptions {
  enabled: boolean
  currentStep: CreationStep
  stepKeys: CreationStep[]
  query: RouteLikeLocation['query']
}

export function useCreateFlowStepPreload({
  enabled,
  currentStep,
  stepKeys,
  query
}: UseCreateFlowStepPreloadOptions) {
  const router = useRouter()
  const hrefByStep = useMemo(
    () => new Map(stepKeys.map((step) => [step, buildCreateFlowStepPrefetchHref(step, query)])),
    [query, stepKeys]
  )

  const preloadStep = useCallback((step: CreationStep) => {
    const href = hrefByStep.get(step)
    if (!href) return
    router.prefetch(href)
    void preloadCreateFlowStepClient(step)
  }, [hrefByStep, router])

  useEffect(() => {
    if (!enabled || typeof window === 'undefined') return
    const order = resolveCreateFlowStepPreloadOrder(stepKeys, currentStep)

    // RSC 与目标 URL 数据交给 Next 调度；客户端大模块按邻近程度分批解析，避免阻塞首屏。
    order.forEach((step) => {
      const href = hrefByStep.get(step)
      if (href) router.prefetch(href)
    })
    order.slice(0, 2).forEach((step) => void preloadCreateFlowStepClient(step))

    const remaining = order.slice(2)
    if (!remaining.length) return
    const warmRemaining = () => {
      remaining.forEach((step) => void preloadCreateFlowStepClient(step))
    }
    const idleWindow = window as Window & {
      requestIdleCallback?: (callback: IdleRequestCallback, options?: IdleRequestOptions) => number
      cancelIdleCallback?: (handle: number) => void
    }
    if (typeof idleWindow.requestIdleCallback === 'function') {
      const idleId = idleWindow.requestIdleCallback(warmRemaining, { timeout: 800 })
      return () => idleWindow.cancelIdleCallback?.(idleId)
    }
    const timer = window.setTimeout(warmRemaining, 80)
    return () => window.clearTimeout(timer)
  }, [currentStep, enabled, hrefByStep, router, stepKeys])

  return preloadStep
}

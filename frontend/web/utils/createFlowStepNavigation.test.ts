/// <reference types="vite/client" />

import { describe, expect, it } from 'vitest'
import routeHookSource from '../hooks/useCreateFlowRouteAndSteps.ts?raw'
import {
  isCreateFlowStepPillDisabled,
  resolveCreateFlowStepClickAction
} from './createFlowStepNavigation'
import {
  CREATE_FLOW_STEP_ORDER,
  creationStepToRoutePath,
  resolveCreateFlowBackTarget,
  routePathToCreationStep
} from './createFlowRoutes'

describe('create-flow step navigation', () => {
  it('allows safe history navigation from a direct preview route with stale unlocked state', () => {
    const state = { currentRouteIndex: 6, unlockedStepIndex: 0, targetIndex: 1 }

    expect(resolveCreateFlowStepClickAction(state)).toBe('navigate')
    expect(
      isCreateFlowStepPillDisabled({
        ...state,
        stepStatusLoading: true,
        stepSubmitting: false
      })
    ).toBe(false)
  })

  it('does not directly unlock a step after the current route', () => {
    const state = { currentRouteIndex: 2, unlockedStepIndex: 0, targetIndex: 4 }

    expect(resolveCreateFlowStepClickAction(state)).toBe('advance')
    expect(
      isCreateFlowStepPillDisabled({
        ...state,
        stepStatusLoading: true,
        stepSubmitting: false
      })
    ).toBe(true)
  })

  it('blocks forward navigation during status loading even if cached state had unlocked it', () => {
    expect(
      isCreateFlowStepPillDisabled({
        currentRouteIndex: 2,
        unlockedStepIndex: 5,
        targetIndex: 4,
        stepStatusLoading: true,
        stepSubmitting: false
      })
    ).toBe(true)
  })

  it('blocks every pill while a serialized next-step submission is active', () => {
    expect(
      isCreateFlowStepPillDisabled({
        currentRouteIndex: 6,
        unlockedStepIndex: 0,
        targetIndex: 1,
        stepStatusLoading: false,
        stepSubmitting: true
      })
    ).toBe(true)
  })

  it('keeps every top-strip step mapped to its canonical route', () => {
    for (const step of CREATE_FLOW_STEP_ORDER) {
      const path = creationStepToRoutePath(step)
      expect(routePathToCreationStep(path)).toBe(step)
    }
    expect(routeHookSource).toContain('query: { ...routeRef.current.query }')
  })

  it('keeps preview return navigation aligned with its source and project type', () => {
    expect(
      resolveCreateFlowBackTarget({ path: '/create/preview', query: { from: 'works' } })
    ).toEqual({ type: 'path', path: '/works' })
    expect(
      resolveCreateFlowBackTarget(
        { path: '/create/preview', query: { from: 'works' } },
        { projectType: 'series' }
      )
    ).toEqual({ type: 'route', path: '/works', query: { tab: 'series' } })
    expect(resolveCreateFlowBackTarget({ path: '/create/preview', query: {} })).toEqual({
      type: 'path',
      path: '/'
    })
  })
})

'use client'

import { message } from 'antd'
import { useCallback,useRef } from 'react'
import { waitForCreationStoreHydrated } from '~/composables/useCreationStoreHydration'
import { useRouteLikeNavigator } from '~/composables/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import type { RouteLikeLocation } from '~/types/routeLike'
import {
clearStaleCreateFlowProjectContext,
isProjectMissingApiError,
shouldSkipFlowProjectScopedApis
} from '~/utils/createFlowProjectContext'
import {
hydrateCreationStoreFromProjectDetail,
resolveRouteProjectId
} from '~/utils/hydrateCreationStoreFromProjectDetail'

/**
 * 项目配置页 / 流程壳层：从 /api/user/project/detail 回显左侧基本信息与风格
 * （原 composables/useGlobalSettingProjectHydrate.ts）
 */
export function useGlobalSettingProjectHydrate() {
  const navigator = useRouteLikeNavigator()
  const hydratingRef = useRef(false)
  const hydrateGenRef = useRef(0)

  const hydrateFromProjectApi = useCallback(
    async (route: RouteLikeLocation, options?: { force?: boolean }): Promise<boolean> => {
      if (shouldSkipFlowProjectScopedApis(route)) return false

      const projectId = resolveRouteProjectId(route.query as Record<string, unknown>)
      if (!projectId) return false

      const gen = ++hydrateGenRef.current
      hydratingRef.current = true
      const creationStore = useCreationStore.getState()
      try {
        await waitForCreationStoreHydrated(creationStore, route)
        if (gen !== hydrateGenRef.current) return false
        /** 快速切换作品时旧 detail 可能后返回：世代失效则在写 store 前丢弃 */
        const detail = await hydrateCreationStoreFromProjectDetail(
          useCreationStore.getState(),
          projectId,
          {
            ...options,
            shouldApply: () => gen === hydrateGenRef.current
          }
        )
        return detail != null
      } catch (e: unknown) {
        if (isProjectMissingApiError(e)) {
          await clearStaleCreateFlowProjectContext({
            router: navigator,
            route,
            store: useCreationStore.getState()
          })
          return false
        }
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '加载项目信息失败，请稍后重试')
        return false
      } finally {
        if (gen === hydrateGenRef.current) {
          hydratingRef.current = false
        }
      }
    },
    [navigator]
  )

  return {
    hydratingRef,
    hydrateFromProjectApi
  }
}

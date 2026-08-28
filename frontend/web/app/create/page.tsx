'use client'

/**
 * 仅负责将 /create 重定向到具体步骤路由（原 pages/create/index.vue，definePageMeta layout:false）。
 * 原创作页大块逻辑在 app/create/layout.tsx 使用的 CreateFlowShell（业务已拆至 hooks）。
 */

import { useRouter } from 'next/navigation'
import { Suspense,useEffect,useRef } from 'react'
import { useRouteLike } from '~/hooks/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import { resolveFlowEpisodeIdFromRoute } from '~/utils/createFlowProjectContext'
import {
CREATE_FLOW_STEP_ORDER,
CREATE_SERIES_SCRIPT_UPLOAD_PATH,
creationStepToRoutePath
} from '~/utils/createFlowRoutes'
import { fetchCreationStepStatusOnce } from '~/utils/creationStepStatusOnce'

function CreateIndexRedirect() {
  const router = useRouter()
  const route = useRouteLike()
  const ranRef = useRef(false)

  useEffect(() => {
    if (ranRef.current) return
    ranRef.current = true

    const creationStore = useCreationStore.getState()
    const replaceWithQuery = (path: string) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(route.query)) {
        if (v == null) continue
        if (Array.isArray(v)) {
          for (const item of v) {
            if (item != null) qs.append(k, String(item))
          }
        } else {
          qs.set(k, String(v))
        }
      }
      router.replace(qs.toString() ? `${path}?${qs.toString()}` : path)
    }

    const routeProjectIdRaw = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
    const routeProjectId =
      Number.isFinite(routeProjectIdRaw) && routeProjectIdRaw > 0 ? routeProjectIdRaw : null
    const routeEpisodeId = resolveFlowEpisodeIdFromRoute(route, creationStore.currentProjectType)
    const routeEpisodeIdForApi = routeEpisodeId === null ? undefined : routeEpisodeId

    void (async () => {
      if (routeProjectId) {
        creationStore.setCurrentProjectContext({
          projectId: routeProjectId,
          ...(routeEpisodeId !== null ? { episodeId: routeEpisodeId } : {})
        })
        const stepInitAdvance =
          route.query.stepInitAdvance === '1' ||
          String(route.query.stepInitAdvance ?? '') === 'true'
        // 首页弹窗新建电影后：服务端 currentStep 可能仍为 1，若按状态重定向会回到项目配置；
        // 此处直接落到剧本创作，由壳层 fetchCreationStepStatus 配合 stepInitAdvance 同步服务端
        if (stepInitAdvance) {
          const storyScriptIndex = CREATE_FLOW_STEP_ORDER.indexOf('story-script')
          const idx = storyScriptIndex >= 0 ? storyScriptIndex : 1
          creationStore.setCurrentStepIndex(idx)
          replaceWithQuery(creationStepToRoutePath(CREATE_FLOW_STEP_ORDER[idx]!))
        } else {
          try {
            const status = await fetchCreationStepStatusOnce({
              projectId: routeProjectId,
              ...(routeEpisodeIdForApi !== undefined ? { episodeId: routeEpisodeIdForApi } : {})
            })
            const stepNumber = Number(status?.currentStep)
            let stepIndex = Number.isFinite(stepNumber)
              ? Math.min(Math.max(Math.floor(stepNumber) - 1, 0), CREATE_FLOW_STEP_ORDER.length - 1)
              : 0
            // 剧集不进入「项目配置」步骤页
            if (
              useCreationStore.getState().currentProjectType === 'series' &&
              CREATE_FLOW_STEP_ORDER[stepIndex] === 'global-setting'
            ) {
              stepIndex = CREATE_FLOW_STEP_ORDER.indexOf('story-script')
            }
            useCreationStore.getState().setCurrentStepIndex(stepIndex)
            replaceWithQuery(creationStepToRoutePath(CREATE_FLOW_STEP_ORDER[stepIndex]!))
          } catch {
            // 接口失败时保守回到剧本创作，避免沿用本地缓存导致串号
            const fallbackIndex = 1
            useCreationStore.getState().setCurrentStepIndex(fallbackIndex)
            replaceWithQuery(creationStepToRoutePath(CREATE_FLOW_STEP_ORDER[fallbackIndex]!))
          }
        }
      } else if (
        creationStore.currentProjectType === 'series' &&
        !creationStore.seriesFlowEnteredStoryScript
      ) {
        replaceWithQuery(CREATE_SERIES_SCRIPT_UPLOAD_PATH)
      } else {
        const idx = Math.min(
          Math.max(creationStore.currentStepIndex, 0),
          CREATE_FLOW_STEP_ORDER.length - 1
        )
        replaceWithQuery(creationStepToRoutePath(CREATE_FLOW_STEP_ORDER[idx]!))
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return <div className="create-route-redirect" style={{ minHeight: '40vh' }} aria-hidden="true" />
}

export default function CreateIndexPage() {
  return (
    <Suspense fallback={null}>
      <CreateIndexRedirect />
    </Suspense>
  )
}

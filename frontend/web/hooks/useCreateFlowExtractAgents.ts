'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { message } from 'antd'
import type { ExtractAgents, ExtractModalScope } from '~/components/steps/ExtractAgentModal'
import type { ExtractModelCodes } from '~/utils/extractAgentBiz'
import type { AssetExtractType } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import { useCreationStore } from '~/stores/creation'
import { useRouteLike } from '~/composables/useRouteLike'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { userAssetRpsList } from '~/utils/businessApi'
import { fetchFlowUserTaskList, filterUserTaskRowsForEpisode } from '~/utils/userTaskListFlowOnce'
import {
  isCreateFlowEmbeddedLibraryPanel,
  routePathToCreationStep
} from '~/utils/createFlowRoutes'
import {
  getExtractAutoOpenContextKey,
  isStoryScriptContentFilledForExtract,
  startExtractAssets as runStartExtractAssets,
  stopExtractAssets as runStopExtractAssets,
  stopExtractStreamForContextChange,
  tryResumeAssetExtractTrack,
  type ExtractAgentsRuntime
} from './createFlowExtractAgents/extractRun'

/**
 * 自动弹出提取弹窗的上下文（原 composables/useCreateFlowExtractAgents.ts）：
 * - current-route：已落在第三步路由（刷新/直达），仅当剧本有内容且场景/角色/道具在本地与后端均为空时弹
 * - step-click-scene-character：点击流程条进入第三步，同上
 * - after-advance-to-scene-character：从剧本创作「下一步」推进到素材准备，条件同上（与另两类一致）
 */
export type ExtractModalAutoOpenContext =
  | 'current-route'
  | 'step-click-scene-character'
  | 'after-advance-to-scene-character'

function store() {
  return useCreationStore.getState()
}

export function useCreateFlowExtractAgents() {
  const route = useRouteLike()
  const routeRef = useRef<RouteLikeLocation>(route)
  routeRef.current = route

  const [extractModalScope, setExtractModalScopeState] = useState<ExtractModalScope>('all')
  const extractModalScopeRef = useRef<ExtractModalScope>('all')
  const setExtractModalScope = useCallback((v: ExtractModalScope) => {
    extractModalScopeRef.current = v
    setExtractModalScopeState(v)
  }, [])

  /** 用户在本作品/剧集上下文、仍停留在第三步时手动关闭自动弹窗后，不再重复弹出；离开第三步或切换作品/剧集后清除 */
  const autoExtractModalSuppressedKeyRef = useRef<string | null>(null)
  /** 丢弃过期的 openExtractAgentModalIfNeeded 异步结果（切面板/作品时递增） */
  const openExtractModalGenerationRef = useRef(0)

  const runtimeRef = useRef<ExtractAgentsRuntime | null>(null)
  if (!runtimeRef.current) {
    runtimeRef.current = {
      extractStopRequested: { value: false },
      extractActiveTaskId: { value: null },
      extractStreamCloser: { value: null },
      extractStreamScopeKey: { value: null },
      extractFollowSession: { value: 0 },
      extractResumeGeneration: { value: 0 },
      getRoute: () => routeRef.current
    }
  }
  const runtime = runtimeRef.current

  const hasStoryScript = useCallback((): boolean => {
    return isStoryScriptContentFilledForExtract(store().formData.storyScript.content)
  }, [])

  /** 已有进行中的资产提取任务时不再自动弹出智能体弹窗（刷新后由列表页恢复 SSE 即可） */
  const hasOngoingAssetExtractTask = useCallback(async (): Promise<boolean> => {
    try {
      const ctx = await resolveStoryScriptSaveContext(store(), routeRef.current)
      if (!ctx) return false
      /**
       * 切步回来时用 mutate 写穿一次：避免提交后缓存仍是提取前列表，误判无进行中而弹窗。
       * 此路径非 restore 风暴，且与 schedule 并发会合并。
       * 剧集隔离：其它集的提取任务不影响本集弹窗判定。
       */
      const list = filterUserTaskRowsForEpisode(
        await fetchFlowUserTaskList(ctx.projectId, { intent: 'mutate' }),
        ctx.episodeId
      )
      const st = (s: unknown) => String(s ?? '').trim().toUpperCase()
      return list.some((t) => {
        if (t?.taskType !== 'asset_extract') return false
        const u = st(t.status)
        return (
          u === 'PENDING' ||
          u === 'PROCESSING' ||
          u === 'RUNNING' ||
          u === 'QUEUED' ||
          u === 'WAITING'
        )
      })
    } catch {
      return false
    }
  }, [])

  const isSceneCharacterPropEmpty = useCallback((): boolean => {
    const sc = store().formData.sceneCharacter
    const hasName = (arr: string[]) => arr.some((s) => typeof s === 'string' && s.trim().length > 0)
    return !hasName(sc.scenes) && !hasName(sc.characters) && !hasName(sc.props)
  }, [])

  /** 是否应自动弹出提取弹窗：本地与服务端的场景、角色、道具必须全部为空。 */
  const areAllStep3AssetRpsListsEmptyForAutoExtract = useCallback(async (): Promise<boolean> => {
    if (!isSceneCharacterPropEmpty()) return false

    const ctx = await resolveStoryScriptSaveContext(store(), routeRef.current)
    if (!ctx) return false

    try {
      const results = await Promise.all(
        (['scene', 'character', 'prop'] as AssetExtractType[]).map((assetType) =>
          userAssetRpsList({
            projectId: ctx.projectId,
            episodeId: ctx.episodeId,
            assetType
          })
        )
      )
      return results.every(({ rows }) => (rows?.length ?? 0) === 0)
    } catch {
      return false
    }
  }, [isSceneCharacterPropEmpty])

  /** stepInitAdvance 期间可能先落到素材准备再被回退到剧本，此时禁止自动弹窗避免闪现 */
  const hasPendingStepInitAdvance = useCallback((): boolean => {
    const q = routeRef.current.query
    return q.stepInitAdvance === '1' || String(q.stepInitAdvance ?? '') === 'true'
  }, [])

  const shouldAllowAutoExtractModalOpen = useCallback(
    (ctxKey: string): boolean => {
      const r = routeRef.current
      if (isCreateFlowEmbeddedLibraryPanel(r.query)) return false
      if (hasPendingStepInitAdvance()) return false
      if (autoExtractModalSuppressedKeyRef.current === ctxKey) return false
      /** 提取 UI 仍挂着（切步骤断 SSE 未 finish）或壳层仍在跟提取任务时，禁止再自动弹提取弹窗 */
      if (store().isExtractingAssets) return false
      if (store().getAssetExtractShellLiveTaskId()) return false
      // 最终必须以素材准备路由为准（防止异步完成时已回退到剧本制作）
      if (routePathToCreationStep(r.path) !== 'scene-character') return false
      return true
    },
    [hasPendingStepInitAdvance]
  )

  const openExtractAgentModalIfNeeded = useCallback(
    (context: ExtractModalAutoOpenContext = 'current-route') => {
      const gen = ++openExtractModalGenerationRef.current
      void (async () => {
        const ctxKeyAtStart = getExtractAutoOpenContextKey(routeRef.current)
        if (!shouldAllowAutoExtractModalOpen(ctxKeyAtStart)) return
        if (
          context === 'current-route' ||
          context === 'step-click-scene-character' ||
          context === 'after-advance-to-scene-character'
        ) {
          if (!store().step3AssetListSyncReady) return
          if (await hasOngoingAssetExtractTask()) {
            useCreationStore.setState({ showExtractAgentModal: false })
            return
          }
        }
        if (gen !== openExtractModalGenerationRef.current) return
        if (getExtractAutoOpenContextKey(routeRef.current) !== ctxKeyAtStart) return
        if (!shouldAllowAutoExtractModalOpen(ctxKeyAtStart)) return

        if (!hasStoryScript()) return

        const serverEmpty = await areAllStep3AssetRpsListsEmptyForAutoExtract()
        if (gen !== openExtractModalGenerationRef.current) return
        if (getExtractAutoOpenContextKey(routeRef.current) !== ctxKeyAtStart) return
        if (!shouldAllowAutoExtractModalOpen(ctxKeyAtStart)) return
        if (!serverEmpty) return

        // 服务端三列表均为空：以接口为准，清掉持久化遗留的本地名称，避免误拦弹窗
        if (!isSceneCharacterPropEmpty()) {
          store().updateSceneCharacterData({ scenes: [], characters: [], props: [] })
        }
        if (gen !== openExtractModalGenerationRef.current) return
        if (getExtractAutoOpenContextKey(routeRef.current) !== ctxKeyAtStart) return
        if (!shouldAllowAutoExtractModalOpen(ctxKeyAtStart)) return

        setExtractModalScope('all')
        store().setExtractModalActionMode('start')
        useCreationStore.setState({ showExtractAgentModal: true })
      })()
    },
    [
      areAllStep3AssetRpsListsEmptyForAutoExtract,
      hasOngoingAssetExtractTask,
      hasStoryScript,
      isSceneCharacterPropEmpty,
      setExtractModalScope,
      shouldAllowAutoExtractModalOpen
    ]
  )

  // ---- 原 watch(getExtractFlowContextKey())：作品/剧集/路由上下文变化 ----
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const ctxKey = [
    route.path,
    String(currentProjectId ?? ''),
    String(currentEpisodeId ?? ''),
    String(route.query.projectId ?? ''),
    String(route.query.id ?? ''),
    String(route.query.workId ?? ''),
    String(route.query.episodeId ?? '')
  ].join('|')
  const prevCtxKeyRef = useRef<string | null>(null)
  const ctxWatchBootstrappedRef = useRef(false)
  useEffect(() => {
    const prev = prevCtxKeyRef.current
    prevCtxKeyRef.current = ctxKey
    if (!ctxWatchBootstrappedRef.current) {
      ctxWatchBootstrappedRef.current = true
      return
    }
    if (prev && ctxKey !== prev) {
      stopExtractStreamForContextChange(runtime)
      autoExtractModalSuppressedKeyRef.current = null
      setTimeout(() => {
        void tryResumeAssetExtractTrack(runtime)
      }, 0)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ctxKey])

  // ---- 原 watch(showExtractAgentModal)：手动关闭后抑制本上下文的自动弹窗 ----
  const showExtractAgentModal = useCreationStore((s) => s.showExtractAgentModal)
  const prevShowExtractModalRef = useRef(showExtractAgentModal)
  useEffect(() => {
    const prev = prevShowExtractModalRef.current
    prevShowExtractModalRef.current = showExtractAgentModal
    if (prev === true && showExtractAgentModal === false && !store().isExtractingAssets) {
      autoExtractModalSuppressedKeyRef.current = getExtractAutoOpenContextKey(routeRef.current)
    }
  }, [showExtractAgentModal])

  // ---- 原 watch(path/panel/ctx, immediate)：离开第三步或进内嵌库时关闭弹窗 ----
  const panel = String(route.query.panel ?? '')
  const embeddedCtx = `${String(route.query.projectId ?? '')}:${String(route.query.id ?? '')}:${String(route.query.workId ?? '')}:${String(route.query.episodeId ?? '')}`
  useEffect(() => {
    const onSceneCharacter = routePathToCreationStep(route.path) === 'scene-character'
    const onEmbeddedLib = panel === 'works' || panel === 'assets'
    if (!onSceneCharacter || onEmbeddedLib) {
      openExtractModalGenerationRef.current++
      useCreationStore.setState({ showExtractAgentModal: false })
      autoExtractModalSuppressedKeyRef.current = null
    }
     
  }, [route.path, panel, embeddedCtx])

  // ---- 原 watch(panel)：从内嵌作品库回到流程时恢复提取 SSE ----
  const prevPanelRef = useRef<string | null>(null)
  useEffect(() => {
    const prev = prevPanelRef.current
    prevPanelRef.current = panel
    if (prev == null) return
    const wasEmbeddedLib = prev === 'works' || prev === 'assets'
    const nowFlow = panel !== 'works' && panel !== 'assets'
    if (!wasEmbeddedLib || !nowFlow) return
    void tryResumeAssetExtractTrack(runtime)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [panel])

  const handleOpenExtractModalFromScp = useCallback(
    (scope: Exclude<ExtractModalScope, 'all'>) => {
      if (!hasStoryScript()) {
        message.warning('请先添加剧本故事')
        return
      }
      setExtractModalScope(scope)
      store().setExtractModalActionMode('start')
      useCreationStore.setState({ showExtractAgentModal: true })
    },
    [hasStoryScript, setExtractModalScope]
  )

  const updateExtractAgents = useCallback((v: ExtractAgents) => {
    store().updateExtractAgents(v)
  }, [])

  const updateExtractModelCodes = useCallback((v: ExtractModelCodes) => {
    store().updateExtractModelCodes(v)
  }, [])

  const startExtractAssets = useCallback(
    (payload: {
      agents: ExtractAgents
      modelCodes: ExtractModelCodes
      manualModelPickByKind?: Partial<Record<AssetExtractType, boolean>>
      scope: ExtractModalScope
      overwrite?: boolean
    }) => {
      runStartExtractAssets(runtime, payload)
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  )

  const stopExtractAssets = useCallback(async () => {
    await runStopExtractAssets(runtime)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return {
    extractModalScope,
    setExtractModalScope,
    updateExtractAgents,
    updateExtractModelCodes,
    startExtractAssets,
    stopExtractAssets,
    openExtractAgentModalIfNeeded,
    handleOpenExtractModalFromScp
  }
}

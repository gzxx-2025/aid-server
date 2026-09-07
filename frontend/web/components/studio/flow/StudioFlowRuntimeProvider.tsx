'use client'

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Button, Result, Select, message } from 'antd'
import { useRouter, useSearchParams } from 'next/navigation'
import type { StudioFlowStep, StudioTask } from '@/types/studio'
import type { UserEpisodeRow } from '@/types/business-api'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import { waitForCreationStoreHydrated } from '@/hooks/creationStoreStep3Hydration'
import { applyStoryboardScriptPanelsFromApi } from '@/hooks/useCreateFlowStoryboardSync'
import {
  applyEpisodeRowToCreationStore,
  hydrateCreationStoreFromProjectDetail
} from '@/utils/hydrateCreationStoreFromProjectDetail'
import { resolveStoryScriptEditorHtmlAfterApiLoad } from '@/utils/htmlPlain'
import { setStoryScriptServerBaseline } from '@/utils/storyScriptPersistence'
import { mapStoryboardListRowToPanel } from '@/utils/storyboardPanelMap'
import { userEpisodeList } from '@/utils/businessApi'
import type { FlowUserTaskListIntent } from '@/utils/userTaskListFlowOnce'
import type { StoryScriptFlushResult } from '@/utils/storyScriptPersistence'
import {
  loadStudioFlowProject,
  mapStudioFlowUserTasks,
  preserveStudioFlowTasksAfterFailure,
  resolveStudioFlowEpisodeForStore,
  type LoadedStudioFlowProject
} from '@/utils/studio/studioFlowProject'
import {
  reconcileStudioFlowGraph,
  shouldApplyStudioFlowStoreSection
} from '@/utils/studio/studioFlowReconciler'
import {
  nextStudioFlowEditorMountRevision,
  STUDIO_FLOW_OPEN_EDITOR_EVENT,
  type StudioFlowOpenEditorDetail
} from '@/utils/studio/studioFlowEvents'
import { requestStudioFlowAdvancedModal } from '@/utils/studio/studioFlowAdvancedModal'
import { StudioFlowModalHost } from './composer/StudioFlowModalHost'
import { StudioFlowExportProvider } from './StudioFlowExportProvider'
import { isStudioFlowWorkspaceScopeReady } from '@/utils/studio/studioFlowWorkspace'
import { canonicalStudioFlowEpisodeId } from '@/utils/studio/studioFlowEntry'
import { CREATE_SERIES_EPISODE_LIST_PATH } from '@/utils/createFlowRoutes'
import {
  isStudioFlowRuntimeScopeReady,
  StudioFlowRuntimeScopeBoundary,
  type StudioFlowRuntimePhase as RuntimePhase
} from './StudioFlowRuntimeScopeBoundary'

export interface StudioFlowRuntimeValue {
  projectId: number
  episodeId: number | null
  readOnly: boolean
  phase: RuntimePhase
  errors: string[]
  refreshing: boolean
  refresh: (options?: { taskIntent?: FlowUserTaskListIntent; bypassBurst?: boolean }) => Promise<void>
  openEditor: (step: StudioFlowStep) => void
  activeEditorStep: StudioFlowStep | null
  editorMountRevision: number
  editorOpen: boolean
  closeEditor: () => Promise<void>
  registerStoryScriptFlush: (flush: (() => Promise<StoryScriptFlushResult>) | null) => void
  flushDirtyEditors: () => Promise<boolean>
}

const StudioFlowRuntimeContext = createContext<StudioFlowRuntimeValue | null>(null)

export function useStudioFlowRuntime(): StudioFlowRuntimeValue | null {
  return useContext(StudioFlowRuntimeContext)
}

export function StudioFlowRuntimeProvider({
  projectId,
  episodeId,
  workspaceScopeKey,
  readOnly = false,
  children
}: {
  projectId: number
  episodeId: number | null
  workspaceScopeKey: string
  readOnly?: boolean
  children: ReactNode
}) {
  const router = useRouter()
  const query = useSearchParams()
  const [phase, setPhase] = useState<RuntimePhase>('loading')
  const [errors, setErrors] = useState<string[]>([])
  const [refreshing, setRefreshing] = useState(false)
  const [activeEditorStep, setActiveEditorStep] = useState<StudioFlowStep | null>(null)
  const [editorOpen, setEditorOpen] = useState(false)
  const [editorMountRevision, setEditorMountRevision] = useState(0)
  const [episodes, setEpisodes] = useState<UserEpisodeRow[]>([])
  const [selectedEpisodeId, setSelectedEpisodeId] = useState<number | null>(null)
  const [resolvedEpisodeId, setResolvedEpisodeId] = useState<number | null>(episodeId)
  const [readyScopeKey, setReadyScopeKey] = useState<string | null>(null)
  const generationRef = useRef(0)
  const lastSuccessRef = useRef<LoadedStudioFlowProject | null>(null)
  const refreshRequestRef = useRef<{ scopeKey: string; promise: Promise<void> } | null>(null)
  const refreshBurstRef = useRef<{ scopeKey: string; completedAt: number } | null>(null)
  const storyScriptFlushRef = useRef<(() => Promise<StoryScriptFlushResult>) | null>(null)
  const workspaceScopeReady = useStudioUiStore((state) =>
    isStudioFlowWorkspaceScopeReady(state, workspaceScopeKey)
  )

  const applySnapshot = useCallback((snapshot: LoadedStudioFlowProject) => {
    const creation = useCreationStore.getState()
    const previous = lastSuccessRef.current
    const episodeForStore = resolveStudioFlowEpisodeForStore(snapshot, previous)
    creation.setCurrentProjectContext({ projectId, episodeId: snapshot.episodeId })
    creation.setCurrentProjectType(snapshot.projectType)
    creation.setWorkTitle(snapshot.title)
    if (episodeForStore) applyEpisodeRowToCreationStore(creation, episodeForStore)
    if (shouldApplyStudioFlowStoreSection(snapshot.failedSteps, 'story-script')) {
      const serverText = String(snapshot.source.script?.originalText ?? '')
      setStoryScriptServerBaseline(
        { projectId, episodeId: snapshot.episodeId },
        snapshot.source.script
      )
      creation.updateFormData({
        storyScript: {
          content: resolveStoryScriptEditorHtmlAfterApiLoad(
            serverText,
            creation.formData.storyScript.content || ''
          )
        }
      })
    }
    if (shouldApplyStudioFlowStoreSection(snapshot.failedSteps, 'storyboard-script')) {
      const panels = [...snapshot.source.storyboards]
        .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
        .map((row, index) => mapStoryboardListRowToPanel(row, index))
      applyStoryboardScriptPanelsFromApi(panels)
    }
    creation.setStep3AssetListSyncReady(true)

    useStudioUiStore.setState((state) => {
      const graph = reconcileStudioFlowGraph(
        { nodes: state.nodes, edges: state.edges },
        { nodes: snapshot.nodes, edges: snapshot.edges },
        {
          preserveMissingSteps: snapshot.failedSteps,
          preserveMissingAssetTypes: snapshot.failedAssetTypes,
          preserveTaskStatus: !snapshot.taskStateComplete
        }
      )
      return {
        ...graph,
        tasks: snapshot.taskStateComplete
          ? preserveActiveStudioNodeTasks(state.tasks, mapStudioFlowUserTasks(snapshot.source.tasks))
          : state.tasks,
        title: `AID Studio · ${snapshot.title}`
      }
    })
    return episodeForStore
  }, [projectId])

  const performRefresh = useCallback(async (options?: { taskIntent?: FlowUserTaskListIntent }) => {
    const generation = ++generationRef.current
    const isInitial = lastSuccessRef.current === null
    if (isInitial) setPhase('loading')
    else setRefreshing(true)
    try {
      await waitForCreationStoreHydrated(useCreationStore.getState())
      if (generation !== generationRef.current) return
      const creation = useCreationStore.getState()
      const projectChanged = Number(creation.currentProjectId ?? 0) !== projectId
      creation.setCurrentProjectContext({ projectId, episodeId })
      const project = await hydrateCreationStoreFromProjectDetail(useCreationStore.getState(), projectId, {
        force: !isInitial,
        shouldApply: () => generation === generationRef.current,
        explicitSeriesEpisodeId: episodeId,
        preservePreviousProjectStyle: !projectChanged
      })
      if (generation !== generationRef.current) return
      if (project && canonicalStudioFlowEpisodeId(project.projectType, episodeId) !== episodeId) {
        const next = new URLSearchParams(query.toString())
        next.set('episodeId', '0')
        router.replace(`/create/studio?${next.toString()}`)
        return
      }
      if (project?.projectType === 'series' && !episodeId) {
        const rows = await userEpisodeList({ projectId })
        if (generation !== generationRef.current) return
        const sorted = [...rows].sort((a, b) => (a.episodeNo ?? 0) - (b.episodeNo ?? 0))
        setEpisodes(sorted)
        setSelectedEpisodeId(null)
        setErrors([])
        setReadyScopeKey(workspaceScopeKey)
        setPhase('select-episode')
        return
      }
      const loaded = await loadStudioFlowProject(projectId, episodeId, {
        taskIntent: options?.taskIntent,
        project
      })
      if (generation !== generationRef.current) return
      const snapshot = preserveStudioFlowTasksAfterFailure(loaded, lastSuccessRef.current)
      const preservedEpisode = applySnapshot(snapshot)
      lastSuccessRef.current = preservedEpisode === snapshot.source.episode
        ? snapshot
        : { ...snapshot, source: { ...snapshot.source, episode: preservedEpisode } }
      setResolvedEpisodeId(snapshot.episodeId)
      setErrors(snapshot.errors)
      setReadyScopeKey(workspaceScopeKey)
      setPhase('ready')
      if (snapshot.episodeId > 0 && snapshot.episodeId !== episodeId) {
        const next = new URLSearchParams(query.toString())
        next.set('episodeId', String(snapshot.episodeId))
        router.replace(`/create/studio?${next.toString()}`)
      }
    } catch (error) {
      if (generation !== generationRef.current) return
      const message = error instanceof Error ? error.message : '流程数据加载失败'
      setErrors([message])
      if (!lastSuccessRef.current) {
        setReadyScopeKey(workspaceScopeKey)
        setPhase('error')
      }
    } finally {
      if (generation === generationRef.current) setRefreshing(false)
    }
  }, [applySnapshot, episodeId, projectId, query, router, workspaceScopeKey])

  const refresh = useCallback((options?: {
    taskIntent?: FlowUserTaskListIntent
    bypassBurst?: boolean
  }) => {
    const scopeKey = `${workspaceScopeKey}:${projectId}:${episodeId ?? 0}`
    const burstKey = `${scopeKey}:${options?.taskIntent ?? 'bootstrap'}`
    const running = refreshRequestRef.current
    if (running?.scopeKey === scopeKey) return running.promise

    const burst = refreshBurstRef.current
    if (!options?.bypassBurst && burst?.scopeKey === burstKey && Date.now() - burst.completedAt < 800) {
      return Promise.resolve()
    }

    const request: Promise<void> = performRefresh(options)
      .then(() => {
        refreshBurstRef.current = { scopeKey: burstKey, completedAt: Date.now() }
      })
      .finally(() => {
        if (refreshRequestRef.current?.promise === request) {
          refreshRequestRef.current = null
        }
      })
    refreshRequestRef.current = { scopeKey, promise: request }
    return request
  }, [episodeId, performRefresh, projectId, workspaceScopeKey])

  useEffect(() => {
    if (!workspaceScopeReady) return
    let active = true
    lastSuccessRef.current = null
    queueMicrotask(() => {
      if (!active) return
      setActiveEditorStep(null)
      setEditorOpen(false)
      setEditorMountRevision(0)
      setEpisodes([])
      setSelectedEpisodeId(null)
      setResolvedEpisodeId(episodeId)
      void refresh()
    })
    return () => {
      active = false
      generationRef.current += 1
    }
  }, [projectId, episodeId, refresh, workspaceScopeReady])

  useEffect(() => {
    const open = (event: Event) => {
      const detail = (event as CustomEvent<StudioFlowOpenEditorDetail>).detail
      if (!detail?.step) return
      if (detail.step === 'global-setting') {
        requestStudioFlowAdvancedModal({ kind: 'global-setting' })
        return
      }
      setEditorMountRevision((current) => nextStudioFlowEditorMountRevision(current, detail.remount))
      setActiveEditorStep(detail.step)
      setEditorOpen(true)
    }
    window.addEventListener(STUDIO_FLOW_OPEN_EDITOR_EVENT, open)
    return () => window.removeEventListener(STUDIO_FLOW_OPEN_EDITOR_EVENT, open)
  }, [])

  const registerStoryScriptFlush = useCallback((flush: (() => Promise<StoryScriptFlushResult>) | null) => {
    storyScriptFlushRef.current = flush
  }, [])

  const flushDirtyEditors = useCallback(async (): Promise<boolean> => {
    if (readOnly) return true
    const result = await storyScriptFlushRef.current?.() ?? 'clean'
    return result === 'clean' || result === 'saved'
  }, [readOnly])

  const closeEditor = useCallback(async () => {
    if (readOnly) {
      setEditorOpen(false)
      return
    }
    if (activeEditorStep === 'story-script' && !await flushDirtyEditors()) {
      message.error('剧本自动保存失败或存在内容冲突，请处理后再关闭')
      return
    }
    setEditorOpen(false)
    // 关闭编辑器只同步业务实体；任务列表复用当前快照，终态由 SSE 唯一刷新入口处理。
    await refresh({ taskIntent: 'read' })
  }, [activeEditorStep, flushDirtyEditors, readOnly, refresh])

  const openEditor = useCallback((step: StudioFlowStep) => {
    if (step === 'global-setting') {
      requestStudioFlowAdvancedModal({ kind: 'global-setting' })
      return
    }
    setActiveEditorStep(step)
    setEditorOpen(true)
  }, [])

  const value = useMemo<StudioFlowRuntimeValue>(() => ({
    projectId,
    episodeId: resolvedEpisodeId,
    readOnly,
    phase,
    errors,
    refreshing,
    refresh,
    openEditor,
    activeEditorStep,
    editorMountRevision,
    editorOpen,
    closeEditor,
    registerStoryScriptFlush,
    flushDirtyEditors
  }), [activeEditorStep, closeEditor, editorMountRevision, editorOpen, errors, flushDirtyEditors, openEditor, phase, projectId, readOnly, refresh, refreshing, registerStoryScriptFlush, resolvedEpisodeId])

  const enterSelectedEpisode = () => {
    if (!selectedEpisodeId) return
    const next = new URLSearchParams(query.toString())
    next.set('episodeId', String(selectedEpisodeId))
    router.replace(`/create/studio?${next.toString()}`)
  }

  const openEpisodeManagement = () => {
    const next = new URLSearchParams(query.toString())
    next.delete('episodeId')
    next.set('projectId', String(projectId))
    next.set('id', String(projectId))
    next.set('from', 'studio')
    router.push(`${CREATE_SERIES_EPISODE_LIST_PATH}?${next.toString()}`)
  }

  const runtimeScopeReady = isStudioFlowRuntimeScopeReady(
    workspaceScopeReady,
    readyScopeKey,
    workspaceScopeKey
  )
  if (!runtimeScopeReady || phase === 'loading') {
    return (
      <StudioFlowRuntimeScopeBoundary
        workspaceScopeReady={runtimeScopeReady}
        phase={phase}
      />
    )
  }
  if (phase === 'error') {
    return (
      <div className="studio-loading-screen">
        <Result
          status="error"
          title="流程画布数据加载失败"
          subTitle={errors.join('；')}
          extra={<Button type="primary" onClick={() => void refresh({ bypassBurst: true })}>局部重试</Button>}
        />
      </div>
    )
  }
  if (phase === 'select-episode') {
    return (
      <div className="studio-loading-screen studio-flow-episode-gate">
        <div className="studio-loading-screen__brand">AI·D <span>FLOW</span></div>
        <section className="studio-flow-episode-gate__panel" aria-labelledby="studio-flow-episode-title">
          <h2 id="studio-flow-episode-title">选择要进入的剧集</h2>
          <p>流程画布按具体剧集恢复剧本、素材、分镜、视频和配音。</p>
          {episodes.length ? (
            <div className="studio-flow-episode-gate__row">
              <Select
                className="studio-flow-episode-gate__select"
                value={selectedEpisodeId}
                placeholder="请选择剧集"
                onChange={(value) => setSelectedEpisodeId(value)}
                options={episodes.map((episode) => ({
                  value: episode.id,
                  label: `第 ${episode.episodeNo ?? '-'} 集 · ${episode.comicTitle || '未命名剧集'}`
                }))}
              />
              <Button
                type="primary"
                className="studio-flow-episode-gate__enter"
                disabled={!selectedEpisodeId}
                onClick={enterSelectedEpisode}
              >
                进入流程画布
              </Button>
            </div>
          ) : (
            <>
              <p role="alert">当前作品还没有可用剧集，请先在剧集管理中创建剧集。</p>
              <div className="studio-flow-episode-gate__actions">
                <Button onClick={openEpisodeManagement}>前往剧集管理</Button>
              </div>
            </>
          )}
        </section>
      </div>
    )
  }
  return (
    <StudioFlowRuntimeContext.Provider value={value}>
      <StudioFlowExportProvider pageReady={phase === 'ready'}>
        {children}
        {errors.length ? (
          <aside className="studio-flow-runtime-errors" role="alert" aria-live="polite">
            <span>部分流程数据暂未刷新：{errors.join('；')}</span>
            <Button size="small" onClick={() => void refresh({ taskIntent: 'mutate', bypassBurst: true })}>重试失败分区</Button>
          </aside>
        ) : null}
        <StudioFlowModalHost />
      </StudioFlowExportProvider>
    </StudioFlowRuntimeContext.Provider>
  )
}

/**
 * 业务快照刷新不能覆盖当前节点持有的 SSE 真相。进行中的节点任务优先保留，远端列表仅
 * 补充未被本地 SSE 跟随的任务；终态刷新后本地任务自然退出优先集合。
 */
function preserveActiveStudioNodeTasks(
  current: StudioTask[],
  remote: StudioTask[]
): StudioTask[] {
  const activeStatuses = new Set<StudioTask['status']>(['queued', 'running', 'waiting'])
  const activeLocal = current.filter((task) => Boolean(task.nodeId) && activeStatuses.has(task.status))
  if (!activeLocal.length) return remote
  const locallyFollowedRemoteIds = new Set(activeLocal.flatMap((task) => {
    const id = Number(task.remoteTaskId)
    return Number.isSafeInteger(id) && id > 0 ? [id] : []
  }))
  return [
    ...activeLocal,
    ...remote.filter((task) => !locallyFollowedRemoteIds.has(Number(task.remoteTaskId)))
  ].slice(0, 30)
}

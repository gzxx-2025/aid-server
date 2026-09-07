'use client'

import {
  applyNodeChanges as applyReactFlowNodeChanges,
  type NodeChange,
  type Viewport
} from '@xyflow/react'
import { create } from 'zustand'
import { DEFAULT_STUDIO_ZONES } from '@/config/studioMeta'
import type {
  StudioCanvasNode,
  StudioCanvasTool,
  StudioFlowEdge,
  StudioNodeData,
  StudioTask,
  StudioWorkspaceData,
  StudioWorkspaceSnapshot,
  StudioZone
} from '@/types/studio'
import { mergeStudioFlowBusinessForHistory } from '@/utils/studio/studioFlowWorkspace'

type SaveStatus = 'idle' | 'saving' | 'saved' | 'error'

interface StudioUiState {
  scopeKey: string
  title: string
  nodes: StudioCanvasNode[]
  edges: StudioFlowEdge[]
  zones: StudioZone[]
  viewport: Viewport
  tasks: StudioTask[]
  hydrated: boolean
  loading: boolean
  loadError: string
  selectedNodeIds: string[]
  activeNodeId: string | null
  canvasTool: StudioCanvasTool
  miniMapOpen: boolean
  hideEdges: boolean
  leftCollapsed: boolean
  rightCollapsed: boolean
  dirtyRevision: number
  saveStatus: SaveStatus
  savedAt: string
  saveError: string
  historyPast: StudioWorkspaceSnapshot[]
  historyFuture: StudioWorkspaceSnapshot[]
  transactionBaseline: StudioWorkspaceSnapshot | null
  beginLoad: (scopeKey: string) => void
  hydrate: (workspace: StudioWorkspaceData) => void
  setTitle: (title: string) => void
  setLoadError: (message: string) => void
  applyNodeChanges: (changes: NodeChange<StudioCanvasNode>[]) => void
  beginGraphTransaction: () => void
  commitGraphTransaction: () => void
  selectNodes: (ids: string[]) => void
  clearSelection: () => void
  updateNodeData: (id: string, patch: Partial<StudioNodeData>, recordHistory?: boolean) => void
  undo: () => void
  redo: () => void
  setViewport: (viewport: Viewport) => void
  setCanvasTool: (tool: StudioCanvasTool) => void
  setMiniMapOpen: (open: boolean) => void
  setHideEdges: (hide: boolean) => void
  setLeftCollapsed: (value: boolean) => void
  setRightCollapsed: (value: boolean) => void
  upsertTask: (task: StudioTask) => void
  patchTask: (id: string, patch: Partial<StudioTask>) => void
  removeTask: (id: string) => void
  markSaving: () => void
  markSaved: (savedAt: string, revision?: number) => void
  markSaveError: (message: string) => void
}

const emptyViewport: Viewport = { x: 38, y: 72, zoom: 0.78 }

function cleanSnapshot(
  state: Pick<StudioUiState, 'nodes' | 'edges' | 'zones'>
): StudioWorkspaceSnapshot {
  return {
    nodes: structuredClone(state.nodes).map((node) => ({ ...node, selected: false })),
    edges: structuredClone(state.edges).map((edge) => ({ ...edge, selected: false })),
    zones: structuredClone(state.zones)
  }
}

function snapshotEqual(a: StudioWorkspaceSnapshot, b: StudioWorkspaceSnapshot): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

function selectedState(nodes: StudioCanvasNode[]) {
  const selectedNodeIds = nodes.filter((node) => node.selected).map((node) => node.id)
  return { selectedNodeIds, activeNodeId: selectedNodeIds.at(-1) ?? null }
}

export const useStudioUiStore = create<StudioUiState>((set, get) => ({
  scopeKey: '',
  title: 'AID Studio',
  nodes: [],
  edges: [],
  zones: structuredClone(DEFAULT_STUDIO_ZONES),
  viewport: emptyViewport,
  tasks: [],
  hydrated: false,
  loading: true,
  loadError: '',
  selectedNodeIds: [],
  activeNodeId: null,
  canvasTool: 'select',
  miniMapOpen: false,
  hideEdges: false,
  leftCollapsed: true,
  rightCollapsed: true,
  dirtyRevision: 0,
  saveStatus: 'idle',
  savedAt: '',
  saveError: '',
  historyPast: [],
  historyFuture: [],
  transactionBaseline: null,

  beginLoad: (scopeKey) => set({
    scopeKey,
    title: 'AID Studio',
    nodes: [],
    edges: [],
    zones: structuredClone(DEFAULT_STUDIO_ZONES),
    viewport: emptyViewport,
    tasks: [],
    hydrated: false,
    loading: true,
    loadError: '',
    selectedNodeIds: [],
    activeNodeId: null,
    dirtyRevision: 0,
    saveStatus: 'idle',
    savedAt: '',
    saveError: '',
    historyPast: [],
    historyFuture: [],
    transactionBaseline: null
  }),

  hydrate: (workspace) => set({
    scopeKey: workspace.scopeKey,
    title: workspace.title || 'AID Studio',
    nodes: structuredClone(workspace.nodes ?? []).map((node) => ({ ...node, selected: false })),
    edges: structuredClone(workspace.edges ?? []),
    zones: workspace.zones?.length
      ? structuredClone(workspace.zones)
      : structuredClone(DEFAULT_STUDIO_ZONES),
    viewport: workspace.viewport ?? emptyViewport,
    tasks: workspace.tasks ?? [],
    hydrated: true,
    loading: false,
    loadError: '',
    selectedNodeIds: [],
    activeNodeId: null,
    dirtyRevision: 0,
    saveStatus: 'idle',
    savedAt: workspace.savedAt ?? '',
    saveError: '',
    historyPast: [],
    historyFuture: [],
    transactionBaseline: null
  }),

  setTitle: (title) => set({ title }),
  setLoadError: (loadError) => set({ loading: false, loadError }),

  applyNodeChanges: (changes) => set((state) => {
    if (!changes.length) return state
    const allowed = changes.filter((change) => change.type !== 'remove')
    const nodes = applyReactFlowNodeChanges(allowed, state.nodes)
    return { nodes, ...selectedState(nodes) }
  }),

  beginGraphTransaction: () => {
    if (get().transactionBaseline) return
    set({ transactionBaseline: cleanSnapshot(get()) })
  },

  commitGraphTransaction: () => {
    const state = get()
    const baseline = state.transactionBaseline
    if (!baseline) return
    const current = cleanSnapshot(state)
    if (snapshotEqual(baseline, current)) {
      set({ transactionBaseline: null })
      return
    }
    set({
      transactionBaseline: null,
      historyPast: [...state.historyPast, baseline].slice(-50),
      historyFuture: [],
      dirtyRevision: state.dirtyRevision + 1
    })
  },

  selectNodes: (ids) => set((state) => {
    const selected = new Set(ids)
    const nodes = state.nodes.map((node) => ({ ...node, selected: selected.has(node.id) }))
    return { nodes, selectedNodeIds: [...ids], activeNodeId: ids.at(-1) ?? null }
  }),

  clearSelection: () => set((state) => ({
    nodes: state.nodes.map((node) => node.selected ? { ...node, selected: false } : node),
    selectedNodeIds: [],
    activeNodeId: null
  })),

  updateNodeData: (id, patch, recordHistory = false) => set((state) => {
    const baseline = cleanSnapshot(state)
    const nodes = state.nodes.map((node) => node.id === id
      ? { ...node, data: { ...node.data, ...patch, updatedAt: new Date().toISOString() } }
      : node)
    return {
      nodes,
      dirtyRevision: state.dirtyRevision + 1,
      ...(recordHistory
        ? { historyPast: [...state.historyPast, baseline].slice(-50), historyFuture: [] }
        : {})
    }
  }),

  undo: () => {
    const state = get()
    const previous = state.historyPast.at(-1)
    if (!previous) return
    const current = cleanSnapshot(state)
    const target = mergeStudioFlowBusinessForHistory(current, previous)
    set({
      ...target,
      selectedNodeIds: [],
      activeNodeId: null,
      historyPast: state.historyPast.slice(0, -1),
      historyFuture: [current, ...state.historyFuture].slice(0, 50),
      dirtyRevision: state.dirtyRevision + 1
    })
  },

  redo: () => {
    const state = get()
    const next = state.historyFuture[0]
    if (!next) return
    const current = cleanSnapshot(state)
    const target = mergeStudioFlowBusinessForHistory(current, next)
    set({
      ...target,
      selectedNodeIds: [],
      activeNodeId: null,
      historyPast: [...state.historyPast, current].slice(-50),
      historyFuture: state.historyFuture.slice(1),
      dirtyRevision: state.dirtyRevision + 1
    })
  },

  setViewport: (viewport) => set((state) => ({
    viewport,
    dirtyRevision: state.dirtyRevision + 1
  })),
  setCanvasTool: (canvasTool) => set({ canvasTool }),
  setMiniMapOpen: (miniMapOpen) => set({ miniMapOpen }),
  setHideEdges: (hideEdges) => set({ hideEdges }),
  setLeftCollapsed: (leftCollapsed) => set({ leftCollapsed }),
  setRightCollapsed: (rightCollapsed) => set({ rightCollapsed }),

  upsertTask: (task) => set((state) => ({
    tasks: [task, ...state.tasks.filter((item) => item.id !== task.id)].slice(0, 30)
  })),
  patchTask: (id, patch) => set((state) => ({
    tasks: state.tasks.map((task) => task.id === id
      ? { ...task, ...patch, updatedAt: new Date().toISOString() }
      : task)
  })),
  removeTask: (id) => set((state) => ({
    tasks: state.tasks.filter((task) => task.id !== id)
  })),

  markSaving: () => set({ saveStatus: 'saving', saveError: '' }),
  markSaved: (savedAt, revision) => set((state) => ({
    saveStatus: 'saved',
    savedAt,
    saveError: '',
    dirtyRevision: revision != null && state.dirtyRevision === revision ? 0 : state.dirtyRevision
  })),
  markSaveError: (saveError) => set({ saveStatus: 'error', saveError })
}))

export function selectStudioTasks(state: StudioUiState): StudioTask[] {
  return state.tasks
}

export function createStudioTask(
  title: string,
  options: Partial<StudioTask> = {}
): StudioTask {
  const now = new Date().toISOString()
  return {
    id: options.id ?? `task-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
    title,
    nodeId: options.nodeId,
    runId: options.runId,
    stage: options.stage ?? '准备中',
    progress: options.progress ?? 0,
    status: options.status ?? 'queued',
    errorMessage: options.errorMessage,
    remoteTaskId: options.remoteTaskId,
    remoteTaskType: options.remoteTaskType,
    completedNodeIds: options.completedNodeIds,
    createdAt: options.createdAt ?? now,
    updatedAt: now
  }
}

export function getStudioWorkspaceData(): StudioWorkspaceData {
  const state = useStudioUiStore.getState()
  return {
    version: 3,
    scopeKey: state.scopeKey,
    title: state.title,
    ...cleanSnapshot(state),
    viewport: structuredClone(state.viewport),
    tasks: [],
    savedAt: state.savedAt
  }
}

'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { message } from 'antd'
import {
  Background,
  BackgroundVariant,
  MiniMap,
  PanOnScrollMode,
  ReactFlow,
  ReactFlowProvider,
  SelectionMode,
  type EdgeTypes,
  type NodeTypes,
  type ProOptions,
  type XYPosition
} from '@xyflow/react'
import { useStudioFlowNodeDockPosition } from '@/hooks/studio/useStudioFlowNodeDockPosition'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioFlowEdge } from '@/types/studio'
import { edgeColor } from '@/utils/studio/studioGraph'
import { filterStudioFlowCanvasVisibleNodes } from '@/utils/studio/studioFlowCanvasProjection'
import { openStudioFlowBoundEntity } from '@/utils/studio/studioFlowEntityEditor'
import { StudioCanvasControls } from './StudioCanvasControls'
import { StudioCanvasToolbar } from './StudioCanvasToolbar'
import { StudioEnergyEdge } from './StudioEnergyEdge'
import {
  StudioFlowNodeComposerDock,
  type StudioFlowNodeComposerDockHandle
} from './StudioFlowNodeComposerDock'
import { StudioFlowPaneContextMenu } from './StudioFlowPaneContextMenu'
import { StudioNodeCard } from './StudioNodeCard'
import '@/components/studio/composer/studio-composer.css'

const nodeTypes: NodeTypes = { studioNode: StudioNodeCard }
const edgeTypes: EdgeTypes = { studioEnergy: StudioEnergyEdge }
const proOptions: ProOptions = { hideAttribution: true }

function StudioCanvasInner({
  onSave,
  readOnly = false
}: {
  onSave?: () => void | Promise<void>
  readOnly?: boolean
}) {
  const nodes = useStudioUiStore((state) => state.nodes)
  const edges = useStudioUiStore((state) => state.edges)
  const viewport = useStudioUiStore((state) => state.viewport)
  const canvasTool = useStudioUiStore((state) => state.canvasTool)
  const miniMapOpen = useStudioUiStore((state) => state.miniMapOpen)
  const hideEdges = useStudioUiStore((state) => state.hideEdges)
  const activeNodeId = useStudioUiStore((state) => state.activeNodeId)
  const saveStatus = useStudioUiStore((state) => state.saveStatus)
  const historyPast = useStudioUiStore((state) => state.historyPast)
  const applyNodeChanges = useStudioUiStore((state) => state.applyNodeChanges)
  const beginGraphTransaction = useStudioUiStore((state) => state.beginGraphTransaction)
  const commitGraphTransaction = useStudioUiStore((state) => state.commitGraphTransaction)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const clearSelection = useStudioUiStore((state) => state.clearSelection)
  const setViewport = useStudioUiStore((state) => state.setViewport)
  const undo = useStudioUiStore((state) => state.undo)
  const redo = useStudioUiStore((state) => state.redo)
  const canvasRef = useRef<HTMLDivElement>(null)
  const nodeDockRef = useRef<StudioFlowNodeComposerDockHandle>(null)
  const [paneMenu, setPaneMenu] = useState<XYPosition | null>(null)
  const [spacePanning, setSpacePanning] = useState(false)

  const visibleNodes = useMemo(() => filterStudioFlowCanvasVisibleNodes(nodes), [nodes])
  const visibleIds = useMemo(() => new Set(visibleNodes.map((node) => node.id)), [visibleNodes])
  const visibleEdges = useMemo(
    () => hideEdges ? [] : prepareStudioVisibleEdges(edges, visibleIds, activeNodeId),
    [activeNodeId, edges, hideEdges, visibleIds]
  )
  const activeNode = visibleNodes.find((node) => node.id === activeNodeId)
  const scheduleNodeDockPosition = useStudioFlowNodeDockPosition({
    canvasRef,
    dockRef: nodeDockRef,
    node: activeNode
  })

  const openNode = useCallback((nodeId: string) => {
    const node = useStudioUiStore.getState().nodes.find((item) => item.id === nodeId)
    const binding = node?.data.flowBinding
    if (!binding) return
    const creation = useCreationStore.getState()
    if (!openStudioFlowBoundEntity(
      binding,
      creation.formData.storyboardScript.panels,
      creation.currentProjectId
    )) {
      message.warning('未找到对应的流程实体，请刷新后重试')
    }
  }, [])

  const openPaneMenu = useCallback((client: XYPosition) => {
    const rect = canvasRef.current?.getBoundingClientRect()
    setPaneMenu(clampCanvasMenuPosition(client, rect, { width: 220, height: 150 }))
  }, [])

  const closeMenus = useCallback(() => setPaneMenu(null), [])

  useEffect(() => {
    const releaseSpace = (event: KeyboardEvent) => {
      if (event.code === 'Space') setSpacePanning(false)
    }
    const cancelSpacePan = () => setSpacePanning(false)
    window.addEventListener('keyup', releaseSpace)
    window.addEventListener('blur', cancelSpacePan)
    return () => {
      window.removeEventListener('keyup', releaseSpace)
      window.removeEventListener('blur', cancelSpacePan)
    }
  }, [])

  return (
    <div
      ref={canvasRef}
      className={`studio-canvas${spacePanning ? ' is-space-panning' : ''}`}
      role="region"
      aria-label="AID Studio 流程画布"
      tabIndex={0}
      onPointerDownCapture={(event) => {
        if (
          paneMenu
          && event.target instanceof Element
          && !event.target.closest('.studio-pane-context-menu')
        ) closeMenus()
      }}
      onKeyDown={(event) => {
        if (event.code === 'Space' && !isEditableTarget(event.target)) {
          event.preventDefault()
          pauseMediaTarget(event.target)
          setSpacePanning(true)
        }
        if (event.key === 'Escape') clearSelection()
        if (!(event.ctrlKey || event.metaKey)) return
        if (event.key.toLowerCase() === 's') {
          event.preventDefault()
          void onSave?.()
        }
        if (event.key.toLowerCase() === 'z') {
          event.preventDefault()
          if (event.shiftKey) redo()
          else undo()
        }
      }}
      onKeyUp={(event) => {
        if (event.code !== 'Space' || isEditableTarget(event.target)) return
        event.preventDefault()
        setSpacePanning(false)
      }}
    >
      <StudioCanvasToolbar />
      <StudioCanvasControls />
      <ReactFlow
        nodes={visibleNodes}
        edges={visibleEdges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        defaultViewport={viewport}
        minZoom={0.08}
        maxZoom={2.2}
        zoomOnScroll={false}
        zoomOnPinch
        panOnScroll
        panOnScrollMode={PanOnScrollMode.Vertical}
        zoomActivationKeyCode="Control"
        zoomOnDoubleClick={false}
        nodesDraggable={!readOnly}
        nodesConnectable={false}
        elementsSelectable
        selectionOnDrag={canvasTool === 'select' && !spacePanning}
        panOnDrag={canvasTool === 'pan' || spacePanning ? true : [1]}
        panActivationKeyCode="Space"
        selectionMode={SelectionMode.Partial}
        deleteKeyCode={null}
        noDragClassName="nodrag"
        noPanClassName="nopan"
        noWheelClassName="nowheel"
        onlyRenderVisibleElements
        colorMode="dark"
        proOptions={proOptions}
        onNodesChange={applyNodeChanges}
        onNodeClick={(_, node) => {
          closeMenus()
          selectNodes([node.id])
        }}
        onNodeDoubleClick={(_, node) => openNode(node.id)}
        onNodeDragStart={readOnly ? undefined : () => {
          canvasRef.current?.classList.add('studio-canvas--dragging-nodes')
          beginGraphTransaction()
        }}
        onNodeDrag={readOnly ? undefined : scheduleNodeDockPosition}
        onNodeDragStop={readOnly ? undefined : () => {
          canvasRef.current?.classList.remove('studio-canvas--dragging-nodes')
          commitGraphTransaction()
          scheduleNodeDockPosition()
        }}
        onNodeContextMenu={readOnly ? undefined : (event, node) => {
          event.preventDefault()
          selectNodes([node.id])
          openPaneMenu({ x: event.clientX, y: event.clientY })
        }}
        onPaneContextMenu={readOnly ? undefined : (event) => {
          event.preventDefault()
          openPaneMenu({ x: event.clientX, y: event.clientY })
        }}
        onPaneClick={() => {
          closeMenus()
          clearSelection()
        }}
        onMoveStart={closeMenus}
        onMove={scheduleNodeDockPosition}
        onMoveEnd={(_, nextViewport) => {
          setViewport(nextViewport)
          scheduleNodeDockPosition()
        }}
      >
        <Background color="rgba(118, 126, 142, .26)" gap={24} size={1} variant={BackgroundVariant.Dots} />
        {miniMapOpen ? (
          <MiniMap
            className="studio-canvas-minimap"
            pannable
            zoomable
            nodeColor="rgba(184,192,204,.55)"
            maskColor="rgba(7,11,18,.72)"
          />
        ) : null}
      </ReactFlow>
      {!visibleNodes.length ? (
        <div className="studio-flow-canvas-empty" role="status">
          <strong>当前步骤暂无可展示内容</strong>
          <span>可从左侧流程步骤开始创作，完成的业务实体会自动出现在画布中。</span>
        </div>
      ) : null}
      {!readOnly && paneMenu ? (
        <StudioFlowPaneContextMenu
          client={paneMenu}
          canUndo={historyPast.length > 0}
          saving={saveStatus === 'saving'}
          platform={typeof navigator !== 'undefined' && /Mac/i.test(navigator.platform) ? 'mac' : 'win'}
          onUndo={undo}
          onSave={() => void onSave?.()}
          onClose={closeMenus}
        />
      ) : null}
      {!readOnly && activeNode ? (
        <StudioFlowNodeComposerDock
          key={activeNode.id}
          ref={nodeDockRef}
          node={activeNode}
          onClose={clearSelection}
        />
      ) : null}
      <div className="studio-canvas-hud" aria-hidden="true">
        拖拽节点调整布局 <i /> Space 临时抓手 <i /> Ctrl+滚轮缩放 <i /> 双击节点打开编辑器
      </div>
    </div>
  )
}

export function StudioCanvas(props: {
  onSave?: () => void | Promise<void>
  readOnly?: boolean
}) {
  return (
    <ReactFlowProvider>
      <StudioCanvasInner {...props} />
    </ReactFlowProvider>
  )
}

export function clampCanvasMenuPosition(
  client: XYPosition,
  rect: DOMRect | undefined,
  menu: { width: number; height: number }
): XYPosition {
  if (!rect) return client
  return {
    x: Math.max(8, Math.min(client.x - rect.left, rect.width - menu.width - 8)),
    y: Math.max(52, Math.min(client.y - rect.top, rect.height - menu.height - 8))
  }
}

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return Boolean(target.closest('input, textarea, select, [contenteditable="true"]'))
}

function pauseMediaTarget(target: EventTarget | null) {
  if (typeof HTMLMediaElement === 'undefined' || !(target instanceof Element)) return
  const media = target instanceof HTMLMediaElement
    ? target
    : target.closest<HTMLMediaElement>('video, audio')
  media?.pause()
}

export function prepareStudioVisibleEdges(
  edges: StudioFlowEdge[],
  visibleNodeIds: ReadonlySet<string>,
  activeNodeId: string | null
): StudioFlowEdge[] {
  return edges
    .filter((edge) => visibleNodeIds.has(edge.source) && visibleNodeIds.has(edge.target))
    .map((edge) => {
      const kind = edge.data?.kind ?? 'flow'
      const related = Boolean(activeNodeId && (edge.source === activeNodeId || edge.target === activeNodeId))
      return {
        ...edge,
        type: 'studioEnergy',
        animated: false,
        data: { ...edge.data, kind, label: edge.data?.label ?? '', energy: related },
        style: {
          ...edge.style,
          stroke: edgeColor(kind),
          strokeWidth: related ? 2 : 1.35,
          opacity: activeNodeId && !related ? 0.24 : 0.78
        }
      }
    })
}

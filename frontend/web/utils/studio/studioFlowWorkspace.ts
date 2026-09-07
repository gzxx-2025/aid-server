import type {
  StudioCanvasNode,
  StudioWorkspaceData,
  StudioWorkspaceSnapshot
} from '@/types/studio'

export function isStudioFlowWorkspaceScopeReady(
  state: { hydrated: boolean; scopeKey: string },
  expectedScopeKey: string
): boolean {
  return state.hydrated && state.scopeKey === expectedScopeKey
}

/** 流程画布仅持久化实体绑定键和布局，业务内容始终来自服务端。 */
export function serializeStudioFlowLayoutWorkspace(workspace: StudioWorkspaceData): StudioWorkspaceData {
  return {
    ...workspace,
    nodes: workspace.nodes.map(toFlowLayoutNode),
    edges: workspace.edges
      .filter((edge) => !edge.data?.flowManaged)
      .map((edge) => structuredClone(edge)),
    tasks: []
  }
}

/** 撤销和重做只能改变布局，不能用历史快照覆盖最新业务数据。 */
export function mergeStudioFlowBusinessForHistory(
  current: StudioWorkspaceSnapshot,
  target: StudioWorkspaceSnapshot
): StudioWorkspaceSnapshot {
  const layoutByBinding = new Map(target.nodes.flatMap((node) => node.data.flowBinding
    ? [[node.data.flowBinding.bindingKey, node] as const]
    : []))
  const nodes = current.nodes.map((node) => {
    const binding = node.data.flowBinding
    const layout = binding ? layoutByBinding.get(binding.bindingKey) : undefined
    if (!layout) return { ...structuredClone(node), selected: false }
    return {
      ...structuredClone(node),
      position: structuredClone(layout.position),
      width: layout.width,
      height: layout.height,
      measured: layout.measured ? structuredClone(layout.measured) : undefined,
      hidden: layout.hidden,
      zIndex: layout.zIndex,
      selected: false
    }
  })
  const survivingIds = new Set(nodes.map((node) => node.id))
  const managedEdges = current.edges.filter((edge) =>
    edge.data?.flowManaged && survivingIds.has(edge.source) && survivingIds.has(edge.target)
  )
  return {
    nodes,
    edges: structuredClone(managedEdges),
    zones: structuredClone(target.zones)
  }
}

function toFlowLayoutNode(node: StudioCanvasNode): StudioCanvasNode {
  const binding = node.data.flowBinding
  if (!binding) return structuredClone(node)
  return {
    ...structuredClone(node),
    selected: false,
    data: {
      kind: node.data.kind,
      title: '',
      status: 'empty',
      prompt: '',
      zoneId: node.data.zoneId,
      flowBinding: structuredClone(binding),
      flowLayoutOnly: true,
      createdAt: node.data.createdAt,
      updatedAt: node.data.updatedAt
    }
  }
}

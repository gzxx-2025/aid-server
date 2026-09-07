import type {
  StudioCanvasNode,
  StudioFlowBinding,
  StudioFlowEdge,
  StudioFlowStep
} from '@/types/studio'
import { placeNewStudioNodesWithoutOverlap } from './studioNodePlacement'

export interface StudioFlowGraphSnapshot {
  nodes: StudioCanvasNode[]
  edges: StudioFlowEdge[]
}

/**
 * 服务端快照覆盖流程业务字段；本地只保留流程实体的布局。
 */
export function reconcileStudioFlowGraph(
  current: StudioFlowGraphSnapshot,
  incoming: StudioFlowGraphSnapshot,
  options?: {
    preserveMissingSteps?: Iterable<StudioFlowStep>
    preserveMissingAssetTypes?: Iterable<NonNullable<StudioFlowBinding['assetType']>>
    preserveTaskStatus?: boolean
  }
): StudioFlowGraphSnapshot {
  const preserveMissingSteps = new Set(options?.preserveMissingSteps)
  const preserveMissingAssetTypes = new Set(options?.preserveMissingAssetTypes)
  const shouldPreserveBinding = (binding: StudioFlowBinding | undefined): boolean => {
    if (!binding || binding.role !== 'item') return false
    // 素材三类由独立接口加载；单类失败不能阻止另外两类正常更新和删除。
    if (binding.step === 'scene-character') {
      return Boolean(binding.assetType && preserveMissingAssetTypes.has(binding.assetType))
    }
    return preserveMissingSteps.has(binding.step)
  }
  const incomingBindings = new Set(incoming.nodes.flatMap((node) =>
    node.data.flowBinding ? [node.data.flowBinding.bindingKey] : []
  ))
  const directExistingByBinding = new Map(
    current.nodes.flatMap((node) => node.data.flowBinding
      ? [[node.data.flowBinding.bindingKey, node] as const]
      : [])
  )
  const existingByBinding = new Map(directExistingByBinding)
  current.nodes.forEach((node) => {
    if (node.data.flowBinding) return
    const key = resolveLegacyStudioFlowBindingKey(node, incoming.nodes)
    if (key && !existingByBinding.has(key)) existingByBinding.set(key, node)
  })
  const incomingIdToResolvedId = new Map<string, string>()
  const newFlowNodeIds = new Set<string>()
  const flowNodes = incoming.nodes.map((serverNode) => {
    const key = serverNode.data.flowBinding?.bindingKey
    const local = key ? existingByBinding.get(key) : undefined
    if (!local) {
      incomingIdToResolvedId.set(serverNode.id, serverNode.id)
      newFlowNodeIds.add(serverNode.id)
      return { ...serverNode, selected: false }
    }
    if (shouldPreserveBinding(serverNode.data.flowBinding)) {
      incomingIdToResolvedId.set(serverNode.id, local.id)
      return local.data.flowLayoutOnly
        ? mergeServerNodeWithLocalLayout(serverNode, local, unavailableFlowData(serverNode.data))
        : local
    }
    incomingIdToResolvedId.set(serverNode.id, local.id)
    return mergeServerNodeWithLocalLayout(serverNode, local, {
      // task/list 局部失败时任务面板继续保留旧快照，节点也必须保留同一
      // 轮任务投影（含 failed），不能被 tasks=[] 构造出的业务基础状态覆盖。
      status: options?.preserveTaskStatus && !local.data.flowLayoutOnly
        ? local.data.status
        : serverNode.data.status,
      flowLayoutOnly: undefined
    })
  })
  const preservedFailedStepNodes = current.nodes.flatMap((node) => {
    const binding = node.data.flowBinding
    if (!binding || !shouldPreserveBinding(binding) || incomingBindings.has(binding.bindingKey)) return []
    return [node.data.flowLayoutOnly
      ? { ...node, data: { ...node.data, ...unavailableFlowData(node.data) } }
      : node]
  })
  let nodes = [...preservedFailedStepNodes, ...flowNodes]
  if (newFlowNodeIds.size) {
    nodes = placeNewStudioNodesWithoutOverlap(nodes, (node) => newFlowNodeIds.has(node.id))
  }
  const survivingIds = new Set(nodes.map((node) => node.id))
  const preservedFailedStepIds = new Set(preservedFailedStepNodes.map((node) => node.id))

  const incomingManagedBindings = new Set(incoming.edges.flatMap((edge) =>
    edge.data?.flowManaged && edge.data.bindingKey ? [edge.data.bindingKey] : []
  ))
  const preservedManagedEdges = current.edges.filter((edge) =>
    edge.data?.flowManaged &&
    (!edge.data.bindingKey || !incomingManagedBindings.has(edge.data.bindingKey)) &&
    survivingIds.has(edge.source) &&
    survivingIds.has(edge.target) &&
    (preservedFailedStepIds.has(edge.source) || preservedFailedStepIds.has(edge.target))
  )
  const existingManagedByBinding = new Map(
    current.edges.flatMap((edge) => edge.data?.flowManaged && edge.data.bindingKey
      ? [[edge.data.bindingKey, edge] as const]
      : [])
  )
  const managedEdges = incoming.edges.flatMap((edge) => {
    const source = incomingIdToResolvedId.get(edge.source)
    const target = incomingIdToResolvedId.get(edge.target)
    if (!source || !target || !survivingIds.has(source) || !survivingIds.has(target)) return []
    const key = edge.data?.bindingKey
    const local = key ? existingManagedByBinding.get(key) : undefined
    return [{
      ...edge,
      id: local?.id ?? edge.id,
      source,
      target,
      selected: false
    }]
  })
  return {
    nodes,
    edges: [...preservedManagedEdges, ...managedEdges]
  }
}

function mergeServerNodeWithLocalLayout(
  serverNode: StudioCanvasNode,
  local: StudioCanvasNode,
  dataOverrides: Partial<StudioCanvasNode['data']>
): StudioCanvasNode {
  const localComposerData = local.data.flowLayoutOnly ? {} : {
    ...(local.data.model !== undefined ? { model: local.data.model } : {}),
    ...(local.data.modelName !== undefined ? { modelName: local.data.modelName } : {}),
    ...(local.data.modelCostCredits !== undefined
      ? { modelCostCredits: local.data.modelCostCredits }
      : {}),
    ...(local.data.modelIsFree !== undefined ? { modelIsFree: local.data.modelIsFree } : {}),
    ...(local.data.generationOptions !== undefined
      ? { generationOptions: local.data.generationOptions }
      : {}),
    ...(local.data.composerRefs !== undefined ? { composerRefs: local.data.composerRefs } : {}),
    ...(local.data.composerSkillId !== undefined
      ? { composerSkillId: local.data.composerSkillId }
      : {}),
    ...(local.data.composerAgentCode !== undefined
      ? { composerAgentCode: local.data.composerAgentCode }
      : {}),
    ...(local.data.composerAgentName !== undefined
      ? { composerAgentName: local.data.composerAgentName }
      : {}),
    ...(local.data.negativePrompt !== undefined
      ? { negativePrompt: local.data.negativePrompt }
      : {}),
    recommendedDurationSeconds: serverNode.data.recommendedDurationSeconds !== undefined
      ? serverNode.data.recommendedDurationSeconds
      : local.data.recommendedDurationSeconds,
    recommendedDurationSource: serverNode.data.recommendedDurationSource !== undefined
      ? serverNode.data.recommendedDurationSource
      : local.data.recommendedDurationSource,
    recommendedDurationDescription: serverNode.data.recommendedDurationDescription !== undefined
      ? serverNode.data.recommendedDurationDescription
      : local.data.recommendedDurationDescription
  }
  return {
    ...serverNode,
    id: local.id,
    position: local.position,
    measured: local.measured,
    width: local.width,
    height: local.height,
    hidden: local.hidden,
    zIndex: local.zIndex,
    selected: local.selected,
    data: {
      ...local.data,
      ...serverNode.data,
      ...dataOverrides,
      // 模型、参数和 Composer 引用是本次画布会话的提交配置；业务刷新不能把
      // 用户刚选择的值重置为 createStudioNode 的空值或默认值。
      ...localComposerData,
      composerAgentOpen: local.data.composerAgentOpen
    }
  }
}

function unavailableFlowData(data: StudioCanvasNode['data']): Partial<StudioCanvasNode['data']> {
  const binding = data.flowBinding
  const assetLabel = binding?.assetType === 'character'
    ? '角色'
    : binding?.assetType === 'scene'
      ? '场景'
      : binding?.assetType === 'prop'
        ? '道具'
        : '流程内容'
  return {
    title: data.title || `${assetLabel}${binding?.serverId ? ` #${binding.serverId}` : ''}`,
    subtitle: '该分区暂未刷新，请重试',
    prompt: '',
    resultSummary: '',
    mediaUrl: undefined,
    flowAssetForms: undefined,
    status: 'failed',
    errorMessage: '该分区暂未刷新，请重试。',
    flowLayoutOnly: true
  }
}

/** 旧 flow bridge 业务节点不能当成画布专属节点永久保留。 */
export function isLegacyStudioFlowBusinessNode(node: StudioCanvasNode): boolean {
  const key = String(node.data.bindingKey || '')
  return key === 'storyScript.content' ||
    /^sceneCharacter\.(characters|scenes|props)\.\d+$/.test(key) ||
    /^storyboardScript\.panels\./.test(key) ||
    /^storyboardResult\./.test(key)
}

export function resolveLegacyStudioFlowBindingKey(
  node: StudioCanvasNode,
  incomingNodes: StudioCanvasNode[]
): string | null {
  const legacyKey = String(node.data.bindingKey || '')
  if (!isLegacyStudioFlowBusinessNode(node)) return null
  if (legacyKey === 'storyScript.content') {
    return incomingNodes.find((item) =>
      item.data.flowBinding?.role === 'item' && item.data.flowBinding.entityType === 'story_script'
    )?.data.flowBinding?.bindingKey ?? null
  }
  const storyboardMatch = /^storyboardScript\.panels\.(.+)$/.exec(legacyKey)
  if (storyboardMatch) {
    return resolveLegacyStoryboardBinding('storyboard', storyboardMatch[1], node, incomingNodes)
  }
  const videoMatch = /^storyboardResult\.(.+)$/.exec(legacyKey)
  if (videoMatch) {
    return resolveLegacyStoryboardBinding('storyboard_video', videoMatch[1], node, incomingNodes)
  }
  const assetType = node.data.imagePurpose === 'character' || node.data.imagePurpose === 'scene' || node.data.imagePurpose === 'prop'
    ? node.data.imagePurpose
    : null
  if (!assetType) return null
  const numericAssetId = Number(node.data.assetId)
  if (Number.isSafeInteger(numericAssetId) && numericAssetId > 0) {
    const key = `flow:rps:${assetType}:${numericAssetId}`
    return incomingNodes.some((item) => item.data.flowBinding?.bindingKey === key) ? key : null
  }
  const title = node.data.title.trim()
  if (!title) return null
  const matches = incomingNodes.filter((item) =>
    item.data.flowBinding?.entityType === 'rps_asset' &&
    item.data.flowBinding.assetType === assetType &&
    item.data.title.trim() === title
  )
  return matches.length === 1 ? matches[0].data.flowBinding!.bindingKey : null
}

function resolveLegacyStoryboardBinding(
  entityType: 'storyboard' | 'storyboard_video',
  legacyId: string,
  node: StudioCanvasNode,
  incomingNodes: StudioCanvasNode[]
): string | null {
  const candidates = new Set([legacyId, String(node.data.storyboardId || '')].filter(Boolean))
  const matches = incomingNodes.filter((item) => {
    const binding = item.data.flowBinding
    return binding?.entityType === entityType && (
      candidates.has(String(binding.serverId || '')) ||
      candidates.has(String(item.data.storyboardId || ''))
    )
  })
  return matches.length === 1 ? matches[0].data.flowBinding!.bindingKey : null
}

export function shouldApplyStudioFlowStoreSection(
  failedSteps: Iterable<StudioFlowStep>,
  step: StudioFlowStep
): boolean {
  return !new Set(failedSteps).has(step)
}

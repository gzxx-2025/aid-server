import type {
  ScriptDetailRow,
  UserAssetRpsFormRow,
  UserAssetRpsRow,
  UserEpisodeRow,
  UserProjectRow,
  UserTaskDetailData,
  UserTaskRow,
  UserStoryboardListRow
} from '@/types/business-api'
import type {
  StudioCanvasNode,
  StudioFlowBinding,
  StudioFlowEdge,
  StudioFlowStep,
  StudioImagePurpose,
  StudioNodeStatus,
  StudioTask,
  StudioTaskStatus
} from '@/types/studio'
import {
  userAssetRpsList,
  userEpisodeDetail,
  userProjectDetail,
  userScriptDetailByProject,
  userStoryboardList,
  userTaskDetailCached
} from '@/utils/businessApi'
import { sortUserAssetRpsRows } from '@/utils/business/rps'
import { skipsStoryboardImageGeneration } from '@/utils/creationModeUiRules'
import { useStudioUiStore } from '@/stores/studioUi'
import { packStudioNodesWithoutOverlap } from '@/utils/studio/studioNodePlacement'
import { fetchFlowUserTaskList, filterUserTaskRowsForEpisode } from '@/utils/userTaskListFlowOnce'
import type { FlowUserTaskListIntent } from '@/utils/userTaskListFlowOnce'
import { createStudioEdge, createStudioNode } from './studioGraph'

export interface StudioFlowProjectSource {
  project: UserProjectRow
  episode: UserEpisodeRow | null
  script: ScriptDetailRow | null
  assets: Record<'character' | 'scene' | 'prop', UserAssetRpsRow[]>
  storyboards: UserStoryboardListRow[]
  tasks: UserTaskRow[]
}

export interface LoadedStudioFlowProject {
  title: string
  projectType: UserProjectRow['projectType']
  episodeId: number
  complete: boolean
  errors: string[]
  failedSteps: StudioFlowStep[]
  failedAssetTypes: Array<NonNullable<StudioFlowBinding['assetType']>>
  taskStateComplete: boolean
  source: StudioFlowProjectSource
  nodes: StudioCanvasNode[]
  edges: StudioFlowEdge[]
}

export function resolveStudioFlowEpisodeForStore(
  snapshot: LoadedStudioFlowProject,
  previous: LoadedStudioFlowProject | null
): UserEpisodeRow | null {
  if (snapshot.projectType !== 'series') return null
  if (snapshot.source.episode) return snapshot.source.episode
  if (!snapshot.failedSteps.includes('preview')) return null
  if (previous?.projectType !== 'series' || previous.episodeId !== snapshot.episodeId) return null
  return previous.source.episode
}

/** task/list 局部失败时沿用同 scope 上一次任务真相，并用新业务实体重建投影。 */
export function preserveStudioFlowTasksAfterFailure(
  snapshot: LoadedStudioFlowProject,
  previous: LoadedStudioFlowProject | null
): LoadedStudioFlowProject {
  if (
    snapshot.taskStateComplete ||
    !previous ||
    Number(previous.source.project.id) !== Number(snapshot.source.project.id) ||
    previous.episodeId !== snapshot.episodeId
  ) return snapshot
  const source = { ...snapshot.source, tasks: previous.source.tasks }
  return {
    ...snapshot,
    source,
    ...buildStudioFlowProjectGraph(source, snapshot.episodeId)
  }
}

export function shouldRefreshStudioFlowBusinessAfterTaskTransition(
  previousTasks: UserTaskRow[],
  nextTasks: UserTaskRow[]
): boolean {
  const previousById = new Map(previousTasks.flatMap((task) => {
    const id = Number(task.id)
    return Number.isSafeInteger(id) && id > 0 ? [[id, task] as const] : []
  }))
  return nextTasks.some((task) => {
    const id = Number(task.id)
    if (!Number.isSafeInteger(id) || id <= 0) return false
    const nextStatus = mapUserTaskStatusToStudioTaskStatus(task.status)
    if (nextStatus !== 'succeeded' && nextStatus !== 'failed' && nextStatus !== 'cancelled') return false
    const previous = previousById.get(id)
    if (!previous) return true
    const previousStatus = mapUserTaskStatusToStudioTaskStatus(previous.status)
    return previousStatus === 'queued' || previousStatus === 'running'
  })
}

type SettledSource = [
  PromiseSettledResult<ScriptDetailRow | null>,
  PromiseSettledResult<{ rows: UserAssetRpsRow[] }>,
  PromiseSettledResult<{ rows: UserAssetRpsRow[] }>,
  PromiseSettledResult<{ rows: UserAssetRpsRow[] }>,
  PromiseSettledResult<UserStoryboardListRow[]>,
  PromiseSettledResult<UserTaskRow[]>,
  PromiseSettledResult<UserEpisodeRow | null>
]

/**
 * 仅组合现有七步流程接口。项目详情是必需真相；其余接口局部失败时保留已成功快照，
 * 由 Runtime 暴露重试，不把接口失败解释成服务端删除。
 */
export async function loadStudioFlowProject(
  projectId: number,
  requestedEpisodeId: number | null,
  options?: { taskIntent?: FlowUserTaskListIntent; project?: UserProjectRow | null }
): Promise<LoadedStudioFlowProject> {
  const project = options?.project ?? await userProjectDetail(projectId)
  const episodeId = project.projectType === 'movie' ? 0 : normalizeEpisodeId(requestedEpisodeId)
  if (project.projectType === 'series' && episodeId === null) {
    throw new Error('剧集作品必须先选择具体剧集。')
  }
  const resolvedEpisodeId = episodeId ?? 0
  const settled = await Promise.allSettled([
    userScriptDetailByProject({ projectId, episodeId: resolvedEpisodeId }),
    userAssetRpsList({ projectId, episodeId: resolvedEpisodeId, assetType: 'character' }),
    userAssetRpsList({ projectId, episodeId: resolvedEpisodeId, assetType: 'scene' }),
    userAssetRpsList({ projectId, episodeId: resolvedEpisodeId, assetType: 'prop' }),
    userStoryboardList({ projectId, episodeId: resolvedEpisodeId }),
    loadStudioFlowTasks(projectId, project.projectType, resolvedEpisodeId, options?.taskIntent ?? 'bootstrap'),
    project.projectType === 'series'
      ? userEpisodeDetail({ id: resolvedEpisodeId })
      : Promise.resolve(null)
  ] as const) as SettledSource
  const episodeCandidate = fulfilledValue(settled[6]) ?? null
  const episodeScopeMismatch = Boolean(
    project.projectType === 'series' &&
    episodeCandidate &&
    Number(episodeCandidate.projectId) !== projectId
  )
  const source: StudioFlowProjectSource = {
    project,
    episode: episodeScopeMismatch ? null : episodeCandidate,
    script: fulfilledValue(settled[0]) ?? null,
    assets: {
      character: fulfilledValue(settled[1])?.rows ?? [],
      scene: fulfilledValue(settled[2])?.rows ?? [],
      prop: fulfilledValue(settled[3])?.rows ?? []
    },
    storyboards: fulfilledValue(settled[4]) ?? [],
    tasks: fulfilledValue(settled[5]) ?? []
  }
  const errors = settled.flatMap((result, index) => {
    if (result.status === 'fulfilled') return []
    return [`${SOURCE_LABELS[index]}：${errorMessage(result.reason)}`]
  })
  if (episodeScopeMismatch) errors.push('剧集：所选剧集不属于当前作品')
  const failedSteps = Array.from(new Set(settled.flatMap((result, index) =>
    result.status === 'rejected' ? FAILED_SOURCE_STEPS[index] : []
  ).concat(episodeScopeMismatch ? ['preview'] : [])))
  const failedAssetTypes = RPS_SOURCE_ASSET_TYPES.flatMap(({ index, assetType }) =>
    settled[index].status === 'rejected' ? [assetType] : []
  )
  return {
    title: project.projectName,
    projectType: project.projectType,
    episodeId: resolvedEpisodeId,
    complete: errors.length === 0,
    errors,
    failedSteps,
    failedAssetTypes,
    taskStateComplete: settled[5].status === 'fulfilled',
    source,
    ...buildStudioFlowProjectGraph(source, resolvedEpisodeId)
  }
}

export async function loadStudioFlowTasks(
  projectId: number,
  projectType: UserProjectRow['projectType'],
  episodeId: number,
  intent: FlowUserTaskListIntent = 'mutate'
): Promise<UserTaskRow[]> {
  const rows = await fetchFlowUserTaskList(projectId, { intent })
  const scopedRows = filterStudioFlowTasksForScope(rows, projectType, episodeId)
  return enrichStudioFlowTasksForProjection(scopedRows, {
    projectId,
    projectType,
    episodeId
  })
}

interface StudioFlowTaskProjectionScope {
  projectId: number
  projectType: UserProjectRow['projectType']
  episodeId: number
}

interface StudioFlowTaskOwnership {
  inputSnapshot: string
  taskType?: string
  status: string
}

type StudioFlowTaskDetailLoader = (taskId: number) => Promise<UserTaskDetailData | null>

const STUDIO_FLOW_TASK_DETAIL_CONCURRENCY = 4
const STUDIO_FLOW_TASK_OWNERSHIP_CACHE_LIMIT = 256
const studioFlowTaskOwnershipCache = new Map<string, StudioFlowTaskOwnership>()

/**
 * `/api/user/task/list` 是不含 inputSnapshot 的摘要。仅为需要精确投影到内容节点的近期任务
 * 补查既有 task/detail；不遍历全部历史任务，也不把单个详情失败升级为整个任务分区失败。
 */
export async function enrichStudioFlowTasksForProjection(
  rows: UserTaskRow[],
  scope: StudioFlowTaskProjectionScope,
  loadDetail: StudioFlowTaskDetailLoader = (taskId) => userTaskDetailCached(taskId)
): Promise<UserTaskRow[]> {
  const enriched = rows.map((row) => {
    const snapshot = normalizedTaskSnapshot(row.inputSnapshot)
    if (snapshot) {
      rememberStudioFlowTaskOwnership(row, scope, snapshot)
      return row
    }
    const taskId = positiveNumber(row.id)
    if (taskId == null) return row
    const cached = studioFlowTaskOwnershipCache.get(studioFlowTaskOwnershipCacheKey(scope, taskId))
    if (!cached) return row
    const current = {
      ...cached,
      status: String(row.status ?? '').trim().toUpperCase()
    }
    rememberStudioFlowTaskOwnership(row, scope, current.inputSnapshot, current)
    return mergeStudioFlowTaskOwnership(row, current)
  })
  const candidates = enriched
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => shouldLoadStudioFlowTaskDetail(row))
    .sort((left, right) => compareTaskFreshness(right.row, left.row))

  await runWithConcurrency(candidates, STUDIO_FLOW_TASK_DETAIL_CONCURRENCY, async ({ row, index }) => {
    const taskId = positiveNumber(row.id)
    if (taskId == null) return
    const cacheKey = studioFlowTaskOwnershipCacheKey(scope, taskId)
    const previous = studioFlowTaskOwnershipCache.get(cacheKey)
    const status = String(row.status ?? '').trim().toUpperCase()
    if (previous) {
      enriched[index] = mergeStudioFlowTaskOwnership(row, previous)
      return
    }

    let detail: UserTaskDetailData | null = null
    try {
      detail = await loadDetail(taskId)
    } catch {
      detail = null
    }
    const ownership = validStudioFlowTaskDetailOwnership(detail, taskId, scope)
    if (!ownership) return
    rememberStudioFlowTaskOwnership(row, scope, ownership.inputSnapshot, {
      taskType: ownership.taskType,
      status
    })
    enriched[index] = mergeStudioFlowTaskOwnership(row, ownership)
  })

  return enriched
}

/**
 * 批量形态图刚提交时，用本地已知的 formIds 预填任务归属。
 * 列表接口无 inputSnapshot，详情偶发只带「当前形态」时，避免投影落到错误节点。
 */
export function seedStudioFlowTaskFormIdTargets(input: {
  projectId: number
  projectType: UserProjectRow['projectType'] | string
  episodeId: number
  taskId: number
  formIds: number[]
  taskType?: string
}): void {
  const taskId = positiveNumber(input.taskId)
  if (taskId == null) return
  const formIds = [...new Set(
    input.formIds
      .map((id) => Number(id))
      .filter((id) => Number.isFinite(id) && id > 0)
  )]
  if (!formIds.length) return
  const scope: StudioFlowTaskProjectionScope = {
    projectId: input.projectId,
    projectType: input.projectType === 'series' ? 'series' : 'movie',
    episodeId: input.episodeId
  }
  const key = studioFlowTaskOwnershipCacheKey(scope, taskId)
  studioFlowTaskOwnershipCache.delete(key)
  studioFlowTaskOwnershipCache.set(key, {
    inputSnapshot: JSON.stringify({ formIds }),
    taskType: String(input.taskType || 'FORM_IMAGE_BATCH').trim() || 'FORM_IMAGE_BATCH',
    status: 'PROCESSING'
  })
  while (studioFlowTaskOwnershipCache.size > STUDIO_FLOW_TASK_OWNERSHIP_CACHE_LIMIT) {
    const oldest = studioFlowTaskOwnershipCache.keys().next().value
    if (oldest == null) break
    studioFlowTaskOwnershipCache.delete(oldest)
  }
}

/** 按已提交 formIds 立刻把对应素材节点标为 generating，避免空节点无反馈 */
export function markStudioFlowRpsNodesGeneratingByFormIds(formIds: number[]): void {
  const targets = new Set(
    formIds
      .map((id) => Number(id))
      .filter((id) => Number.isFinite(id) && id > 0)
  )
  if (!targets.size) return
  useStudioUiStore.setState((state) => ({
    nodes: state.nodes.map((node) => {
      if (node.data.flowBinding?.entityType !== 'rps_asset') return node
      const hit = (node.data.flowAssetForms ?? []).some((form) => targets.has(Number(form.formId)))
      if (!hit) return node
      if (node.data.status === 'generating' || node.data.status === 'queued') return node
      return {
        ...node,
        data: {
          ...node.data,
          status: 'generating' as const
        }
      }
    })
  }))
}

function shouldLoadStudioFlowTaskDetail(row: UserTaskRow): boolean {
  if (normalizedTaskSnapshot(row.inputSnapshot)) return false
  if (!taskTypeCanTargetStudioFlowEntity(row.taskType)) return false
  const status = mapUserTaskStatusToStudioTaskStatus(row.status)
  return status === 'queued' || status === 'running' || status === 'failed'
}

function taskTypeCanTargetStudioFlowEntity(taskType: string | null | undefined): boolean {
  const type = String(taskType ?? '').trim().toUpperCase()
  return /(STORYBOARD|ASSET|RPS|CHARACTER|SCENE|PROP|FORM|EXTRACT|AUDIO|DUBB|VOICE|COMPOSE|LIP)/.test(type)
}

function validStudioFlowTaskDetailOwnership(
  detail: UserTaskDetailData | null,
  taskId: number,
  scope: StudioFlowTaskProjectionScope
): StudioFlowTaskOwnership | null {
  if (!detail || Number(detail.taskId) !== taskId) return null
  if (detail.projectId != null && Number(detail.projectId) !== scope.projectId) return null
  if (
    scope.projectType === 'series' &&
    (detail.episodeId == null || Number(detail.episodeId) !== scope.episodeId)
  ) return null
  if (
    scope.projectType === 'movie' &&
    detail.episodeId != null && Number(detail.episodeId) > 0
  ) return null
  const inputSnapshot = normalizedTaskSnapshot(detail.inputSnapshot)
  if (!inputSnapshot) return null
  return {
    inputSnapshot,
    taskType: String(detail.taskType ?? '').trim() || undefined,
    status: String(detail.status ?? '').trim().toUpperCase()
  }
}

function normalizedTaskSnapshot(snapshot: string | null | undefined): string {
  return String(snapshot ?? '').trim()
}

function studioFlowTaskOwnershipCacheKey(scope: StudioFlowTaskProjectionScope, taskId: number): string {
  return `${scope.projectId}:episode-${scope.projectType === 'movie' ? 0 : scope.episodeId}:task-${taskId}`
}

function rememberStudioFlowTaskOwnership(
  row: UserTaskRow,
  scope: StudioFlowTaskProjectionScope,
  inputSnapshot: string,
  overrides?: { taskType?: string; status?: string }
): void {
  const taskId = positiveNumber(row.id)
  if (taskId == null) return
  const key = studioFlowTaskOwnershipCacheKey(scope, taskId)
  studioFlowTaskOwnershipCache.delete(key)
  const fallbackTaskType = String(row.taskType ?? '').trim() || undefined
  studioFlowTaskOwnershipCache.set(key, {
    inputSnapshot,
    taskType: overrides?.taskType ?? fallbackTaskType,
    status: overrides?.status ?? String(row.status ?? '').trim().toUpperCase()
  })
  while (studioFlowTaskOwnershipCache.size > STUDIO_FLOW_TASK_OWNERSHIP_CACHE_LIMIT) {
    const oldest = studioFlowTaskOwnershipCache.keys().next().value
    if (oldest == null) break
    studioFlowTaskOwnershipCache.delete(oldest)
  }
}

function mergeStudioFlowTaskOwnership(
  row: UserTaskRow,
  ownership: Pick<StudioFlowTaskOwnership, 'inputSnapshot' | 'taskType'>
): UserTaskRow {
  return {
    ...row,
    taskType: ownership.taskType || row.taskType,
    inputSnapshot: ownership.inputSnapshot
  }
}

async function runWithConcurrency<T>(
  items: T[],
  concurrency: number,
  worker: (item: T) => Promise<void>
): Promise<void> {
  let cursor = 0
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (cursor < items.length) {
      const item = items[cursor]
      cursor += 1
      await worker(item)
    }
  }))
}

export function resetStudioFlowTaskOwnershipCacheForTest(): void {
  studioFlowTaskOwnershipCache.clear()
}

export function filterStudioFlowTasksForScope(
  rows: UserTaskRow[],
  projectType: UserProjectRow['projectType'],
  episodeId: number
): UserTaskRow[] {
  if (projectType === 'series') {
    return rows.filter((row) => Number(row.episodeId) === episodeId)
  }
  return filterUserTaskRowsForEpisode(rows, 0)
}

const SOURCE_LABELS = ['剧本', '角色', '场景', '道具', '分镜', '任务', '剧集'] as const
const FAILED_SOURCE_STEPS: StudioFlowStep[][] = [
  ['story-script'],
  ['scene-character'],
  ['scene-character'],
  ['scene-character'],
  ['storyboard-script', 'storyboard-video', 'dubbing'],
  [],
  ['preview']
]
const RPS_SOURCE_ASSET_TYPES = [
  { index: 1, assetType: 'character' },
  { index: 2, assetType: 'scene' },
  { index: 3, assetType: 'prop' }
] as const

const STEP_LAYOUT: Record<StudioFlowStep, { x: number; width: number }> = {
  'global-setting': { x: 0, width: 330 },
  'story-script': { x: 370, width: 330 },
  'scene-character': { x: 740, width: 620 },
  'storyboard-script': { x: 1400, width: 620 },
  'storyboard-video': { x: 2060, width: 620 },
  dubbing: { x: 2720, width: 330 },
  preview: { x: 3090, width: 330 }
}

export function buildStudioFlowProjectGraph(source: StudioFlowProjectSource, scopeEpisodeId: number): {
  nodes: StudioCanvasNode[]
  edges: StudioFlowEdge[]
} {
  const { project, episode, script, tasks } = source
  const projectId = project.id
  const episodeId = project.projectType === 'movie' ? 0 : scopeEpisodeId
  const skipStoryboardImage = skipsStoryboardImageGeneration(project.defaultCreationMode)
  const nodes = createStepNodes(source, episodeId)
  const edges: StudioFlowEdge[] = []
  const scriptText = String(script?.originalText ?? '').trim()
  const scriptNode = createBoundNode('text', 'story-script', 0, {
    title: '故事剧本',
    subtitle: scriptText ? '服务端剧本正文' : '尚未创建剧本',
    prompt: scriptText,
    resultSummary: scriptText,
    status: taskAwareStatus(scriptText ? 'success' : 'empty', tasks, 'story-script', { singleton: true }),
    flowBinding: binding({
      role: 'item', step: 'story-script', entityType: 'story_script', projectId, episodeId,
      serverId: script?.id, bindingKey: `flow:script:${projectId}:${episodeId}`
    })
  })
  nodes.push(scriptNode)

  const sortedAssets = (Object.entries(source.assets) as Array<[
    'character' | 'scene' | 'prop', UserAssetRpsRow[]
  ]>).flatMap(([assetType, rows]) => sortUserAssetRpsRows(rows).map((asset) => ({ assetType, asset })))
  const assetNodes = sortedAssets.map(({ assetType, asset }, index) => {
    const prompt = assetPrompt(asset)
    const mediaUrl = assetImage(asset)
    return createBoundNode('image', 'scene-character', index, {
      title: asset.assetName || `${assetType} ${index + 1}`,
      subtitle: asset.forms?.length ? `${asset.forms.length} 个形态` : '尚未添加形态',
      prompt,
      resultSummary: prompt,
      status: taskAwareStatus(mediaUrl ? 'success' : 'empty', tasks, 'scene-character', {
        entityType: 'rps_asset',
        serverId: asset.id,
        formIds: (asset.forms ?? [])
          .map((form) => Number(form.id))
          .filter((id) => Number.isFinite(id) && id > 0)
      }),
      imagePurpose: assetType as StudioImagePurpose,
      mediaKind: mediaUrl ? 'image' : undefined,
      mediaUrl,
      flowAssetForms: assetFormsPreview(asset),
      assetId: String(asset.id),
      flowBinding: binding({
        role: 'item', step: 'scene-character', entityType: 'rps_asset', projectId, episodeId,
        serverId: asset.id, assetType, bindingKey: `flow:rps:${assetType}:${asset.id}`
      })
    })
  })
  nodes.push(...assetNodes)

  const sortedStoryboards = [...source.storyboards]
    .sort((a, b) => (a.sortOrder ?? a.id) - (b.sortOrder ?? b.id))
  const storyboardNodes: StudioCanvasNode[] = []
  const storyboardImageNodes: Array<StudioCanvasNode | undefined> = []
  const videoNodes: StudioCanvasNode[] = []
  const dubbingNodes: StudioCanvasNode[] = []
  sortedStoryboards.forEach((row, index) => {
    const prompt = String(row.storyScript || row.imagePrompt || '').trim()
    const title = String(row.title || `分镜 ${index + 1}`)
    const lane = skipStoryboardImage ? undefined : storyboardLanePosition(index)
    const storyboard = createBoundNode('storyboard_script', 'storyboard-script', index, {
      title,
      subtitle: skipStoryboardImage ? '当前模式仅需分镜脚本' : '分镜脚本',
      prompt,
      resultSummary: prompt,
      status: taskAwareStatus(
        prompt ? 'success' : 'empty',
        tasks,
        'storyboard-script',
        { entityType: 'storyboard', serverId: row.id, taskMedia: 'script' }
      ),
      mediaKind: 'text',
      storyboardId: String(row.id),
      flowBinding: binding({
        role: 'item', step: 'storyboard-script', entityType: 'storyboard', projectId, episodeId,
        serverId: row.id, bindingKey: `flow:storyboard:${row.id}`
      })
    }, lane?.script)
    const stillUrl = String(row.finalImageUrl || '').trim()
    const storyboardImage = skipStoryboardImage ? undefined : createBoundNode('image', 'storyboard-script', index, {
      title: `${title} · 分镜图`,
      subtitle: stillUrl ? '服务端分镜主图' : '尚未生成分镜图',
      prompt,
      resultSummary: prompt,
      status: taskAwareStatus(
        stillUrl ? 'success' : 'empty',
        tasks,
        'storyboard-script',
        { entityType: 'storyboard', serverId: row.id, taskMedia: 'image' }
      ),
      mediaKind: stillUrl ? 'image' : undefined,
      mediaUrl: stillUrl || undefined,
      imagePurpose: 'storyboard',
      storyboardId: String(row.id),
      flowBinding: binding({
        role: 'item', step: 'storyboard-script', entityType: 'storyboard', projectId, episodeId,
        serverId: row.id, bindingKey: `flow:storyboard-image:${row.id}`
      })
    }, lane?.image)
    const videoUrl = String(row.finalVideoUrl || '').trim()
    const video = createBoundNode('video', 'storyboard-video', index, {
      title: `${title} · 视频`,
      subtitle: videoUrl ? '服务端主视频' : '尚未生成视频',
      prompt: String(row.videoPrompt || row.videoPromptImage || prompt).trim(),
      recommendedDurationSeconds: row.recommendedDurationSeconds,
      recommendedDurationSource: row.recommendedDurationSource,
      recommendedDurationDescription: row.recommendedDurationDescription,
      status: taskAwareStatus(videoUrl ? 'success' : 'empty', tasks, 'storyboard-video', {
        entityType: 'storyboard_video', serverId: row.id
      }),
      mediaUrl: videoUrl || undefined,
      storyboardId: String(row.id),
      flowBinding: binding({
        role: 'item', step: 'storyboard-video', entityType: 'storyboard_video', projectId, episodeId,
        serverId: row.id, bindingKey: `flow:video:${row.id}`
      })
    }, lane?.video)
    const composeUrl = String(row.finalComposeVideoUrl || '').trim()
    const dubbing = createBoundNode('voice', 'dubbing', index, {
      title: `${title} · 配音`,
      subtitle: row.audioStatus === 'SUCCEEDED' ? '配音已完成' : '台词、字幕与音画同步',
      prompt: String(row.dialogueText || row.subtitleText || '').trim(),
      resultSummary: String(row.subtitleText || row.dialogueText || '').trim(),
      status: taskAwareStatus(
        composeUrl || row.audioStatus === 'SUCCEEDED' ? 'success' : 'empty',
        tasks,
        'dubbing',
        { entityType: 'dubbing', serverId: row.id }
      ),
      mediaKind: composeUrl ? 'video' : 'audio',
      mediaUrl: composeUrl || undefined,
      storyboardId: String(row.id),
      flowBinding: binding({
        role: 'item', step: 'dubbing', entityType: 'dubbing', projectId, episodeId,
        serverId: row.id, bindingKey: `flow:dubbing:${row.id}`
      })
    })
    storyboardNodes.push(storyboard)
    storyboardImageNodes.push(storyboardImage)
    videoNodes.push(video)
    dubbingNodes.push(dubbing)
  })
  nodes.push(
    ...storyboardNodes,
    ...storyboardImageNodes.filter((node): node is StudioCanvasNode => Boolean(node)),
    ...videoNodes,
    ...dubbingNodes
  )

  const previewUrl = project.projectType === 'series'
    ? String(episode?.finalVideoUrl || '').trim()
    : String(project.finalVideoUrl || '').trim()
  const preview = createBoundNode('video', 'preview', 0, {
    title: project.projectType === 'series'
      ? `${episode?.comicTitle || project.projectName} · 成片`
      : `${project.projectName} · 成片`,
    subtitle: previewUrl ? '时间轴导出成片' : '打开时间轴进行预览和导出',
    prompt: '',
    status: previewUrl ? 'success' : 'empty',
    mediaUrl: previewUrl || undefined,
    flowBinding: binding({
      role: 'item', step: 'preview', entityType: 'preview', projectId, episodeId,
      bindingKey: `flow:preview:${projectId}:${episodeId}`
    })
  })
  nodes.push(preview)

  const storySource = scriptNode
  assetNodes.forEach((node) => edges.push(createManagedEdge(storySource, node, 'reference')))
  storyboardNodes.forEach((node, index) => {
    edges.push(createManagedEdge(storySource, node, 'flow'))
    resolveStoryboardAssetDependencies(sortedStoryboards[index], assetNodes)
      .forEach((assetNode) => edges.push(createManagedEdge(assetNode, node, 'reference')))
    const still = storyboardImageNodes[index]
    const video = videoNodes[index]
    if (still) {
      edges.push(createManagedEdge(node, still, 'flow'))
      edges.push(createManagedEdge(still, video, 'flow'))
    } else {
      edges.push(createManagedEdge(node, video, 'flow'))
    }
    edges.push(createManagedEdge(video, dubbingNodes[index], 'sequence'))
    edges.push(createManagedEdge(dubbingNodes[index], preview, 'flow'))
  })
  return { nodes: packStudioNodesWithoutOverlap(nodes), edges }
}

function resolveStoryboardAssetDependencies(
  row: UserStoryboardListRow | undefined,
  assetNodes: StudioCanvasNode[]
): StudioCanvasNode[] {
  if (!assetNodes.length) return []
  const references = row?.referenceImages ?? []
  const storyboardText = [
    row?.storyScript,
    row?.imagePrompt,
    ...references.flatMap((reference) => [reference.name, reference.assetName])
  ].map(normalizedDependencyText).filter(Boolean).join('\n')
  const matched = assetNodes.filter((node) => {
    const title = normalizedDependencyText(node.data.title)
    const assetType = String(node.data.flowBinding?.assetType || '').trim().toLowerCase()
    const mediaUrl = String(node.data.mediaUrl || '').trim()
    if (title && storyboardText.includes(title)) return true
    return references.some((reference) => {
      const kind = String(reference.assetKind || '').trim().toLowerCase()
      const name = normalizedDependencyText(reference.assetName || reference.name)
      const url = String(reference.url || '').trim()
      const kindMatches = !kind || !assetType || kind === assetType
      return kindMatches && (
        Boolean(name && title && (name === title || name.includes(title) || title.includes(name)))
        || Boolean(url && mediaUrl && url === mediaUrl)
      )
    })
  })
  if (matched.length) return matched

  // 分镜列表尚未返回引用快照时，素材阶段仍是分镜脚本的真实上游；连接已有图的
  // 素材节点，避免自动任务完成后出现视觉与语义上的断链。
  const withImages = assetNodes.filter((node) => Boolean(String(node.data.mediaUrl || '').trim()))
  return withImages.length ? withImages : assetNodes
}

function normalizedDependencyText(value: unknown): string {
  return String(value ?? '').trim().toLocaleLowerCase('zh-CN')
}

function createStepNodes(source: StudioFlowProjectSource, episodeId: number): StudioCanvasNode[] {
  const { project, tasks } = source
  const projectId = project.id
  const definitions: Array<{
    step: StudioFlowStep
    kind: Parameters<typeof createStudioNode>[0]
    title: string
    entityType: StudioFlowBinding['entityType']
  }> = [
    { step: 'global-setting', kind: 'text', title: '项目配置', entityType: 'project' },
    { step: 'story-script', kind: 'text', title: '剧本创作', entityType: 'story_script' },
    { step: 'scene-character', kind: 'image', title: '素材准备', entityType: 'rps_asset' },
    { step: 'storyboard-script', kind: 'storyboard_script', title: '分镜脚本', entityType: 'storyboard' },
    { step: 'storyboard-video', kind: 'video', title: '分镜视频', entityType: 'storyboard_video' },
    { step: 'dubbing', kind: 'voice', title: '音画同步', entityType: 'dubbing' },
    { step: 'preview', kind: 'video', title: '成品预览', entityType: 'preview' }
  ]
  return definitions.map(({ step, kind, title, entityType }) => createBoundNode(kind, step, -1, {
    title,
    subtitle: '流程步骤控制节点',
    prompt: '',
    status: taskAwareStatus('success', tasks, step, {
      stepControl: true,
      isFailedTaskResolved: (task) => failedTaskResolvedByBusiness(task, step, source)
    }),
    flowBinding: binding({
      role: 'step', step, entityType, projectId, episodeId,
      serverId: step === 'global-setting' ? projectId : undefined,
      bindingKey: step === 'global-setting'
        ? `flow:project:${projectId}`
        : `flow:step:${step}:${projectId}:${episodeId}`
    })
  }))
}

function storyboardLanePosition(index: number): {
  script: { x: number; y: number }
  image: { x: number; y: number }
  video: { x: number; y: number }
} {
  const y = 72 + (index + 1) * 420
  return {
    script: { x: STEP_LAYOUT['storyboard-script'].x + 24, y },
    image: { x: STEP_LAYOUT['storyboard-script'].x + 340, y },
    video: { x: STEP_LAYOUT['storyboard-video'].x + 24, y }
  }
}

function createBoundNode(
  kind: Parameters<typeof createStudioNode>[0],
  step: StudioFlowStep,
  index: number,
  options: Parameters<typeof createStudioNode>[2],
  position?: { x: number; y: number }
): StudioCanvasNode {
  const layout = STEP_LAYOUT[step]
  const column = layout.width > 400 && index >= 0 ? index % 2 : 0
  const row = index < 0 ? 0 : Math.floor(index / (layout.width > 400 ? 2 : 1)) + 1
  return createStudioNode(kind, position ?? {
    x: layout.x + 24 + column * 320,
    y: 72 + row * 420
  }, { ...options, zoneId: step })
}

function createManagedEdge(
  source: StudioCanvasNode,
  target: StudioCanvasNode,
  kind: Parameters<typeof createStudioEdge>[2]
): StudioFlowEdge {
  const edge = createStudioEdge(source.id, target.id, kind)
  const sourceKey = source.data.flowBinding?.bindingKey ?? source.id
  const targetKey = target.data.flowBinding?.bindingKey ?? target.id
  return {
    ...edge,
    data: {
      ...edge.data!,
      flowManaged: true,
      bindingKey: `flow-edge:${sourceKey}:${targetKey}:${kind}`
    }
  }
}

function binding(value: StudioFlowBinding): StudioFlowBinding {
  return value
}

function assetPrompt(asset: UserAssetRpsRow): string {
  const forms = asset.forms ?? []
  return [
    ...forms.flatMap((form) => [form.descriptions, form.prompt, form.promptText, form.introduction, form.summary]),
    asset.introduction,
    asset.summary
  ].map((value) => String(value ?? '').trim()).find(Boolean) ?? ''
}

function assetImage(asset: UserAssetRpsRow): string | undefined {
  for (const form of asset.forms ?? []) {
    const image = selectedFormImage(form)
    if (image) return image
  }
  return undefined
}

function assetFormsPreview(asset: UserAssetRpsRow): NonNullable<StudioCanvasNode['data']['flowAssetForms']> {
  return (asset.forms ?? []).map((form) => {
    const selectedImageUrl = selectedFormImage(form)
    const images = (form.images ?? []).flatMap((image) => {
      const imageUrl = String(image.imageUrl ?? '').trim()
      if (!imageUrl) return []
      return [{
        imageId: positiveNumber(image.id) ?? positiveNumber(image.imgId) ?? `${form.id}:${imageUrl}`,
        name: String(image.name ?? '').trim() || undefined,
        imageUrl,
        selected: image.isUse === 1 || image.id === form.currentImageId || image.imgId === form.currentImageId
      }]
    })
    if (!images.length && selectedImageUrl) {
      images.push({
        imageId: `form-${form.id}-current`,
        name: form.name,
        imageUrl: selectedImageUrl,
        selected: true
      })
    }
    return {
      formId: form.id,
      name: String(form.name ?? '').trim() || `形态 ${form.id}`,
      prompt: [form.descriptions, form.prompt, form.promptText, form.introduction, form.summary]
        .map((value) => String(value ?? '').trim())
        .find(Boolean) ?? '',
      selectedImageUrl,
      images
    }
  })
}

function selectedFormImage(form: UserAssetRpsFormRow): string | undefined {
  const images = form.images ?? []
  const selected = images.find((image) => image.isUse === 1)
    ?? images.find((image) => image.id === form.currentImageId || image.imgId === form.currentImageId)
    ?? images[0]
  return String(selected?.imageUrl || form.imageUrl || '').trim() || undefined
}

function taskAwareStatus(
  base: StudioNodeStatus,
  tasks: UserTaskRow[],
  step: StudioFlowStep,
  scope: StudioFlowTaskScope
): StudioNodeStatus {
  const stepTasks = tasks.filter((task) =>
    taskMatchesStep(task.taskType, step) && taskMatchesStoryboardMedia(task.taskType, scope.taskMedia)
  )
  const matching = latestTasksByScope(scope.stepControl || scope.singleton
    ? stepTasks
    : stepTasks.filter((task) => taskTargetsFlowEntity(task, scope)))
  if (matching.some((task) => mapUserTaskStatusToStudioTaskStatus(task.status) === 'running')) return 'generating'
  if (matching.some((task) => mapUserTaskStatusToStudioTaskStatus(task.status) === 'queued')) return 'queued'
  if ((scope.stepControl || base !== 'success') && matching.some((task) =>
    mapUserTaskStatusToStudioTaskStatus(task.status) === 'failed' &&
    !scope.isFailedTaskResolved?.(task)
  )) return 'failed'
  return base
}

interface StudioFlowTaskScope {
  stepControl?: boolean
  singleton?: boolean
  entityType?: StudioFlowBinding['entityType']
  serverId?: number
  formIds?: number[]
  taskMedia?: 'script' | 'image'
  isFailedTaskResolved?: (task: UserTaskRow) => boolean
}

/**
 * 内容节点只接受 inputSnapshot 能精确定位的任务。列表行缺少实体范围时，状态仅投影到
 * 步骤控制节点，避免把一条单镜/单形态任务伪装为整列实体同时生成。
 */
function taskTargetsFlowEntity(task: UserTaskRow, scope: StudioFlowTaskScope): boolean {
  const serverId = positiveNumber(scope.serverId)
  if (serverId == null) return false
  const targets = readTaskTargetIds(task.inputSnapshot)
  if (scope.entityType === 'rps_asset') {
    if (targets.assetIds.has(serverId)) return true
    return (scope.formIds ?? []).some((formId) => targets.formIds.has(Number(formId)))
  }
  if (scope.entityType === 'storyboard' || scope.entityType === 'storyboard_video' || scope.entityType === 'dubbing') {
    return targets.storyboardIds.has(serverId)
  }
  return false
}

function latestTasksByScope(tasks: UserTaskRow[]): UserTaskRow[] {
  const latest = new Map<string, UserTaskRow>()
  tasks.forEach((task) => {
    const key = taskEntityScopeKey(task)
    const previous = latest.get(key)
    if (!previous || compareTaskFreshness(task, previous) > 0) latest.set(key, task)
  })
  return [...latest.values()]
}

function taskEntityScopeKey(task: UserTaskRow): string {
  const targets = readTaskTargetIds(task.inputSnapshot)
  const ids = [
    ...[...targets.storyboardIds].sort((a, b) => a - b).map((id) => `s${id}`),
    ...[...targets.assetIds].sort((a, b) => a - b).map((id) => `a${id}`),
    ...[...targets.formIds].sort((a, b) => a - b).map((id) => `f${id}`)
  ]
  return ids.length
    ? ids.join(',')
    : `${String(task.taskType ?? '').trim().toUpperCase()}:step`
}

function failedTaskResolvedByBusiness(
  task: UserTaskRow,
  step: StudioFlowStep,
  source: StudioFlowProjectSource
): boolean {
  const targets = readTaskTargetIds(task.inputSnapshot)
  if (step === 'story-script') return Boolean(String(source.script?.originalText ?? '').trim())
  if (step === 'scene-character') {
    const assets = Object.values(source.assets).flat()
    const assetsById = new Map(assets.map((asset) => [asset.id, asset]))
    const formsById = new Map(assets.flatMap((asset) =>
      (asset.forms ?? []).map((form) => [form.id, form] as const)))
    const hasTargets = targets.assetIds.size > 0 || targets.formIds.size > 0
    if (!hasTargets) return false
    const currentAssets = [...targets.assetIds].flatMap((id) => {
      const asset = assetsById.get(id)
      return asset ? [asset] : []
    })
    const currentForms = [...targets.formIds].flatMap((id) => {
      const form = formsById.get(id)
      return form ? [form] : []
    })
    // 已从服务端删除的历史实体不再属于当前步骤，不能让旧失败永久污染步骤状态。
    if (!currentAssets.length && !currentForms.length) return true
    const taskType = String(task.taskType ?? '').trim().toUpperCase()
    if (taskType === 'FORM_GENERATE') {
      return currentAssets.every((asset) => Boolean(asset.forms?.length)) && currentForms.length === targets.formIds.size
    }
    return currentAssets.every((asset) => Boolean(assetImage(asset))) &&
      currentForms.every((form) => Boolean(selectedFormImage(form)))
  }
  if (!targets.storyboardIds.size) return false
  const storyboardsById = new Map(source.storyboards.map((row) => [row.id, row]))
  const currentStoryboards = [...targets.storyboardIds].flatMap((id) => {
    const row = storyboardsById.get(id)
    return row ? [row] : []
  })
  if (!currentStoryboards.length) return true
  if (step === 'storyboard-script') {
    const skipImage = skipsStoryboardImageGeneration(source.project.defaultCreationMode)
    return currentStoryboards.every((row) =>
      Boolean(skipImage ? String(row.storyScript ?? '').trim() : row.finalImageUrl))
  }
  if (step === 'storyboard-video') {
    return currentStoryboards.every((row) => Boolean(row.finalVideoUrl))
  }
  if (step === 'dubbing') {
    return currentStoryboards.every((row) =>
      Boolean(row.finalComposeVideoUrl || row.audioStatus === 'SUCCEEDED'))
  }
  return false
}

function compareTaskFreshness(left: UserTaskRow, right: UserTaskRow): number {
  const leftTime = Date.parse(String(left.updateTime || left.createTime || ''))
  const rightTime = Date.parse(String(right.updateTime || right.createTime || ''))
  const normalizedLeft = Number.isFinite(leftTime) ? leftTime : 0
  const normalizedRight = Number.isFinite(rightTime) ? rightTime : 0
  return normalizedLeft - normalizedRight || Number(left.id) - Number(right.id)
}

function readTaskTargetIds(snapshot: string | null | undefined): {
  storyboardIds: Set<number>
  assetIds: Set<number>
  formIds: Set<number>
} {
  const result = {
    storyboardIds: new Set<number>(),
    assetIds: new Set<number>(),
    formIds: new Set<number>()
  }
  const raw = String(snapshot ?? '').trim()
  if (!raw) return result
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return result
  }
  collectTaskTargetIds(parsed, result, 0)
  return result
}

function collectTaskTargetIds(
  value: unknown,
  target: ReturnType<typeof readTaskTargetIds>,
  depth: number
): void {
  if (depth > 6 || value == null) return
  if (Array.isArray(value)) {
    value.forEach((item) => collectTaskTargetIds(item, target, depth + 1))
    return
  }
  if (typeof value !== 'object') return
  for (const [rawKey, nested] of Object.entries(value as Record<string, unknown>)) {
    const key = rawKey.replaceAll('_', '').toLowerCase()
    const bucket = key === 'storyboardid' || key === 'storyboardids'
      ? target.storyboardIds
      : key === 'assetid' || key === 'assetids' || key === 'rpsid' || key === 'rpsids'
        ? target.assetIds
        : key === 'formid' || key === 'formids'
          ? target.formIds
          : null
    if (bucket) addPositiveNumbers(bucket, nested)
    collectTaskTargetIds(nested, target, depth + 1)
  }
}

function addPositiveNumbers(target: Set<number>, value: unknown): void {
  const values = Array.isArray(value) ? value : [value]
  values.forEach((item) => {
    const id = positiveNumber(item)
    if (id != null) target.add(id)
  })
}

function positiveNumber(value: unknown): number | null {
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

export function mapUserTaskStatusToStudioTaskStatus(status: unknown): StudioTaskStatus | null {
  const normalized = String(status ?? '').trim().toUpperCase()
  if (['CREATED', 'QUEUED', 'PENDING', 'WAITING', '0'].includes(normalized)) return 'queued'
  if (['PROCESSING', 'RUNNING', 'EXECUTING', 'IN_PROGRESS', '1'].includes(normalized)) return 'running'
  if (['SUCCEEDED', 'SUCCESS', 'COMPLETED', 'DONE', '2'].includes(normalized)) return 'succeeded'
  if (['FAILED', 'PARTIAL_FAILED', 'ERROR', '3'].includes(normalized)) return 'failed'
  if (['CANCELED', 'CANCELLED', 'STOPPED', '4'].includes(normalized)) return 'cancelled'
  return null
}

export function mapStudioFlowUserTasks(tasks: UserTaskRow[]): StudioTask[] {
  return tasks.flatMap((task) => {
    const status = mapUserTaskStatusToStudioTaskStatus(task.status)
    const remoteTaskId = Number(task.id)
    if (!status || !Number.isSafeInteger(remoteTaskId) || remoteTaskId <= 0) return []
    const timestamp = task.updateTime || task.createTime || new Date(0).toISOString()
    return [{
      id: `studio-flow-task-${remoteTaskId}`,
      title: taskTitle(task.taskType),
      stage: taskStage(status),
      // task/list 不提供 SSE progress；非终态禁止用固定百分比伪造实时进度。
      progress: status === 'succeeded' ? 100 : 0,
      status,
      errorMessage: task.errorMessage || undefined,
      remoteTaskId,
      createdAt: task.createTime || timestamp,
      updatedAt: timestamp
    }]
  })
}

export function hasOngoingStudioFlowTasks(tasks: UserTaskRow[]): boolean {
  return tasks.some((task) => {
    const status = mapUserTaskStatusToStudioTaskStatus(task.status)
    return status === 'queued' || status === 'running'
  })
}

function taskTitle(taskType: string | null | undefined): string {
  const type = String(taskType || '').trim()
  if (!type) return '流程生成任务'
  return type.replaceAll('_', ' ').toLowerCase().replace(/(^|\s)\S/g, (value) => value.toUpperCase())
}

function taskStage(status: StudioTaskStatus): string {
  if (status === 'queued') return '等待服务端调度'
  if (status === 'running') return '服务端正在生成'
  if (status === 'succeeded') return '服务端任务已完成'
  if (status === 'failed') return '服务端任务失败'
  if (status === 'cancelled') return '服务端任务已取消'
  return '等待确认'
}

function taskMatchesStep(taskType: string | null | undefined, step: StudioFlowStep): boolean {
  const type = String(taskType || '').toUpperCase()
  if (!type) return false
  if (step === 'story-script') return type.includes('SCRIPT') && !type.includes('STORYBOARD')
  if (step === 'scene-character') return /(ASSET|RPS|CHARACTER|SCENE|PROP|FORM|EXTRACT)/.test(type)
  if (step === 'storyboard-script') return type.includes('STORYBOARD') && !/(VIDEO|AUDIO|DUBB|LIP)/.test(type)
  if (step === 'storyboard-video') return type.includes('VIDEO') && !/(AUDIO|DUBB|COMPOSE)/.test(type)
  if (step === 'dubbing') return /(AUDIO|DUBB|VOICE|COMPOSE|LIP)/.test(type)
  return false
}

function taskMatchesStoryboardMedia(
  taskType: string | null | undefined,
  media: StudioFlowTaskScope['taskMedia']
): boolean {
  if (!media) return true
  const type = String(taskType || '').toUpperCase()
  const isImage = type.includes('IMAGE') || type.includes('IMG')
  return media === 'image' ? isImage : !isImage
}

function normalizeEpisodeId(value: number | null): number | null {
  return value != null && Number.isFinite(value) && value > 0 ? value : null
}

function fulfilledValue<T>(result: PromiseSettledResult<T>): T | undefined {
  return result.status === 'fulfilled' ? result.value : undefined
}

function errorMessage(reason: unknown): string {
  if (reason instanceof Error) return reason.message
  if (reason && typeof reason === 'object' && 'msg' in reason) return String(reason.msg)
  return String(reason || '加载失败')
}

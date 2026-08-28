import type { UserTaskDetailData,UserTaskRow } from '~/types/business-api'
export type StoryboardGenerationMedia = 'image' | 'video'
export type StoryboardGenerationTaskOwner = 'batch' | 'modal'
export type StoryboardVideoGenerationTaskKind = 'i2v' | 'multi' | 'edge' | 'grid'
export type StoryboardModalImageTaskKind = 'dialogue' | 'upscale' | 'multiangle' | 'ninegrid'

export type StoryboardGenerationTaskDescriptor = {
  taskId: number
  media: StoryboardGenerationMedia
  owner: StoryboardGenerationTaskOwner
  storyboardIds: number[]
  overwriteExistingFinal: boolean | null
  videoTaskKind: StoryboardVideoGenerationTaskKind
}

export type StoryboardModalImageTaskDescriptor = {
  taskId: number
  storyboardId: number
  kind: StoryboardModalImageTaskKind
  sourceRecordId: number | null
  referenceImageUrl: string | null
}

type StoryboardGenerationSnapshot = {
  storyboardIds: number[]
  overwriteExistingFinal: boolean | null
  videoTaskKind: StoryboardVideoGenerationTaskKind
}

function parsePositiveId(raw: unknown): number | null {
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? value : null
}

function normalizeTaskType(raw: unknown): string {
  return String(raw ?? '')
    .trim()
    .toLowerCase()
    .replace(/-/g, '_')
}

export function isOngoingStoryboardGenerationTaskStatus(raw: unknown): boolean {
  const status = String(raw ?? '')
    .trim()
    .toUpperCase()
  return (
    status === 'PENDING' ||
    status === 'PROCESSING' ||
    status === 'RUNNING' ||
    status === 'QUEUED' ||
    status === 'WAITING' ||
    status === '0'
  )
}

function parseSnapshotObject(raw: unknown): Record<string, unknown> | null {
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    return raw as Record<string, unknown>
  }
  if (typeof raw !== 'string' || !raw.trim()) return null
  try {
    const parsed = JSON.parse(raw) as unknown
    if (typeof parsed === 'string') return parseSnapshotObject(parsed)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}

function collectStoryboardIds(snapshot: Record<string, unknown>): number[] {
  const ids: number[] = []
  const append = (raw: unknown) => {
    const id = parsePositiveId(raw)
    if (id != null && !ids.includes(id)) ids.push(id)
  }

  if (Array.isArray(snapshot.storyboardIds)) {
    for (const raw of snapshot.storyboardIds) append(raw)
  }

  // Keep compatibility with early task snapshots that only persisted per-shot objects.
  for (const key of ['shots', 'items']) {
    const rows = snapshot[key]
    if (!Array.isArray(rows)) continue
    for (const row of rows) {
      if (!row || typeof row !== 'object' || Array.isArray(row)) continue
      const item = row as Record<string, unknown>
      append(item.storyboardId ?? item.id)
    }
  }
  append(snapshot.storyboardId)
  return ids
}

function parseExplicitBoolean(
  snapshot: Record<string, unknown>,
  key: string
): boolean | null {
  if (!Object.prototype.hasOwnProperty.call(snapshot, key)) return null
  const value = snapshot[key]
  if (typeof value === 'boolean') return value
  if (value === 1 || value === '1' || String(value).trim().toLowerCase() === 'true') return true
  if (value === 0 || value === '0' || String(value).trim().toLowerCase() === 'false') return false
  return null
}

function resolveVideoTaskKind(raw: unknown): StoryboardVideoGenerationTaskKind {
  const direction = String(raw ?? '')
    .trim()
    .toLowerCase()
  if (direction === 'multi') return 'multi'
  if (direction === 'edge') return 'edge'
  if (direction === 'grid') return 'grid'
  return 'i2v'
}

export function parseStoryboardGenerationSnapshot(
  raw: unknown
): StoryboardGenerationSnapshot | null {
  const snapshot = parseSnapshotObject(raw)
  if (!snapshot) return null
  const storyboardIds = collectStoryboardIds(snapshot)
  if (!storyboardIds.length) return null
  return {
    storyboardIds,
    overwriteExistingFinal: parseExplicitBoolean(snapshot, 'overwriteExistingFinal'),
    videoTaskKind: resolveVideoTaskKind(snapshot.direction)
  }
}

export function resolveStoryboardGenerationTaskOwner(options: {
  taskId: number
  snapshot: StoryboardGenerationSnapshot
  knownBatchTaskIds?: ReadonlySet<number>
  knownModalTaskIds?: ReadonlySet<number>
}): StoryboardGenerationTaskOwner {
  const taskId = Number(options.taskId)
  // The current server snapshot is authoritative. Scoped ownership is only a legacy fallback.
  if (options.snapshot.overwriteExistingFinal === false) return 'modal'
  if (options.snapshot.overwriteExistingFinal === true) return 'batch'
  if (options.knownModalTaskIds?.has(taskId)) return 'modal'
  if (options.knownBatchTaskIds?.has(taskId)) return 'batch'
  // The backend treats an absent legacy flag as overwrite=true as well.
  return 'batch'
}

export async function discoverOngoingStoryboardGenerationTasks(options: {
  rows: UserTaskRow[]
  media: StoryboardGenerationMedia
  loadDetail: (taskId: number) => Promise<UserTaskDetailData | null>
  knownBatchTaskIds?: ReadonlySet<number>
  knownModalTaskIds?: ReadonlySet<number>
}): Promise<StoryboardGenerationTaskDescriptor[]> {
  const expectedType =
    options.media === 'video' ? 'storyboard_video_generate' : 'storyboard_image_generate'
  const candidates = options.rows.filter((row) => {
    const taskId = parsePositiveId(row?.id)
    return (
      taskId != null &&
      normalizeTaskType(row?.taskType) === expectedType &&
      isOngoingStoryboardGenerationTaskStatus(row?.status)
    )
  })

  const discovered = await Promise.all(
    candidates.map(async (row): Promise<StoryboardGenerationTaskDescriptor | null> => {
      const taskId = parsePositiveId(row.id)
      if (taskId == null) return null
      const detail = await options.loadDetail(taskId)
      if (!detail) return null
      if (normalizeTaskType(detail.taskType ?? row.taskType) !== expectedType) return null
      if (!isOngoingStoryboardGenerationTaskStatus(detail.status)) return null
      const snapshot = parseStoryboardGenerationSnapshot(detail.inputSnapshot ?? row.inputSnapshot)
      if (!snapshot) return null
      return {
        taskId,
        media: options.media,
        owner: resolveStoryboardGenerationTaskOwner({
          taskId,
          snapshot,
          knownBatchTaskIds: options.knownBatchTaskIds,
          knownModalTaskIds: options.knownModalTaskIds
        }),
        storyboardIds: snapshot.storyboardIds,
        overwriteExistingFinal: snapshot.overwriteExistingFinal,
        videoTaskKind: snapshot.videoTaskKind
      }
    })
  )

  return discovered
    .filter((task): task is StoryboardGenerationTaskDescriptor => task != null)
    .sort((a, b) => b.taskId - a.taskId)
}

function resolveModalImageTaskKind(raw: unknown): StoryboardModalImageTaskKind | null {
  const taskType = normalizeTaskType(raw)
  if (taskType === 'storyboard_edit_image') return 'dialogue'
  if (taskType === 'storyboard_image_upscale') return 'upscale'
  if (taskType === 'storyboard_multi_view_image') return 'multiangle'
  if (taskType === 'storyboard_multi_grid_image') return 'ninegrid'
  return null
}

function parseReferenceImageUrl(snapshot: Record<string, unknown>): string | null {
  const direct = String(snapshot.referenceUrl ?? snapshot.imageUrl ?? '').trim()
  if (direct) return direct
  const references = snapshot.referenceImages
  if (!Array.isArray(references)) return null
  const first = references.find((item) => typeof item === 'string' && item.trim())
  return typeof first === 'string' ? first.trim() : null
}

/** Discover modal-only image operations that never belong to the outer batch owner. */
export async function discoverOngoingStoryboardModalImageTasks(options: {
  rows: UserTaskRow[]
  loadDetail: (taskId: number) => Promise<UserTaskDetailData | null>
}): Promise<StoryboardModalImageTaskDescriptor[]> {
  const candidates = options.rows.filter(
    (row) =>
      parsePositiveId(row?.id) != null &&
      resolveModalImageTaskKind(row?.taskType) != null &&
      isOngoingStoryboardGenerationTaskStatus(row?.status)
  )

  const discovered = await Promise.all(
    candidates.map(async (row): Promise<StoryboardModalImageTaskDescriptor | null> => {
      const taskId = parsePositiveId(row.id)
      if (taskId == null) return null
      const detail = await options.loadDetail(taskId)
      if (!detail || !isOngoingStoryboardGenerationTaskStatus(detail.status)) return null
      const kind = resolveModalImageTaskKind(detail.taskType ?? row.taskType)
      if (!kind) return null
      const snapshot = parseSnapshotObject(detail.inputSnapshot ?? row.inputSnapshot)
      if (!snapshot) return null
      const storyboardId = parsePositiveId(snapshot.storyboardId)
      if (storyboardId == null) return null
      return {
        taskId,
        storyboardId,
        kind,
        sourceRecordId: parsePositiveId(snapshot.genRecordId),
        referenceImageUrl: parseReferenceImageUrl(snapshot)
      }
    })
  )

  return discovered
    .filter((task): task is StoryboardModalImageTaskDescriptor => task != null)
    .sort((a, b) => b.taskId - a.taskId)
}

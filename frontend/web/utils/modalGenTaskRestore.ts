import {
fetchUserTaskDetailOnce,
isTerminalTaskStatus
} from '~/composables/useTaskSseFollow'
import type { StoryboardDubbingGenTaskSnapshot } from '~/stores/creation'
import { userAssetCenterDetail,userComposeStatus } from '~/utils/businessApi'
import type { StoryboardDubbingModalGenSession } from '~/utils/storyboardDubbingModalGenSession'

function normalizeComposeStatus(status: string | null | undefined): string {
  return String(status || '').trim().toUpperCase()
}

function resolveDubbingDetailStatus(content: Record<string, unknown> | null | undefined): string {
  return String(content?.status || '').trim().toUpperCase()
}

export type OngoingComposeDubbingJob = {
  composeBatchId: string
  audioRecordId: number
  taskId?: number
  lipSync?: boolean
}

/**
 * 刷新恢复前校验 compose 配音 / 对口型任务是否仍为进行中。
 * compose 批次用 compose/status；对口型以 taskId + task/detail 为主，audio 仅兜底。
 */
export async function resolveOngoingComposeDubbingJob(
  job: Pick<StoryboardDubbingGenTaskSnapshot, 'composeBatchId' | 'audioRecordId' | 'taskId' | 'lipSync'>
): Promise<OngoingComposeDubbingJob | null> {
  const composeBatchId = String(job.composeBatchId || '').trim()
  const audioRecordIdRaw = Number(job.audioRecordId)
  const audioRecordId =
    Number.isFinite(audioRecordIdRaw) && audioRecordIdRaw > 0 ? audioRecordIdRaw : 0
  const taskIdRaw = Number(job.taskId)
  const taskId = Number.isFinite(taskIdRaw) && taskIdRaw > 0 ? taskIdRaw : 0

  if (!composeBatchId && job.lipSync) {
    // 对口型 SSE 必须以 taskId 续跟；无 taskId 的旧快照直接丢弃（用户重点会幂等拿回 taskId）
    if (taskId <= 0) return null
    const ongoing = await resolveOngoingTaskId(taskId)
    if (!ongoing) return null
    return {
      composeBatchId: '',
      audioRecordId,
      taskId,
      lipSync: true
    }
  }

  if (!composeBatchId) return null
  if (audioRecordId <= 0) return null

  try {
    const status = await userComposeStatus({ composeBatchId })
    const st = normalizeComposeStatus(status.status)
    if (st === 'VOICING' || st === 'COMPOSING') {
      return { composeBatchId, audioRecordId }
    }
    if (st === 'SUCCEEDED' || st === 'FAILED') return null
  } catch {
    /* 批次不存在或网络异常时回退 detail 查询 */
  }

  try {
    const detail = await userAssetCenterDetail({
      categoryCode: 'dubbing',
      id: audioRecordId
    })
    const status = resolveDubbingDetailStatus(
      (detail.content as Record<string, unknown> | null | undefined) ?? null
    )
    if (status === 'SUCCEEDED' || status === 'FAILED') return null
    return { composeBatchId, audioRecordId }
  } catch {
    return { composeBatchId, audioRecordId }
  }
}

export function resolveComposeJobFromDubbingSnapshots(
  persisted: StoryboardDubbingGenTaskSnapshot | null,
  session: StoryboardDubbingModalGenSession | null,
  storyboardId: number
): OngoingComposeDubbingJob | null {
  const fromPersistedBatch = String(persisted?.composeBatchId || '').trim()
  const fromPersistedAudio = Number(persisted?.audioRecordId)
  const fromPersistedTask = Number(persisted?.taskId)
  const persistedHasLipSyncTask =
    !!persisted?.lipSync &&
    ((Number.isFinite(fromPersistedTask) && fromPersistedTask > 0) ||
      (Number.isFinite(fromPersistedAudio) && fromPersistedAudio > 0))
  if (fromPersistedBatch || persistedHasLipSyncTask) {
    return {
      composeBatchId: fromPersistedBatch,
      audioRecordId:
        Number.isFinite(fromPersistedAudio) && fromPersistedAudio > 0 ? fromPersistedAudio : 0,
      ...(Number.isFinite(fromPersistedTask) && fromPersistedTask > 0
        ? { taskId: fromPersistedTask }
        : {}),
      ...(persisted?.lipSync != null ? { lipSync: Boolean(persisted.lipSync) } : {})
    }
  }

  if (session?.storyboardId !== storyboardId) return null
  const fromSessionBatch = String(session.composeBatchId || '').trim()
  const fromSessionAudio = Number(session.audioRecordId)
  const fromSessionTask = Number(session.taskId)
  const sessionHasLipSyncTask =
    !!session.lipSync &&
    ((Number.isFinite(fromSessionTask) && fromSessionTask > 0) ||
      (Number.isFinite(fromSessionAudio) && fromSessionAudio > 0))
  if (fromSessionBatch || sessionHasLipSyncTask) {
    return {
      composeBatchId: fromSessionBatch,
      audioRecordId:
        Number.isFinite(fromSessionAudio) && fromSessionAudio > 0 ? fromSessionAudio : 0,
      ...(Number.isFinite(fromSessionTask) && fromSessionTask > 0
        ? { taskId: fromSessionTask }
        : {}),
      ...(session.lipSync != null ? { lipSync: Boolean(session.lipSync) } : {})
    }
  }

  return null
}

/**
 * 刷新恢复前校验 taskId 是否仍为进行中；终态则返回 null 以便清理脏快照。
 * 详情拉取失败时保留 taskId，避免网络抖动误清任务。
 */
export async function resolveOngoingTaskId(taskId: number): Promise<number | null> {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return null
  try {
    const detail = await fetchUserTaskDetailOnce(id)
    if (!detail) return id
    if (isTerminalTaskStatus(detail.status)) return null
    return id
  } catch {
    return id
  }
}

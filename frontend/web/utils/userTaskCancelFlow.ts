import type { UserTaskRow } from '~/types/business-api'
import { userTaskCancel,userTaskCancelBatch } from '~/utils/businessApi'

export function parseUserTaskId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

export function normUserTaskCancelType(taskType: unknown): string {
  return String(taskType ?? '')
    .trim()
    .toLowerCase()
    .replace(/-/g, '_')
}

/**
 * 独立子任务（非父任务 batch）：批量提交时每个 item 一条 task，停止剩余走 cancel-batch。
 * 见接口文档 cancel-batch 适用 form_image / form_card_image / form_generate 等。
 */
export function isIndependentUserTaskType(taskType: unknown): boolean {
  const n = normUserTaskCancelType(taskType)
  return n === 'form_image' || n === 'form_card_image' || n === 'form_generate'
}

/** 父任务 / 单项任务：统一走 POST /api/user/task/cancel（入参仅 taskId） */
export function isParentOrSingleCancelTaskType(taskType: unknown): boolean {
  const n = normUserTaskCancelType(taskType)
  if (!n) return false
  if (isIndependentUserTaskType(n)) return true
  return (
    n === 'asset_extract' ||
    n.endsWith('_batch') ||
    n === 'form_multi_view' ||
    n === 'form_edit_chat' ||
    n === 'image_upscale' ||
    n === 'storyboard_edit_image' ||
    n === 'storyboard_image_upscale' ||
    n === 'storyboard_multi_view_image' ||
    n === 'storyboard_multi_grid_image' ||
    n === 'storyboard_image_generate' ||
    n === 'storyboard_video_generate'
  )
}

function dedupeTaskIds(ids: number[]): number[] {
  const seen = new Set<number>()
  const out: number[] = []
  for (const id of ids) {
    if (!Number.isFinite(id) || id <= 0 || seen.has(id)) continue
    seen.add(id)
    out.push(id)
  }
  return out
}

/** 取消单个任务：POST /api/user/task/cancel { taskId } */
export async function requestCancelUserTaskById(taskId: number): Promise<void> {
  const id = parseUserTaskId(taskId)
  if (!id) throw new Error('任务ID无效')
  await userTaskCancel({ taskId: id })
}

/** 取消单个任务（含 asset_extract / 父任务 / 单项生图等） */
export async function requestCancelUserTask(
  task: Pick<UserTaskRow, 'id' | 'taskType'>
): Promise<void> {
  const taskId = parseUserTaskId(task.id)
  if (!taskId) throw new Error('任务ID无效')
  await userTaskCancel({ taskId })
}

/**
 * 批量取消多个独立 PENDING 任务（停止剩余）。
 * 仅当 taskIds.length >= 2 时走 cancel-batch；单个仍走 /cancel。
 */
export async function requestCancelIndependentPendingTasksBatch(
  taskIds: number[]
): Promise<number> {
  const ids = dedupeTaskIds(taskIds)
  if (!ids.length) return 0
  if (ids.length === 1) {
    await userTaskCancel({ taskId: ids[0]! })
    return 1
  }
  const res = await userTaskCancelBatch({ taskIds: ids })
  return res.cancelCount
}

/** taskType/status 允许缺失：内部会归一化兜底（缺 status 视作非 PENDING） */
export type UserTaskCancelCandidate = Pick<UserTaskRow, 'id'> &
  Partial<Pick<UserTaskRow, 'taskType' | 'status'>>

function isPendingTaskStatus(status: unknown): boolean {
  return String(status ?? '').trim().toUpperCase() === 'PENDING'
}

/**
 * 智能停止：父任务/单项任务逐个 /cancel；多个独立 PENDING 子任务合并 /cancel-batch。
 */
export async function requestStopUserTasks(tasks: UserTaskCancelCandidate[]): Promise<void> {
  const rows = tasks
    .map((t) => ({
      taskId: parseUserTaskId(t.id),
      taskType: normUserTaskCancelType(t.taskType),
      status: String(t.status ?? '').trim().toUpperCase()
    }))
    .filter((t): t is { taskId: number; taskType: string; status: string } => t.taskId != null)

  if (!rows.length) throw new Error('任务ID无效')

  const independentPending: number[] = []
  const independentNonPending: number[] = []
  const parentOrSingle: number[] = []

  for (const row of rows) {
    if (isIndependentUserTaskType(row.taskType)) {
      if (isPendingTaskStatus(row.status)) {
        independentPending.push(row.taskId)
      } else {
        independentNonPending.push(row.taskId)
      }
    } else {
      parentOrSingle.push(row.taskId)
    }
  }

  if (independentPending.length > 1) {
    await requestCancelIndependentPendingTasksBatch(independentPending)
  } else if (independentPending.length === 1) {
    await userTaskCancel({ taskId: independentPending[0]! })
  }

  for (const taskId of dedupeTaskIds([...independentNonPending, ...parentOrSingle])) {
    await userTaskCancel({ taskId })
  }
}

/** 按 taskId 列表停止：自动区分父任务（逐个 cancel）与独立任务（多条 PENDING 时 cancel-batch） */
export async function requestStopUserTasksByIds(
  items: Array<{ taskId: number; taskType?: string | null; status?: string | null }>
): Promise<void> {
  await requestStopUserTasks(
    items.map((item) => ({
      id: item.taskId,
      taskType: item.taskType ?? undefined,
      status: item.status ?? undefined
    }))
  )
}

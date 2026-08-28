import type { UserTaskRow } from '~/types/business-api'
import {
isOngoingImageBatchTaskStatus,
isStoryboardImagePromptBatchTask,
parseImageBatchTaskId
} from '~/utils/storyboardImageBatchShared'
import {
isOngoingVideoBatchUserTaskStatus,
isStoryboardVideoPromptBatchTask,
parseVideoBatchTaskId
} from '~/utils/storyboardVideoBatchShared'
function pickPreferredTask(
  tasks: UserTaskRow[],
  preferredTaskId: number | null | undefined,
  parseTaskId: (raw: unknown) => number | null
): UserTaskRow | null {
  if (!tasks.length) return null
  const preferred = parseTaskId(preferredTaskId)
  if (preferred != null) {
    const hit = tasks.find((task) => Number(task.id) === preferred)
    if (hit) return hit
  }
  return tasks[0] ?? null
}

export function pickOngoingVideoPromptBatchTask(
  tasks: UserTaskRow[],
  preferredTaskId?: number | null
): UserTaskRow | null {
  const ongoing = tasks
    .filter((task) =>
      Boolean(task)
      && isStoryboardVideoPromptBatchTask(task.taskType)
      && isOngoingVideoBatchUserTaskStatus(task.status)
    )
    .sort((a, b) => Number(b.id || 0) - Number(a.id || 0))
  return pickPreferredTask(ongoing, preferredTaskId, parseVideoBatchTaskId)
}

export function pickOngoingImagePromptBatchTask(
  tasks: UserTaskRow[],
  preferredTaskId?: number | null
): UserTaskRow | null {
  const ongoing = tasks
    .filter((task) =>
      Boolean(task)
      && isStoryboardImagePromptBatchTask(task.taskType)
      && isOngoingImageBatchTaskStatus(task.status)
    )
    .sort((a, b) => Number(b.id || 0) - Number(a.id || 0))
  return pickPreferredTask(ongoing, preferredTaskId, parseImageBatchTaskId)
}

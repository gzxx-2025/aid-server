import type { UserTaskRow } from '~/types/business-api'
import {
  isOngoingVideoBatchUserTaskStatus,
  isStoryboardVideoPromptBatchTask,
  parseVideoBatchTaskId
} from '~/utils/storyboardVideoBatchShared'

export function pickOngoingStoryboardVideoPromptBatchTask(
  tasks: UserTaskRow[],
  preferredTaskId?: number | null
): UserTaskRow | null {
  const ongoing = tasks
    .filter(
      (task) =>
        task &&
        isStoryboardVideoPromptBatchTask(task.taskType) &&
        isOngoingVideoBatchUserTaskStatus(task.status)
    )
    .sort((left, right) => Number(right.id || 0) - Number(left.id || 0))

  if (!ongoing.length) return null
  const preferred = parseVideoBatchTaskId(preferredTaskId)
  if (preferred != null) {
    const match = ongoing.find((task) => Number(task.id) === preferred)
    if (match) return match
  }
  return ongoing[0] ?? null
}

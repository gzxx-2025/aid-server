import type { UserTaskRow } from '~/types/business-api'
import { isStoryboardImageGenerateTaskType } from '~/utils/taskPartialFailed'
import {
  isOngoingImageBatchTaskStatus,
  isStoryboardImagePromptBatchTask,
  parseImageBatchTaskId
} from '~/utils/storyboardImageBatchShared'

export function pickOngoingStoryboardImageGenerateTask(
  tasks: UserTaskRow[],
  preferredTaskId?: number | null,
  isModalOwned: (taskId: number) => boolean = () => false
): UserTaskRow | null {
  const ongoing = tasks
    .filter(
      (task) =>
        task &&
        isStoryboardImageGenerateTaskType(task.taskType) &&
        isOngoingImageBatchTaskStatus(task.status) &&
        !isModalOwned(Number(task.id))
    )
    .sort((left, right) => Number(right.id || 0) - Number(left.id || 0))
  if (!ongoing.length) return null
  const preferred = parseImageBatchTaskId(preferredTaskId)
  return ongoing.find((task) => Number(task.id) === preferred) ?? ongoing[0] ?? null
}

export function pickOngoingStoryboardImagePromptBatchTask(
  tasks: UserTaskRow[],
  preferredTaskId?: number | null
): UserTaskRow | null {
  const ongoing = tasks
    .filter(
      (task) =>
        task &&
        isStoryboardImagePromptBatchTask(task.taskType) &&
        isOngoingImageBatchTaskStatus(task.status)
    )
    .sort((left, right) => Number(right.id || 0) - Number(left.id || 0))
  if (!ongoing.length) return null
  const preferred = parseImageBatchTaskId(preferredTaskId)
  return ongoing.find((task) => Number(task.id) === preferred) ?? ongoing[0] ?? null
}

export type TaskSseFollowSlot = {
  abort: () => void
  superseded: boolean
}

/** Mark and abort matching browser follows without cancelling server-side tasks. */
export function suspendTaskSseFollowSlots(
  slots: Map<number, TaskSseFollowSlot>,
  taskId?: number
): number {
  const targetId = taskId == null ? null : Number(taskId)
  let suspended = 0

  for (const [id, slot] of slots) {
    if ((targetId != null && id !== targetId) || slot.superseded) continue
    slot.superseded = true
    suspended++
    try {
      slot.abort()
    } catch {
      /* ignore */
    }
  }

  return suspended
}

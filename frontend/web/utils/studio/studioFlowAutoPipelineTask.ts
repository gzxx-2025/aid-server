import { createTaskStream } from '@/hooks/useTaskStream'
import { handleSseErrorRecharge, openRechargeModalFromInsufficientBalance } from '@/utils/api'
import type { TaskSseProgressInput } from '@/utils/taskSseProgressText'

export type StudioFlowTaskProgressSnapshot = TaskSseProgressInput

export async function waitStudioFlowTaskStreamDone(
  taskId: number,
  onProgress?: (snapshot: StudioFlowTaskProgressSnapshot) => void
): Promise<{ ok: boolean; partial?: boolean; message?: string }> {
  const stream = createTaskStream(taskId)
  const stopWatch = onProgress
    ? stream.subscribeProgress((payload) => {
        if (!payload) return
        onProgress(payload)
      })
    : () => {}

  try {
    const result = await stream.done
    if (result.type === 'complete') return { ok: true }
    if (result.type === 'partial_failed') {
      return { ok: false, partial: true, message: '部分任务失败，可稍后重试' }
    }
    if (result.type === 'cancelled') {
      return { ok: false, message: result.message || '任务已取消' }
    }
    const errorMessage = result.errorMessage || '任务失败'
    handleSseErrorRecharge(result.errorData, errorMessage)
    return { ok: false, message: errorMessage }
  } finally {
    stopWatch()
    try {
      stream.close()
    } catch {
      /* ignore */
    }
  }
}

/** 提交失败 / catch 文案触发充值（与步骤页 hook 行为一致） */
export function maybeOpenStudioFlowRechargeFromMessage(message?: string): void {
  if (!message) return
  openRechargeModalFromInsufficientBalance(message)
}

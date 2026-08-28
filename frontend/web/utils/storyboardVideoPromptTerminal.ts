import { extractChainChildTaskIds } from '~/utils/taskChainChild'
import { parseTaskChainFailure } from '~/utils/taskChainOutcome'
export interface StoryboardVideoPromptTerminalResult {
  ok: boolean
  partial?: boolean
  chainFailed?: boolean
  message?: string
  taskId?: number
  chainChildTaskIds?: number[]
}

/** SSE 与 task/detail 共用的提示词终态解释器。 */
export function resolveStoryboardVideoPromptTerminalResult(
  taskId: number,
  kind: 'succeeded' | 'partial_failed',
  payload: unknown
): StoryboardVideoPromptTerminalResult {
  const chainChildTaskIds = extractChainChildTaskIds(payload)
  const chainFailure = parseTaskChainFailure(payload)
  if (chainFailure) {
    return {
      ok: false,
      chainFailed: true,
      taskId,
      message: chainFailure.message,
      ...(chainChildTaskIds.length ? { chainChildTaskIds } : {})
    }
  }
  if (kind === 'partial_failed') {
    return {
      ok: false,
      partial: true,
      taskId,
      message: '部分视频提示词生成失败，可续生',
      chainChildTaskIds
    }
  }
  return { ok: true, taskId, chainChildTaskIds }
}

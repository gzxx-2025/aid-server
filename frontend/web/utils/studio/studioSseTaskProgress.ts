import type { StudioTask } from '@/types/studio'
import {
  withTaskSseDisplayTiming,
  type TaskSseProgressInput
} from '@/utils/taskSseProgressText'

export interface StudioSseTaskProgressInput extends TaskSseProgressInput {
  /** 现有任务 hooks 对 SSE progress 的兼容别名。 */
  percent?: number
}

/** 进度百分比改由节点底部进度条展示，文案只保留状态与预计时间。 */
function stripTrailingProgressPercent(text: string): string {
  return text.replace(/(?:[·，,]\s*|\s+)\d{1,3}%\s*$/u, '').trim()
}

function resolveStudioDisplayProgress(input: StudioSseTaskProgressInput): number | undefined {
  const etaPercent = Number(input.eta?.displayProgress)
  if (Number.isFinite(etaPercent)) {
    return Math.max(0, Math.min(100, Math.round(etaPercent)))
  }
  const percent = Number(input.progress ?? input.percent)
  if (!Number.isFinite(percent)) return undefined
  return Math.max(0, Math.min(100, percent))
}

/**
 * Studio 节点：文案展示 SSE 状态与预计时间；百分比写入 progress，由底部进度条实时展示。
 * 优先使用 eta.displayProgress（与通用 SSE 预计进度同源），否则回退 progress/percent。
 * 没有百分比时不改写 progress，禁止用固定值伪造。
 */
export function studioSseTaskProgressPatch(
  input: StudioSseTaskProgressInput
): Pick<StudioTask, 'stage'> & Partial<Pick<StudioTask, 'progress'>> {
  const display = withTaskSseDisplayTiming(input)
  const stage =
    stripTrailingProgressPercent(String(display.message || display.stepTitle || '').trim()) ||
    '任务执行中'
  const progress = resolveStudioDisplayProgress({ ...input, eta: display.eta ?? input.eta })
  if (progress == null) return { stage }
  return { stage, progress }
}

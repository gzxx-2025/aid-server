import { describe, expect, it } from 'vitest'
import {
  formatTaskSseLiveText,
  parseTaskSseProgressPayload,
  withTaskSseDisplayTiming
} from './taskSseProgressText'

describe('task SSE estimated time display', () => {
  it('shows a readable duration returned through updateTime', () => {
    const parsed = parseTaskSseProgressPayload({
      taskId: 12,
      progress: 30,
      message: '生成中',
      updateTime: '2分钟'
    })

    expect(parsed && withTaskSseDisplayTiming(parsed).message).toBe('生成中，预计2分钟')
  })

  it('does not treat a documented update timestamp as estimated duration', () => {
    const parsed = parseTaskSseProgressPayload({
      taskId: 12,
      progress: 30,
      message: '生成中',
      updateTime: '2026-08-30 12:00:00'
    })

    expect(parsed && withTaskSseDisplayTiming(parsed).message).toBe('生成中')
  })

  it('uses eta remaining time and avoids appending it twice', () => {
    const parsed = parseTaskSseProgressPayload({
      taskId: 12,
      progress: 30,
      message: '生成中',
      eta: {
        displayProgress: 30,
        remainingSecondsP50: 120,
        calculatedAt: 1_000
      }
    })
    const display = parsed && withTaskSseDisplayTiming(parsed, 1_000)

    expect(display?.message).toBe('生成中，预计约 2 分钟 · 30%')
    expect(display && formatTaskSseLiveText(display, '')).toBe(
      '生成中，预计约 2 分钟 · 30%'
    )
  })
})

import { describe, expect, it } from 'vitest'
import { formatStep3TabTaskProgressText } from './scpTaskUtils'

describe('formatStep3TabTaskProgressText', () => {
  it('does not append a duplicate item count already present in live copy', () => {
    expect(
      formatStep3TabTaskProgressText({
        percent: 10,
        stepTitle: '正在生成形态图 1/1',
        message: '正在生成形态图 1/1',
        stepIndex: 1,
        stepTotal: 1
      })
    ).toBe('10% 正在生成形态图 1/1')
  })

  it('keeps the more complete copy when title and message overlap', () => {
    expect(
      formatStep3TabTaskProgressText({
        percent: 35,
        stepTitle: '正在生成形态图',
        message: '正在生成形态图 2/4',
        stepIndex: 2,
        stepTotal: 4
      })
    ).toBe('35% 正在生成形态图 2/4')
  })

  it('appends the count once when live copy does not include it', () => {
    expect(
      formatStep3TabTaskProgressText({
        percent: 50,
        stepTitle: '正在生成形态图',
        message: '',
        stepIndex: 1,
        stepTotal: 2
      })
    ).toBe('50% 正在生成形态图（1/2）')
  })
})

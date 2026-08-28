import { describe, expect, it } from 'vitest'

import { resolveStoryboardDesignProgressCounts } from './creationModeUiRules'

describe('resolveStoryboardDesignProgressCounts', () => {
  it.each(['pro', 'multi'])('仅分镜脚本模式 %s 按全部完成展示', (creationMode) => {
    expect(resolveStoryboardDesignProgressCounts(creationMode, 3, 0)).toEqual({
      completed: 3,
      total: 3
    })
  })

  it('需要分镜图的模式仍按实际图片完成数展示', () => {
    expect(resolveStoryboardDesignProgressCounts('i2v', 3, 1)).toEqual({
      completed: 1,
      total: 3
    })
  })

  it('空分镜保持 0/0', () => {
    expect(resolveStoryboardDesignProgressCounts('pro', 0, 4)).toEqual({
      completed: 0,
      total: 0
    })
  })
})

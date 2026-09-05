import { describe, expect, it } from 'vitest'
import { createAsyncPromptApplyGuard } from './asyncPromptApplyGuard'

describe('async prompt apply guard', () => {
  it('invalidates a pending result after a user edit', () => {
    const guard = createAsyncPromptApplyGuard()
    const ticket = guard.begin('scene:1')
    guard.markEdited()
    expect(guard.isCurrent(ticket, 'scene:1')).toBe(false)
  })

  it('only allows the newest operation in the same prompt channel', () => {
    const guard = createAsyncPromptApplyGuard()
    const first = guard.begin('scene:1')
    const second = guard.begin('scene:1')
    expect(guard.isCurrent(first, 'scene:1')).toBe(false)
    expect(guard.isCurrent(second, 'scene:1')).toBe(true)
  })

  it('rejects a result after switching storyboard scope', () => {
    const guard = createAsyncPromptApplyGuard()
    const ticket = guard.begin('scene:1')
    expect(guard.isCurrent(ticket, 'scene:2')).toBe(false)
  })
})

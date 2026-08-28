import { beforeEach,describe,expect,it,vi } from 'vitest'
import { suspendTaskSseFollowSlots,type TaskSseFollowSlot } from './taskSseFollowRegistry'
import {
claimTaskStreamConnectSlot,
resetTaskStreamConnectGuardForTests
} from './taskStreamConnectGuard'
describe('task SSE guards', () => {
  beforeEach(() => {
    resetTaskStreamConnectGuardForTests()
  })

  it('suspends only the requested live task without cancelling other slots', () => {
    const firstAbort = vi.fn()
    const secondAbort = vi.fn()
    const slots = new Map<number, TaskSseFollowSlot>([
      [11, { abort: firstAbort, superseded: false }],
      [22, { abort: secondAbort, superseded: false }]
    ])

    expect(suspendTaskSseFollowSlots(slots, 11)).toBe(1)
    expect(firstAbort).toHaveBeenCalledOnce()
    expect(secondAbort).not.toHaveBeenCalled()
    expect(slots.get(11)?.superseded).toBe(true)
    expect(suspendTaskSseFollowSlots(slots, 11)).toBe(0)
  })

  it('marks every live follow superseded even when one abort throws', () => {
    const slots = new Map<number, TaskSseFollowSlot>([
      [11, { abort: () => { throw new Error('closed') }, superseded: false }],
      [22, { abort: vi.fn(), superseded: false }]
    ])

    expect(suspendTaskSseFollowSlots(slots)).toBe(2)
    expect([...slots.values()].every((slot) => slot.superseded)).toBe(true)
  })

  it('rejects a same-task connection storm while isolating other task ids', () => {
    for (let index = 0; index < 3; index += 1) {
      expect(
        claimTaskStreamConnectSlot(101, { now: 1_000 + index, windowMs: 100, maxConnects: 3 })
      ).toBe(true)
    }

    expect(claimTaskStreamConnectSlot(101, { now: 1_010, windowMs: 100, maxConnects: 3 })).toBe(
      false
    )
    expect(claimTaskStreamConnectSlot(202, { now: 1_010, windowMs: 100, maxConnects: 3 })).toBe(
      true
    )
    expect(claimTaskStreamConnectSlot(101, { now: 1_200, windowMs: 100, maxConnects: 3 })).toBe(
      true
    )
  })
})

import { beforeEach,describe,expect,it,vi } from 'vitest'
const mocks = vi.hoisted(() => ({
  invalidateUserTaskListCache: vi.fn(),
  userTaskListRecentPage: vi.fn()
}))

vi.mock('./businessApi', () => mocks)

import {
beginFlowTaskListQuietWindow,
endFlowTaskListQuietWindow,
fetchFlowUserTaskList,
getCachedFlowUserTaskList,
invalidateFlowUserTaskListCache
} from './userTaskListFlowOnce'
describe('flow task list request scheduler', () => {
  beforeEach(() => {
    invalidateFlowUserTaskListCache()
    mocks.invalidateUserTaskListCache.mockReset()
    mocks.userTaskListRecentPage.mockReset()
  })

  it('shares one inflight read and then serves the project cache', async () => {
    let resolveRequest!: (rows: Array<{ id: number }>) => void
    mocks.userTaskListRecentPage.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveRequest = resolve
      })
    )

    const first = fetchFlowUserTaskList(9, { intent: 'read' })
    const duplicate = fetchFlowUserTaskList(9, { intent: 'bootstrap' })

    expect(duplicate).toBe(first)
    expect(mocks.userTaskListRecentPage).toHaveBeenCalledOnce()

    resolveRequest([{ id: 1 }])
    await expect(first).resolves.toEqual([{ id: 1 }])
    await expect(fetchFlowUserTaskList(9, { intent: 'read' })).resolves.toEqual([{ id: 1 }])
    expect(mocks.userTaskListRecentPage).toHaveBeenCalledOnce()
    expect(getCachedFlowUserTaskList(9)).toEqual([{ id: 1 }])
  })

  it('coalesces concurrent mutate requests and invalidates the lower cache once', async () => {
    mocks.userTaskListRecentPage.mockResolvedValueOnce([{ id: 2 }])

    const first = fetchFlowUserTaskList(9, { intent: 'mutate' })
    const duplicate = fetchFlowUserTaskList(9, { intent: 'mutate' })

    expect(duplicate).toBe(first)
    await first
    expect(mocks.invalidateUserTaskListCache).toHaveBeenCalledOnce()
    expect(mocks.userTaskListRecentPage).toHaveBeenCalledOnce()
  })

  it('defers mutations during nested restore windows and flushes only once', async () => {
    mocks.userTaskListRecentPage.mockResolvedValueOnce([{ id: 3 }])
    beginFlowTaskListQuietWindow(9)
    beginFlowTaskListQuietWindow(9)

    await expect(fetchFlowUserTaskList(9, { intent: 'mutate' })).resolves.toEqual([])
    await expect(fetchFlowUserTaskList(9, { intent: 'mutate' })).resolves.toEqual([])
    expect(mocks.userTaskListRecentPage).not.toHaveBeenCalled()

    endFlowTaskListQuietWindow(9)
    expect(mocks.userTaskListRecentPage).not.toHaveBeenCalled()
    endFlowTaskListQuietWindow(9)
    expect(mocks.userTaskListRecentPage).toHaveBeenCalledOnce()

    await vi.waitFor(() => {
      expect(getCachedFlowUserTaskList(9)).toEqual([{ id: 3 }])
    })
  })
})

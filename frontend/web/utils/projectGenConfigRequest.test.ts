import { afterEach, describe, expect, it, vi } from 'vitest'
import { userProjectGenConfigGet } from './businessApi'
import { clearProjectGenConfigCache, fetchProjectGenConfigList } from './projectGenConfig'
import type { ProjectGenConfigVO } from '~/types/business-api'

vi.mock('~/utils/businessApi', () => ({ userProjectGenConfigGet: vi.fn(), userProjectGenConfigSave: vi.fn() }))
const read = vi.mocked(userProjectGenConfigGet)
afterEach(() => { clearProjectGenConfigCache(); vi.resetAllMocks() })

describe('project generation config request ownership', () => {
  it('coalesces concurrent readers including force and caches empty responses', async () => {
    read.mockResolvedValue([])
    await Promise.all([fetchProjectGenConfigList(42), fetchProjectGenConfigList(42, { force: true })])
    await fetchProjectGenConfigList(42)
    expect(read).toHaveBeenCalledTimes(1)
  })
  it('keeps different episode scopes separate', async () => {
    read.mockResolvedValue([])
    await Promise.all([fetchProjectGenConfigList(42, { episodeId: 1 }), fetchProjectGenConfigList(42, { episodeId: 2 })])
    expect(read).toHaveBeenCalledTimes(2)
  })
  it('does not repopulate invalidated cache from an older read', async () => {
    let resolveOld!: (value: ProjectGenConfigVO[]) => void
    read.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const old = fetchProjectGenConfigList(42)
    await Promise.resolve()
    clearProjectGenConfigCache(42)
    const refreshed = fetchProjectGenConfigList(42, { force: true })
    read.mockResolvedValue([])
    resolveOld([{ sceneCode: 'stale' } as ProjectGenConfigVO])
    await old
    expect(await refreshed).toEqual([])
    expect(await fetchProjectGenConfigList(42)).toEqual([])
    expect(read).toHaveBeenCalledTimes(2)
  })
  it('releases failed reads so retry can succeed', async () => {
    read.mockRejectedValueOnce(new Error('offline')).mockResolvedValue([])
    await expect(fetchProjectGenConfigList(42)).rejects.toThrow('offline')
    expect(await fetchProjectGenConfigList(42)).toEqual([])
    expect(read).toHaveBeenCalledTimes(2)
  })
})

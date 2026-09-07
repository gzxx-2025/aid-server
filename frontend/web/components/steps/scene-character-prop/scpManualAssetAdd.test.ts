import { describe, expect, it, vi } from 'vitest'
import { createScpDerivedActionOps } from './scpDerivedActionOps'
import { createScpDerivedViewOps } from './scpDerivedViewOps'
import type { ScpCtx, TabKey } from './types'

function state<T>(initial: T) {
  let value = initial
  return { get: () => value, set: (next: T) => { value = next } }
}

function setup(tab: TabKey) {
  const props = { isExtracting: false }
  const store = { isExtractingAssets: false }
  const ctx = {
    props: () => props,
    store: () => store,
    activeTab: state(tab),
    localValue: state({ scenes: [] as string[], characters: [] as string[], props: [] as string[] }),
    step3AssetBootstrapReady: state(true),
    tabAssetLoading: state({ scene: false, character: false, prop: false }),
    manualAssetAdding: state(false),
    addScene: vi.fn(async () => {}),
    addCharacter: vi.fn(async () => {}),
    addProp: vi.fn(async () => {})
  }
  const view = createScpDerivedViewOps(ctx as unknown as ScpCtx)
  const actions = createScpDerivedActionOps(ctx as unknown as ScpCtx, view)
  return { ctx, props, store, view, actions }
}

describe.each(['scene', 'character', 'prop'] as const)('manual %s creation', (tab) => {
  it('allows adding the first asset without extracted content', async () => {
    const { ctx, view, actions } = setup(tab)
    expect(view.topbarAddDisabled()).toBe(false)
    await actions.handleEmptyAssetAddClick()
    expect(ctx.addScene).toHaveBeenCalledTimes(tab === 'scene' ? 1 : 0)
    expect(ctx.addCharacter).toHaveBeenCalledTimes(tab === 'character' ? 1 : 0)
    expect(ctx.addProp).toHaveBeenCalledTimes(tab === 'prop' ? 1 : 0)
    expect(ctx.manualAssetAdding.get()).toBe(false)
  })

  it('does not depend on the contents of this or another tab', () => {
    const { ctx, view } = setup(tab)
    ctx.localValue.set({ scenes: ['场景'], characters: [], props: [] })
    expect(view.topbarAddDisabled()).toBe(false)
    ctx.localValue.set({ scenes: ['场景'], characters: ['角色'], props: ['道具'] })
    expect(view.topbarAddDisabled()).toBe(false)
  })

  it.each(['bootstrap', 'tab-loading', 'extracting', 'store-extracting'])(
    'blocks additions during %s and enables them when ready', async (busy) => {
      const { ctx, props, store, view, actions } = setup(tab)
      if (busy === 'bootstrap') ctx.step3AssetBootstrapReady.set(false)
      if (busy === 'tab-loading') ctx.tabAssetLoading.set({ scene: false, character: false, prop: false, [tab]: true })
      if (busy === 'extracting') props.isExtracting = true
      if (busy === 'store-extracting') store.isExtractingAssets = true
      expect(view.topbarAddDisabled()).toBe(true)
      await actions.handleEmptyAssetAddClick()
      expect(ctx.addScene).not.toHaveBeenCalled()
      expect(ctx.addCharacter).not.toHaveBeenCalled()
      expect(ctx.addProp).not.toHaveBeenCalled()
      ctx.step3AssetBootstrapReady.set(true)
      ctx.tabAssetLoading.set({ scene: false, character: false, prop: false })
      props.isExtracting = false
      store.isExtractingAssets = false
      expect(view.topbarAddDisabled()).toBe(false)
    }
  )

  it('ignores repeated clicks until creation completes, then allows another asset', async () => {
    const { ctx, view, actions } = setup(tab)
    let finish!: () => void
    const add = tab === 'scene' ? ctx.addScene : tab === 'character' ? ctx.addCharacter : ctx.addProp
    add.mockImplementationOnce(() => new Promise<void>((resolve) => { finish = resolve }))
    const pending = actions.handleEmptyAssetAddClick()
    expect(view.topbarAddDisabled()).toBe(true)
    await actions.handleEmptyAssetAddClick()
    expect(add).toHaveBeenCalledTimes(1)
    finish()
    await pending
    expect(view.topbarAddDisabled()).toBe(false)
    await actions.handleEmptyAssetAddClick()
    expect(add).toHaveBeenCalledTimes(2)
  })

  it('releases the submission lock after a failure', async () => {
    const { ctx, view, actions } = setup(tab)
    const add = tab === 'scene' ? ctx.addScene : tab === 'character' ? ctx.addCharacter : ctx.addProp
    add.mockRejectedValueOnce(new Error('creation failed'))
    await expect(actions.handleEmptyAssetAddClick()).rejects.toThrow('creation failed')
    expect(view.topbarAddDisabled()).toBe(false)
    await actions.handleEmptyAssetAddClick()
    expect(add).toHaveBeenCalledTimes(2)
  })
})

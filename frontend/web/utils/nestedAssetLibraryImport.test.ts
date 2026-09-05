import { describe, expect, it } from 'vitest'
import { runNestedAssetLibraryImport } from './nestedAssetLibraryImport'

describe('runNestedAssetLibraryImport', () => {
  it('closes the nested asset library before importing, then closes the owner', async () => {
    const order: string[] = []

    const accepted = await runNestedAssetLibraryImport({
      closeAssetLibrary: () => {
        order.push('assetLibrary')
      },
      importAsset: async (payload) => {
        order.push(`import:${payload.url}`)
        return true
      },
      closeOwner: () => {
        order.push('owner')
      },
      payload: { url: 'https://cdn.example.com/scene.png' }
    })

    expect(accepted).toBe(true)
    expect(order).toEqual(['assetLibrary', 'import:https://cdn.example.com/scene.png', 'owner'])
  })

  it('keeps the owner open when persistence rejects the payload', async () => {
    const order: string[] = []

    const accepted = await runNestedAssetLibraryImport({
      closeAssetLibrary: () => {
        order.push('assetLibrary')
      },
      importAsset: async () => {
        order.push('import')
        return false
      },
      closeOwner: () => {
        order.push('owner')
      },
      payload: { url: 'https://cdn.example.com/scene.png' }
    })

    expect(accepted).toBe(false)
    expect(order).toEqual(['assetLibrary', 'import'])
  })
})

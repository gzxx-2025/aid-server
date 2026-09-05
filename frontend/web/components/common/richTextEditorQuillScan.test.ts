import { describe, expect, it } from 'vitest'
import { findAssetEmbedIndexByMatch } from './richTextEditorQuillScan'

function quillWithAssets(values: Array<Record<string, unknown>>) {
  return {
    getContents: () => ({
      ops: values.map((promptAssetRef) => ({ insert: { promptAssetRef } }))
    })
  } as unknown as Parameters<typeof findAssetEmbedIndexByMatch>[0]
}

describe('rich text prompt asset matching', () => {
  it('uses exact id for stable duplicate-name assets', () => {
    const quill = quillWithAssets([
      { assetId: 'image-1', assetType: 'other', name: '同名素材', imageIndex: 1 },
      { assetId: 'image-2', assetType: 'other', name: '同名素材', imageIndex: 2 }
    ])
    expect(
      findAssetEmbedIndexByMatch(quill, {
        assetId: 'image-2',
        assetType: 'other',
        name: '同名素材'
      })
    ).toBe(1)
    expect(
      findAssetEmbedIndexByMatch(quill, {
        assetId: 'image-3',
        assetType: 'other',
        name: '同名素材'
      })
    ).toBeNull()
  })

  it('allows a unique provisional embed to hydrate to a stable id', () => {
    const quill = quillWithAssets([
      { assetId: 'placeholder-1-素材', assetType: 'other', name: '素材', imageIndex: 1 }
    ])
    expect(
      findAssetEmbedIndexByMatch(quill, {
        assetId: 'image-1',
        assetType: 'other',
        name: '素材'
      })
    ).toBe(0)
  })
})

import { describe, expect, it } from 'vitest'
import {
  dedupePromptAssets,
  findPromptAsset,
  mergePromptAssets,
  patchEmptyResolvedPromptAssets,
  type PromptAssetItem
} from './storyboardPromptAssetCore'

function asset(
  assetId: string,
  name: string,
  imageIndex: number,
  assetType: PromptAssetItem['assetType'] = 'other',
  url = ''
): PromptAssetItem {
  return { assetId, name, imageIndex, assetType, url, label: `@${name}` }
}

describe('storyboard prompt asset identity', () => {
  it('keeps duplicate names and separates image/audio index namespaces', () => {
    const result = dedupePromptAssets([
      asset('image-1', '同名素材', 1),
      asset('image-2', '同名素材', 1),
      asset('image-1', '重复记录', 3),
      asset('image-1', '同名音频', 1, 'audio')
    ])

    expect(result.map((item) => [item.assetType, item.assetId, item.imageIndex])).toEqual([
      ['other', 'image-1', 1],
      ['other', 'image-2', 2],
      ['audio', 'image-1', 1]
    ])
  })

  it('does not associate an ambiguous duplicate name with the wrong asset', () => {
    const candidates = [
      asset('image-1', '同名素材', 1, 'other', '/first.png'),
      asset('image-2', '同名素材', 2, 'other', '/second.png')
    ]
    expect(findPromptAsset(candidates, { name: '同名素材' })).toBeUndefined()

    const unresolved = [
      asset('resolved-1-同名素材', '同名素材', 1),
      asset('resolved-2-同名素材', '同名素材', 2)
    ]
    expect(patchEmptyResolvedPromptAssets(unresolved, candidates)).toEqual(unresolved)
  })

  it('hydrates one unique provisional asset but preserves distinct same-name assets', () => {
    const merged = mergePromptAssets(
      [asset('resolved-1-唯一素材', '唯一素材', 1)],
      [
        asset('image-1', '唯一素材', 1, 'scene', '/one.png'),
        asset('image-2', '唯一素材', 1, 'scene', '/two.png')
      ]
    )

    expect(merged).toHaveLength(2)
    expect(merged[0]).toMatchObject({ assetId: 'image-1', imageIndex: 1, url: '/one.png' })
    expect(merged[1]).toMatchObject({ assetId: 'image-2', imageIndex: 2, url: '/two.png' })
  })
})

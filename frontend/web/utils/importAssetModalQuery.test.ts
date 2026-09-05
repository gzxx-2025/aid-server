import { describe, expect, it } from 'vitest'
import { resolveImportAssetDisplayMode } from './importAssetModalQuery'

describe('resolveImportAssetDisplayMode', () => {
  it('renders storyboard video categories as video cards even without file extensions', () => {
    expect(
      resolveImportAssetDisplayMode(
        [{ type: 'script', thumbnail: '' }],
        'storyboard_video'
      )
    ).toBe('video')
  })

  it('renders an all-video result set as video cards', () => {
    expect(resolveImportAssetDisplayMode([{ type: 'video' }, { type: 'video' }], null)).toBe(
      'video'
    )
  })

  it('keeps image categories in image-card mode', () => {
    expect(resolveImportAssetDisplayMode([{ type: 'image' }], 'storyboard_image')).toBe('image')
  })
})

import { describe, expect, it } from 'vitest'
import { computeCreateEditorScale, createEditorScaleStyle } from './createEditorViewport'

describe('creation editor viewport sizing', () => {
  it.each([
    [1920, 1080, 1], [2560, 1440, 4 / 3], [3072, 1728, 1.6], [3840, 2160, 2],
    // 4K desktop at 150%, 200%, 250% and 300%: use available CSS dimensions.
    [2560, 1440, 4 / 3], [1920, 1080, 1], [1536, 864, 1], [1280, 720, 1],
    [1366, 768, 1], [3840, 1080, 1], [2560, 1200, 1200 / 1080], [7680, 4320, 2]
  ])('fits %i × %i without a second zoom multiplier', (width, height, scale) => {
    expect(computeCreateEditorScale(width, height)).toBeCloseTo(scale)
  })

  it('falls back safely before a valid viewport is available', () => {
    expect(computeCreateEditorScale(0, 0)).toBe(1)
    expect(computeCreateEditorScale(Number.NaN, 1080)).toBe(1)
    expect(computeCreateEditorScale(3840, Infinity)).toBe(1)
  })

  it('exposes size tokens without changing the browser coordinate system', () => {
    expect(createEditorScaleStyle(2)).toHaveProperty('--create-editor-scale', 2)
    expect(createEditorScaleStyle(2)).not.toHaveProperty('zoom')
    expect(createEditorScaleStyle(2)).not.toHaveProperty('transform')
  })
})

import { describe, expect, it } from 'vitest'
import { isSameResolvedStyleSnapshot } from './projectStyleSelection'

describe('isSameResolvedStyleSnapshot', () => {
  const resolved = {
    id: 'OFFICIAL-7',
    name: '软萌三维Q版',
    thumbnail: 'https://example.com/style.webp',
    assetId: 7,
    sourceFlag: 'official' as const,
    assetName: '软萌三维Q版',
    promptText: 'Q版三维动画风格'
  }

  it('requires the loaded thumbnail to be persisted even when the stable id is unchanged', () => {
    expect(isSameResolvedStyleSnapshot({ ...resolved, thumbnail: '' }, resolved)).toBe(false)
  })

  it('does not rewrite an already complete style snapshot', () => {
    expect(isSameResolvedStyleSnapshot({ ...resolved }, resolved)).toBe(true)
  })
})

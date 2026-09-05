import { describe, expect, it } from 'vitest'
import { resolveFormImageClaimIds } from './formImageAutoUse'

describe('resolveFormImageClaimIds', () => {
  it('keeps only the latest generated image in single-main mode', () => {
    expect(resolveFormImageClaimIds([11, 12, 11], true)).toEqual([11])
  })

  it('deduplicates valid ids without collapsing multi-image modes', () => {
    expect(resolveFormImageClaimIds([11, 0, Number.NaN, 12, 11])).toEqual([11, 12])
  })
})

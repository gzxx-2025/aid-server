import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { resolveFlattenedStaticExportRscPath } from '../scripts/staticExportRscPaths.mjs'

describe('static export RSC path flatten', () => {
  it('flattens windows-style nested __next segment files', () => {
    const nested = path.join('out', 'works', '__next.!KGhvbWUp', 'works', '__PAGE__.txt')
    const flat = path.join('out', 'works', '__next.!KGhvbWUp.works.__PAGE__.txt')
    expect(resolveFlattenedStaticExportRscPath(nested)).toBe(flat)
  })

  it('flattens create-flow nested segment files', () => {
    const nested = path.join(
      'out',
      'create',
      'storyboard-script',
      '__next.create',
      'storyboard-script',
      '__PAGE__.txt'
    )
    const flat = path.join(
      'out',
      'create',
      'storyboard-script',
      '__next.create.storyboard-script.__PAGE__.txt'
    )
    expect(resolveFlattenedStaticExportRscPath(nested)).toBe(flat)
  })

  it('returns null when already flat', () => {
    const flat = path.join('out', 'works', '__next.!KGhvbWUp.works.__PAGE__.txt')
    expect(resolveFlattenedStaticExportRscPath(flat)).toBeNull()
  })

  it('returns null for unrelated txt files', () => {
    expect(resolveFlattenedStaticExportRscPath(path.join('out', 'works', 'index.txt'))).toBeNull()
  })
})

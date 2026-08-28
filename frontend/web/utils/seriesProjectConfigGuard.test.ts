import { describe,expect,it } from 'vitest'
import { resolveSeriesProjectConfigAccess } from './seriesProjectConfigGuard'
describe('series project config access', () => {
  it('opens with content-affecting options locked after storyboard generation', () => {
    expect(resolveSeriesProjectConfigAccess({ blocked: true, reason: 'has-storyboard' })).toEqual({
      mode: 'content-locked'
    })
  })

  it('keeps a project without storyboards editable', () => {
    expect(resolveSeriesProjectConfigAccess({ blocked: false })).toEqual({ mode: 'editable' })
  })

  it('remains fail-closed when storyboard status cannot be confirmed', () => {
    expect(
      resolveSeriesProjectConfigAccess({
        blocked: true,
        reason: 'check-failed',
        message: 'network failed'
      })
    ).toEqual({ mode: 'blocked', message: 'network failed' })
  })
})

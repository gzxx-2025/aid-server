import { describe, expect, it } from 'vitest'
import { buildStudioFlowCanvasEntryHref, buildStudioFlowCanvasHref, buildStudioStepsFlowHref } from './studioFlowNavigation'
import { canonicalStudioFlowEpisodeId, parsePositiveStudioId, parseStudioEpisodeId } from './studioFlowEntry'

describe('flow canvas entry scope', () => {
  it('opens a film from works with its canonical episode', () => {
    expect(buildStudioFlowCanvasHref({ projectId: 42, episodeId: 0, from: 'works' }))
      .toBe('/create/studio?projectId=42&episodeId=0&from=works')
    expect(canonicalStudioFlowEpisodeId('movie', 99)).toBe(0)
  })
  it('keeps a selected series episode when switching views', () => {
    expect(buildStudioFlowCanvasEntryHref('?projectId=42&id=42&episodeId=17&from=steps'))
      .toBe('/create/studio?projectId=42&episodeId=17&from=steps')
    expect(buildStudioStepsFlowHref({ projectId: 42, episodeId: 17, step: 'dubbing' }))
      .toBe('/create/dubbing?projectId=42&id=42&episodeId=17')
  })
  it('leaves episode selection to the existing series gate', () => {
    expect(buildStudioFlowCanvasHref({ projectId: 42, from: 'works' }))
      .toBe('/create/studio?projectId=42&from=works')
    expect(buildStudioFlowCanvasEntryHref('?workId=42')).toBe('/create/studio?projectId=42')
  })
  it('rejects invalid project and episode identifiers', () => {
    for (const value of ['-1', 'NaN', '1.5', '9007199254740992']) {
      expect(parsePositiveStudioId(value)).toBeNull()
      expect(parseStudioEpisodeId(value)).toBeNull()
    }
  })
})

import { describe, expect, it } from 'vitest'
import {
  buildCreateFlowStepPrefetchHref,
  resolveCreateFlowStepPreloadOrder
} from './createFlowStepPreload'

describe('create-flow step preloading', () => {
  it('prefetches the exact project and episode route instead of a query-less shell', () => {
    expect(
      buildCreateFlowStepPrefetchHref('storyboard-video', {
        projectId: '18',
        episodeId: '6',
        from: 'works',
        tag: ['a', 'b']
      })
    ).toBe('/create/storyboard-video?projectId=18&episodeId=6&from=works&tag=a&tag=b')
  })

  it('warms adjacent steps before distant client modules', () => {
    expect(
      resolveCreateFlowStepPreloadOrder(
        ['story-script', 'scene-character', 'storyboard-script', 'storyboard-video', 'dubbing', 'preview'],
        'storyboard-script'
      )
    ).toEqual([
      'scene-character',
      'storyboard-video',
      'story-script',
      'dubbing',
      'preview'
    ])
  })
})

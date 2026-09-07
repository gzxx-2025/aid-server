/// <reference types="vite/client" />

import { describe, expect, it } from 'vitest'
import { parse } from 'postcss'
import listSource from '../components/steps/dubbing/DubbingListView.tsx?raw'
import { readCssImportGraph } from './testSupport/readCssImportGraph'

const shared = parse(readCssImportGraph(new URL('../assets/css/storyboard-step-shared.css', import.meta.url)))
const dubbing = parse(readCssImportGraph(new URL('../components/steps/dubbing/dubbing.css', import.meta.url)))

function declarations(css: ReturnType<typeof parse>, selector: string) {
  const values: Record<string, string> = {}
  css.walkRules(rule => {
    if (!rule.selectors.includes(selector)) return
    rule.walkDecls(declaration => { values[declaration.prop] = declaration.value })
  })
  return values
}

describe('dubbing list responsive layout', () => {
  it('uses the shared video-list column and remaining-height media layout', () => {
    expect(listSource).toContain('storyboard-list-body dubbing-list-body')
    expect(listSource).toContain('storyboard-block dubbing-video-block')
    expect(listSource).toContain('storyboard-block dubbing-info-block')
    expect(listSource).toContain('storyboard-list-media dubbing-video-area')
    expect(declarations(shared, '.storyboard-step .storyboard-list-body .storyboard-list-media'))
      .toEqual(declarations(shared, '.storyboard-step .storyboard-list-body .storyboard-video-set'))
    expect(declarations(shared, '.storyboard-step .storyboard-list-body .storyboard-list-media'))
      .toMatchObject({ flex: '1 1 auto', height: 'auto', 'min-height': '0' })
  })

  it('does not let media intrinsic height stretch the grid beyond the row budget', () => {
    expect(declarations(dubbing, '.dubbing-step .dubbing-list-body.dubbing-list-body--compact'))
      .toMatchObject({ 'grid-template-rows': 'minmax(0, 1fr)', height: '100%' })
    const media = declarations(dubbing, '.dubbing-step .dubbing-video-area--list')
    expect(media.height).toBeUndefined()
    expect(media['min-height']).toBeUndefined()
    expect(media['max-height']).toBeUndefined()
    expect(declarations(dubbing, '.dubbing-step .dubbing-video-placeholder--list')['min-height']).toBe('0')
  })

  it('keeps dialogue scrollable and the missing-video mask inside the row body', () => {
    expect(declarations(dubbing, '.dubbing-step .dubbing-dialogue-render'))
      .toMatchObject({ 'min-height': '0', 'overflow-y': 'auto' })
    expect(declarations(dubbing, '.dubbing-step .dubbing-body-mask'))
      .toMatchObject({ position: 'absolute', inset: '0' })
    expect(listSource).toContain('className="dubbing-list-body-shell"')
  })
})

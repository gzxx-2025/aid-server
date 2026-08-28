/// <reference types="vite/client" />

import { describe,expect,it } from 'vitest'
import pageSource from '../app/create/series-episode-list/page.tsx?raw'
import { readCssImportGraph } from './testSupport/readCssImportGraph'
// 源码守护：原断言目标 pages/create/series-episode-list.vue，迁移后拆为 page.tsx + 伴随 css
// （css 的 ?raw 导入会被 vitest 的 CSS 管线拦成空串，这里直接读文件）
const cssSource = readCssImportGraph(
  new URL('../app/create/series-episode-list/series-episode-list.css', import.meta.url)
)
describe('series episode table layout', () => {
  it('keeps episode number and title in the same semantic column', () => {
    expect(pageSource).toContain('series-ep-list__th-col--episode')
    expect(pageSource).toMatch(
      /series-ep-list__cell--ep[\s\S]*series-ep-list__num[\s\S]*series-ep-list__ep-title/
    )
    expect(pageSource).not.toContain('series-ep-list__cell--num')
    expect(cssSource).not.toContain('series-ep-list__cell--num')
  })

  it('uses a shared three-column grid with a left-aligned episode header', () => {
    expect(cssSource).toContain('grid-template-columns: var(--episode-table-columns)')
    expect(cssSource).toMatch(/series-ep-list__th-col--episode\s*{[\s\S]*?text-align:\s*left/)
    expect(cssSource).toMatch(/series-ep-list__cell--ep\s*{[\s\S]*?justify-content:\s*flex-start/)
  })

  it('preserves table columns on narrow screens instead of stacking cells', () => {
    expect(cssSource).toMatch(/@media \(max-width: 900px\)[\s\S]*min-width:\s*620px/)
    expect(cssSource).not.toMatch(/@media \(max-width: 900px\)[\s\S]*grid-template-columns:\s*1fr/)
  })
})

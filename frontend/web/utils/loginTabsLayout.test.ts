import { describe,expect,it } from 'vitest'
import { readCssImportGraph } from './testSupport/readCssImportGraph'
// 源码守护：原断言目标 pages/login.vue <style>，迁移后为登录页伴随 css
// （css 的 ?raw 导入会被 vitest 的 CSS 管线拦成空串，这里直接读文件）
const source = readCssImportGraph(new URL('../components/login/login.css', import.meta.url))
describe('login method tabs layout', () => {
  it('keeps the two login methods in an equal-width horizontal grid', () => {
    expect(source).toMatch(/\.login-page \.form-tabs\s*{[\s\S]*?display:\s*grid/)
    expect(source).toMatch(
      /\.login-page \.form-tabs\s*{[\s\S]*?grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/
    )
    expect(source).toMatch(
      /@media \(max-width: 520px\)[\s\S]*?\.login-page \.form-tabs\s*{[\s\S]*?width:\s*100%/
    )
  })
})

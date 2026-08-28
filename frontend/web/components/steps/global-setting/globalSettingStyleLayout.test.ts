import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const dialogSource = readFileSync(
  new URL('./GlobalSettingStyleDialog.tsx', import.meta.url),
  'utf-8'
)
const globalSettingSource = readFileSync(
  new URL('../GlobalSetting.tsx', import.meta.url),
  'utf-8'
)
const globalSettingCss = readFileSync(
  new URL('./global-setting-global-setting.css', import.meta.url),
  'utf-8'
)
const createProjectCss = readFileSync(
  new URL('../create-first-step-form-body.css', import.meta.url),
  'utf-8'
)

describe('global setting style layout', () => {
  it('uses a stable viewport-safe dialog shell with a compact single-row category bar', () => {
    expect(dialogSource).toContain('width={840}')
    expect(dialogSource).toContain('centered')
    expect(globalSettingCss).toMatch(
      /\.global-setting-style-modal-wrap \.ant-modal\s*\{[^}]*height:\s*min\(698px, calc\(100dvh - 80px\)\) !important;[^}]*max-height:\s*min\(698px, calc\(100dvh - 80px\)\) !important;/s
    )
    expect(globalSettingSource).toContain('className="style-browser-shell"')
    expect(globalSettingCss).toMatch(
      /\.global-setting-style-modal \.style-browser-shell,[^{]*\{[^}]*height:\s*100%;[^}]*overflow:\s*hidden;/s
    )
    expect(globalSettingCss).toMatch(
      /\.global-setting-style-modal \.style-browser-popover__header\s*\{[^}]*padding:\s*12px 24px 8px;/s
    )
    expect(globalSettingCss).toMatch(/\.style-category-filter\s*\{[^}]*flex-wrap:\s*nowrap;/s)
  })

  it('uses four cards per row in the dialog while preserving the compact create form', () => {
    expect(globalSettingCss).toMatch(
      /\.global-setting-style-modal \.styles-grid\s*\{[^}]*repeat\(4,[^}]*gap:\s*10px;/s
    )
    expect(globalSettingCss).toMatch(
      /\.global-setting \.styles-grid\s*\{[^}]*repeat\(6,[^}]*gap:\s*10px;/s
    )
    expect(createProjectCss).toMatch(
      /\.create-first-step-form \.right-panel \.styles-grid\s*\{[^}]*repeat\(7,/s
    )
  })
})

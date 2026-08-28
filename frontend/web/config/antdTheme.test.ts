/// <reference types="vite/client" />

import { theme } from 'antd'
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import providerSource from '../components/app/AntdThemeProvider.tsx?raw'
import layoutSource from '../app/layout.tsx?raw'
import exportProgressSource from '../components/steps/video-preview/exportOps.tsx?raw'
import { AID_ANTD_THEME } from './antdTheme'

const exportProgressCss = readFileSync(
  new URL('../components/steps/video-preview/video-preview-global.css', import.meta.url),
  'utf8'
)

describe('Ant Design application theme', () => {
  it('uses the dark algorithm and dark semantic surfaces for every component state', () => {
    const tokens = theme.getDesignToken(AID_ANTD_THEME)

    expect(AID_ANTD_THEME.algorithm).toBe(theme.darkAlgorithm)
    expect(AID_ANTD_THEME.token).toMatchObject({
      colorBgBase: '#060a12',
      colorBgContainer: '#191a1d',
      colorBgElevated: '#191a1d',
      colorText: '#e6edf3'
    })
    expect(tokens.colorBgContainerDisabled).not.toBe('#ffffff')
    expect(tokens.colorTextDisabled).not.toBe('#00000040')
    expect(tokens.colorError).toBeTruthy()
    expect(tokens.controlOutline).toBe('rgba(14, 89, 250, 0.2)')
  })

  it('covers form controls, pickers and all elevated overlay families with tokens', () => {
    expect(Object.keys(AID_ANTD_THEME.components ?? {})).toEqual(
      expect.arrayContaining([
        'Modal',
        'Message',
        'Drawer',
        'Select',
        'Input',
        'InputNumber',
        'DatePicker',
        'Dropdown',
        'Popover',
        'Tooltip'
      ])
    )
    expect(AID_ANTD_THEME.components?.Select).toMatchObject({
      selectorBg: 'rgba(28, 38, 54, 0.92)',
      activeBorderColor: '#00abd8'
    })
    expect(AID_ANTD_THEME.components?.Message).toMatchObject({ contentBg: '#191a1d' })
  })

  it('targets the AntD v6 message notice surface for video export progress', () => {
    expect(exportProgressSource).toContain("className: 'episode-export-progress-toast-notice'")
    expect(exportProgressCss).toContain(
      '.ant-message-notice.episode-export-progress-toast-notice'
    )
    expect(exportProgressCss).toContain('background: var(--create-surface-modal')
  })

  it('themes both the React tree and static API portal holders', () => {
    expect(layoutSource).toContain('<AntdThemeProvider>')
    expect(providerSource).toContain('ConfigProvider.config({')
    expect(providerSource).toContain('holderRender: renderAidAntdStaticHolder')
    expect(providerSource).toContain("'aid-antd-static-holder'")
    expect(providerSource).toContain('if (staticThemeInstalled) return')
  })
})

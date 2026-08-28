'use client'

import { AID_ANTD_THEME } from '@/config/antdTheme'
import { App, ConfigProvider } from 'antd'
import { type ReactNode } from 'react'

export const AID_ANTD_STATIC_HOLDER_CLASS = 'aid-antd-static-holder'

/** Static Modal/message/notification APIs render outside the React provider tree. */
export function renderAidAntdStaticHolder(children: ReactNode) {
  return (
    <ConfigProvider theme={AID_ANTD_THEME} wave={{ disabled: true }}>
      <App component={false}>
        <div className={AID_ANTD_STATIC_HOLDER_CLASS}>{children}</div>
      </App>
    </ConfigProvider>
  )
}

let staticThemeInstalled = false

/** Idempotent for React Strict Mode and Fast Refresh. */
export function installAidAntdStaticTheme(): void {
  if (staticThemeInstalled) return
  staticThemeInstalled = true
  ConfigProvider.config({
    theme: AID_ANTD_THEME,
    holderRender: renderAidAntdStaticHolder
  })
}

export function AntdThemeProvider({ children }: { children: ReactNode }) {
  return (
    <ConfigProvider theme={AID_ANTD_THEME} wave={{ disabled: true }}>
      <App component={false}>{children}</App>
    </ConfigProvider>
  )
}

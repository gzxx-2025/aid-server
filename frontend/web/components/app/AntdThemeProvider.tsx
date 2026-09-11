'use client'

import { AID_ANTD_THEME } from '@/config/antdTheme'
import { App, ConfigProvider } from 'antd'
import { type ReactNode } from 'react'
import { getAidPortalRoot } from '~/utils/portalRoot'

export const AID_ANTD_STATIC_HOLDER_CLASS = 'aid-antd-static-holder'

/** 锚定浮层复用 Portal；无锚点的全局消息必须脱离业务 Portal 的 stacking context。 */
export function resolveAidPopupContainer(triggerNode?: HTMLElement): HTMLElement {
  if (!triggerNode) return document.body
  return getAidPortalRoot() ?? triggerNode?.parentElement ?? document.body
}

/** Static Modal/message/notification APIs render outside the React provider tree. */
export function renderAidAntdStaticHolder(children: ReactNode) {
  return (
    <ConfigProvider
      theme={AID_ANTD_THEME}
      wave={{ disabled: true }}
      getPopupContainer={resolveAidPopupContainer}
    >
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
    <ConfigProvider
      theme={AID_ANTD_THEME}
      wave={{ disabled: true }}
      getPopupContainer={resolveAidPopupContainer}
    >
      <App component={false}>{children}</App>
    </ConfigProvider>
  )
}

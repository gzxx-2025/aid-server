'use client'

import { useEffect } from 'react'

/**
 * 壳层 html class 管理：原 Nuxt 各布局通过 htmlAttrs 挂 `app-shell-create` 等 class，
 * 全局 CSS（如 create-steps-ant-overrides.css）以 `html.app-shell-create .ant-*` 为选择器契约。
 */
export function HtmlShellClass({ classes }: { classes: string }) {
  useEffect(() => {
    const list = classes.split(/\s+/).filter(Boolean)
    const el = document.documentElement
    for (const c of list) el.classList.add(c)
    return () => {
      for (const c of list) el.classList.remove(c)
    }
  }, [classes])

  return null
}

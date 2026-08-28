'use client'

import { loadPublicConfig } from '@/hooks/useAuthPublicConfig'
import { resolveSafeHttpUrl } from '~/utils/safeNavigation'

/** 侧栏「新手教程」：链接来自 /auth/public-config → basic */
export function useHomeSidebarExtraNav() {
  async function openTutorial() {
    // await 后从最新配置取值（对齐原 Vue ref 的响应式读取，避免闭包读到旧值）
    const config = await loadPublicConfig()
    const raw = config?.basic?.tutorial_url
    const tutorialUrl = typeof raw === 'string' ? raw.trim() : ''
    const url = resolveSafeHttpUrl(tutorialUrl, window.location.href)
    if (url) {
      window.open(url, '_blank', 'noopener,noreferrer')
    }
  }

  return { openTutorial }
}

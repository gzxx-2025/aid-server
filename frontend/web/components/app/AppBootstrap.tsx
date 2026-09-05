'use client'

import { bootstrapAuthPublicConfig } from '@/hooks/useAuthPublicConfig'
import { useUserStore } from '@/stores/user'
import { installAidAntdStaticTheme } from './AntdThemeProvider'
import { message } from 'antd'
import { useEffect } from 'react'
import { installAppConfirmModalPatch } from '~/utils/appConfirm'

/**
 * 应用启动引导：与原 Nuxt 项目的 client 插件对齐。
 * - ant-design.ts：全局 message 配置
 * - modal-confirm.client.ts：全局替换 Modal.confirm/info/warning/error 默认图标
 * - api-crypto-bootstrap.client.ts：拉 /auth/public-config 同步加密配置
 * - auth-sync.client.ts：恢复登录态并刷新用户资料
 */
export function AppBootstrap() {
  useEffect(() => {
    installAidAntdStaticTheme()
    message.config({ top: 24, duration: 3, maxCount: 1 })
    installAppConfirmModalPatch()

    void bootstrapAuthPublicConfig()

    const userStore = useUserStore.getState()
    userStore.hydrateFromStorage()
    const token = useUserStore.getState().token
    if (token) {
      localStorage.setItem('token', token)
      void useUserStore.getState().fetchProfile()
    }
  }, [])

  return null
}

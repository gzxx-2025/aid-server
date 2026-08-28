'use client'

import { create } from 'zustand'

/**
 * 跨壳层进入创作流程时的全屏遮罩开关。
 * 供「我的作品」等入口在 hydrate / 解析路由阶段提前拉起，
 * 由应用根壳（components/app/AppShellOverlay，原 app.vue）在页面真正就绪
 * （原 page:finish，对应 useRouteReadySignal）后关闭。
 *
 * 原 Nuxt useState('enter-create-flow-overlay') 全局单例 → zustand store。
 */
const useEnterCreateFlowOverlayStore = create<{ pending: boolean }>(() => ({
  pending: false
}))

export function beginEnterCreateFlowOverlay() {
  useEnterCreateFlowOverlayStore.setState({ pending: true })
}

export function endEnterCreateFlowOverlay() {
  useEnterCreateFlowOverlayStore.setState({ pending: false })
}

export function useEnterCreateFlowOverlay() {
  const pending = useEnterCreateFlowOverlayStore((s) => s.pending)

  return {
    enterCreateFlowOverlayPending: pending,
    beginEnterCreateFlowOverlay,
    endEnterCreateFlowOverlay
  }
}

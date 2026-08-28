'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { usePathname } from 'next/navigation'
import { ViewportCompactScaleToggle } from '@/components/common/ViewportCompactScaleToggle'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { useEnterCreateFlowOverlay } from '~/composables/useEnterCreateFlowOverlay'
import { usePublicSiteHead } from '~/composables/usePublicSiteHead'
import { endCreateFlowNavTransition, isCreateFlowNavPath } from '~/utils/createFlowNavSerialize'

import './AppShellOverlay.css'

/**
 * 应用根壳遮罩（原 app.vue）：全局 loading 遮罩 + 跨壳层路由遮罩的状态机。
 *
 * 挂载方式：作为 RouteGuard/页面子树的兄弟节点渲染，两个遮罩仅作覆盖层——
 * 原注释「全局 loading 仅作为遮罩，不再通过 v-if 卸载 NuxtLayout（避免路由切换时空 vnode 报错）」，
 * React 侧同样禁止用条件卸载页面子树实现遮罩，这里的条件渲染只作用于遮罩 div 本身。
 *
 * 与原 Nuxt 生命周期的映射：
 * - router.beforeEach（导航开始）→ usePathname() 变化（Next 无导航前钩子，跨壳层遮罩
 *   在新路径提交时拉起；home → create 方向由入口 beginEnterCreateFlowOverlay 在
 *   router.push 前提前拉起，覆盖了「导航前反馈」的关键场景）
 * - page:finish / page:loading:end（页面真正就绪）→ useRouteReadySignal（见下）
 * - router.onError → Next 无路由错误钩子；导航失败由 MAX_ROUTE_OVERLAY_MS 极端兜底 +
 *   入口失败时主动 endEnterCreateFlowOverlay 兜住，遮罩不会永久卡住
 *
 * 原 nuxtPageEpoch / recoverCreateFlowPageIfNeeded / CREATE_FLOW_PAGE_TRANSITION /
 * resolveNuxtPageKey（createFlowPageKey）/ app:error / vueApp.errorHandler /
 * onUnhandledRejection：全部是 Vue <Transition> out-in 竞态白屏（null.type / null.nextSibling）
 * 的自愈兜底与页面 key 控制。React/Next 侧无 Vue Transition，路由子树由 App Router 直接换挂，
 * 不存在该竞态，故不迁移「抬 key 强制重挂」逻辑；isCreateFlowTransitionCrashError 因此在
 * React 侧无消费者（保留在 utils/createFlowNavSerialize 中，仅供排查参考）。
 *
 * 注意：原 watch(route.fullPath) 会被「仅 query 变化」触发；React 侧以 usePathname()（不含
 * query）为导航信号，query-only 变化不视为导航——home 壳与 create 壳内的 query 变化在原逻辑
 * 中同样不会亮遮罩/loading，行为一致。
 */

const MIN_LOADING_MS = 320
const MIN_ROUTE_OVERLAY_MS = 420
/** 极端兜底：避免 chunk 失败导致遮罩永久卡住 */
const MAX_ROUTE_OVERLAY_MS = 20000

/** 首页壳层路由：侧栏 /、/works、/assets 之间切换不显示全屏 loading */
function isHomeShellPath(fullPath: string): boolean {
  const path = (fullPath.split('?')[0].split('#')[0] || '/').replace(/\/$/, '') || '/'
  if (path === '/') return true
  if (path === '/works' || path.startsWith('/works/')) return true
  if (path === '/assets' || path.startsWith('/assets/')) return true
  return false
}

function isCreateFlowPath(fullPath: string): boolean {
  return isCreateFlowNavPath(fullPath)
}

/**
 * 路由就绪信号：对应原 nuxtApp.hook('page:finish') / hook('page:loading:end')。
 * Next App Router 没有页面级生命周期事件，用 usePathname() 变化作为「导航已提交」信号；
 * 提交后再等双 requestAnimationFrame（新路由子树已挂载并完成一帧绘制提交）视为「页面真正就绪」。
 * 与 page:finish 一样，首屏挂载后也会触发一次（收遮罩入口有 awaiting 守卫，天然幂等）。
 */
function useRouteReadySignal(onReady: () => void) {
  const pathname = usePathname()
  const onReadyRef = useRef(onReady)
  onReadyRef.current = onReady

  useEffect(() => {
    let secondRaf = 0
    const firstRaf = requestAnimationFrame(() => {
      secondRaf = requestAnimationFrame(() => {
        onReadyRef.current()
      })
    })
    return () => {
      cancelAnimationFrame(firstRaf)
      if (secondRaf) cancelAnimationFrame(secondRaf)
    }
  }, [pathname])
}

export function AppShellOverlay() {
  usePublicSiteHead()

  const pathname = usePathname()
  const { siteName } = useAuthPublicConfig()
  /**
   * 全屏加载品牌统一走 /auth/public-config → basic.site_name。
   * 未返回前用中文兜底，避免旧英文 fallback「AI Director」与接口中文名来回跳。
   * （首屏还会读 sessionStorage 缓存，多数情况一进来就是接口名。）
   */
  const brandDisplayName = siteName || 'AI 创作平台'
  const brandShortName = siteName || 'AI 创作平台'
  const { enterCreateFlowOverlayPending, endEnterCreateFlowOverlay } = useEnterCreateFlowOverlay()

  // 全局 loading 仅作为遮罩，不再通过 v-if 卸载 NuxtLayout（避免路由切换时空 vnode 报错）
  const [showGlobalLoading, setShowGlobalLoading] = useState(
    () => !isCreateFlowNavPath(pathname)
  )
  const [routeOverlayVisible, setRouteOverlayVisible] = useState(false)
  /** 本次跨壳层遮罩是否仍在等待页面真正就绪 */
  const routeOverlayAwaitingPageRef = useRef(false)
  /** routeOverlayVisible 的 ref 镜像：状态机回调需要读到最新值（原 Vue ref 的 .value 语义） */
  const routeOverlayVisibleRef = useRef(false)

  /** 当前路径 / pending 的 ref 镜像，供 setTimeout / rAF 回调读取最新值 */
  const pathnameRef = useRef(pathname)
  pathnameRef.current = pathname
  const pendingRef = useRef(enterCreateFlowOverlayPending)
  pendingRef.current = enterCreateFlowOverlayPending

  const loadingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const navTokenRef = useRef(0)
  const routeOverlayTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const routeOverlayFailSafeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const routeOverlayStartAtRef = useRef(0)
  const routeOverlayHideTokenRef = useRef(0)

  const setRouteOverlayVisibleWithRef = useCallback((visible: boolean) => {
    routeOverlayVisibleRef.current = visible
    setRouteOverlayVisible(visible)
  }, [])

  const clearLoadingTimer = useCallback(() => {
    if (!loadingTimerRef.current) return
    clearTimeout(loadingTimerRef.current)
    loadingTimerRef.current = null
  }, [])

  const clearRouteOverlayTimer = useCallback(() => {
    if (!routeOverlayTimerRef.current) return
    clearTimeout(routeOverlayTimerRef.current)
    routeOverlayTimerRef.current = null
  }, [])

  const clearRouteOverlayFailSafeTimer = useCallback(() => {
    if (!routeOverlayFailSafeTimerRef.current) return
    clearTimeout(routeOverlayFailSafeTimerRef.current)
    routeOverlayFailSafeTimerRef.current = null
  }, [])

  const enterLoading = useCallback(() => {
    if (isCreateFlowNavPath(pathnameRef.current)) {
      setShowGlobalLoading(false)
      return
    }
    setShowGlobalLoading(true)
  }, [])

  const leaveLoadingWithDelay = useCallback(() => {
    if (isCreateFlowNavPath(pathnameRef.current)) {
      setShowGlobalLoading(false)
      return
    }
    const token = ++navTokenRef.current
    clearLoadingTimer()
    loadingTimerRef.current = setTimeout(() => {
      if (token !== navTokenRef.current) return
      setShowGlobalLoading(false)
    }, MIN_LOADING_MS)
  }, [clearLoadingTimer])

  /**
   * 等页面真正就绪后再关遮罩（至少展示 MIN_ROUTE_OVERLAY_MS）。
   * 不能在 router.afterEach 立刻关：首次进入流程页时 chunk/布局仍可能在加载，
   * 过早关闭会露出「我的作品」再空等几秒。
   */
  const hideRouteOverlayWhenReady = useCallback(() => {
    if (!routeOverlayVisibleRef.current && !routeOverlayAwaitingPageRef.current) {
      endEnterCreateFlowOverlay()
      return
    }
    const token = ++routeOverlayHideTokenRef.current
    const elapsed = Date.now() - routeOverlayStartAtRef.current
    const wait = Math.max(0, MIN_ROUTE_OVERLAY_MS - elapsed)
    clearRouteOverlayTimer()
    routeOverlayTimerRef.current = setTimeout(() => {
      if (token !== routeOverlayHideTokenRef.current) return
      routeOverlayAwaitingPageRef.current = false
      setRouteOverlayVisibleWithRef(false)
      endEnterCreateFlowOverlay()
      clearRouteOverlayFailSafeTimer()
    }, wait)
  }, [
    clearRouteOverlayTimer,
    clearRouteOverlayFailSafeTimer,
    endEnterCreateFlowOverlay,
    setRouteOverlayVisibleWithRef
  ])

  const armRouteOverlayFailSafe = useCallback(() => {
    clearRouteOverlayFailSafeTimer()
    routeOverlayFailSafeTimerRef.current = setTimeout(() => {
      hideRouteOverlayWhenReady()
    }, MAX_ROUTE_OVERLAY_MS)
  }, [clearRouteOverlayFailSafeTimer, hideRouteOverlayWhenReady])

  const showRouteOverlay = useCallback(() => {
    clearRouteOverlayTimer()
    routeOverlayHideTokenRef.current += 1
    routeOverlayStartAtRef.current = Date.now()
    routeOverlayAwaitingPageRef.current = true
    setRouteOverlayVisibleWithRef(true)
    armRouteOverlayFailSafe()
  }, [clearRouteOverlayTimer, setRouteOverlayVisibleWithRef, armRouteOverlayFailSafe])

  // 以 Nuxt 页面就绪为准，而不是 afterEach（afterEach 往往早于 chunk 渲染完成）
  const tryHideRouteOverlayAfterPageReady = useCallback(() => {
    if (!routeOverlayAwaitingPageRef.current) return
    // 入口已拉起遮罩但尚未 router.push：仍停在首页壳层，不能误关
    if (pendingRef.current && isHomeShellPath(pathnameRef.current)) return
    hideRouteOverlayWhenReady()
  }, [hideRouteOverlayWhenReady])

  // 首屏/刷新：短暂全屏 loading（原 onMounted）；卸载时清理所有计时器（原 onBeforeUnmount）
  useEffect(() => {
    leaveLoadingWithDelay()
    return () => {
      clearLoadingTimer()
      clearRouteOverlayTimer()
      clearRouteOverlayFailSafeTimer()
    }
  }, [leaveLoadingWithDelay, clearLoadingTimer, clearRouteOverlayTimer, clearRouteOverlayFailSafeTimer])

  /**
   * 路径变化 = 导航发生。前半段对应原 router.beforeEach（跨壳层遮罩），
   * 后半段对应原 watch(() => route.fullPath)（全屏 loading）。
   */
  const prevPathRef = useRef<string | null>(null)
  useEffect(() => {
    const oldPath = prevPathRef.current
    prevPathRef.current = pathname
    if (!oldPath || pathname === oldPath) return

    // 关键体验点：跨壳层（首页/作品库 <-> 创作流程）切换时，立即给出视觉反馈
    const fromHome = isHomeShellPath(oldPath)
    const toHome = isHomeShellPath(pathname)
    const fromCreate = isCreateFlowPath(oldPath)
    const toCreate = isCreateFlowPath(pathname)

    // 仅在「跨壳层」时显示遮罩：避免打扰 home 内部 tab 切换 & create 内部步骤切换
    const isCrossShell = (fromHome && toCreate) || (fromCreate && toHome)
    if (isCrossShell) {
      showRouteOverlay()
    }

    // /、/works、/assets 之间互跳：不触发全屏 loading
    if (isHomeShellPath(pathname) && isHomeShellPath(oldPath)) {
      return
    }
    enterLoading()
    leaveLoadingWithDelay()
  }, [pathname, showRouteOverlay, enterLoading, leaveLoadingWithDelay])

  useRouteReadySignal(() => {
    // 对应原 CREATE_FLOW_PAGE_TRANSITION.onAfterEnter：流程页就绪后释放导航门闩
    // （完整 out-in 结束后才放行下一跳）。React 侧无 Transition，门闩空转时该调用为幂等 no-op。
    if (isCreateFlowNavPath(pathnameRef.current)) {
      endCreateFlowNavTransition()
    }
    tryHideRouteOverlayAfterPageReady()
  })

  // 入口在 router.push 前就会拉起遮罩（hydrate / 解析步骤），避免点完后仍停在作品页空等
  // （原 watch(enterCreateFlowOverlayPending, ..., { immediate: true })：首挂 pending=false 命中
  //  下方 early return，与 immediate 初次执行行为一致）
  useEffect(() => {
    if (enterCreateFlowOverlayPending) {
      showRouteOverlay()
      return
    }
    // hydrate / 解析失败时入口会主动 end：立即收起，勿干等 page:finish
    if (!routeOverlayVisibleRef.current && !routeOverlayAwaitingPageRef.current) return
    clearRouteOverlayTimer()
    clearRouteOverlayFailSafeTimer()
    routeOverlayHideTokenRef.current += 1
    routeOverlayAwaitingPageRef.current = false
    setRouteOverlayVisibleWithRef(false)
  }, [
    enterCreateFlowOverlayPending,
    showRouteOverlay,
    clearRouteOverlayTimer,
    clearRouteOverlayFailSafeTimer,
    setRouteOverlayVisibleWithRef
  ])

  return (
    <>
      {showGlobalLoading && (
        <div
          className="global-page-loading"
          role="status"
          aria-live="polite"
          aria-label="页面加载中"
        >
          <div className="global-page-loading__inner">
            <div className="global-page-loading__logo">{brandDisplayName}</div>
            <div className="global-page-loading__dots">
              <span></span>
              <span></span>
              <span></span>
            </div>
            <p>页面加载中...</p>
          </div>
        </div>
      )}

      <ViewportCompactScaleToggle />


      {routeOverlayVisible && (
        <div className="route-overlay" role="status" aria-live="polite" aria-label="页面切换中">
          <div className="route-overlay__inner">
            <div className="route-overlay__glow" />
            <div className="route-overlay__brand">{brandShortName}</div>
            <div className="route-overlay__hint">正在进入创作流程…</div>
            <div className="route-overlay__bar" aria-hidden="true">
              <i />
            </div>
          </div>
        </div>
      )}
    </>
  )
}

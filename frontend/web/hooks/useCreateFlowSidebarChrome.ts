'use client'

import { Modal,message } from 'antd'
import { useRouter } from 'next/navigation'
import { useCallback,useEffect,useLayoutEffect,useRef,useState } from 'react'
import type { FloatingPanelHandle } from '~/components/common/UserMenuDropdown'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { useHomeSidebarExtraNav } from '~/composables/useHomeSidebarExtraNav'
import { useUserStore } from '~/stores/user'
import { logoutToPublicHome, requireLogin } from '~/utils/authLoginNavigation'
import { retainFloatingPosition } from '~/utils/reactUpdateGuards'

/**
 * 创作页左侧栏（原 composables/useCreateFlowSidebarChrome.ts）：
 * 与首页一致，「我的作品 / 资产库」走独立页面路由，避免内嵌面板缓存串作品。
 */
export function useCreateFlowSidebarChrome() {
  const router = useRouter()
  const token = useUserStore((s) => s.token)
  const { anyPaymentEnabled, loadPublicConfig } = useAuthPublicConfig()
  useEffect(() => {
    void loadPublicConfig()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const [showRechargeModal, setShowRechargeModal] = useState(false)
  const isLoggedIn = !!token

  const [showUserMenuCard, setShowUserMenuCard] = useState(false)
  const showUserMenuCardRef = useRef(false)
  const userMenuTriggerRef = useRef<HTMLElement | null>(null)
  const userMenuDropdownRef = useRef<FloatingPanelHandle | null>(null)
  const [userMenuCardStyle, setUserMenuCardStyle] = useState<Record<string, string>>({})

  /** 事件回调读取最新登录/支付开关，避免闭包过期 */
  const isLoggedInRef = useRef(isLoggedIn)
  const anyPaymentEnabledRef = useRef(anyPaymentEnabled)
  useLayoutEffect(() => {
    isLoggedInRef.current = isLoggedIn
    anyPaymentEnabledRef.current = anyPaymentEnabled
  }, [anyPaymentEnabled, isLoggedIn])

  const goLogin = useCallback(() => {
    requireLogin()
  }, [])

  const goHomeFromCreate = useCallback(() => {
    router.push('/')
  }, [router])

  const openWorksPanel = useCallback(() => {
    if (!isLoggedInRef.current) {
      requireLogin({ redirect: '/works' })
      return
    }
    router.push('/works')
  }, [router])

  const openAssetsPanel = useCallback(() => {
    if (!isLoggedInRef.current) {
      requireLogin({ redirect: '/assets' })
      return
    }
    router.push('/assets')
  }, [router])

  const openInvite = useCallback(() => {
    if (!isLoggedInRef.current) {
      requireLogin({ redirect: '/invite' })
      return
    }
    router.push('/invite')
  }, [router])

  const { openTutorial } = useHomeSidebarExtraNav()

  const updateUserMenuPosition = useCallback(() => {
    if (!showUserMenuCardRef.current) return
    const trigger = userMenuTriggerRef.current
    if (!trigger) return
    const rect = trigger.getBoundingClientRect()
    const nextStyle = {
      left: `${rect.right + 10}px`,
      top: `${rect.bottom}px`
    }
    setUserMenuCardStyle((current) => retainFloatingPosition(current, nextStyle))
  }, [])

  const setUserMenuTriggerElement = useCallback((element: HTMLElement | null) => {
    userMenuTriggerRef.current = element
  }, [])

  const setUserMenuOpen = useCallback((open: boolean) => {
    showUserMenuCardRef.current = open
    setShowUserMenuCard(open)
  }, [])

  const toggleUserMenu = useCallback(() => {
    const next = !showUserMenuCardRef.current
    setUserMenuOpen(next)
    if (next) {
      setTimeout(() => updateUserMenuPosition(), 0)
    }
  }, [setUserMenuOpen, updateUserMenuPosition])

  const closeUserMenu = useCallback(() => {
    setUserMenuOpen(false)
  }, [setUserMenuOpen])

  const openFaq = useCallback(() => {
    closeUserMenu()
    router.push('/faq')
  }, [closeUserMenu, router])

  const openBilling = useCallback(() => {
    closeUserMenu()
    router.push('/billing')
  }, [closeUserMenu, router])

  const handleLogout = useCallback(() => {
    Modal.confirm({
      className: 'home-confirm-modal',
      wrapClassName: 'create-flow-modal home-confirm-wrap',
      title: '确认退出登录',
      content: '退出后需要重新登录，是否继续？',
      okText: '确定',
      cancelText: '取消',
      centered: true,
      onOk: () => {
        logoutToPublicHome((href) => router.replace(href))
        closeUserMenu()
      }
    })
  }, [closeUserMenu, router])

  const handleDocumentClick = useCallback(
    (event: MouseEvent) => {
      const target = event.target as Node | null
      if (!target) return
      if (userMenuTriggerRef.current?.contains(target)) return
      const floating = userMenuDropdownRef.current?.floatingRoot
      if (floating?.contains(target)) return
      closeUserMenu()
    },
    [closeUserMenu]
  )

  const onRecharge = useCallback(() => {
    if (!isLoggedInRef.current) {
      requireLogin()
      return
    }
    if (!anyPaymentEnabledRef.current) {
      message.warning('暂未开放充值')
      return
    }
    setShowRechargeModal(true)
  }, [])

  const openRechargeFromMenu = useCallback(() => {
    closeUserMenu()
    onRecharge()
  }, [closeUserMenu, onRecharge])

  const handleRechargePaid = useCallback(() => {
    void useUserStore.getState().fetchProfile()
    message.success('充值成功，可继续创作')
  }, [])

  const handleOpenRechargeByEvent = useCallback(() => {
    if (!isLoggedInRef.current) {
      requireLogin()
      return
    }
    if (!anyPaymentEnabledRef.current) {
      message.warning('暂未开放充值')
      return
    }
    setShowRechargeModal(true)
  }, [])

  return {
    showRechargeModal,
    setShowRechargeModal,
    isLoggedIn,
    showUserMenuCard,
    setUserMenuTriggerElement,
    userMenuDropdownRef,
    userMenuCardStyle,
    goLogin,
    goHomeFromCreate,
    openWorksPanel,
    openAssetsPanel,
    openInvite,
    openTutorial,
    toggleUserMenu,
    closeUserMenu,
    openFaq,
    openBilling,
    openRechargeFromMenu,
    handleLogout,
    handleDocumentClick,
    updateUserMenuPosition,
    handleRechargePaid,
    handleOpenRechargeByEvent
  }
}

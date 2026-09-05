'use client'

import { HtmlShellClass } from '@/components/app/HtmlShellClass'
import { Modal,message } from 'antd'
import { usePathname,useRouter } from 'next/navigation'
import { useEffect,useRef,useState,type ReactNode } from 'react'
import RechargeModal from '~/components/common/RechargeModal'
import UserMenuDropdown,{ type FloatingPanelHandle } from '~/components/common/UserMenuDropdown'
import HomeNewSidebar,{ type HomeNewSidebarHandle } from '~/components/layout/HomeNewSidebar'
import CreateFirstStepModal from '~/components/steps/CreateFirstStepModal'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { useHomeSidebarExtraNav } from '~/composables/useHomeSidebarExtraNav'
import { useUserStore } from '~/stores/user'
import { logoutToPublicHome, requireLogin } from '~/utils/authLoginNavigation'
import './HomeNewShell.css'

/**
 * home-new 壳层（原 layouts/home-new.vue）：
 * 左侧固定侧栏 + 右侧滚动主区，承载充值弹窗 / 用户菜单 / 创建作品弹窗。
 */
export function HomeNewShell({ children }: { children: ReactNode }) {
  const pathname = usePathname() ?? ''
  const router = useRouter()
  const token = useUserStore((s) => s.token)
  const { anyPaymentEnabled, loadPublicConfig } = useAuthPublicConfig()
  const { openTutorial } = useHomeSidebarExtraNav()
  /* 原 layouts/home-new.vue：壳层共用的「创建作品第一步」弹窗状态 */
  const homeCreateModal = useHomeShellCreateModal()

  const isLoggedIn = !!token
  const isGalleryRoute = pathname === '/' || pathname === ''
  const isWorksRoute = pathname.startsWith('/works')
  const isAssetsRoute = pathname.startsWith('/assets')
  const isInviteRoute = pathname.startsWith('/invite')

  const [showRechargeModal, setShowRechargeModal] = useState(false)
  const [showUserMenuCard, setShowUserMenuCard] = useState(false)
  const [userMenuCardStyle, setUserMenuCardStyle] = useState<Record<string, string>>({})

  const homeNewSidebarRef = useRef<HomeNewSidebarHandle | null>(null)
  const userMenuDropdownRef = useRef<FloatingPanelHandle | null>(null)

  function getUserMenuTriggerEl(): HTMLElement | null {
    const exposed = homeNewSidebarRef.current?.userMenuTriggerRef
    if (!exposed) return null
    return exposed instanceof HTMLElement ? exposed : null
  }

  function goLogin() {
    requireLogin()
  }

  function goGallery() {
    if (isGalleryRoute) {
      requestAnimationFrame(() => {
        document.querySelector('.home-new-main')?.scrollTo({ top: 0, behavior: 'smooth' })
      })
      return
    }
    router.push('/')
  }

  function goWorks() {
    if (!isLoggedIn) {
      requireLogin({ redirect: '/works' })
      return
    }
    router.push('/works')
  }

  function goAssets() {
    if (!isLoggedIn) {
      requireLogin({ redirect: '/assets' })
      return
    }
    router.push('/assets')
  }

  function toggleUserMenu() {
    setShowUserMenuCard((prev) => {
      const next = !prev
      if (next) {
        requestAnimationFrame(() => updateUserMenuPosition())
      }
      return next
    })
  }

  function closeUserMenu() {
    setShowUserMenuCard(false)
  }

  function openFaq() {
    closeUserMenu()
    router.push('/faq')
  }

  function openBilling() {
    closeUserMenu()
    router.push('/billing')
  }

  function openRechargeFromMenu() {
    closeUserMenu()
    if (!isLoggedIn) {
      requireLogin()
      return
    }
    if (!anyPaymentEnabled) {
      message.warning('暂未开放充值')
      return
    }
    setShowRechargeModal(true)
  }

  function openInvite() {
    closeUserMenu()
    if (!isLoggedIn) {
      requireLogin({ redirect: '/invite' })
      return
    }
    router.push('/invite')
  }

  function handleLogout() {
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
  }

  function handleRechargePaid() {
    void useUserStore.getState().fetchProfile()
    message.success('充值已到账')
  }

  function updateUserMenuPosition() {
    const trigger = getUserMenuTriggerEl()
    if (!trigger) return
    const rect = trigger.getBoundingClientRect()
    setUserMenuCardStyle({
      left: `${rect.right + 10}px`,
      top: `${rect.bottom}px`
    })
  }

  /* 全局监听挂载一次，经 latest-ref 读最新闭包（对齐原 onMounted/onBeforeUnmount 时序） */
  const listenersRef = useRef({
    handleDocumentClick: (_event: MouseEvent) => {},
    handleOpenRechargeByEvent: () => {},
    updateUserMenuPosition: () => {}
  })
  listenersRef.current.handleDocumentClick = (event: MouseEvent) => {
    const target = event.target as Node | null
    if (!target) return
    if (getUserMenuTriggerEl()?.contains(target)) return
    const floating = userMenuDropdownRef.current?.floatingRoot
    if (floating?.contains(target)) return
    closeUserMenu()
  }
  listenersRef.current.handleOpenRechargeByEvent = () => {
    if (!isLoggedIn) {
      goLogin()
      return
    }
    if (!anyPaymentEnabled) {
      message.warning('暂未开放充值')
      return
    }
    setShowRechargeModal(true)
  }
  listenersRef.current.updateUserMenuPosition = updateUserMenuPosition

  useEffect(() => {
    void loadPublicConfig()
    const onDocumentClick = (event: MouseEvent) => listenersRef.current.handleDocumentClick(event)
    const onReposition = () => listenersRef.current.updateUserMenuPosition()
    const onOpenRecharge = () => listenersRef.current.handleOpenRechargeByEvent()
    document.addEventListener('click', onDocumentClick)
    window.addEventListener('resize', onReposition)
    window.addEventListener('scroll', onReposition, true)
    window.addEventListener('open-recharge-modal', onOpenRecharge as EventListener)
    return () => {
      document.removeEventListener('click', onDocumentClick)
      window.removeEventListener('resize', onReposition)
      window.removeEventListener('scroll', onReposition, true)
      window.removeEventListener('open-recharge-modal', onOpenRecharge as EventListener)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="home-layout-root">
      <HtmlShellClass classes="home-page-shell app-shell-create layout-home-new" />
      <div className="home-new-page">
        <HomeNewSidebar
          ref={homeNewSidebarRef}
          useRouterLinks
          galleryActive={isGalleryRoute}
          worksActive={isWorksRoute}
          assetsActive={isAssetsRoute}
          inviteActive={isInviteRoute}
          onBrand={goGallery}
          onGallery={goGallery}
          onWorks={goWorks}
          onAssets={goAssets}
          onTutorial={openTutorial}
          onInvite={openInvite}
          onLogin={goLogin}
          onToggleUserMenu={toggleUserMenu}
        />

        <div className="home-new-main-wrap">
          <main className="home-new-main">
            <div className="home-route-transition-host">
              {/*
                原 Vue 版此处用 Suspense 规避 layout Transition + NuxtPage 叠层导致页面 onMounted
                执行两次（首页 / 我的作品列表接口打成双份，Nuxt #32371 同类问题）。
                React 无该问题；过渡改为按 pathname 重挂载 + CSS 进场动画（参数照抄）。
              */}
              <div key={pathname} className="home-main-route">
                {children}
              </div>
            </div>
          </main>
        </div>
      </div>

      <CreateFirstStepModal
        open={homeCreateModal.showCreateFirstStepModal}
        confirmLoading={homeCreateModal.createConfirmLoading}
        title={homeCreateModal.creationTitleDraft}
        projectType={homeCreateModal.creationProjectTypeDraft}
        syncProjectTypeFromParent={homeCreateModal.syncProjectTypeFromParent}
        aspectRatio={homeCreateModal.creationGlobalSettingDraft.aspectRatio}
        scriptType={homeCreateModal.creationGlobalSettingDraft.scriptType}
        modelStrategy={homeCreateModal.creationGlobalSettingDraft.modelStrategy}
        creationMode={homeCreateModal.creationGlobalSettingDraft.creationMode}
        modelValue={homeCreateModal.creationGlobalSettingDraft}
        onOpenChange={homeCreateModal.setShowCreateFirstStepModal}
        onTitleChange={homeCreateModal.setCreationTitleDraft}
        onProjectTypeChange={homeCreateModal.setCreationProjectTypeDraft}
        onAspectRatioChange={(v) => homeCreateModal.updateGlobalSettingDraftField('aspectRatio', v)}
        onScriptTypeChange={(v) => homeCreateModal.updateGlobalSettingDraftField('scriptType', v)}
        onModelStrategyChange={(v) => homeCreateModal.updateGlobalSettingDraftField('modelStrategy', v)}
        onCreationModeChange={(v) => homeCreateModal.updateGlobalSettingDraftField('creationMode', v)}
        onModelValueChange={homeCreateModal.patchGlobalSettingDraftStyle}
        onConfirm={() => homeCreateModal.handleCreateConfirm()}
      />
      <RechargeModal
        open={showRechargeModal}
        onOpenChange={setShowRechargeModal}
        onPaid={handleRechargePaid}
      />
      <UserMenuDropdown
        ref={userMenuDropdownRef}
        open={showUserMenuCard}
        floatingStyle={userMenuCardStyle}
        onFaq={openFaq}
        onBilling={openBilling}
        onRecharge={openRechargeFromMenu}
        onLogout={handleLogout}
      />
    </div>
  )
}

export default HomeNewShell

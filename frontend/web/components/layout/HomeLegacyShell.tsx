'use client'

import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import { Modal, message } from 'antd'
import { HtmlShellClass } from '@/components/app/HtmlShellClass'
import { RechargeModal } from '@/components/common/RechargeModal'
import PublicBrandLogo from '@/components/atoms/PublicBrandLogo'
import UserMenuDropdown, {
  type UserMenuDropdownHandle
} from '@/components/layout/legacy/UserMenuDropdown'
import CreateFirstStepModal from '~/components/steps/CreateFirstStepModal'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { useUserStore } from '~/stores/user'
import { assetUrl } from '~/utils/assetUrl'
import homeNorIconMod from '~/assets/img/icon/home-nor.svg'
import homeSelIconMod from '~/assets/img/icon/home-sel.svg'
import myWorkNorIconMod from '~/assets/img/icon/myWork-nor.svg'
import myWorkSelIconMod from '~/assets/img/icon/myWork-sel.svg'
import myAssetNorIconMod from '~/assets/img/icon/myAsset-nor.svg'
import myAssetSelIconMod from '~/assets/img/icon/myAsset-sel.svg'
import starlightCoinMod from '~/assets/img/home/starlightCoin.svg'
import navActiveBgMod from '~/assets/img/home/Rectangle.svg'
import groupAvtorMod from '~/assets/img/home/Group-avtor.svg'
import './HomeLegacyShell.css'
import { formatCreditAmount } from '~/components/common/recharge/rechargeFormat'

const homeNorIcon = assetUrl(homeNorIconMod)
const homeSelIcon = assetUrl(homeSelIconMod)
const myWorkNorIcon = assetUrl(myWorkNorIconMod)
const myWorkSelIcon = assetUrl(myWorkSelIconMod)
const myAssetNorIcon = assetUrl(myAssetNorIconMod)
const myAssetSelIcon = assetUrl(myAssetSelIconMod)
const starlightCoinUrl = assetUrl(starlightCoinMod)
const groupAvtorUrl = assetUrl(groupAvtorMod)

/** 原 .nav-item.is-active 的 v-bind(navActiveBgUrl)：改为 CSS 变量注入 */
const navActiveBgUrl = `url("${assetUrl(navActiveBgMod)}")`

type NavKey = 'gallery' | 'works' | 'assets'

/**
 * 旧版宽侧栏布局壳（原 layouts/home.vue）：
 * 案例广场 / 我的作品 / 资产库导航 + 积分充值 + 用户菜单。
 */
export function HomeLegacyShell({ children }: { children: ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()
  const token = useUserStore((s) => s.token)
  const user = useUserStore((s) => s.user)
  const { anyPaymentEnabled, loadPublicConfig } = useAuthPublicConfig()

  const homeCreateModal = useHomeShellCreateModal()

  function onHomeCreateFirstConfirm() {
    return homeCreateModal.handleCreateConfirm()
  }

  const isLoggedIn = !!token

  const isGalleryRoute = pathname === '/' || pathname === ''
  const isWorksRoute = pathname.startsWith('/works')
  const isAssetsRoute = pathname.startsWith('/assets')

  const [hoveredNavKey, setHoveredNavKey] = useState<NavKey | null>(null)

  const displayPoints = !isLoggedIn
    ? '—'
    : formatCreditAmount(Number(user?.balance ?? 0))

  const [showRechargeModal, setShowRechargeModal] = useState(false)
  const [showUserMenuCard, setShowUserMenuCard] = useState(false)
  const userMenuRef = useRef<HTMLDivElement | null>(null)
  const userMenuTriggerRef = useRef<HTMLButtonElement | null>(null)
  const userMenuDropdownRef = useRef<UserMenuDropdownHandle | null>(null)
  const [userMenuCardStyle, setUserMenuCardStyle] = useState<Record<string, string>>({})

  const userLabel = (() => {
    const u = user
    if (!u) return ''
    const raw = u.id?.length ? String(u.id) : ''
    const id = raw ? `ID${raw}` : ''
    return id || u.username || '用户'
  })()

  const userNameLabel = user?.username || '用户'

  const userSubLabel = (() => {
    const u = user
    if (!u) return ''
    return u.email || userLabel
  })()

  function goLogin() {
    router.push('/login')
  }

  function goWorks() {
    if (!isLoggedIn) {
      goLogin()
      return
    }
    router.push('/works')
  }

  function goAssets() {
    if (!isLoggedIn) {
      goLogin()
      return
    }
    router.push('/assets')
  }

  function onRecharge() {
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

  function handleRechargePaid() {
    void useUserStore.getState().fetchProfile()
    message.success('充值已到账')
  }

  function handleOpenRechargeByEvent() {
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

  function toggleUserMenu() {
    const next = !showUserMenuCard
    setShowUserMenuCard(next)
    if (next) {
      requestAnimationFrame(() => updateUserMenuPositionRef.current())
    }
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
    onRecharge()
  }

  function openInvite() {
    closeUserMenu()
    if (!isLoggedIn) {
      goLogin()
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
        useUserStore.getState().logout()
        closeUserMenu()
        router.push('/login')
      }
    })
  }

  function handleDocumentClick(event: MouseEvent) {
    const target = event.target as Node | null
    if (!target) return
    if (userMenuRef.current?.contains(target)) return
    const floating = userMenuDropdownRef.current?.floatingRoot
    if (floating?.contains(target)) return
    closeUserMenu()
  }

  function updateUserMenuPosition() {
    const trigger = userMenuTriggerRef.current
    if (!trigger) return
    const rect = trigger.getBoundingClientRect()
    setUserMenuCardStyle({
      left: `${rect.right + 10}px`,
      top: `${rect.bottom}px`
    })
  }

  // 事件监听只挂一次，回调经 ref 读最新闭包（等价原 onMounted / onBeforeUnmount 时序）
  const handleDocumentClickRef = useRef(handleDocumentClick)
  const updateUserMenuPositionRef = useRef(updateUserMenuPosition)
  const handleOpenRechargeByEventRef = useRef(handleOpenRechargeByEvent)
  useEffect(() => {
    handleDocumentClickRef.current = handleDocumentClick
    updateUserMenuPositionRef.current = updateUserMenuPosition
    handleOpenRechargeByEventRef.current = handleOpenRechargeByEvent
  })

  useEffect(() => {
    void loadPublicConfig()
    const onDocumentClick = (event: MouseEvent) => handleDocumentClickRef.current(event)
    const onReposition = () => updateUserMenuPositionRef.current()
    const onOpenRecharge = () => handleOpenRechargeByEventRef.current()
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
  }, [loadPublicConfig])

  return (
    <div
      className="home-layout-root"
      style={{ '--home-nav-active-bg': navActiveBgUrl } as CSSProperties}
    >
      {/* 与流程页共用 app-shell-create：Modal/Message 等挂载到 body，依赖 html 类命中 create-steps-ant-overrides.css */}
      <HtmlShellClass classes="home-page-shell app-shell-create" />
      <div className="home-page">
        <aside className="sidebar" aria-label="主导航">
          <Link href="/" className="brand">
            <PublicBrandLogo className="logo-img" alt="平台首页" />
          </Link>

          <nav className="nav">
            <Link
              href="/"
              className={`nav-item${isGalleryRoute ? ' is-active' : ''}`}
              onMouseEnter={() => setHoveredNavKey('gallery')}
              onMouseLeave={() => setHoveredNavKey(null)}
            >
              <img
                src={isGalleryRoute || hoveredNavKey === 'gallery' ? homeSelIcon : homeNorIcon}
                alt=""
                className="nav-ico"
                width={18}
                height={18}
              />
              <span>案例广场</span>
            </Link>
            <button
              type="button"
              className={`nav-item${isWorksRoute ? ' is-active' : ''}`}
              onMouseEnter={() => setHoveredNavKey('works')}
              onMouseLeave={() => setHoveredNavKey(null)}
              onClick={goWorks}
            >
              <img
                src={isWorksRoute || hoveredNavKey === 'works' ? myWorkSelIcon : myWorkNorIcon}
                alt=""
                className="nav-ico"
                width={18}
                height={18}
              />
              <span>我的作品</span>
            </button>
            <button
              type="button"
              className={`nav-item${isAssetsRoute ? ' is-active' : ''}`}
              onMouseEnter={() => setHoveredNavKey('assets')}
              onMouseLeave={() => setHoveredNavKey(null)}
              onClick={goAssets}
            >
              <img
                src={isAssetsRoute || hoveredNavKey === 'assets' ? myAssetSelIcon : myAssetNorIcon}
                alt=""
                className="nav-ico"
                width={18}
                height={18}
              />
              <span>资产库</span>
            </button>
          </nav>

          <div className="spacer" />

          <div className="wallet" role="group" aria-label="积分与充值">
            <img src={starlightCoinUrl} alt="" />
            <span className="points-num">{displayPoints}</span>
            {anyPaymentEnabled ? (
              <button type="button" className="recharge" onClick={onRecharge}>
                充值
              </button>
            ) : null}
          </div>

          {isLoggedIn ? (
            <div className="user">
              <div ref={userMenuRef} className="user-menu">
                <button
                  ref={userMenuTriggerRef}
                  type="button"
                  className="user-trigger"
                  aria-label="打开用户菜单"
                  onClick={toggleUserMenu}
                >
                  <div className="avatar">
                    <img src={groupAvtorUrl} alt="" />
                  </div>
                  <div className="user-meta">
                    <span className="user-name" title={userNameLabel}>
                      {userNameLabel}
                    </span>
                    <span className="user-id" title={userSubLabel}>
                      {userSubLabel}
                    </span>
                  </div>
                </button>
              </div>
            </div>
          ) : (
            <div className="user user--guest">
              <button type="button" className="login" onClick={goLogin}>
                登录 / 注册
              </button>
            </div>
          )}
        </aside>

        <div className="main-wrap home_box">
          <main className="main">
            {/* mode="out-in"：先出后进；CreateFirstStepModal 已移到 layout 外（见下方），过渡层内无 Teleport 弹层，避免生产环境卸载报错 */}
            <div className="home-route-transition-host">
              {/* Suspense：避免 layout Transition 导致子页面 onMounted 双触发、列表接口重复请求 */}
              <div key={pathname} className="home-main-route">
                {children}
              </div>
            </div>
          </main>
        </div>
      </div>
      {/* 原 home.vue 第 117-137 行：layout 外挂载 CreateFirstStepModal（过渡层内无 Teleport 弹层） */}
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
        onConfirm={onHomeCreateFirstConfirm}
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
        onInvite={openInvite}
        onLogout={handleLogout}
      />
    </div>
  )
}

export default HomeLegacyShell

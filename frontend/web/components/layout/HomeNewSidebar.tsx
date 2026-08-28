'use client'

import Link from 'next/link'
import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent
} from 'react'
import PublicBrandLogo from '~/components/atoms/PublicBrandLogo'
import SidebarNavHoverIcon from '~/components/atoms/SidebarNavHoverIcon'
import OpenSourcePanel, { type FloatingPanelHandle } from '~/components/common/OpenSourcePanel'
import DiscussionGroupPanel from '~/components/common/DiscussionGroupPanel'
import starlightCoinUrl from '~/assets/img/home/starlightCoin.svg'
import groupAvtorUrl from '~/assets/img/home/Group-avtor.svg'
import tutorialIconUrl from '~/assets/img/icon/xsjc.svg'
import openSourceIconUrl from '~/assets/img/icon/ky.svg'
import discussionGroupIconUrl from '~/assets/img/home/discussion _group.svg'
import inviteIconUrl from '~/assets/img/login/invite.svg'
import { assetUrl } from '~/utils/assetUrl'
import { toLayoutPx } from '~/utils/viewportZoom'
import { useUserStore } from '~/stores/user'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { formatCreditAmount } from '~/components/common/recharge/rechargeFormat'
import './HomeNewSidebar.css'

export interface HomeNewSidebarHandle {
  userMenuTriggerRef: HTMLElement | null
  openSourceTriggerRef: HTMLElement | null
  discussionGroupTriggerRef: HTMLElement | null
}

interface HomeNewSidebarProps {
  galleryActive?: boolean
  worksActive?: boolean
  assetsActive?: boolean
  inviteActive?: boolean
  skeleton?: boolean
  /** 首页壳：品牌与案例广场使用 Link；流程页使用 button */
  useRouterLinks?: boolean
  onBrand?: () => void
  onGallery?: () => void
  onWorks?: () => void
  onAssets?: () => void
  onTutorial?: () => void
  onOpenSource?: () => void
  onDiscussionGroup?: () => void
  onInvite?: () => void
  onLogin?: () => void
  onToggleUserMenu?: () => void
  onUserMenuTriggerChange?: (element: HTMLElement | null) => void
}

const HomeNewSidebar = forwardRef<HomeNewSidebarHandle, HomeNewSidebarProps>(
  function HomeNewSidebar(
    {
      galleryActive = false,
      worksActive = false,
      assetsActive = false,
      inviteActive = false,
      skeleton = false,
      useRouterLinks = false,
      onBrand,
      onGallery,
      onWorks,
      onAssets,
      onTutorial,
      onOpenSource,
      onDiscussionGroup,
      onInvite,
      onLogin,
      onToggleUserMenu,
      onUserMenuTriggerChange
    },
    ref
  ) {
    const token = useUserStore((s) => s.token)
    const user = useUserStore((s) => s.user)
    const {
      openSourceGiteeUrl,
      openSourceGitUrl,
      exchangeImageUrl,
      tutorialUrl,
      invitePromotionEnabled,
      loadPublicConfig
    } = useAuthPublicConfig()

    const isLoggedIn = !!token
    const userAvatarUrl = user?.avatar?.trim() || assetUrl(groupAvtorUrl)
    const displayPoints = isLoggedIn
      ? formatCreditAmount(Number(user?.balance ?? 0))
      : '0'

    const showOpenSourceNav = !skeleton && (!!openSourceGiteeUrl || !!openSourceGitUrl)
    const showDiscussionGroupNav = !skeleton && !!exchangeImageUrl
    const showTutorialNav = !skeleton && !!tutorialUrl

    const userMenuTriggerRef = useRef<HTMLButtonElement | null>(null)
    const setUserMenuTriggerRef = useCallback(
      (element: HTMLButtonElement | null) => {
        userMenuTriggerRef.current = element
        onUserMenuTriggerChange?.(element)
      },
      [onUserMenuTriggerChange]
    )
    const openSourceTriggerRef = useRef<HTMLButtonElement | null>(null)
    const openSourcePanelRef = useRef<FloatingPanelHandle | null>(null)
    const [showOpenSourcePanel, setShowOpenSourcePanel] = useState(false)
    const [openSourcePanelStyle, setOpenSourcePanelStyle] = useState<Record<string, string>>({})

    const discussionGroupTriggerRef = useRef<HTMLButtonElement | null>(null)
    const discussionGroupPanelRef = useRef<FloatingPanelHandle | null>(null)
    const [showDiscussionGroupPanel, setShowDiscussionGroupPanel] = useState(false)
    const [discussionGroupPanelStyle, setDiscussionGroupPanelStyle] = useState<
      Record<string, string>
    >({})

    useImperativeHandle(
      ref,
      () => ({
        get userMenuTriggerRef() {
          return userMenuTriggerRef.current
        },
        get openSourceTriggerRef() {
          return openSourceTriggerRef.current
        },
        get discussionGroupTriggerRef() {
          return discussionGroupTriggerRef.current
        }
      }),
      []
    )

    function onBrandClick(event: ReactMouseEvent) {
      if (useRouterLinks) return
      event.preventDefault()
      onBrand?.()
    }

    function updateOpenSourcePanelPosition() {
      const trigger = openSourceTriggerRef.current
      if (!trigger) return
      const rect = trigger.getBoundingClientRect()
      setOpenSourcePanelStyle({
        left: `${toLayoutPx(rect.right + 12)}px`,
        top: `${toLayoutPx(rect.top)}px`
      })
    }

    function updateDiscussionGroupPanelPosition() {
      const trigger = discussionGroupTriggerRef.current
      if (!trigger) return
      const rect = trigger.getBoundingClientRect()
      setDiscussionGroupPanelStyle({
        left: `${toLayoutPx(rect.right + 12)}px`,
        top: `${toLayoutPx(rect.top)}px`
      })
    }

    function closeOpenSourcePanel() {
      setShowOpenSourcePanel(false)
    }

    function closeDiscussionGroupPanel() {
      setShowDiscussionGroupPanel(false)
    }

    function toggleOpenSourcePanel() {
      const next = !showOpenSourcePanel
      setShowOpenSourcePanel(next)
      if (next) {
        closeDiscussionGroupPanel()
        requestAnimationFrame(() => updateOpenSourcePanelPosition())
      }
      onOpenSource?.()
    }

    function toggleDiscussionGroupPanel() {
      const next = !showDiscussionGroupPanel
      setShowDiscussionGroupPanel(next)
      if (next) {
        closeOpenSourcePanel()
        requestAnimationFrame(() => updateDiscussionGroupPanelPosition())
      }
      onDiscussionGroup?.()
    }

    function handleDocumentClick(event: MouseEvent) {
      const target = event.target as Node | null
      if (!target) return

      if (showOpenSourcePanel) {
        if (!openSourceTriggerRef.current?.contains(target)) {
          const floating = openSourcePanelRef.current?.floatingRoot
          if (!floating?.contains(target)) closeOpenSourcePanel()
        }
      }

      if (showDiscussionGroupPanel) {
        if (!discussionGroupTriggerRef.current?.contains(target)) {
          const floating = discussionGroupPanelRef.current?.floatingRoot
          if (!floating?.contains(target)) closeDiscussionGroupPanel()
        }
      }
    }

    function updateFloatingPanelsPosition() {
      if (showOpenSourcePanel) updateOpenSourcePanelPosition()
      if (showDiscussionGroupPanel) updateDiscussionGroupPanelPosition()
    }

    // 全局监听需要读到最新一轮渲染的闭包，统一走 latestRef
    const latestRef = useRef({
      handleDocumentClick,
      updateFloatingPanelsPosition
    })
    useEffect(() => {
      latestRef.current = {
        handleDocumentClick,
        updateFloatingPanelsPosition
      }
    })

    useEffect(() => {
      if (skeleton) return
      void loadPublicConfig()
      const onDocumentClick = (event: MouseEvent) => latestRef.current.handleDocumentClick(event)
      const onReposition = () => latestRef.current.updateFloatingPanelsPosition()
      document.addEventListener('click', onDocumentClick)
      window.addEventListener('resize', onReposition)
      window.addEventListener('scroll', onReposition, true)

      return () => {
        document.removeEventListener('click', onDocumentClick)
        window.removeEventListener('resize', onReposition)
        window.removeEventListener('scroll', onReposition, true)
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    useEffect(() => {
      if (!showOpenSourceNav) closeOpenSourcePanel()
    }, [showOpenSourceNav])

    useEffect(() => {
      if (!showDiscussionGroupNav) closeDiscussionGroupPanel()
    }, [showDiscussionGroupNav])

    const brandChildren = (
      <PublicBrandLogo className="home-new-logo" alt="平台首页" compactFallback />
    )

    return (
      <aside
        className={skeleton ? 'home-new-sidebar create-sidebar--skeleton' : 'home-new-sidebar'}
        aria-label={skeleton ? undefined : '主导航'}
        aria-hidden={skeleton ? true : undefined}
      >
        {skeleton ? (
          <>
            <div className="skeleton-sidebar-logo" />
            {Array.from({ length: 5 }, (_, i) => (
              <div key={i + 1} className="skeleton-sidebar-nav" />
            ))}
          </>
        ) : (
          <>
            {useRouterLinks ? (
              <Link href="/" className="home-new-brand" aria-label="首页" onClick={onBrandClick}>
                {brandChildren}
              </Link>
            ) : (
              <button
                type="button"
                className="home-new-brand"
                aria-label="首页"
                onClick={onBrandClick}
              >
                {brandChildren}
              </button>
            )}

            <nav className="home-new-nav" aria-label="主要导航">
              <button
                type="button"
                className={galleryActive ? 'home-new-nav-item is-active' : 'home-new-nav-item'}
                onClick={() => onGallery?.()}
              >
                <SidebarNavHoverIcon type="gallery" className="home-new-nav-ico" />
                <span>案例广场</span>
              </button>
              <button
                type="button"
                className={worksActive ? 'home-new-nav-item is-active' : 'home-new-nav-item'}
                onClick={() => onWorks?.()}
              >
                <SidebarNavHoverIcon type="works" className="home-new-nav-ico" />
                <span>我的作品</span>
              </button>
              <button
                type="button"
                className={assetsActive ? 'home-new-nav-item is-active' : 'home-new-nav-item'}
                onClick={() => onAssets?.()}
              >
                <SidebarNavHoverIcon type="assets" className="home-new-nav-ico" />
                <span>资产库</span>
              </button>
            </nav>

            <div className="home-new-nav-divider" aria-hidden="true" />

            <nav className="home-new-nav home-new-nav--secondary" aria-label="帮助与资源">
              {showTutorialNav ? (
                <button
                  type="button"
                  className="home-new-nav-item"
                  onClick={() => onTutorial?.()}
                >
                  <img
                    src={assetUrl(tutorialIconUrl)}
                    alt=""
                    className="home-new-nav-ico home-new-nav-ico-img"
                    width={24}
                    height={24}
                  />
                  <span>新手教程</span>
                </button>
              ) : null}
              {showOpenSourceNav ? (
                <button
                  ref={openSourceTriggerRef}
                  type="button"
                  className={
                    showOpenSourcePanel ? 'home-new-nav-item is-active' : 'home-new-nav-item'
                  }
                  aria-haspopup="dialog"
                  aria-expanded={showOpenSourcePanel}
                  onClick={toggleOpenSourcePanel}
                >
                  <img
                    src={assetUrl(openSourceIconUrl)}
                    alt=""
                    className="home-new-nav-ico home-new-nav-ico-img"
                    width={24}
                    height={24}
                  />
                  <span>开源</span>
                </button>
              ) : null}
              {showDiscussionGroupNav ? (
                <button
                  ref={discussionGroupTriggerRef}
                  type="button"
                  className={
                    showDiscussionGroupPanel ? 'home-new-nav-item is-active' : 'home-new-nav-item'
                  }
                  aria-haspopup="dialog"
                  aria-expanded={showDiscussionGroupPanel}
                  onClick={toggleDiscussionGroupPanel}
                >
                  <img
                    src={assetUrl(discussionGroupIconUrl)}
                    alt=""
                    className="home-new-nav-ico home-new-nav-ico-img"
                    width={24}
                    height={24}
                  />
                  <span>交流群</span>
                </button>
              ) : null}
              {invitePromotionEnabled ? (
                <button
                  type="button"
                  className={inviteActive ? 'home-new-nav-item is-active' : 'home-new-nav-item'}
                  onClick={() => onInvite?.()}
                >
                  <img
                    src={assetUrl(inviteIconUrl)}
                    alt=""
                    className="home-new-nav-ico home-new-nav-ico-img"
                    width={24}
                    height={24}
                  />
                  <span>邀请有礼</span>
                </button>
              ) : null}
            </nav>
          </>
        )}

        <div className="home-new-sidebar-spacer" aria-hidden="true" />

        <div className="home-new-sidebar-bottom">
          {skeleton ? (
            <div className="skeleton-sidebar-footer" />
          ) : isLoggedIn ? (
            <div className="home-new-user">
              <div className="home-new-points" role="group" aria-label="积分">
                <img
                  src={assetUrl(starlightCoinUrl)}
                  alt=""
                  className="home-new-points-ico"
                  width={14}
                  height={14}
                />
                <span className="home-new-points-num">{displayPoints}</span>
              </div>
              <button
                ref={setUserMenuTriggerRef}
                type="button"
                className="home-new-user-btn"
                aria-label="打开用户菜单"
                onClick={() => onToggleUserMenu?.()}
              >
                <span className="home-new-avatar-frame">
                  <img src={userAvatarUrl} alt="" className="home-new-avatar-img" width={30} height={30} />
                </span>
              </button>
            </div>
          ) : (
            <div className="home-new-user home-new-user--guest">
              <button type="button" className="home-new-login" onClick={() => onLogin?.()}>
                登录
              </button>
            </div>
          )}
        </div>

        {!skeleton ? (
          <>
            <OpenSourcePanel
              ref={openSourcePanelRef}
              open={showOpenSourcePanel}
              floatingStyle={openSourcePanelStyle}
              giteeUrl={openSourceGiteeUrl}
              gitUrl={openSourceGitUrl}
            />
            <DiscussionGroupPanel
              ref={discussionGroupPanelRef}
              open={showDiscussionGroupPanel}
              floatingStyle={discussionGroupPanelStyle}
              qrImageUrl={exchangeImageUrl}
            />
          </>
        ) : null}
      </aside>
    )
  }
)

export default HomeNewSidebar

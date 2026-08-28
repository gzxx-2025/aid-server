'use client'

import {
DollarCircleOutlined,
LogoutOutlined,
QuestionCircleOutlined,
WalletOutlined
} from '@ant-design/icons'
import { Switch,Tooltip,message } from 'antd'
import {
forwardRef,
useEffect,
useImperativeHandle,
useRef,
useState,
type CSSProperties
} from 'react'
import { createPortal } from 'react-dom'
import groupAvtorUrl from '~/assets/img/home/Group-avtor.svg'
import type { FloatingPanelHandle } from '~/components/common/OpenSourcePanel'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { useWechatNotifyPreference } from '~/composables/useWechatNotifyPreference'
import { useUserStore } from '~/stores/user'
import { assetUrl } from '~/utils/assetUrl'
import './UserMenuDropdown.css'

export type { FloatingPanelHandle }

interface UserMenuDropdownProps {
  open: boolean
  /** fixed 定位，由父级根据触发按钮计算 */
  floatingStyle?: Record<string, string>
  onFaq?: () => void
  onBilling?: () => void
  onRecharge?: () => void
  onLogout?: () => void
}

const POP_DURATION_MS = 200

/** 对齐 Vue transition 的 nextFrame：两帧后再切换 class 才能触发过渡 */
function nextFrame(cb: () => void) {
  requestAnimationFrame(() => {
    requestAnimationFrame(cb)
  })
}

const UserMenuDropdown = forwardRef<FloatingPanelHandle, UserMenuDropdownProps>(
  function UserMenuDropdown(
    { open, floatingStyle = {}, onFaq, onBilling, onRecharge, onLogout },
    ref
  ) {
    const user = useUserStore((s) => s.user)
    const { anyPaymentEnabled, loadPublicConfig } = useAuthPublicConfig()
    const {
      showWechatNotifyRow,
      wechatNotifyChecked,
      wechatNotifySwitchDisabled,
      toggling: notifyToggling,
      loadPreference,
      setWechatNotifyEnabled
    } = useWechatNotifyPreference()

    const floatingRootRef = useRef<HTMLDivElement | null>(null)
    // rendered 控制 DOM 存在（含离场动画期间），transitionClass 复刻 Vue transition 各阶段 class
    const [rendered, setRendered] = useState(false)
    const [transitionClass, setTransitionClass] = useState('')
    const renderedRef = useRef(false)
    const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

    useImperativeHandle(
      ref,
      () => ({
        get floatingRoot() {
          return floatingRootRef.current
        }
      }),
      []
    )

    useEffect(() => {
      if (open) {
        void loadPreference(true)
        void loadPublicConfig()
      }
    }, [open, loadPreference, loadPublicConfig])

    useEffect(() => {
      if (timerRef.current) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }

      if (open) {
        renderedRef.current = true
        setRendered(true)
        setTransitionClass('user-menu-dropdown-pop-enter-from user-menu-dropdown-pop-enter-active')
        nextFrame(() => {
          setTransitionClass('user-menu-dropdown-pop-enter-active')
          timerRef.current = setTimeout(() => {
            timerRef.current = null
            setTransitionClass('')
          }, POP_DURATION_MS)
        })
        return
      }

      if (!renderedRef.current) return
      setTransitionClass('user-menu-dropdown-pop-leave-active user-menu-dropdown-pop-leave-to')
      timerRef.current = setTimeout(() => {
        timerRef.current = null
        renderedRef.current = false
        setRendered(false)
        setTransitionClass('')
      }, POP_DURATION_MS)
    }, [open])

    useEffect(() => {
      return () => {
        if (timerRef.current) clearTimeout(timerRef.current)
      }
    }, [])

    async function onWechatNotifyChange(checked: boolean | string | number) {
      const result = await setWechatNotifyEnabled(Boolean(checked))
      if (!result.ok && result.message) {
        message.error(result.message)
      }
    }

    const avatar = user?.avatar?.trim()
    const userAvatarUrl = avatar || assetUrl(groupAvtorUrl)

    const menuCardUserName = (() => {
      const name = user?.nickName?.trim()
      if (name) return name
      if (user?.email?.trim()) return user.email.trim()
      if (user?.id) return `ID${user.id}`
      return '用户'
    })()

    const menuCardUserSub = (() => {
      if (!user?.id) return ''
      if (user.username?.trim()) return `ID:${user.id}`
      return ''
    })()

    const menuCardUserTitle = (() => {
      if (!user) return ''
      const parts = [user.username, user.id ? `ID${user.id}` : '', user.email].filter(
        Boolean
      ) as string[]
      return parts.join(' · ')
    })()

    if (!rendered || typeof document === 'undefined') return null

    return createPortal(
      <div
        ref={floatingRootRef}
        className={transitionClass ? `user-menu-dropdown ${transitionClass}` : 'user-menu-dropdown'}
        style={floatingStyle as CSSProperties}
        role="menu"
        aria-label="用户菜单"
      >
        <div className="user-menu-dropdown__header">
          <div className="user-menu-dropdown__avatar" aria-hidden="true">
            <img src={userAvatarUrl} alt="" width={44} height={44} />
          </div>
          <div className="user-menu-dropdown__meta">
            <span className="user-menu-dropdown__name" title={menuCardUserTitle}>
              {menuCardUserName}
            </span>
            {menuCardUserSub ? (
              <span className="user-menu-dropdown__sub">{menuCardUserSub}</span>
            ) : null}
          </div>
        </div>
        {showWechatNotifyRow ? (
          <div className="user-menu-dropdown__wechat-notify">
            <Tooltip title={wechatNotifySwitchDisabled ? '请先绑定微信' : ''} placement="top">
              <div
                className={
                  wechatNotifySwitchDisabled
                    ? 'user-menu-dropdown__wechat-notify-row is-disabled'
                    : 'user-menu-dropdown__wechat-notify-row'
                }
              >
                <span className="user-menu-dropdown__wechat-notify-label">开启微信推送</span>
                <Switch
                  checked={wechatNotifyChecked}
                  disabled={wechatNotifySwitchDisabled || notifyToggling}
                  loading={notifyToggling}
                  className="user-menu-dropdown__switch"
                  onChange={onWechatNotifyChange}
                  onClick={(_, event) => event.stopPropagation()}
                />
              </div>
            </Tooltip>
          </div>
        ) : null}
        <div className="user-menu-dropdown__list">
          {/* payment.alipay/wxpay 皆关时隐藏充值入口（/auth/public-config） */}
          {anyPaymentEnabled ? (
            <button
              type="button"
              className="user-menu-dropdown__item"
              role="menuitem"
              onClick={() => onRecharge?.()}
            >
              <WalletOutlined />
              <span>积分充值</span>
            </button>
          ) : null}
          <button
            type="button"
            className="user-menu-dropdown__item"
            role="menuitem"
            onClick={() => onFaq?.()}
          >
            <QuestionCircleOutlined />
            <span>常见问题</span>
          </button>
          <button
            type="button"
            className="user-menu-dropdown__item"
            role="menuitem"
            onClick={() => onBilling?.()}
          >
            <DollarCircleOutlined />
            <span>计费说明</span>
          </button>
          <button
            type="button"
            className="user-menu-dropdown__item user-menu-dropdown__item--danger"
            role="menuitem"
            onClick={() => onLogout?.()}
          >
            <LogoutOutlined />
            <span>退出登录</span>
          </button>
        </div>
      </div>,
      document.body
    )
  }
)

export default UserMenuDropdown

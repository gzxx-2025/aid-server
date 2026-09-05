'use client'

import { Fragment } from 'react'
import wechatIconMod from '~/assets/img/login/icon-wechat.svg'
import passwordIconMod from '~/assets/img/login/icon-mima.svg'
import codeIconMod from '~/assets/img/login/icon-yzm.svg'
import type { LoginModalTab } from '@/stores/loginModal'
import { assetUrl } from '~/utils/assetUrl'
import {
  LOGIN_MODAL_ALT_DIVIDER_CLASS,
  LOGIN_MODAL_ALT_ROW_CLASS
} from '~/utils/loginModalLayout'

const wechatIconUrl = assetUrl(wechatIconMod)
const passwordIconUrl = assetUrl(passwordIconMod)
const codeIconUrl = assetUrl(codeIconMod)

interface LoginAltMethodsProps {
  current: LoginModalTab
  onSelect: (tab: LoginModalTab) => void
  showWechat?: boolean
  showCode?: boolean
  showPassword?: boolean
}

function AltButton({
  icon,
  label,
  onClick
}: {
  icon: string
  label: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      className="flex min-w-0 cursor-pointer flex-col items-center gap-2 text-sm leading-none text-[var(--login-modal-muted)] transition-colors hover:text-white"
      onClick={onClick}
    >
      <img src={icon} alt="" className="h-6 w-6" />
      {label}
    </button>
  )
}

export function LoginAltMethods({
  current,
  onSelect,
  showWechat = true,
  showCode = true,
  showPassword = true
}: LoginAltMethodsProps) {
  const items: { tab: LoginModalTab; icon: string; label: string }[] = []
  if (showWechat && current !== 'wechat') {
    items.push({ tab: 'wechat', icon: wechatIconUrl, label: '微信扫码登录' })
  }
  if (showCode && current !== 'code') {
    items.push({ tab: 'code', icon: codeIconUrl, label: '验证码登录' })
  }
  if (showPassword && current !== 'password') {
    items.push({ tab: 'password', icon: passwordIconUrl, label: '密码登录' })
  }
  if (items.length === 0) return null

  return (
    <div className="flex shrink-0 flex-col">
      <div className={LOGIN_MODAL_ALT_ROW_CLASS}>
        {items.map((item, index) => (
          <Fragment key={item.tab}>
            {index > 0 && <span className={LOGIN_MODAL_ALT_DIVIDER_CLASS} aria-hidden="true" />}
            <AltButton icon={item.icon} label={item.label} onClick={() => onSelect(item.tab)} />
          </Fragment>
        ))}
      </div>
      <div className="h-16 shrink-0" aria-hidden="true" />
    </div>
  )
}

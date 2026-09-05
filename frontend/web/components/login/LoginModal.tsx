'use client'

import { ConfigProvider, message } from 'antd'
import { useRouter } from 'next/navigation'
import AidHoverLogo from '@/components/atoms/AidHoverLogo'
import { LoginAltMethods } from './LoginAltMethods'
import { LoginCodePanel } from './LoginCodePanel'
import { LoginPasswordPanel } from './LoginPasswordPanel'
import { LoginWechatPanel } from './LoginWechatPanel'
import { useLoginModalFitScale } from '@/hooks/useLoginModalFitScale'
import { useLoginModalSession } from '@/hooks/useLoginModalSession'
import { closeLoginModal, enterForgotPasswordFlow } from '@/stores/loginModal'
import { LOGIN_MODAL_ANTD_THEME } from '~/utils/loginModalAntdTheme'
import {
  LOGIN_MODAL_BODY_CLASS,
  LOGIN_MODAL_BRAND_CLASS,
  LOGIN_MODAL_CLOSE_CLASS,
  LOGIN_MODAL_DESIGN_HEIGHT,
  LOGIN_MODAL_DESIGN_WIDTH,
  LOGIN_MODAL_FIT_SHELL_CLASS,
  LOGIN_MODAL_FORM_CLASS,
  LOGIN_MODAL_FRAME_CLASS,
  LOGIN_MODAL_OVERLAY_CLASS,
  LOGIN_MODAL_TITLE_CLASS,
  LOGIN_MODAL_VISUAL_CLASS
} from '~/utils/loginModalLayout'

const LOGIN_VIDEO_URL = '/media/login/login-video-bg.mp4'

const TITLE_MAP = {
  code: '验证码登录',
  wechat: '微信扫码登录',
  password: '密码登录'
} as const

interface LoginModalProps {
  /** Host 控制的进场可见态（退场时先 false 再卸载） */
  visible?: boolean
}

export function LoginModal({ visible = true }: LoginModalProps) {
  const router = useRouter()
  const session = useLoginModalSession()
  const fitScale = useLoginModalFitScale()
  const {
    tab,
    selectTab,
    siteName,
    platformLogoUrl,
    codeLoginPresentation,
    wechatLoginEnabled
  } = session

  function handleOverlayClick(e: React.MouseEvent<HTMLDivElement>) {
    if (e.target === e.currentTarget) closeLoginModal()
  }

  function handleForgotPassword() {
    if (!codeLoginPresentation.enabled) {
      message.warning('验证码登录未开启，无法找回密码')
      return
    }
    enterForgotPasswordFlow(router)
  }

  return (
    <div
      className={LOGIN_MODAL_OVERLAY_CLASS}
      data-state={visible ? 'open' : 'closed'}
      role="presentation"
      onClick={handleOverlayClick}
    >
      <div
        className={LOGIN_MODAL_FIT_SHELL_CLASS}
        style={{
          width: LOGIN_MODAL_DESIGN_WIDTH * fitScale,
          height: LOGIN_MODAL_DESIGN_HEIGHT * fitScale
        }}
        onClick={(e) => e.stopPropagation()}
      >
      <div
        className={LOGIN_MODAL_FRAME_CLASS}
        role="dialog"
        aria-modal="true"
        aria-label={TITLE_MAP[tab]}
        style={{
          transform: `scale(${fitScale})`,
          transformOrigin: 'top left'
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <section className={LOGIN_MODAL_VISUAL_CLASS} aria-hidden="true">
          <video
            className="h-full w-full object-cover"
            src={LOGIN_VIDEO_URL}
            autoPlay
            muted
            loop
            playsInline
            preload="auto"
            disablePictureInPicture
            controlsList="nodownload noplaybackrate nofullscreen noremoteplayback"
          />
        </section>

        <section className={LOGIN_MODAL_FORM_CLASS}>
          <ConfigProvider theme={LOGIN_MODAL_ANTD_THEME} wave={{ disabled: true }}>
          <button
            type="button"
            aria-label="关闭"
            className={LOGIN_MODAL_CLOSE_CLASS}
            onClick={() => closeLoginModal()}
          >
            <svg viewBox="0 0 24 24" className="h-8 w-8" fill="none" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          </button>

          <div
            id="login-captcha-box"
            className="absolute left-1/2 top-28 z-20 -translate-x-1/2"
            aria-hidden="true"
          />

          <div className={LOGIN_MODAL_BODY_CLASS}>
            <div className={LOGIN_MODAL_BRAND_CLASS}>
              {platformLogoUrl ? (
                <img
                  src={platformLogoUrl}
                  alt={siteName || 'AID'}
                  className="h-16 w-16 object-contain"
                />
              ) : (
                <AidHoverLogo className="!h-16 !w-16 !min-w-0" alt={siteName || 'AID'} />
              )}
              <h2 className={LOGIN_MODAL_TITLE_CLASS}>{TITLE_MAP[tab]}</h2>
            </div>
            <div className="flex w-full justify-center">
              {tab === 'code' && (
                <LoginCodePanel
                  account={session.account}
                  code={session.code}
                  accountPlaceholder={codeLoginPresentation.accountPlaceholder}
                  registrationHint={codeLoginPresentation.registrationHint}
                  loading={session.loading}
                  sendCodeLoading={session.sendCodeLoading}
                  sendCodeCountdown={session.sendCodeCountdown}
                  codeMaxLength={session.loginCodeMaxLength}
                  termsOfServiceUrl={session.termsOfServiceUrl}
                  privacyPolicyUrl={session.privacyPolicyUrl}
                  onAccountChange={session.setAccount}
                  onCodeChange={session.setCode}
                  onSendCode={() => void session.handleSendCode()}
                  onSubmit={() => void session.handleSubmit()}
                  onUnlockAutofill={session.clearLoginInputReadonly}
                />
              )}
              {tab === 'password' && (
                <LoginPasswordPanel
                  account={session.account}
                  password={session.password}
                  loading={session.loading}
                  showRegister={codeLoginPresentation.enabled}
                  termsOfServiceUrl={session.termsOfServiceUrl}
                  privacyPolicyUrl={session.privacyPolicyUrl}
                  onAccountChange={session.setAccount}
                  onPasswordChange={session.setPassword}
                  onSubmit={() => void session.handleSubmit()}
                  onRegister={() => selectTab('code')}
                  onForgotPassword={handleForgotPassword}
                  onUnlockAutofill={session.clearLoginInputReadonly}
                />
              )}
              {tab === 'wechat' && (
                <LoginWechatPanel
                  state={session.wechat.state}
                  enabled={wechatLoginEnabled}
                  termsOfServiceUrl={session.termsOfServiceUrl}
                  privacyPolicyUrl={session.privacyPolicyUrl}
                  onRefresh={() => void session.wechat.openWechatLogin()}
                />
              )}
            </div>
          </div>

          <LoginAltMethods
            current={tab}
            onSelect={selectTab}
            showWechat={wechatLoginEnabled}
            showCode={codeLoginPresentation.enabled}
            showPassword
          />
          </ConfigProvider>
        </section>
      </div>
      </div>
    </div>
  )
}

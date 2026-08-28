'use client'

import PublicBrandLogo from '@/components/atoms/PublicBrandLogo'
import { useAuthPublicConfig } from '@/hooks/useAuthPublicConfig'
import { useBehaviorCaptcha } from '@/hooks/useBehaviorCaptcha'
import { useUserStore } from '@/stores/user'
import { Button,Checkbox,Form,Input,message } from 'antd'
import type { Rule } from 'antd/es/form'
import { useRouter,useSearchParams } from 'next/navigation'
import { useEffect,useRef,useState } from 'react'
import inviteIconMod from '~/assets/img/icon/invitation_code.svg'
import numberIconMod from '~/assets/img/login/number.svg'
import peopleIconMod from '~/assets/img/login/pepole.svg'
import veriIconMod from '~/assets/img/login/veri.svg'
import type { LoginData } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import { setAuthLoginChannel,type AuthLoginChannel } from '~/utils/authLoginChannel'
import { normalizeInviteCode,withLoginInviteCode } from '~/utils/authLoginInvite'
import {
getCodeLoginPresentation,
getWechatLoginPresentation,
isValidCodeLoginTarget,
resolveCodeLoginChannel
} from '~/utils/authLoginMethods'
import { authLogin,authSendCode } from '~/utils/businessApi'
import { clearPendingCaptchaToken,setPendingCaptchaToken } from '~/utils/captchaToken'
import { loadTacScriptFallback } from '~/utils/tacAssets'
import { mapLoginDataToUser } from '~/utils/userProfile'
import { WechatLoginSection } from './WechatLoginSection'
import './login.css'
import { usePersistedCountdown } from './usePersistedCountdown'
import { useWechatLogin } from './useWechatLogin'

/** 大视频放 public，避免打包进构建图 */
const loginVideoBgUrl = '/media/login/login-video-bg.mp4'
const numberIconUrl = assetUrl(numberIconMod)
const veriIconUrl = assetUrl(veriIconMod)
const peopleIconUrl = assetUrl(peopleIconMod)
const inviteIconUrl = assetUrl(inviteIconMod)

const LOGIN_SEND_CODE_COUNTDOWN_SCOPE = 'login-send-code'
const agreementHint = '请先阅读并同意《用户协议》和《隐私政策》'

interface QuickLoginFormValues {
  account: string
  code: string
  password: string
  inviteCode: string
  agreement: boolean
}

/** 输入框前缀：图标 + 竖分隔线 */
function InputPrefixIcon({ src }: { src: string }) {
  return (
    <span className="input-prefix-wrap">
      <img src={src} alt="" className="input-prefix-icon" />
      <span className="input-prefix-divider" />
    </span>
  )
}

/** 阻止浏览器自动填充：初始 readonly，聚焦/点击后解除 */
function clearLoginInputReadonly(e: React.SyntheticEvent) {
  const target = e.target
  if (target instanceof HTMLInputElement) {
    target.removeAttribute('readonly')
    return
  }
  if (target instanceof HTMLElement) {
    const input = target
      .closest('.ant-input-affix-wrapper, .ant-input-password')
      ?.querySelector('input')
    if (input instanceof HTMLInputElement) input.removeAttribute('readonly')
  }
}

export function LoginPageClient() {
  const router = useRouter()
  const searchParams = useSearchParams()

  const {
    captchaEnabled,
    captchaType,
    smsLoginEnabled,
    emailLoginEnabled,
    wechatLoginEnabled,
    siteName,
    recordFilingNumber,
    termsOfServiceUrl,
    privacyPolicyUrl,
    loadPublicConfig,
    getSendCodeIntervalSeconds,
    getCodeMaxLength
  } = useAuthPublicConfig()
  const {
    opening: captchaOpening,
    isOpening: isCaptchaOpening,
    openBehaviorCaptcha,
    destroyActive: destroyCaptcha
  } = useBehaviorCaptcha()

  const [form] = Form.useForm<QuickLoginFormValues>()
  const accountValue = Form.useWatch('account', form)
  const inviteCodeValue = Form.useWatch('inviteCode', form)

  const [loading, setLoading] = useState(false)
  const loadingRef = useRef(false)
  const [quickSendCodeLoading, setQuickSendCodeLoading] = useState(false)
  const quickSendCodeLoadingRef = useRef(false)

  const {
    remaining: quickSendCodeCountdown,
    start: startQuickSendCodeCountdown,
    restore: restoreQuickSendCodeCountdown,
    stop: stopQuickSendCodeCountdown
  } = usePersistedCountdown(LOGIN_SEND_CODE_COUNTDOWN_SCOPE)

  const codeLoginPresentation = getCodeLoginPresentation(smsLoginEnabled, emailLoginEnabled)
  const wechatLoginPresentation = getWechatLoginPresentation(wechatLoginEnabled)

  const [activeFormTab, setActiveFormTab] = useState<'code' | 'password'>(
    codeLoginPresentation.enabled ? 'code' : 'password'
  )
  const formTabSelectedByUserRef = useRef(false)

  const accountPlaceholder =
    activeFormTab === 'password' ? '请输入账号' : codeLoginPresentation.accountPlaceholder
  const accountRequiredMessage =
    activeFormTab === 'password' ? '请输入账号' : `请输入${codeLoginPresentation.accountLabel}`
  const registrationHint = activeFormTab === 'code' ? codeLoginPresentation.registrationHint : ''
  const accountIcon = activeFormTab === 'password' ? peopleIconUrl : numberIconUrl

  const activeCodeLoginChannel = resolveCodeLoginChannel(
    String(accountValue || '').trim(),
    smsLoginEnabled,
    emailLoginEnabled
  )
  const loginCodeMaxLength = activeCodeLoginChannel ? getCodeMaxLength(activeCodeLoginChannel) : 6

  /** 首屏挂载拉码完成前，忽略邀请码变化触发的重复刷新 */
  const loginPageReadyRef = useRef(false)
  const loginBgVideoRef = useRef<HTMLVideoElement | null>(null)

  /** 从地址栏 `?invite=` / `?inviteCode=` 读取邀请码（最多 8 位） */
  function readRouteInviteCode(): string {
    const raw = String(searchParams.get('invite') || searchParams.get('inviteCode') || '').trim()
    return raw ? raw.slice(0, 8) : ''
  }

  const [initialFormValues] = useState<QuickLoginFormValues>(() => ({
    account: '',
    code: '',
    password: '',
    inviteCode: readRouteInviteCode(),
    agreement: false
  }))

  function normalizedInviteCode(): string | undefined {
    return normalizeInviteCode(form.getFieldValue('inviteCode'))
  }

  function completeLogin(data: LoginData, channel: AuthLoginChannel) {
    setAuthLoginChannel(channel)
    const account = String(form.getFieldValue('account') || '').trim()
    const userStore = useUserStore.getState()
    userStore.login(mapLoginDataToUser(data, account), data.token)
    void useUserStore.getState().fetchProfile()
    message.success('登录成功', 2)
    const redirect = searchParams.get('redirect')
    setTimeout(() => router.push(redirect || '/'), 0)
  }

  const wechat = useWechatLogin({
    enabled: wechatLoginEnabled,
    getInviteCode: () => normalizedInviteCode(),
    onLoginSuccess: (data) => completeLogin(data, 'wechat')
  })

  function selectFormTab(tab: 'code' | 'password') {
    if (tab === 'code' && !codeLoginPresentation.enabled) return
    formTabSelectedByUserRef.current = true
    setActiveFormTab(tab)
  }

  /** 地址栏带邀请码时回填表单（兼容进入页后 query 变化） */
  function applyInviteFromRoute() {
    const next = readRouteInviteCode()
    if (!next || String(form.getFieldValue('inviteCode') || '').trim() === next) return
    form.setFieldValue('inviteCode', next)
  }

  const agreementRules: Rule[] = [
    {
      validator: (_rule: unknown, value: boolean) => {
        if (value === true) return Promise.resolve()
        return Promise.reject(new Error(agreementHint))
      }
    }
  ]

  function handleFinishFailed(errorInfo: {
    errorFields: { name: (string | number)[]; errors: string[] }[]
  }) {
    const hitAgreement = (errorInfo.errorFields ?? []).some((f) => {
      const n = f.name
      const key = Array.isArray(n) ? n[n.length - 1] : n
      return key === 'agreement'
    })
    if (hitAgreement) {
      message.warning(agreementHint)
    }
  }

  /** 开启行为验证码时先滑块校验，token 写入队列后再调受保护接口 */
  async function withCaptchaToken<T>(
    action: (captchaToken: string) => Promise<T>
  ): Promise<T | null> {
    if (!captchaEnabled) {
      return action('')
    }
    if (isCaptchaOpening()) return null
    let result: T | null = null
    const captchaResult = await openBehaviorCaptcha({
      bindEl: '#login-captcha-box',
      captchaType,
      onSuccess: async (token) => {
        setPendingCaptchaToken(token)
        result = await action(token)
      }
    })
    if (captchaResult.error) throw captchaResult.error
    if (!captchaResult.ok) return null
    return result
  }

  async function doSendQuickLoginCode(captchaToken: string) {
    const account = String(form.getFieldValue('account') || '').trim()
    const channel = activeCodeLoginChannel
    if (!channel) throw new Error('验证码登录未开启')
    if (!isValidCodeLoginTarget(account, channel)) {
      throw new Error(channel === 'sms' ? '手机号格式不正确' : '邮箱格式不正确')
    }
    await authSendCode(
      {
        target: account,
        codeType: channel,
        scene: 'login',
        inviteCode: normalizedInviteCode()
      },
      captchaToken || undefined
    )
    message.success('验证码已发送')
    startQuickSendCodeCountdown(account, getSendCodeIntervalSeconds(channel))
  }

  async function handleSendQuickLoginCode() {
    const account = String(form.getFieldValue('account') || '').trim()
    if (!account) {
      message.warning(accountPlaceholder)
      return
    }
    if (quickSendCodeLoadingRef.current || quickSendCodeCountdown > 0 || isCaptchaOpening()) return
    quickSendCodeLoadingRef.current = true
    setQuickSendCodeLoading(true)
    try {
      const sent = await withCaptchaToken(doSendQuickLoginCode)
      if (sent === null && captchaEnabled) return
    } catch (e: any) {
      message.error(e?.msg ?? e?.message ?? '发送验证码失败')
    } finally {
      quickSendCodeLoadingRef.current = false
      setQuickSendCodeLoading(false)
    }
  }

  async function doPasswordLogin(captchaToken: string) {
    // 账号密码登录不展示邀请码，不携带 inviteCode
    const data = await authLogin(
      {
        loginType: 'password',
        account: String(form.getFieldValue('account') || '').trim(),
        password: form.getFieldValue('password')
      },
      captchaToken || undefined
    )
    completeLogin(data, 'password')
  }

  async function doCodeLogin(inviteCode?: string) {
    // 短信/邮箱登录：人机校验仅在「获取验证码」时完成（见接口文档业务流程），登录不再携带 captcha-token
    const loginType = activeCodeLoginChannel
    if (!loginType) throw new Error('验证码登录未开启')
    if (!isValidCodeLoginTarget(String(form.getFieldValue('account') || ''), loginType)) {
      throw new Error(loginType === 'sms' ? '手机号格式不正确' : '邮箱格式不正确')
    }
    const data = await authLogin(
      withLoginInviteCode(
        {
          loginType,
          account: String(form.getFieldValue('account') || '').trim(),
          code: String(form.getFieldValue('code') || '').trim()
        },
        inviteCode ?? normalizedInviteCode()
      )
    )
    completeLogin(data, loginType)
  }

  async function handleFinish(values?: Partial<QuickLoginFormValues>) {
    if (!form.getFieldValue('agreement')) {
      message.warning(agreementHint)
      return
    }
    if (loadingRef.current || isCaptchaOpening()) return
    loadingRef.current = true
    setLoading(true)
    try {
      if (activeFormTab === 'code') {
        // 优先用 Form onFinish 回传值，避免输入框展示值与 model 不同步时漏传邀请码
        const inviteCode = normalizeInviteCode(values?.inviteCode) ?? normalizedInviteCode()
        if (inviteCode && String(form.getFieldValue('inviteCode') || '').trim() !== inviteCode) {
          form.setFieldValue('inviteCode', inviteCode)
        }
        await doCodeLogin(inviteCode)
        return
      }
      const loggedIn = await withCaptchaToken((token) => doPasswordLogin(token))
      if (loggedIn === null && captchaEnabled) return
    } catch (e: any) {
      message.error(e?.msg ?? e?.message ?? '登录失败')
    } finally {
      loadingRef.current = false
      setLoading(false)
    }
  }

  /* 首屏：加载 TAC 静态资源 + 公开配置，回填邀请码，按开关初始化 tab / 微信码 / 倒计时，播放背景视频 */
  useEffect(() => {
    // 显式引入 /tac/css/tac.css 与 /tac/js/tac.min.js（原 useTacPageHead 职责）
    void loadTacScriptFallback()
    let cancelled = false
    const run = async () => {
      applyInviteFromRoute()
      const cfg = await loadPublicConfig()
      if (cancelled) return
      const codeEnabled = getCodeLoginPresentation(
        cfg?.login?.smsEnabled === true,
        cfg?.login?.emailEnabled === true
      ).enabled
      if (!formTabSelectedByUserRef.current) {
        setActiveFormTab(codeEnabled ? 'code' : 'password')
      } else if (!codeEnabled) {
        setActiveFormTab((tab) => (tab === 'code' ? 'password' : tab))
      }
      loginPageReadyRef.current = true
      if (cfg?.login?.wechatEnabled === true) void wechat.openWechatLogin()
      restoreQuickSendCodeCountdown(String(form.getFieldValue('account') || '').trim())
      const v = loginBgVideoRef.current
      if (v) {
        v.muted = true
        v.play().catch(() => {})
      }
    }
    void run()
    return () => {
      cancelled = true
      stopQuickSendCodeCountdown()
      destroyCaptcha()
      clearPendingCaptchaToken()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /* 微信登录开关变化：开启则拉码，关闭则停轮询并清码 */
  useEffect(() => {
    if (!loginPageReadyRef.current) return
    if (wechatLoginEnabled) {
      void wechat.openWechatLogin()
      return
    }
    wechat.resetOnDisabled()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wechatLoginEnabled])

  /* 地址栏 query 变化时回填邀请码 */
  useEffect(() => {
    applyInviteFromRoute()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams])

  /* 邀请码变更后重新拉微信码（500ms 防抖，已扫码/成功不打断） */
  useEffect(() => {
    if (loginPageReadyRef.current) wechat.scheduleQrRefreshForInvite()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inviteCodeValue])

  /* 切换账号时按账号恢复发送验证码倒计时 */
  useEffect(() => {
    restoreQuickSendCodeCountdown(String(accountValue || '').trim())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountValue])

  return (
    <div className="login-page">
      <video
        ref={loginBgVideoRef}
        className="login-bg-video"
        src={loginVideoBgUrl}
        autoPlay
        muted
        loop
        playsInline
        preload="auto"
        disablePictureInPicture
        controlsList="nodownload noplaybackrate nofullscreen noremoteplayback"
        aria-hidden="true"
      />
      <div className="login-mask" />

      <main className="login-panel">
        <section className="login-left">
          <div className="login-form-card">
            <div>
              <PublicBrandLogo
                className="login-logo login-logo-image"
                alt={siteName || '平台标识'}
              />
              <Form
                form={form}
                layout="vertical"
                className="login-form"
                autoComplete="off"
                initialValues={initialFormValues}
                onFinish={handleFinish}
                onFinishFailed={handleFinishFailed}
              >
                {/* 吸收浏览器自动填充，避免污染真实输入框 */}
                <input
                  type="text"
                  name="username"
                  tabIndex={-1}
                  autoComplete="username"
                  aria-hidden="true"
                  className="login-autofill-trap"
                />
                <input
                  type="password"
                  name="password"
                  tabIndex={-1}
                  autoComplete="current-password"
                  aria-hidden="true"
                  className="login-autofill-trap"
                />
                <div className="login-form-stack">
                  {/* 绝对定位在 Tab+输入区上方，不占文档流 */}
                  <div id="login-captcha-box" className="login-captcha-box" aria-hidden="true" />
                  <div className={`form-tabs${codeLoginPresentation.enabled ? '' : ' is-single'}`}>
                    {codeLoginPresentation.enabled && (
                      <button
                        type="button"
                        className={`form-tab${activeFormTab === 'code' ? ' active' : ''}`}
                        onClick={() => selectFormTab('code')}
                      >
                        {codeLoginPresentation.tabLabel}
                      </button>
                    )}
                    <button
                      type="button"
                      className={`form-tab${activeFormTab === 'password' ? ' active' : ''}`}
                      onClick={() => selectFormTab('password')}
                    >
                      账号密码
                    </button>
                  </div>
                </div>
                <Form.Item
                  name="account"
                  rules={[{ required: true, message: accountRequiredMessage }]}
                >
                  <Input
                    size="large"
                    placeholder={accountPlaceholder}
                    className="login-input"
                    autoComplete="off"
                    name="login-account"
                    readOnly
                    data-lpignore="true"
                    data-1p-ignore=""
                    onFocus={clearLoginInputReadonly}
                    onMouseDown={clearLoginInputReadonly}
                    prefix={<InputPrefixIcon src={accountIcon} />}
                  />
                </Form.Item>
                {activeFormTab === 'code' ? (
                  <Form.Item>
                    <div className="code-row">
                      <Form.Item
                        name="code"
                        noStyle
                        rules={[{ required: true, message: '请输入验证码' }]}
                      >
                        <Input
                          size="large"
                          placeholder="请输入验证码"
                          className="login-input"
                          maxLength={loginCodeMaxLength}
                          autoComplete="off"
                          name="login-code"
                          inputMode="numeric"
                          readOnly
                          data-lpignore="true"
                          data-1p-ignore=""
                          onFocus={clearLoginInputReadonly}
                          onMouseDown={clearLoginInputReadonly}
                          prefix={<InputPrefixIcon src={veriIconUrl} />}
                        />
                      </Form.Item>
                      <Button
                        className="code-btn"
                        disabled={
                          quickSendCodeLoading || quickSendCodeCountdown > 0 || captchaOpening
                        }
                        loading={quickSendCodeLoading || captchaOpening}
                        onClick={handleSendQuickLoginCode}
                      >
                        {quickSendCodeCountdown > 0 ? `${quickSendCodeCountdown}s` : '获取验证码'}
                      </Button>
                    </div>
                  </Form.Item>
                ) : (
                  <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
                    <Input.Password
                      size="large"
                      placeholder="请输入密码"
                      className="login-input"
                      autoComplete="new-password"
                      name="login-password"
                      readOnly
                      data-lpignore="true"
                      data-1p-ignore=""
                      onFocus={clearLoginInputReadonly}
                      onMouseDown={clearLoginInputReadonly}
                      prefix={<InputPrefixIcon src={veriIconUrl} />}
                    />
                  </Form.Item>
                )}
                {activeFormTab === 'code' && (
                  <Form.Item name="inviteCode" className="invite-code-item">
                    <Input
                      size="large"
                      placeholder="邀请码（选填）"
                      className="login-input"
                      maxLength={8}
                      autoComplete="off"
                      name="login-invite"
                      prefix={<InputPrefixIcon src={inviteIconUrl} />}
                    />
                  </Form.Item>
                )}
                <Form.Item
                  name="agreement"
                  valuePropName="checked"
                  className="agreement-form-item"
                  rules={agreementRules}
                >
                  <Checkbox>
                    <span className="agreement-text">
                      我已阅读并同意
                      {termsOfServiceUrl ? (
                        <a
                          href={termsOfServiceUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="agreement-link"
                          onClick={(e) => e.stopPropagation()}
                        >
                          《用户协议》
                        </a>
                      ) : (
                        <span>《用户协议》</span>
                      )}
                      和
                      {privacyPolicyUrl ? (
                        <a
                          href={privacyPolicyUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="agreement-link"
                          onClick={(e) => e.stopPropagation()}
                        >
                          《隐私政策》
                        </a>
                      ) : (
                        <span>《隐私政策》</span>
                      )}
                      {registrationHint}
                    </span>
                  </Checkbox>
                </Form.Item>
                <Form.Item className="submit-item">
                  <Button
                    type="primary"
                    htmlType="submit"
                    className="submit-btn"
                    block
                    size="large"
                    loading={loading || captchaOpening}
                    disabled={captchaOpening}
                  >
                    {activeFormTab === 'password' ? '登录' : '登录/注册'}
                  </Button>
                </Form.Item>
              </Form>
            </div>
          </div>
        </section>

        <WechatLoginSection
          presentation={wechatLoginPresentation}
          state={wechat.state}
          onRefresh={() => void wechat.openWechatLogin()}
        />
      </main>

      {recordFilingNumber && (
        <footer className="login-icp-footer">
          <a
            href="https://beian.miit.gov.cn"
            target="_blank"
            rel="noopener noreferrer"
            className="login-icp-link"
          >
            {recordFilingNumber}
          </a>
        </footer>
      )}
    </div>
  )
}

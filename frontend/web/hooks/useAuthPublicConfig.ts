import { create } from 'zustand'
import type { AuthPublicConfigData } from '~/types/business-api'
import {
  API_CRYPTO_CLIENT_TIME_CACHE_FIELD,
  applyApiCryptoFromPublicConfig,
  refreshApiCryptoConfig,
  registerApiCryptoConfigRefreshHandler
} from '~/utils/apiCrypto'
import { authPublicConfig } from '~/utils/businessApi'

const STORAGE_KEY = 'auth:public-config:v3'

type CachedAuthPublicConfigData = AuthPublicConfigData & {
  [API_CRYPTO_CLIENT_TIME_CACHE_FIELD]?: number
}

const isClient = () => typeof window !== 'undefined'

function trimConfigText(raw: unknown): string {
  return typeof raw === 'string' && raw.trim() ? raw.trim() : ''
}

function readCachedConfig(): CachedAuthPublicConfigData | null {
  if (!isClient()) return null
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    return JSON.parse(raw) as CachedAuthPublicConfigData
  } catch {
    return null
  }
}

function writeCachedConfig(data: AuthPublicConfigData, clientTimeAtSync: number) {
  if (!isClient()) return
  try {
    const cached: CachedAuthPublicConfigData = {
      ...data,
      [API_CRYPTO_CLIENT_TIME_CACHE_FIELD]: clientTimeAtSync
    }
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(cached))
  } catch {
    /* ignore quota */
  }
}

function syncCryptoFromConfig(data: CachedAuthPublicConfigData | null, clientTimeAtSync?: number) {
  applyApiCryptoFromPublicConfig(data?.crypto, data?.serverTime, clientTimeAtSync)
}

interface AuthPublicConfigState {
  config: AuthPublicConfigData | null
  loading: boolean
  loaded: boolean
}

/** 全局共享，保证 SEO / 各页面读到同一份 public-config */
const usePublicConfigStore = create<AuthPublicConfigState>(() => ({
  config: null,
  loading: false,
  loaded: false
}))

function hydrateSharedFromCache() {
  if (usePublicConfigStore.getState().config || !isClient()) return
  const cached = readCachedConfig()
  if (!cached) return
  usePublicConfigStore.setState({ config: cached, loaded: true })
  syncCryptoFromConfig(cached, cached[API_CRYPTO_CLIENT_TIME_CACHE_FIELD])
}

/** 启动引导 / 其它入口写入公开配置（同步内存 + sessionStorage + crypto） */
export function setAuthPublicConfigData(data: AuthPublicConfigData) {
  const clientTimeAtSync = Date.now()
  syncCryptoFromConfig(data, clientTimeAtSync)
  usePublicConfigStore.setState({ config: data, loaded: true })
  writeCachedConfig(data, clientTimeAtSync)
}

export async function loadPublicConfig(force = false): Promise<AuthPublicConfigData | null> {
  if (!isClient()) return null
  if (!force && !usePublicConfigStore.getState().config) {
    hydrateSharedFromCache()
  }
  const existing = usePublicConfigStore.getState().config
  if (!force && existing) {
    usePublicConfigStore.setState({ loaded: true })
    return existing
  }
  usePublicConfigStore.setState({ loading: true })
  try {
    const data = await authPublicConfig()
    setAuthPublicConfigData(data)
    return data
  } catch {
    return usePublicConfigStore.getState().config
  } finally {
    usePublicConfigStore.setState({ loading: false })
  }
}

registerApiCryptoConfigRefreshHandler(async () => {
  const data = await authPublicConfig()
  setAuthPublicConfigData(data)
})

/** 恢复缓存后刷新公开配置，并与首批受保护请求共用同一次请求。 */
export async function bootstrapAuthPublicConfig(): Promise<boolean> {
  hydrateSharedFromCache()
  return refreshApiCryptoConfig()
}

type CodeChannel = 'sms' | 'email'

function getCodePolicy(config: AuthPublicConfigData | null, channel: CodeChannel) {
  return channel === 'email' ? config?.emailPolicy : config?.smsPolicy
}

/** 登录页首屏公开配置：验证码开关、发码策略、SEO 等（与 /auth/public-config 对齐） */
export function useAuthPublicConfig() {
  hydrateSharedFromCache()
  const { config, loading, loaded } = usePublicConfigStore()

  const captcha = config?.captcha
  const captchaEnabled = Boolean(
    captcha?.enabled && captcha.imagesReady !== false && captcha.applicationOk !== false
  )

  const registerBonus = config?.promotion?.registerBonus
  let registerBonusAmountText = ''
  if (registerBonus?.enabled === true) {
    const raw = registerBonus.amount
    const amount = typeof raw === 'number' ? raw : Number(raw)
    if (Number.isFinite(amount) && amount > 0) {
      registerBonusAmountText = Number.isInteger(amount)
        ? String(amount)
        : String(parseFloat(amount.toFixed(2)))
    }
  }

  const inviteRebateRatio = Number(config?.promotion?.invite?.rebateRatio)

  const alipayEnabled = config?.payment?.alipayEnabled === true
  const wxpayEnabled = config?.payment?.wxpayEnabled === true

  const wechatNotifyRules = Array.isArray(config?.wechatNotify?.rules)
    ? config!.wechatNotify!.rules
    : []

  const voicePreviewChars = config?.voicePreview?.estimatedMaxChars

  return {
    config,
    loading,
    loaded,
    captchaEnabled,
    captchaType: config?.captcha?.type ?? 'SLIDER',
    cryptoEnabled: Boolean(config?.crypto?.enabled && config?.crypto?.publicKey),
    smsLoginEnabled: config?.login?.smsEnabled === true,
    emailLoginEnabled: config?.login?.emailEnabled === true,
    wechatLoginEnabled: config?.login?.wechatEnabled === true,
    siteName: trimConfigText(config?.basic?.site_name),
    siteDescription: trimConfigText(config?.basic?.site_description),
    siteKeywords: trimConfigText(config?.basic?.site_keywords),
    faviconUrl: trimConfigText(config?.brand?.faviconUrl),
    platformLogoUrl: trimConfigText(config?.brand?.platformLogoUrl),
    recordFilingNumber: trimConfigText(config?.basic?.record_filing_number),
    termsOfServiceUrl: trimConfigText(config?.basic?.terms_of_service),
    membershipAgreementUrl: trimConfigText(config?.basic?.membership_agreement),
    privacyPolicyUrl: trimConfigText(config?.basic?.privacy_policy),
    exchangeImageUrl: trimConfigText(config?.basic?.exchange_image_url),
    serviceEmail: trimConfigText(config?.basic?.service_email),
    businessEmail: trimConfigText(config?.basic?.business_email),
    contactPhone: trimConfigText(config?.basic?.contact_phone),
    registerBonusAmountText,
    registerBonusBadgeText: registerBonusAmountText
      ? `注册即送${registerBonusAmountText}积分`
      : '注册即送积分',
    invitePromotionEnabled: config?.promotion?.invite?.enabled === true,
    inviteRebateRatioText: Number.isFinite(inviteRebateRatio) ? String(inviteRebateRatio) : '10',
    tutorialUrl: trimConfigText(config?.basic?.tutorial_url),
    openSourceGiteeUrl: trimConfigText(config?.basic?.open_source_gitee_url),
    openSourceGitUrl: trimConfigText(config?.basic?.open_source_git_url),
    wechatNotifySystemEnabled: config?.wechatNotify?.enabled === true,
    wechatNotifyRules,
    voicePreviewEstimatedMaxChars:
      typeof voicePreviewChars === 'number' && voicePreviewChars > 0 ? voicePreviewChars : 15,
    alipayEnabled,
    wxpayEnabled,
    anyPaymentEnabled: alipayEnabled || wxpayEnabled,
    defaultPayType: (alipayEnabled ? 'alipay' : wxpayEnabled ? 'wxpay' : null) as
      | 'alipay'
      | 'wxpay'
      | null,
    loadPublicConfig,
    getSendCodeIntervalSeconds: (channel: CodeChannel) => {
      const sec = getCodePolicy(config, channel)?.sendIntervalSeconds
      return typeof sec === 'number' && sec > 0 ? sec : 120
    },
    getCodeMaxLength: (channel: CodeChannel) => {
      const len = getCodePolicy(config, channel)?.codeLength
      return typeof len === 'number' && len > 0 ? len : 6
    }
  }
}

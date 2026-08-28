/** 认证与账户域：登录/验证码/微信登录绑定/实名认证/个人信息与积分/微信推送偏好/邀请。 */
import type {
ApiEnvelope,
ApiListEnvelopeData,
AuthPublicConfigData,
InviteCodeCheckRequest,
InviteCodeCheckVO,
InvitedUserVO,
InviteInfoVO,
InviteRebateItemVO,
InviteRebatesRequest,
InviteUsersRequest,
LoginData,
LoginRequest,
RealAuthStatusData,
RealAuthVerifyRequest,
ResetPasswordRequest,
SendCodeRequest,
UserBalanceFromApi,
UserInfoFromApi,
WechatLoginCheckData,
WechatLoginSuccessData,
WechatNotifyPreferenceData,
WechatQrcodeData
} from '~/types/business-api'
import { request } from '~/utils/api'
import { unwrap } from '~/utils/business/shared'

let authPublicConfigInflight: Promise<AuthPublicConfigData> | null = null

/**
 * 3.0 查询 C 端公开配置（验证码策略、短信/邮箱发码策略等）。
 * 启动插件与登录页会在冷启动时并发调用，做 in-flight 合并避免重复请求。
 */
export function authPublicConfig(): Promise<AuthPublicConfigData> {
  if (authPublicConfigInflight) return authPublicConfigInflight
  const promise = request
    .post<ApiEnvelope<AuthPublicConfigData>>('/auth/public-config', {})
    .then((res) => unwrap(res))
    .finally(() => {
      if (authPublicConfigInflight === promise) {
        authPublicConfigInflight = null
      }
    })
  authPublicConfigInflight = promise
  return promise
}

/** 1. 统一登录（开启行为验证码时由请求拦截器或显式 headers 携带 captcha-token） */
export async function authLogin(body: LoginRequest, captchaToken?: string): Promise<LoginData> {
  const headers = captchaToken ? { 'captcha-token': captchaToken } : undefined
  // 邀请码仅注册瞬间生效；空串不传，避免后端收到空白字段
  const inviteCode = String(body.inviteCode || '').trim()
  const payload: LoginRequest = { ...body }
  if (inviteCode) payload.inviteCode = inviteCode
  else delete payload.inviteCode
  const res = await request.post<ApiEnvelope<LoginData>>('/auth/login', payload, { headers })
  return unwrap(res)
}

/** 2. 退出登录 */
export async function authLogout(): Promise<void> {
  await request.post<ApiEnvelope>('/auth/logout')
}

/** 3. 发送验证码（开启行为验证码时由请求拦截器或显式 headers 携带 captcha-token） */
export async function authSendCode(body: SendCodeRequest, captchaToken?: string): Promise<void> {
  const headers = captchaToken ? { 'captcha-token': captchaToken } : undefined
  // 邀请码可选；空串不传，避免后端收到空白字段
  const inviteCode = String(body.inviteCode || '').trim()
  const payload: SendCodeRequest = { ...body }
  if (inviteCode) payload.inviteCode = inviteCode
  else delete payload.inviteCode
  await request.post<ApiEnvelope>('/auth/sendCode', payload, { headers })
}

/** 6. 找回密码 */
export async function authResetPassword(body: ResetPasswordRequest): Promise<void> {
  await request.post<ApiEnvelope>('/auth/resetPassword', body)
}

/** 微信：获取登录二维码（可选携带 inviteCode，仅新用户扫码注册时绑定） */
export async function wechatLoginQrcode(inviteCode?: string): Promise<WechatQrcodeData> {
  const code = String(inviteCode || '').trim()
  const res = await request.get<ApiEnvelope<WechatQrcodeData>>(
    '/auth/wechat/qrcode',
    code ? { inviteCode: code } : undefined
  )
  return unwrap(res)
}

/** 微信：检查登录状态（轮询） */
export async function wechatLoginCheck(sceneStr: string): Promise<ApiEnvelope<WechatLoginCheckData>> {
  return request.get<ApiEnvelope<WechatLoginCheckData>>('/auth/wechat/check', { sceneStr })
}

/** 微信：获取绑定二维码（需登录） */
export async function wechatBindQrcode(): Promise<WechatQrcodeData> {
  const res = await request.get<ApiEnvelope<WechatQrcodeData>>('/auth/wechat/bind/qrcode')
  return unwrap(res)
}

/** 微信：检查绑定状态 */
export async function wechatBindCheck(sceneStr: string): Promise<ApiEnvelope<unknown>> {
  return request.get<ApiEnvelope<unknown>>('/auth/wechat/bind/check', { sceneStr })
}

/** 实名认证 */
export async function realAuthVerify(body: RealAuthVerifyRequest): Promise<unknown> {
  const res = await request.post<ApiEnvelope<unknown>>('/realAuth/verify', body)
  return unwrap(res)
}

export async function realAuthStatus(): Promise<RealAuthStatusData> {
  const res = await request.get<ApiEnvelope<RealAuthStatusData>>('/realAuth/status')
  return unwrap(res)
}

let userProfileInflight: Promise<UserInfoFromApi> | null = null

/**
 * 查询个人信息 POST /api/user/profile。
 * 刷新时多个入口（auth-sync 插件、页面守卫、余额组件）会并发拉取，
 * in-flight 合并保证同一时刻只有一个请求；不做 TTL 缓存，余额等实时字段始终以最新响应为准。
 */
export function userProfile(): Promise<UserInfoFromApi> {
  if (userProfileInflight) return userProfileInflight
  const promise = request
    .post<ApiEnvelope<UserInfoFromApi>>('/api/user/profile', {})
    .then((res) => unwrap(res))
    .finally(() => {
      if (userProfileInflight === promise) {
        userProfileInflight = null
      }
    })
  userProfileInflight = promise
  return promise
}

let userBalanceInflight: Promise<UserBalanceFromApi> | null = null

/**
 * 快捷查询账户积分 POST /api/user/balance。
 * 用于生成任务终态后刷新侧栏积分；in-flight 合并避免并发重复请求。
 */
export function userBalance(): Promise<UserBalanceFromApi> {
  if (userBalanceInflight) return userBalanceInflight
  const promise = request
    .post<ApiEnvelope<UserBalanceFromApi>>('/api/user/balance', {})
    .then((res) => unwrap(res))
    .finally(() => {
      if (userBalanceInflight === promise) {
        userBalanceInflight = null
      }
    })
  userBalanceInflight = promise
  return promise
}

/** 查询微信推送偏好 POST /api/user/wechat-notify/preference */
export async function wechatNotifyPreference(): Promise<WechatNotifyPreferenceData> {
  const res = await request.post<ApiEnvelope<WechatNotifyPreferenceData>>(
    '/api/user/wechat-notify/preference',
    {}
  )
  return unwrap(res)
}

/** 开启微信推送 POST /api/user/wechat-notify/enable */
export async function wechatNotifyEnable(): Promise<WechatNotifyPreferenceData> {
  const res = await request.post<ApiEnvelope<WechatNotifyPreferenceData>>(
    '/api/user/wechat-notify/enable',
    {}
  )
  return unwrap(res)
}

/** 关闭微信推送 POST /api/user/wechat-notify/disable */
export async function wechatNotifyDisable(): Promise<WechatNotifyPreferenceData> {
  const res = await request.post<ApiEnvelope<WechatNotifyPreferenceData>>(
    '/api/user/wechat-notify/disable',
    {}
  )
  return unwrap(res)
}

/** 邀请码预校验（匿名）POST /api/user/invite/check */
export async function userInviteCheck(body: InviteCodeCheckRequest): Promise<InviteCodeCheckVO> {
  const res = await request.post<ApiEnvelope<InviteCodeCheckVO>>('/api/user/invite/check', body)
  return unwrap(res)
}

/** 我的邀请信息 POST /api/user/invite/info */
export async function userInviteInfo(): Promise<InviteInfoVO> {
  const res = await request.post<ApiEnvelope<InviteInfoVO>>('/api/user/invite/info', {})
  return unwrap(res)
}

/** 我邀请的用户列表 POST /api/user/invite/users */
export async function userInviteUsers(
  body: InviteUsersRequest = {}
): Promise<{ data: InvitedUserVO[]; total: number }> {
  const res = await request.post<ApiListEnvelopeData<InvitedUserVO>>(
    '/api/user/invite/users',
    body
  )
  return { data: Array.isArray(res.data) ? res.data : [], total: Number(res.total) || 0 }
}

/** 我的返佣明细 POST /api/user/invite/rebates */
export async function userInviteRebates(
  body: InviteRebatesRequest = {}
): Promise<{ data: InviteRebateItemVO[]; total: number }> {
  const res = await request.post<ApiListEnvelopeData<InviteRebateItemVO>>(
    '/api/user/invite/rebates',
    body
  )
  return { data: Array.isArray(res.data) ? res.data : [], total: Number(res.total) || 0 }
}

export type { WechatLoginSuccessData }

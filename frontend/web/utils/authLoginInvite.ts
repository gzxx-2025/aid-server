/** 规范化邀请码：去首尾空格，空则视为未填 */
export function normalizeInviteCode(raw?: string | null): string | undefined {
  const code = String(raw ?? '').trim()
  return code || undefined
}

type LoginBodyBase = {
  loginType: 'password' | 'sms' | 'email'
  account?: string
  password?: string
  code?: string
  inviteCode?: string
}

/** 有邀请码时写入入参；无则不带空字段（登录 / 发验证码等共用） */
export function withOptionalInviteCode<T extends object>(
  body: T,
  inviteCode?: string | null
): T {
  const code = normalizeInviteCode(inviteCode)
  if (!code) return body
  return { ...body, inviteCode: code }
}

/** 有邀请码时写入登录入参；无则不带空字段 */
export function withLoginInviteCode<T extends LoginBodyBase>(
  body: T,
  inviteCode?: string | null
): T {
  return withOptionalInviteCode(body, inviteCode)
}

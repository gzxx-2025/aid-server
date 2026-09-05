/** 认证密码策略（找回/重置/改密共用） */

export const AUTH_PASSWORD_MIN_LENGTH = 5
export const AUTH_PASSWORD_MAX_LENGTH = 20

export const AUTH_PASSWORD_RULE_HINT =
  '密码须为 5-20 位，且同时包含大写字母、小写字母和数字'

export type AuthPasswordIssue =
  | 'empty'
  | 'too_short'
  | 'too_long'
  | 'weak_charset'
  | 'mismatch'
  | 'same_as_old'

const ISSUE_MESSAGE: Record<AuthPasswordIssue, string> = {
  empty: '请输入新密码',
  too_short: `密码不能少于 ${AUTH_PASSWORD_MIN_LENGTH} 位`,
  too_long: `密码不能超过 ${AUTH_PASSWORD_MAX_LENGTH} 位`,
  weak_charset: AUTH_PASSWORD_RULE_HINT,
  mismatch: '两次输入的密码不一致',
  same_as_old: '新密码不得与旧密码相同'
}

export function authPasswordIssueMessage(issue: AuthPasswordIssue): string {
  return ISSUE_MESSAGE[issue]
}

/** 是否满足：含大写 + 小写 + 数字（可含其它字符） */
export function hasAuthPasswordCharset(password: string): boolean {
  return /[A-Z]/.test(password) && /[a-z]/.test(password) && /\d/.test(password)
}

/**
 * 校验新密码本体（长度 + 字符集）。
 * @returns 首个问题码；通过返回 null
 */
export function validateAuthPasswordValue(password: string): AuthPasswordIssue | null {
  if (!password) return 'empty'
  if (password.length < AUTH_PASSWORD_MIN_LENGTH) return 'too_short'
  if (password.length > AUTH_PASSWORD_MAX_LENGTH) return 'too_long'
  if (!hasAuthPasswordCharset(password)) return 'weak_charset'
  return null
}

export interface ValidateAuthPasswordChangeInput {
  newPassword: string
  confirmPassword: string
  /** 有旧密码时校验不得相同（登录后改密）；找回密码无旧密码则不传 */
  oldPassword?: string
}

/**
 * 改密 / 重置统一校验：新密码规则 + 确认一致 +（可选）不同于旧密码。
 */
export function validateAuthPasswordChange(
  input: ValidateAuthPasswordChangeInput
): AuthPasswordIssue | null {
  const valueIssue = validateAuthPasswordValue(input.newPassword)
  if (valueIssue) return valueIssue
  if (!input.confirmPassword) return 'mismatch'
  if (input.newPassword !== input.confirmPassword) return 'mismatch'
  if (input.oldPassword != null && input.oldPassword !== '' && input.newPassword === input.oldPassword) {
    return 'same_as_old'
  }
  return null
}

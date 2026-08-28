export type CodeLoginChannel = 'sms' | 'email'

export interface CodeLoginPresentation {
  enabled: boolean
  tabLabel: string
  accountLabel: string
  accountPlaceholder: string
  registrationHint: string
}

export interface WechatLoginPresentation {
  enabled: boolean
  title: string
  description: string
}

/** 根据当前启用的验证码渠道生成与实际能力一致的登录文案。 */
export function getCodeLoginPresentation(
  smsEnabled: boolean,
  emailEnabled: boolean
): CodeLoginPresentation {
  if (smsEnabled && emailEnabled) {
    return {
      enabled: true,
      tabLabel: '手机/邮箱',
      accountLabel: '手机号或邮箱',
      accountPlaceholder: '请输入手机号或邮箱',
      registrationHint: '，未注册的手机号或邮箱将自动创建账号'
    }
  }
  if (smsEnabled) {
    return {
      enabled: true,
      tabLabel: '手机号',
      accountLabel: '手机号',
      accountPlaceholder: '请输入手机号',
      registrationHint: '，未注册的手机号将自动创建账号'
    }
  }
  if (emailEnabled) {
    return {
      enabled: true,
      tabLabel: '邮箱',
      accountLabel: '邮箱',
      accountPlaceholder: '请输入邮箱',
      registrationHint: '，未注册的邮箱将自动创建账号'
    }
  }
  return {
    enabled: false,
    tabLabel: '',
    accountLabel: '账号',
    accountPlaceholder: '请输入账号',
    registrationHint: ''
  }
}

/** 按开关约束验证码登录渠道；单渠道开启时不再根据用户输入猜测另一渠道。 */
export function resolveCodeLoginChannel(
  target: string,
  smsEnabled: boolean,
  emailEnabled: boolean
): CodeLoginChannel | null {
  if (smsEnabled && emailEnabled) return target.includes('@') ? 'email' : 'sms'
  if (smsEnabled) return 'sms'
  if (emailEnabled) return 'email'
  return null
}

/** 校验验证码目标是否符合当前已选择渠道的基础格式。 */
export function isValidCodeLoginTarget(target: string, channel: CodeLoginChannel): boolean {
  const value = target.trim()
  if (channel === 'sms') return /^1[3-9]\d{9}$/.test(value)
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
}

/** 根据微信扫码开关返回二维码区或标准占位区所需文案。 */
export function getWechatLoginPresentation(enabled: boolean): WechatLoginPresentation {
  return enabled
    ? { enabled: true, title: '微信扫码登录', description: '请在微信中扫描登录' }
    : { enabled: false, title: '便捷登录', description: '请使用左侧账号方式登录' }
}

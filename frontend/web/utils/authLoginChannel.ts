export type AuthLoginChannel = 'wechat' | 'password' | 'sms' | 'email'

const STORAGE_KEY = 'auth:login-channel:v1'

export function setAuthLoginChannel(channel: AuthLoginChannel) {
  if (!(typeof window !== 'undefined')) return
  localStorage.setItem(STORAGE_KEY, channel)
}

export function getAuthLoginChannel(): AuthLoginChannel | null {
  if (!(typeof window !== 'undefined')) return null
  const value = localStorage.getItem(STORAGE_KEY)
  if (value === 'wechat' || value === 'password' || value === 'sms' || value === 'email') {
    return value
  }
  return null
}

export function clearAuthLoginChannel() {
  if (!(typeof window !== 'undefined')) return
  localStorage.removeItem(STORAGE_KEY)
}

export function isWechatLoginChannel(): boolean {
  return getAuthLoginChannel() === 'wechat'
}

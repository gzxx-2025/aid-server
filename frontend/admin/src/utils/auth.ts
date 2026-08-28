import Cookies from 'js-cookie';

const TokenKey = 'Admin-Token';

function cookieOptions(): Cookies.CookieAttributes {
  // 运行时判断协议，避免 SSR / 协议切换后状态陈旧
  return {
    secure: typeof window !== 'undefined' && window.location.protocol === 'https:',
    sameSite: 'lax'
  };
}

export function getToken(): string | undefined {
  return Cookies.get(TokenKey);
}

export function setToken(token: string) {
  return Cookies.set(TokenKey, token, cookieOptions());
}

export function removeToken() {
  // 删除时使用与设置相同的 path / secure 组合，避免残留
  return Cookies.remove(TokenKey, cookieOptions());
}

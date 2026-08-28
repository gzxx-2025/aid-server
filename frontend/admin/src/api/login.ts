import { request } from '@/utils/request';

export interface LoginParams {
  username: string;
  password: string;
  code?: string;
  uuid?: string;
  /** 后台随机登录入口访问码（启用时必传，作为请求头 X-Admin-Entry-Code 发送，不进 body） */
  entryCode?: string;
}

export interface LoginResult {
  token: string;
}

export function login(params: LoginParams) {
  const { entryCode, ...body } = params;
  const headers: Record<string, any> = { isToken: false, repeatSubmit: false };
  // 后台随机登录入口访问码：优先用显式传入的，其次回退到校验通过时存下的（sessionStorage），
  // 以请求头 X-Admin-Entry-Code 发送，保证后端 /login 能拿到并校验
  let code = entryCode;
  if (!code) {
    try {
      code = sessionStorage.getItem('adminEntryCode') || undefined;
    } catch {
      code = undefined;
    }
  }
  if (code) {
    headers['X-Admin-Entry-Code'] = code;
  }
  return request<LoginResult>({
    url: '/login',
    method: 'post',
    headers,
    data: body
  });
}

export function register(data: any) {
  return request({
    url: '/register',
    method: 'post',
    headers: { isToken: false },
    data
  });
}

export function getInfo() {
  return request<any>({
    url: '/getInfo',
    method: 'get'
  });
}

export function logout() {
  return request({
    url: '/logout',
    method: 'post'
  });
}

export interface CaptchaResult {
  captchaEnabled?: boolean;
  img?: string;
  uuid?: string;
}

export function getCodeImg() {
  return request<any>({
    url: '/captchaImage',
    method: 'get',
    headers: { isToken: false },
    timeout: 20000
  });
}

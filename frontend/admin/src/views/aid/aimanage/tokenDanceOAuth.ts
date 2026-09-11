import { completeTokenDanceAuthorization, getTokenDanceAuthorization, type TokenDanceAuthorizationView } from '@/api/aid/aimanage';

export interface AuthorizationContext {
  providerId: number;
  authorizationRef: string;
  returnPath: string;
  replacing: boolean;
  oldHint?: string;
  createdAt: number;
}

const storageKey = 'tokendance:oauth:pending';
const exchanges = new Map<string, Promise<TokenDanceAuthorizationView>>();

export function tokenDanceCallbackUrl() {
  const base = (import.meta.env.BASE_URL || '/').replace(/\/?$/, '/');
  return new URL(`${base}tokendance/oauth/callback`, window.location.origin).href;
}

export function rememberAuthorization(context: AuthorizationContext) {
  sessionStorage.setItem(storageKey, JSON.stringify(context));
}

export function forgetAuthorization() {
  sessionStorage.removeItem(storageKey);
}

export function readAuthorization(): AuthorizationContext {
  const raw = sessionStorage.getItem(storageKey);
  if (!raw) throw new Error('未找到本次授权，请从供应商配置重新发起');
  const context = JSON.parse(raw) as AuthorizationContext;
  if (!Number.isSafeInteger(context.providerId) || context.providerId <= 0 || !context.authorizationRef
    || !Number.isFinite(context.createdAt) || context.createdAt > Date.now()
    || Date.now() - context.createdAt > 10 * 60 * 1000) {
    sessionStorage.removeItem(storageKey);
    throw new Error('授权流程已过期，请重新发起');
  }
  return context;
}

// React 重挂载和重复点击共用同一次交换；失败也不自动重发一次性 code。
export function exchangeAuthorization(code: string | null, context: AuthorizationContext) {
  const requestKey = context.authorizationRef;
  let pending = exchanges.get(requestKey);
  if (!pending) {
    pending = (async () => {
      let result: TokenDanceAuthorizationView;
      try {
        const response = code
          ? await completeTokenDanceAuthorization(context.providerId, {
            authorizationRef: context.authorizationRef, code
          })
          : await getTokenDanceAuthorization(context.providerId, context.authorizationRef);
        result = response.data as TokenDanceAuthorizationView;
      } catch {
        // 响应丢失只查询状态，不重放授权码，也不自动创建另一个 Key。
        result = (await getTokenDanceAuthorization(context.providerId, context.authorizationRef)).data as TokenDanceAuthorizationView;
      }
      for (let attempt = 0; result.status === 'EXCHANGING' && attempt < 15; attempt++) {
        await new Promise(resolve => window.setTimeout(resolve, 2000));
        result = (await getTokenDanceAuthorization(context.providerId, context.authorizationRef)).data as TokenDanceAuthorizationView;
      }
      return result;
    })();
    exchanges.set(requestKey, pending);
  }
  return pending;
}

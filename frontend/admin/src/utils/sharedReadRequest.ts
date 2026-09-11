import { request } from './request';
import { getToken } from './auth';

const pending = new Map<string, Promise<any>>();
const recent = new Map<string, { expires: number; value: any }>();
let sessionToken = getToken();
let sessionGeneration = 0;
let invalidationGeneration = 0;
const ordered = (value: any): any => Array.isArray(value) ? value.map(ordered) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, ordered(value[key])])) : value;

/** 同一业务读取共用在途请求；强制刷新仅忽略短时结果缓存。 */
export function sharedReadRequest(url: string, params?: Record<string, unknown>, force = false) {
  const token = getToken();
  if (token !== sessionToken) {
    sessionToken = token; sessionGeneration++; recent.clear();
  }
  const generation = invalidationGeneration;
  const session = sessionGeneration;
  const key = `${url}:${session}:${JSON.stringify(ordered(params || {}))}`;
  const running = pending.get(key);
  if (running) return running;
  const cached = recent.get(key);
  if (!force && cached && cached.expires > Date.now()) return Promise.resolve(cached.value);
  for (const [oldKey, old] of recent) if (old.expires <= Date.now()) recent.delete(oldKey);
  const promise = request({ url, method: 'get', params }).then((value) => {
    if (generation === invalidationGeneration && session === sessionGeneration && token === getToken())
      recent.set(key, { expires: Date.now() + 1000, value });
    return value;
  }).finally(() => { pending.delete(key); });
  pending.set(key, promise);
  return promise;
}

export function invalidateSharedReads(prefix: string) {
  invalidationGeneration++;
  for (const key of recent.keys()) if (key.startsWith(prefix)) recent.delete(key);
}

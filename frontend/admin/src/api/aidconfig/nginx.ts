import { request } from '@/utils/request';

export type NginxAction = 'NGINX_VALIDATE' | 'NGINX_APPLY' | 'NGINX_ROLLBACK';
export interface NginxConfigParams {
  expectedRevision: string;
  backendOrigin?: string;
  maxBodyMb?: string;
  readTimeoutSeconds?: string;
  connectTimeoutSeconds?: string;
  extraDirectives?: string;
}

const inflight = new Map<string, ReturnType<typeof request<string>>>();
const recent = new Map<string, { until: number; result: Awaited<ReturnType<typeof request<string>>> }>();

/** Merge identical commands across buttons and remounts; failed commands remain retryable. */
export function submitNginxTask(action: NginxAction, params: NginxConfigParams) {
  const data = Object.fromEntries(Object.entries(params).sort(([a], [b]) => a.localeCompare(b)));
  const key = JSON.stringify([action, data]);
  const pending = inflight.get(key);
  if (pending) return pending;
  const now = Date.now();
  for (const [entry, cached] of recent) if (cached.until <= now) recent.delete(entry);
  const cached = recent.get(key);
  if (cached) return Promise.resolve(cached.result);
  const endpoint = { NGINX_VALIDATE: 'validate', NGINX_APPLY: 'apply', NGINX_ROLLBACK: 'rollback' }[action];
  const promise = request<string>({ url: `/aidconfig/upgrade/nginx/${endpoint}`, method: 'post', data })
    .then((result) => {
      recent.set(key, { until: Date.now() + 1500, result });
      return result;
    })
    .finally(() => inflight.delete(key));
  inflight.set(key, promise);
  return promise;
}

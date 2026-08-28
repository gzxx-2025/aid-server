import { isExternal } from '@/utils/validate';

/** 拼接父路径与子路径，行为对齐 Vue 版本的 path.resolve */
export function resolvePath(base: string, target: string): string {
  if (isExternal(target)) return target;
  if (isExternal(base)) return base;
  if (target.startsWith('/')) return normalize(target);
  if (!base) return '/' + target;
  return normalize(`${base.endsWith('/') ? base.slice(0, -1) : base}/${target}`);
}

export function normalize(p: string) {
  const out = p.replace(/\/+/g, '/');
  if (out.length > 1 && out.endsWith('/')) return out.slice(0, -1);
  return out;
}

import { useUserStore } from '@/store/useUserStore';

const ALL_PERM = '*:*:*';
const SUPER_ADMIN = 'admin';

function hasPermi(permission: string) {
  if (!permission) return false;
  const permissions = useUserStore.getState().permissions;
  return permissions.some((p) => p === ALL_PERM || p === permission);
}

function hasRole(role: string) {
  if (!role) return false;
  // 不再将 admin 视为通配符，精确匹配角色
  return useUserStore.getState().roles.includes(role);
}

const authActions = Object.freeze({
  hasPermi,
  hasPermiOr: (list: string[]) => list.some(hasPermi),
  hasPermiAnd: (list: string[]) => list.every(hasPermi),
  hasRole,
  hasRoleOr: (list: string[]) => list.some(hasRole),
  hasRoleAnd: (list: string[]) => list.every(hasRole),
  isSuperAdmin: () => useUserStore.getState().roles.includes(SUPER_ADMIN)
});

/** 权限校验 hook */
export function useAuth() {
  // 订阅权限与角色以刷新使用方视图；返回的校验函数引用在整个应用生命周期内保持不变。
  useUserStore((s) => s.permissions);
  useUserStore((s) => s.roles);
  return authActions;
}

/** 非 hook 形式（用于路由守卫等） */
export function checkPermi(permission: string[] | string) {
  const permissions = useUserStore.getState().permissions;
  const arr = Array.isArray(permission) ? permission : [permission];
  return arr.some((p) => permissions.some((x) => x === ALL_PERM || x === p));
}

export function checkRole(role: string[] | string) {
  const roles = useUserStore.getState().roles;
  const arr = Array.isArray(role) ? role : [role];
  // 精确匹配角色，不再将 admin 视为通配符
  return arr.some((r) => roles.includes(r));
}

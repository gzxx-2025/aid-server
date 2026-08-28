import React from 'react';
import { useAuth } from '@/hooks/useAuth';

interface Props {
  /** 需要的权限（或） */
  permission?: string | string[];
  /** 需要的角色（或） */
  role?: string | string[];
  children: React.ReactElement;
}

/**
 * 权限控制组件 - 条件渲染
 * 用法： <Auth permission={['system:user:edit']}><Button>编辑</Button></Auth>
 */
export default function Auth({ permission, role, children }: Props) {
  const { hasPermiOr, hasRoleOr } = useAuth();
  if (permission) {
    const arr = Array.isArray(permission) ? permission : [permission];
    if (!hasPermiOr(arr)) return null;
  }
  if (role) {
    const arr = Array.isArray(role) ? role : [role];
    if (!hasRoleOr(arr)) return null;
  }
  return children;
}

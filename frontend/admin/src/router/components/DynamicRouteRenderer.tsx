import React, { Suspense, useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Result, Button } from 'antd';

import { usePermissionStore, AppRoute } from '@/store/usePermissionStore';
import { getToken } from '@/utils/auth';
import { resolvePath } from '@/router/utils';
import LoadingFallback from './LoadingFallback';

/** 在拍扁的路由里按 path 匹配渲染 */
function findRoute(routes: AppRoute[], path: string, base = ''): AppRoute | null {
  for (const r of routes) {
    const full = resolvePath(base, r.path);
    if (r.component && matchPath(full, path)) return r;
    if (r.children && r.children.length) {
      const hit = findRoute(r.children, path, full);
      if (hit) return hit;
    }
  }
  return null;
}

/** 简单的路径参数匹配：将 :param 或 :param(regex) 转为通配 */
function matchPath(pattern: string, actual: string): boolean {
  if (pattern === actual) return true;
  // 将 :paramName(\d+) 或 :paramName 转为正则片段
  const regexStr = pattern
    .split('/')
    .map((seg) => {
      if (seg.startsWith(':')) {
        // 带括号约束的参数如 :tableId(\d+)
        const parenIdx = seg.indexOf('(');
        if (parenIdx > 0 && seg.endsWith(')')) {
          return seg.slice(parenIdx + 1, -1); // 提取括号内正则
        }
        return '[^/]+';
      }
      return seg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    })
    .join('/');
  try {
    return new RegExp(`^${regexStr}$`).test(actual);
  } catch {
    return false;
  }
}

/** 如果该 path 只是父层级且只有一个子，用于侧边栏只显示一层。这里做 default 重定向 */
function findDefaultChild(routes: AppRoute[], path: string, base = ''): AppRoute | null {
  for (const r of routes) {
    const full = resolvePath(base, r.path);
    if (full === path && r.children?.length) {
      const first = r.children.find((c) => !c.hidden && c.component);
      if (first) return first;
    }
    if (r.children) {
      const hit = findDefaultChild(r.children, path, full);
      if (hit) return hit;
    }
  }
  return null;
}

export default function DynamicRouteRenderer() {
  const location = useLocation();
  const navigate = useNavigate();
  const routes = usePermissionStore((s) => s.routes);
  const loaded = usePermissionStore((s) => s.loaded);

  const path = location.pathname;

  // 尝试按精确 path 命中
  const matched = findRoute(routes, path);

  useEffect(() => {
    if (!loaded) return;
    if (matched) return;
    // 命中父级节点但无叶子，尝试跳转到第一个子
    const child = findDefaultChild(routes, path);
    if (child) {
      const base = path.endsWith('/') ? path.slice(0, -1) : path;
      navigate(resolvePath(base, child.path), { replace: true });
    }
  }, [path, loaded, matched, routes, navigate]);

  if (!loaded) {
    // 如果没有 token，不应该永远 loading，RootGuard 会处理跳转
    const token = getToken();
    if (!token) return null;
    return <LoadingFallback />;
  }

  if (matched && matched.component) {
    const Cmp = matched.component;
    return (
      <Suspense fallback={<LoadingFallback />}>
        <Cmp />
      </Suspense>
    );
  }

  // 父级节点渲染中
  const child = findDefaultChild(routes, path);
  if (child) return <LoadingFallback />;

  return (
    <Result
      status="404"
      title="404"
      subTitle="您访问的页面不存在，或您没有访问该页面的权限。"
      extra={
        <Button type="primary" onClick={() => navigate('/index', { replace: true })}>
          回到首页
        </Button>
      }
    />
  );
}

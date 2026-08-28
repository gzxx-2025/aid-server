import { create } from 'zustand';
import { lazy, ComponentType, LazyExoticComponent } from 'react';

import { getRouters } from '@/api/menu';
import type { BackendRoute } from '@/types/common';

export interface AppRoute {
  name?: string;
  path: string;
  component?: ComponentType<any> | LazyExoticComponent<any> | null;
  /** 是否是布局节点：Layout / ParentView */
  isLayout?: boolean;
  isParentView?: boolean;
  isInnerLink?: boolean;
  hidden?: boolean;
  redirect?: string;
  alwaysShow?: boolean;
  meta?: BackendRoute['meta'];
  query?: string;
  permissions?: string[];
  roles?: string[];
  children?: AppRoute[];
}

interface PermissionState {
  routes: AppRoute[]; // 全量已挂载路由（常量 + 动态）
  sidebarRouters: AppRoute[]; // 侧边栏用
  loaded: boolean;
  setRoutes: (dynamicRoutes: AppRoute[]) => void;
  generateRoutes: () => Promise<AppRoute[]>;
  reset: () => void;
}

// 动态 import.meta.glob，在构建期生成 views 下所有 tsx/jsx 的映射
// 注意：这会把所有 view 文件路径打入 manifest，但实际组件仍是 lazy 加载
// 如果 views 目录过大，可按功能线拆分为多个 glob
const viewModules = import.meta.glob('/src/views/**/*.{tsx,jsx}');

/** 根据后端返回的 component 字段找到对应的 React 组件 */
function loadView(view: string): ComponentType<any> {
  const candidates = [
    `/src/views/${view}.tsx`,
    `/src/views/${view}/index.tsx`,
    `/src/views/${view}.jsx`,
    `/src/views/${view}/index.jsx`
  ];
  for (const p of candidates) {
    if ((viewModules as any)[p]) {
      return lazy((viewModules as any)[p] as any);
    }
  }
  // fallback：占位页
  return lazy(() => import('@/views/placeholder'));
}

function transform(raw: BackendRoute[]): AppRoute[] {
  return raw.map((item) => {
    const route: AppRoute = {
      name: item.name,
      path: item.path,
      hidden: item.hidden,
      redirect: item.redirect,
      alwaysShow: item.alwaysShow,
      meta: item.meta,
      query: item.query,
      permissions: item.permissions,
      roles: item.roles
    };
    if (item.component) {
      if (item.component === 'Layout') {
        route.isLayout = true;
      } else if (item.component === 'ParentView') {
        route.isParentView = true;
      } else if (item.component === 'InnerLink') {
        route.isInnerLink = true;
      } else {
        route.component = loadView(item.component);
      }
    }
    if (item.children && item.children.length) {
      route.children = transform(item.children);
    }
    return route;
  });
}

export const usePermissionStore = create<PermissionState>((set) => ({
  routes: [],
  sidebarRouters: [],
  loaded: false,
  setRoutes: (dynamic) =>
    set({
      routes: dynamic,
      sidebarRouters: dynamic,
      loaded: true
    }),
  generateRoutes: async () => {
    const res: any = await getRouters();
    const raw: BackendRoute[] = res.data || [];
    const routes = transform(raw);
    set({ routes, sidebarRouters: routes, loaded: true });
    return routes;
  },
  reset: () => set({ routes: [], sidebarRouters: [], loaded: false })
}));

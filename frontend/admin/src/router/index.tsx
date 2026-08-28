import React, { Suspense, lazy, useEffect, useState } from 'react';
import {
  createBrowserRouter,
  Navigate,
  RouterProvider,
  useLocation,
  useNavigate,
  Outlet
} from 'react-router-dom';
import NProgress from 'nprogress';
import 'nprogress/nprogress.css';

import { getToken } from '@/utils/auth';
import { getAdminEntryStatus, verifyAdminEntry } from '@/api/aid/adminEntry';
import { useUserStore } from '@/store/useUserStore';
import { usePermissionStore } from '@/store/usePermissionStore';
import { useSettingsStore } from '@/store/useSettingsStore';
import { useAdminBrandStore } from '@/store/useAdminBrandStore';
import { isPathMatch } from '@/utils/validate';

import MainLayout from '@/layouts/MainLayout';
import {
  LoginPage,
  RegisterPage,
  NotFoundPage,
  UnauthorizedPage,
  RedirectPage,
  DashboardPage,
  ProfilePage
} from './constants';
import LoadingFallback from './components/LoadingFallback';
import DynamicRouteRenderer from './components/DynamicRouteRenderer';

NProgress.configure({ showSpinner: false });

const JobLogPage = lazy(() => import('@/views/monitor/job/log'));
const UserAuthRolePage = lazy(() => import('@/views/system/user/authRole'));
const RoleAuthUserPage = lazy(() => import('@/views/system/role/authUser'));

const WHITE_LIST = ['/login', '/register', '/404', '/401'];
const ADMIN_ENTRY_CODE_PATTERN = /^[A-Za-z0-9]{8,32}$/;

function isWhite(path: string) {
  return WHITE_LIST.some((p) => isPathMatch(p, path));
}

/**
 * 随机后台入口只允许单段字母数字路径，长度口径与配置页保持一致。
 * 先做本地形态过滤，避免普通动态路由触发访问码校验和限流。
 */
function getAdminEntryCandidate(path: string): string | null {
  const normalized = path.replace(/^\/+|\/+$/g, '');
  if (!normalized || normalized.includes('/') || !ADMIN_ENTRY_CODE_PATTERN.test(normalized)) {
    return null;
  }
  return normalized;
}

/** 已登录状态下确认当前单段路径是否为正在生效的后台访问码。 */
async function isActiveAdminEntryPath(path: string): Promise<boolean> {
  const candidate = getAdminEntryCandidate(path);
  if (!candidate || !(await ensureEntryEnabled())) return false;

  // 同一标签页登录成功后优先使用已校验缓存，避免重复占用访问码校验限额。
  try {
    const storedCode = sessionStorage.getItem('adminEntryCode');
    // 已有可信缓存时可以直接判定：匹配则跳首页，不匹配则按普通业务路径处理。
    if (storedCode) return storedCode === candidate;
  } catch {
    /* ignore storage error */
  }

  // 新标签页可能只有登录 Token、没有 sessionStorage，仍需由后端确认当前访问码。
  const res: any = await verifyAdminEntry(candidate);
  const valid = !!(res?.valid ?? res?.data?.valid);
  if (valid) {
    try {
      sessionStorage.setItem('adminEntryCode', candidate);
    } catch {
      /* ignore storage error */
    }
  }
  return valid;
}

/** 缓存入口启用状态（一次性拉取；失败按未启用，避免误锁死登录入口） */
let _entryEnabled: boolean | null = null;
let _entryPromise: Promise<boolean> | null = null;
function ensureEntryEnabled(): Promise<boolean> {
  if (_entryEnabled !== null) return Promise.resolve(_entryEnabled);
  if (!_entryPromise) {
    _entryPromise = getAdminEntryStatus()
      .then((r: any) => {
        _entryEnabled = !!(r?.enabled ?? r?.data?.enabled);
        return _entryEnabled;
      })
      .catch(() => {
        _entryEnabled = false;
        return false;
      });
  }
  return _entryPromise;
}

/**
 * 根守卫：
 *  - 无 token 且不在白名单 → 跳转 /login
 *  - 有 token 访问 /login → 跳转 /
 *  - 有 token 首次进入 → 拉取用户信息 + 生成动态路由
 */
function RootGuard() {
  const location = useLocation();
  const navigate = useNavigate();
  const setTitle = useSettingsStore((s) => s.setTitle);
  const siteName = useAdminBrandStore((s) => s.resolvedSiteName);
  const brandLoaded = useAdminBrandStore((s) => s.loaded);
  const loadBrand = useAdminBrandStore((s) => s.load);

  const roles = useUserStore((s) => s.roles);
  const fetchInfo = useUserStore((s) => s.fetchInfo);
  const logoutUser = useUserStore((s) => s.logout);
  const generateRoutes = usePermissionStore((s) => s.generateRoutes);
  const routesLoaded = usePermissionStore((s) => s.loaded);

  // 未登录时先进入"门禁校验中"状态，避免在校验前闪出登录页
  const [gateChecking, setGateChecking] = useState(!getToken());
  // 启用随机入口且访问码校验通过时，直接在 /<访问码> 地址渲染登录页（不跳转 /login）
  const [secretLogin, setSecretLogin] = useState(false);
  // 已登录时的访问码路径也必须先完成确认，防止动态路由在重定向前闪出 404。
  const [checkedEntryPath, setCheckedEntryPath] = useState<string | null>(null);

  useEffect(() => {
    if (!brandLoaded) loadBrand();
  }, [brandLoaded, loadBrand]);

  useEffect(() => {
    NProgress.start();
    const path = location.pathname;
    const hasToken = !!getToken();

    if (!hasToken) {
      (async () => {
        try {
          const enabled = await ensureEntryEnabled();
          // 未启用随机入口：保持原有行为
          if (!enabled) {
            setSecretLogin(false);
            if (!isWhite(path)) {
              navigate(`/login?redirect=${encodeURIComponent(path + location.search)}`, { replace: true });
            }
            setGateChecking(false);
            return;
          }
          // 启用随机入口：登录页只在 /<访问码> 地址本身渲染；/login 与根路径一律挡到 404
          if (path === '/404' || path === '/401' || path === '/register' || path.startsWith('/redirect')) {
            setSecretLogin(false);
            setGateChecking(false);
            return;
          }
          if (path === '/login') {
            setSecretLogin(false);
            navigate('/404', { replace: true });
            return;
          }
          const seg = path.replace(/^\/+/, '').split('/')[0];
          if (!seg) {
            setSecretLogin(false);
            navigate('/404', { replace: true });
            return;
          }
          const res: any = await verifyAdminEntry(seg);
          if (res?.valid ?? res?.data?.valid) {
            // 校验通过：记下访问码（供 /login 请求头携带），并在当前 /<访问码> 地址直接渲染登录页，
            // 不跳转 /login，避免留下可复用入口
            try {
              sessionStorage.setItem('adminEntryCode', seg);
            } catch {
              /* ignore storage error */
            }
            setSecretLogin(true);
            setGateChecking(false);
          } else {
            setSecretLogin(false);
            navigate('/404', { replace: true });
          }
        } finally {
          NProgress.done();
        }
      })();
      return;
    }

    // 已登录
    setSecretLogin(false);
    if (path === '/login') {
      setGateChecking(false);
      navigate('/', { replace: true });
      NProgress.done();
      return;
    }

    if (isWhite(path)) {
      setGateChecking(false);
      NProgress.done();
      return;
    }

    const entryCandidate = getAdminEntryCandidate(path);
    // 访问码形态的路径必须等后端确认后再交给动态路由，避免先显示 404。
    setGateChecking(!!entryCandidate);

    (async () => {
      try {
        if (entryCandidate && await isActiveAdminEntryPath(path)) {
          navigate('/index', { replace: true });
          return;
        }
        if (roles.length === 0) {
          await fetchInfo();
          await generateRoutes();
        } else if (!routesLoaded) {
          await generateRoutes();
        }
      } catch (e) {
        await logoutUser();
        navigate('/login', { replace: true });
      } finally {
        setCheckedEntryPath(path);
        setGateChecking(false);
        NProgress.done();
      }
    })();

    return () => {
      NProgress.done();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname]);

  // 平台名称来自后台专用品牌接口，不能复用 C 端公开配置接口
  useEffect(() => {
    const title = `${siteName} 管理系统`;
    document.title = title;
    setTitle(title);
  }, [setTitle, siteName]);

  const loggedInEntryCandidate = !!getToken() && !!getAdminEntryCandidate(location.pathname);
  if (gateChecking || (loggedInEntryCandidate && checkedEntryPath !== location.pathname)) {
    return <LoadingFallback />;
  }

  // 启用随机入口且访问码校验通过：在 /<访问码> 地址直接渲染登录页（仅未登录时）
  if (secretLogin && !getToken()) {
    return (
      <Suspense fallback={<LoadingFallback />}>
        <LoginPage />
      </Suspense>
    );
  }

  return (
    <Suspense fallback={<LoadingFallback />}>
      <Outlet />
    </Suspense>
  );
}

// 路由 basename 跟随构建时的部署上下文路径（生产 /admin/，开发 /）
const routerBasename = (import.meta.env.BASE_URL || '/').replace(/\/$/, '') || '/';

const router = createBrowserRouter([
  {
    path: '/',
    element: <RootGuard />,
    children: [
      { path: 'login', element: <LoginPage /> },
      { path: 'register', element: <RegisterPage /> },
      { path: '404', element: <NotFoundPage /> },
      { path: '401', element: <UnauthorizedPage /> },
      { path: 'redirect/*', element: <RedirectPage /> },
      {
        path: '',
        element: <MainLayout />,
        children: [
          { index: true, element: <Navigate to="/index" replace /> },
          {
            path: 'index',
            element: <DashboardPage />,
            handle: { title: '首页', icon: 'dashboard', affix: true, name: 'Index' }
          },
          {
            path: 'user/profile',
            element: <ProfilePage />,
            handle: { title: '个人中心', icon: 'user', name: 'Profile' }
          },
          {
            path: 'monitor/job-log/index/:jobId',
            element: <JobLogPage />,
            handle: { title: '调度日志', name: 'JobLog' }
          },
          // 菜单操作跳转的隐藏页不会由后端动态菜单下发，需要在前端静态注册。
          {
            path: 'system/user-auth/role/:userId',
            element: <UserAuthRolePage />,
            handle: { title: '分配角色', name: 'AuthRole' }
          },
          {
            path: 'system/role-auth/user/:roleId',
            element: <RoleAuthUserPage />,
            handle: { title: '分配用户', name: 'AuthUser' }
          },
          // 动态路由占位：支持无限层级的后端路由
          { path: '*', element: <DynamicRouteRenderer /> }
        ]
      }
    ]
  }
], { basename: routerBasename });

export default function AppRouter() {
  return <RouterProvider router={router} />;
}

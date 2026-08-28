import { lazy } from 'react';
import type { RouteObject } from 'react-router-dom';

/**
 * 常量路由：与登录/错误/个人中心等无关权限的路由
 */
export const LoginPage = lazy(() => import('@/views/login'));
export const RegisterPage = lazy(() => import('@/views/register'));
export const NotFoundPage = lazy(() => import('@/views/error/404'));
export const UnauthorizedPage = lazy(() => import('@/views/error/401'));
export const RedirectPage = lazy(() => import('@/views/redirect'));
export const DashboardPage = lazy(() => import('@/views/dashboard'));
export const ProfilePage = lazy(() => import('@/views/system/user/profile'));

/**
 * 首页 affix 标签路由定义
 */
export const AFFIX_TAGS = [
  { path: '/index', title: '首页', name: 'Index', icon: 'dashboard' }
];

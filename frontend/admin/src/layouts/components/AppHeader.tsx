import React, { useMemo } from 'react';
import { Breadcrumb, Button, Space, Tooltip } from 'antd';
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ReloadOutlined,
  FullscreenOutlined,
  FullscreenExitOutlined,
  GithubOutlined,
  BellOutlined
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';

import { useAppStore } from '@/store/useAppStore';
import { usePermissionStore, AppRoute } from '@/store/usePermissionStore';
import { resolvePath } from '@/router/utils';
import './AppHeader.less';

function useBreadcrumbs() {
  const routes = usePermissionStore((s) => s.routes);
  const location = useLocation();

  return useMemo(() => {
    const segments = location.pathname.split('/').filter(Boolean);
    if (segments.length === 0) return [];
    // 逐段匹配
    const crumbs: Array<{ title: string; path: string }> = [
      { title: '首页', path: '/index' }
    ];
    if (location.pathname === '/index') return crumbs;

    const base = '';
    const walk = (list: AppRoute[], idx: number, parentPath: string) => {
      if (idx >= segments.length) return;
      const curr = '/' + segments.slice(0, idx + 1).join('/');
      for (const r of list) {
        const full = resolvePath(parentPath, r.path);
        if (full === curr) {
          if (r.meta?.title) crumbs.push({ title: r.meta.title, path: full });
          if (r.children) walk(r.children, idx + 1, full);
          return;
        }
        if (r.children) {
          const childFull = resolvePath(parentPath, r.path);
          if (curr.startsWith(childFull + '/')) {
            if (r.meta?.title && !r.alwaysShow && r.children.length === 1) {
              // 合并显示
            } else if (r.meta?.title) {
              crumbs.push({ title: r.meta.title, path: childFull });
            }
            walk(r.children, idx + 1, childFull);
            return;
          }
        }
      }
    };
    walk(routes, 0, '');
    return crumbs;
  }, [location.pathname, routes]);
}

export default function AppHeader() {
  const { sidebarOpened, toggleSidebar } = useAppStore();
  const navigate = useNavigate();
  const location = useLocation();
  const [isFullscreen, setFullscreen] = React.useState(false);

  const crumbs = useBreadcrumbs();

  const refresh = () => {
    const { pathname, search } = location;
    navigate(`/redirect${pathname}${search}`, { replace: true });
  };

  const toggleFullscreen = () => {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen();
      setFullscreen(true);
    } else {
      document.exitFullscreen();
      setFullscreen(false);
    }
  };

  return (
    <div className="app-header">
      <div className="app-header__left">
        <Button
          type="text"
          className="app-header__collapse"
          icon={sidebarOpened ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />}
          onClick={toggleSidebar}
        />
        <Breadcrumb
          className="app-header__breadcrumb"
          items={crumbs.map((c, idx) => ({
            title:
              idx === crumbs.length - 1 ? (
                <span>{c.title}</span>
              ) : (
                <a onClick={() => navigate(c.path)}>{c.title}</a>
              )
          }))}
        />
      </div>
      <div className="app-header__right">
        <Space size={4}>
          <Tooltip title="刷新">
            <Button type="text" icon={<ReloadOutlined />} onClick={refresh} />
          </Tooltip>
          <Tooltip title={isFullscreen ? '退出全屏' : '全屏'}>
            <Button
              type="text"
              icon={isFullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
              onClick={toggleFullscreen}
            />
          </Tooltip>
          <Tooltip title="消息">
            <Button type="text" icon={<BellOutlined />} />
          </Tooltip>
        </Space>
      </div>
    </div>
  );
}

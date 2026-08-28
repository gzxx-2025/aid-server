import React, { useMemo } from 'react';
import { Menu, Dropdown, Avatar, Modal } from 'antd';
import type { MenuProps } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { UserOutlined, LogoutOutlined, DownOutlined } from '@ant-design/icons';

import { usePermissionStore, AppRoute } from '@/store/usePermissionStore';
import { useUserStore } from '@/store/useUserStore';
import { useAdminBrandStore } from '@/store/useAdminBrandStore';
import { resolvePath } from '@/router/utils';
import { resolveAppUrl } from '@/utils/ruoyi';
import MenuIcon from './MenuIcon';
import SidebarVersionPanel from './SidebarVersionPanel';
import './AppSidebar.less';

interface Props {
  collapsed: boolean;
}

type MenuItem = Required<MenuProps>['items'][number];

/**
 * 把动态路由转换成 antd Menu items
 */
function convertToMenuItems(routes: AppRoute[], basePath = ''): MenuItem[] {
  const list: MenuItem[] = [];
  for (const r of routes) {
    if (r.hidden) continue;
    const full = resolvePath(basePath, r.path);
    const visibleChildren = (r.children || []).filter((c) => !c.hidden);
    // 单子且父级不 alwaysShow → 直接显示子
    if (visibleChildren.length === 1 && !r.alwaysShow) {
      const only = visibleChildren[0];
      const childFull = resolvePath(full, only.path);
      list.push({
        key: childFull,
        icon: <MenuIcon icon={only.meta?.icon || r.meta?.icon} />,
        label: only.meta?.title || r.meta?.title || ''
      });
    } else if (visibleChildren.length > 0) {
      list.push({
        key: full,
        icon: <MenuIcon icon={r.meta?.icon} />,
        label: r.meta?.title || '',
        children: convertToMenuItems(visibleChildren, full)
      });
    } else if (r.component || r.meta) {
      list.push({
        key: full,
        icon: <MenuIcon icon={r.meta?.icon} />,
        label: r.meta?.title || ''
      });
    }
  }
  return list;
}

export default function AppSidebar({ collapsed }: Props) {
  const sidebarRouters = usePermissionStore((s) => s.sidebarRouters);
  const location = useLocation();
  const navigate = useNavigate();
  const { nickName, avatar, logout } = useUserStore();
  const sidebarLogo = useAdminBrandStore((s) => s.resolvedSidebarLogo);
  const siteName = useAdminBrandStore((s) => s.resolvedSiteName);

  const menuItems = useMemo(() => convertToMenuItems(sidebarRouters), [sidebarRouters]);

  const selectedKey = location.pathname;
  const openKeys = useMemo(() => {
    const segs = location.pathname.split('/').filter(Boolean);
    const keys: string[] = [];
    let current = '';
    for (const s of segs) {
      current += '/' + s;
      keys.push(current);
    }
    return keys;
  }, [location.pathname]);

  const handleClick: MenuProps['onClick'] = ({ key }) => {
    if (key.startsWith('http://') || key.startsWith('https://')) {
      window.open(key, '_blank');
      return;
    }
    navigate(key);
  };

  const handleUserMenu: MenuProps['onClick'] = async ({ key }) => {
    if (key === 'profile') {
      navigate('/user/profile');
    } else if (key === 'logout') {
      Modal.confirm({
        title: '提示',
        content: '确定注销并退出系统吗？',
        okText: '确定',
        cancelText: '取消',
        onOk: async () => {
          await logout();
          window.location.href = resolveAppUrl('/login');
        }
      });
    }
  };

  return (
    <div className={`app-sidebar ${collapsed ? 'is-collapsed' : ''}`}>
      <div className="app-sidebar__logo">
        <img src={sidebarLogo} alt="logo" />
        {!collapsed && (
          <div className="app-sidebar__brand">
            <span className="app-sidebar__title">{siteName}</span>
            <SidebarVersionPanel collapsed={collapsed} />
          </div>
        )}
      </div>

      <div className="app-sidebar__menu-wrap">
        <Menu
          mode="inline"
          theme="light"
          items={menuItems}
          selectedKeys={[selectedKey]}
          defaultOpenKeys={openKeys}
          onClick={handleClick}
          inlineCollapsed={collapsed}
          className="app-sidebar__menu"
        />
      </div>

      <Dropdown
        placement="topRight"
        trigger={['click']}
        menu={{
          items: [
            { key: 'profile', icon: <UserOutlined />, label: '个人中心' },
            { type: 'divider' },
            { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }
          ],
          onClick: handleUserMenu
        }}
      >
        <div className={`app-sidebar__user ${collapsed ? 'is-collapsed' : ''}`}>
          <Avatar size={collapsed ? 32 : 34} src={avatar} icon={!avatar && <UserOutlined />} />
          {!collapsed && (
            <>
              <span className="app-sidebar__user-name">{nickName || '-'}</span>
              <DownOutlined className="app-sidebar__user-arrow" />
            </>
          )}
        </div>
      </Dropdown>
    </div>
  );
}

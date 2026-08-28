import React, { useEffect, useMemo } from 'react';
import { Layout } from 'antd';
import { Outlet, useLocation } from 'react-router-dom';

import AppSidebar from './components/AppSidebar';
import AppHeader from './components/AppHeader';
import TagsView from './components/TagsView';
import AppMain from './components/AppMain';
import { useAppStore } from '@/store/useAppStore';
import { useSettingsStore } from '@/store/useSettingsStore';

import './MainLayout.less';

const { Sider, Header, Content } = Layout;

export default function MainLayout() {
  const sidebarOpened = useAppStore((s) => s.sidebarOpened);
  const sidebarHide = useAppStore((s) => s.sidebarHide);
  const device = useAppStore((s) => s.device);
  const setDevice = useAppStore((s) => s.setDevice);
  const closeSidebar = useAppStore((s) => s.closeSidebar);
  const tagsView = useSettingsStore((s) => s.tagsView);
  const fixedHeader = useSettingsStore((s) => s.fixedHeader);
  const location = useLocation();

  // 响应式：小屏自动收起侧边栏
  useEffect(() => {
    const onResize = () => {
      const w = window.innerWidth;
      if (w < 992) {
        setDevice('mobile');
        closeSidebar(true);
      } else {
        setDevice('desktop');
      }
    };
    onResize();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [setDevice, closeSidebar]);

  // 移动端路由切换自动关闭侧边栏
  useEffect(() => {
    if (device === 'mobile' && sidebarOpened) {
      closeSidebar(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname]);

  const collapsed = !sidebarOpened;
  const siderWidth = 230;
  const collapsedWidth = 64;

  return (
    <Layout className={`app-layout ${device === 'mobile' ? 'is-mobile' : ''}`}>
      {!sidebarHide && (
        <>
          {device === 'mobile' && sidebarOpened && (
            <div className="app-layout__mask" onClick={() => closeSidebar(false)} />
          )}
          <Sider
            className="app-layout__sider"
            collapsed={collapsed}
            collapsedWidth={device === 'mobile' ? 0 : collapsedWidth}
            width={siderWidth}
            trigger={null}
            breakpoint="lg"
          >
            <AppSidebar collapsed={collapsed} />
          </Sider>
        </>
      )}

      <Layout className="app-layout__main">
        <Header className={`app-layout__header ${fixedHeader ? 'is-fixed' : ''}`}>
          <AppHeader />
        </Header>
        {tagsView && (
          <div className={`app-layout__tags ${fixedHeader ? 'is-fixed' : ''}`}>
            <TagsView />
          </div>
        )}
        <Content className={`app-layout__content ${fixedHeader ? 'has-fixed-header' : ''} ${tagsView ? 'has-tags' : ''}`}>
          <AppMain>
            <Outlet />
          </AppMain>
        </Content>
      </Layout>
    </Layout>
  );
}

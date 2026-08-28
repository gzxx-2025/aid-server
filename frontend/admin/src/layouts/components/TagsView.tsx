import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Dropdown, type MenuProps } from 'antd';
import {
  ReloadOutlined,
  CloseOutlined,
  ArrowLeftOutlined,
  ArrowRightOutlined,
  DoubleRightOutlined
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';

import { useTagsViewStore, TagView } from '@/store/useTagsViewStore';
import { usePermissionStore, AppRoute } from '@/store/usePermissionStore';
import { resolvePath } from '@/router/utils';
import { AFFIX_TAGS } from '@/router/constants';
import './TagsView.less';

function findRouteMeta(
  routes: AppRoute[],
  path: string,
  base = ''
): AppRoute | null {
  for (const r of routes) {
    const full = resolvePath(base, r.path);
    if (full === path) return r;
    if (r.children) {
      const hit = findRouteMeta(r.children, path, full);
      if (hit) return hit;
    }
  }
  return null;
}

export default function TagsView() {
  const { visitedViews, addView, delView, delOthersViews, delAllViews, delLeftViews, delRightViews, addAffixTags } =
    useTagsViewStore();
  const routes = usePermissionStore((s) => s.routes);
  const location = useLocation();
  const navigate = useNavigate();
  const wrapRef = useRef<HTMLDivElement>(null);
  const [ctxVisible, setCtxVisible] = useState(false);
  const [ctxPos, setCtxPos] = useState({ x: 0, y: 0 });
  const [ctxTarget, setCtxTarget] = useState<TagView | null>(null);

  // 初次加载 affix 标签
  useEffect(() => {
    const tags: TagView[] = AFFIX_TAGS.map((t) => ({
      path: t.path,
      fullPath: t.path,
      name: t.name,
      title: t.title,
      affix: true,
      meta: { icon: t.icon, affix: true }
    }));
    addAffixTags(tags);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 路由变化时添加
  useEffect(() => {
    const path = location.pathname;
    const matched = findRouteMeta(routes, path);
    const title = matched?.meta?.title || AFFIX_TAGS.find((t) => t.path === path)?.title;
    if (!title) return;
    addView({
      path,
      fullPath: path + location.search,
      name: matched?.name || path,
      title,
      meta: matched?.meta
    });
  }, [location.pathname, location.search, routes, addView]);

  // 激活的标签滚入视图
  useEffect(() => {
    if (!wrapRef.current) return;
    const active = wrapRef.current.querySelector('.tags-view__item.active') as HTMLElement;
    if (active) active.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
  }, [location.pathname]);

  const activePath = location.pathname;

  const goToLast = (list: TagView[], fallback = '/index') => {
    const last = list[list.length - 1];
    if (last) navigate(last.fullPath);
    else navigate(fallback);
  };

  const handleClose = async (tag: TagView, e: React.MouseEvent) => {
    e.stopPropagation();
    await delView(tag);
    if (tag.path === activePath) {
      const list = useTagsViewStore.getState().visitedViews;
      goToLast(list);
    }
  };

  const handleRefresh = (tag: TagView) => {
    navigate(`/redirect${tag.fullPath}`, { replace: true });
  };

  const openCtx = (tag: TagView, e: React.MouseEvent) => {
    e.preventDefault();
    setCtxTarget(tag);
    setCtxPos({ x: e.clientX, y: e.clientY });
    setCtxVisible(true);
  };

  useEffect(() => {
    const close = () => setCtxVisible(false);
    document.addEventListener('click', close);
    return () => document.removeEventListener('click', close);
  }, []);

  const menuItems: MenuProps['items'] = useMemo(
    () => [
      { key: 'refresh', icon: <ReloadOutlined />, label: '刷新页面' },
      { key: 'close', icon: <CloseOutlined />, label: '关闭当前', disabled: ctxTarget?.affix },
      { type: 'divider' },
      { key: 'closeOthers', icon: <DoubleRightOutlined />, label: '关闭其他' },
      { key: 'closeLeft', icon: <ArrowLeftOutlined />, label: '关闭左侧' },
      { key: 'closeRight', icon: <ArrowRightOutlined />, label: '关闭右侧' },
      { type: 'divider' },
      { key: 'closeAll', icon: <CloseOutlined />, label: '全部关闭' }
    ],
    [ctxTarget]
  );

  const handleMenuClick: MenuProps['onClick'] = async ({ key }) => {
    if (!ctxTarget) return;
    switch (key) {
      case 'refresh':
        handleRefresh(ctxTarget);
        break;
      case 'close':
        await delView(ctxTarget);
        if (ctxTarget.path === activePath) goToLast(useTagsViewStore.getState().visitedViews);
        break;
      case 'closeOthers':
        await delOthersViews(ctxTarget);
        navigate(ctxTarget.fullPath);
        break;
      case 'closeLeft':
        await delLeftViews(ctxTarget);
        break;
      case 'closeRight':
        await delRightViews(ctxTarget);
        break;
      case 'closeAll':
        await delAllViews();
        navigate('/index');
        break;
    }
    setCtxVisible(false);
  };

  return (
    <div className="tags-view" ref={wrapRef}>
      <AnimatePresence initial={false}>
        {visitedViews.map((v) => {
          const active = v.path === activePath;
          return (
            <motion.div
              key={v.path}
              layout
              initial={{ opacity: 0, y: -4, scale: 0.96 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, scale: 0.9 }}
              transition={{ duration: 0.18 }}
              className={`tags-view__item ${active ? 'active' : ''} ${v.affix ? 'is-affix' : ''}`}
              onClick={() => navigate(v.fullPath)}
              onContextMenu={(e) => openCtx(v, e)}
              onAuxClick={(e) => {
                if (e.button === 1 && !v.affix) handleClose(v, e);
              }}
            >
              <span className="tags-view__dot" />
              <span className="tags-view__title">{v.title}</span>
              {!v.affix && (
                <CloseOutlined
                  className="tags-view__close"
                  onClick={(e) => handleClose(v, e)}
                />
              )}
            </motion.div>
          );
        })}
      </AnimatePresence>

      <Dropdown
        menu={{ items: menuItems, onClick: handleMenuClick }}
        open={ctxVisible}
        trigger={[]}
        align={{ offset: [0, 0] }}
      >
        <div
          style={{
            position: 'fixed',
            top: ctxPos.y,
            left: ctxPos.x,
            width: 1,
            height: 1,
            pointerEvents: 'none'
          }}
        />
      </Dropdown>
    </div>
  );
}

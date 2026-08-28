import { create } from 'zustand';

import { getAdminBrandPublic, AdminBrandConfig } from '@/api/aid/adminBrand';
import defaultLogo from '@/assets/logo/logo.png';

interface AdminBrandState extends AdminBrandConfig {
  loaded: boolean;
  loading: boolean;
  /** 管理端实际展示的平台名称 */
  resolvedSiteName: string;
  /** 登录页实际展示的 Logo（配置优先，否则内置默认） */
  resolvedLoginLogo: string;
  /** 侧栏实际展示的 Logo */
  resolvedSidebarLogo: string;
  load: (force?: boolean) => Promise<void>;
}

export const DEFAULT_SITE_NAME = 'AID';
const DEFAULT_FAVICON = '/favicon.ico';

/** 动态替换浏览器页签图标 */
function applyFavicon(url?: string) {
  let link = document.querySelector("link[rel*='icon']") as HTMLLinkElement | null;
  if (!link) {
    link = document.createElement('link');
    link.rel = 'icon';
    document.head.appendChild(link);
  }
  link.href = url || DEFAULT_FAVICON;
}

/**
 * 后台平台品牌全局状态：各页面共用平台名称和 LOGO，并统一维护 favicon。
 * 未登录也可拉取（后台匿名接口），失败时回退内置品牌。
 */
export const useAdminBrandStore = create<AdminBrandState>((set, get) => ({
  siteName: undefined,
  platformLogoUrl: undefined,
  faviconUrl: undefined,
  loaded: false,
  loading: false,
  resolvedSiteName: DEFAULT_SITE_NAME,
  resolvedLoginLogo: defaultLogo,
  resolvedSidebarLogo: defaultLogo,

  load: async (force = false) => {
    if (get().loading || (get().loaded && !force)) return;
    set({ loading: true });
    try {
      const res: any = await getAdminBrandPublic();
      const data: AdminBrandConfig = res?.data || {};
      const siteName = String(data.siteName || '').trim() || DEFAULT_SITE_NAME;
      const platformLogo = data.platformLogoUrl || defaultLogo;
      applyFavicon(data.faviconUrl);
      set({
        siteName: data.siteName,
        platformLogoUrl: data.platformLogoUrl,
        faviconUrl: data.faviconUrl,
        resolvedSiteName: siteName,
        resolvedLoginLogo: platformLogo,
        resolvedSidebarLogo: platformLogo,
        loaded: true,
        loading: false
      });
    } catch {
      applyFavicon();
      set({
        siteName: undefined,
        platformLogoUrl: undefined,
        faviconUrl: undefined,
        resolvedSiteName: DEFAULT_SITE_NAME,
        resolvedLoginLogo: defaultLogo,
        resolvedSidebarLogo: defaultLogo,
        loaded: true,
        loading: false
      });
    }
  }
}));

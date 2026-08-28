import { request } from '@/utils/request';

export interface AdminBrandConfig {
  siteName?: string;
  platformLogoUrl?: string;
  faviconUrl?: string;
}

/** 匿名拉取后台平台品牌配置（平台名称 / LOGO / 页签图标） */
export function getAdminBrandPublic() {
  return request<AdminBrandConfig>({
    url: '/aid/adminBrand/public',
    method: 'get',
    headers: { isToken: false, repeatSubmit: false } as any
  });
}

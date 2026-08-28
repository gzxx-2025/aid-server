import { request } from '@/utils/request';
import type { BackendRoute } from '@/types/common';

/** 获取用户动态路由 */
export function getRouters() {
  return request<BackendRoute[]>({
    url: '/getRouters',
    method: 'get'
  });
}

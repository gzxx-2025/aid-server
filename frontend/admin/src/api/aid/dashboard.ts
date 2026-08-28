import { request } from '@/utils/request';

// 后台首页业务概览（一次性聚合：用户/在线/项目/剧集/分镜/生成/订单 全部计数）
export function getDashboardOverview() {
  return request({ url: '/aid/dashboard/overview', method: 'get' });
}

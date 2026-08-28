import { request } from '@/utils/request'

// 查询在线用户列表
export function list(query) {
  return request({
    url: '/monitor/online/list',
    method: 'get',
    params: query
  })
}

// 强退单个会话（Token）
export function forceLogout(tokenId) {
  return request({
    url: '/monitor/online/' + tokenId,
    method: 'delete'
  })
}

// 强退某个用户名下的全部会话（Token）
export function forceLogoutByUser(userId) {
  return request({
    url: '/monitor/online/user/' + userId,
    method: 'delete'
  })
}

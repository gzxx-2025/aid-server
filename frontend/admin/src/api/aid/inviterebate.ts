import { request } from '@/utils/request'

// 查询邀请返佣记录列表
export function listInviteRebate(query) {
  return request({
    url: '/aid/inviterebate/list',
    method: 'get',
    params: query
  })
}

// 查询邀请返佣记录详细
export function getInviteRebate(id) {
  return request({
    url: '/aid/inviterebate/' + id,
    method: 'get'
  })
}

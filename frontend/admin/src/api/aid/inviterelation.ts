import { request } from '@/utils/request'

// 查询邀请关系列表
export function listInviteRelation(query) {
  return request({
    url: '/aid/inviterelation/list',
    method: 'get',
    params: query
  })
}

// 查询邀请关系详细
export function getInviteRelation(id) {
  return request({
    url: '/aid/inviterelation/' + id,
    method: 'get'
  })
}

// 禁用/恢复邀请关系（status: 0正常 1禁用）
export function changeInviteRelationStatus(data) {
  return request({
    url: '/aid/inviterelation/changeStatus',
    method: 'put',
    data: data
  })
}

import { request } from '@/utils/request'

// 查询余额变动记录列表
export function listBalancelog(query) {
  return request({
    url: '/aid/balancelog/list',
    method: 'get',
    params: query
  })
}

// 查询余额变动记录详细
export function getBalancelog(id) {
  return request({
    url: '/aid/balancelog/' + id,
    method: 'get'
  })
}

// 新增余额变动记录
export function addBalancelog(data) {
  return request({
    url: '/aid/balancelog',
    method: 'post',
    data: data
  })
}

// 修改余额变动记录
export function updateBalancelog(data) {
  return request({
    url: '/aid/balancelog',
    method: 'put',
    data: data
  })
}

// 删除余额变动记录
export function delBalancelog(id) {
  return request({
    url: '/aid/balancelog/' + id,
    method: 'delete'
  })
}

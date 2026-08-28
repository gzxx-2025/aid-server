import { request } from '@/utils/request'

// 查询用户第三方登录授权列表
export function listThridsocial(query) {
  return request({
    url: '/aid/thridsocial/list',
    method: 'get',
    params: query
  })
}

// 查询用户第三方登录授权详细
export function getThridsocial(id) {
  return request({
    url: '/aid/thridsocial/' + id,
    method: 'get'
  })
}

// 新增用户第三方登录授权
export function addThridsocial(data) {
  return request({
    url: '/aid/thridsocial',
    method: 'post',
    data: data
  })
}

// 修改用户第三方登录授权
export function updateThridsocial(data) {
  return request({
    url: '/aid/thridsocial',
    method: 'put',
    data: data
  })
}

// 删除用户第三方登录授权
export function delThridsocial(id) {
  return request({
    url: '/aid/thridsocial/' + id,
    method: 'delete'
  })
}

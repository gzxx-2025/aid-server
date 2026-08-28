import { request } from '@/utils/request'

// 查询C端公告列表
export function listNotice(query) {
  return request({
    url: '/aid/notice/list',
    method: 'get',
    params: query
  })
}

// 查询C端公告详细
export function getNotice(id) {
  return request({
    url: '/aid/notice/' + id,
    method: 'get'
  })
}

// 新增C端公告
export function addNotice(data) {
  return request({
    url: '/aid/notice',
    method: 'post',
    data: data
  })
}

// 修改C端公告
export function updateNotice(data) {
  return request({
    url: '/aid/notice',
    method: 'put',
    data: data
  })
}

// 删除C端公告
export function delNotice(id) {
  return request({
    url: '/aid/notice/' + id,
    method: 'delete'
  })
}

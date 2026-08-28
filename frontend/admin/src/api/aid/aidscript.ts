import { request } from '@/utils/request'

// 查询剧本原文与简化版列表
export function listAidscript(query) {
  return request({
    url: '/aid/aidscript/list',
    method: 'get',
    params: query
  })
}

// 查询剧本原文与简化版详细
export function getAidscript(id) {
  return request({
    url: '/aid/aidscript/' + id,
    method: 'get'
  })
}

// 新增剧本原文与简化版
export function addAidscript(data) {
  return request({
    url: '/aid/aidscript',
    method: 'post',
    data: data
  })
}

// 修改剧本原文与简化版
export function updateAidscript(data) {
  return request({
    url: '/aid/aidscript',
    method: 'put',
    data: data
  })
}

// 删除剧本原文与简化版
export function delAidscript(id) {
  return request({
    url: '/aid/aidscript/' + id,
    method: 'delete'
  })
}

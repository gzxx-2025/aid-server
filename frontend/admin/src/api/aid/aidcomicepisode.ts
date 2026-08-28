import { request } from '@/utils/request'

// 查询剧集信息列表
export function listAidcomicepisode(query) {
  return request({
    url: '/aid/aidcomicepisode/list',
    method: 'get',
    params: query
  })
}

// 查询剧集信息详细
export function getAidcomicepisode(id) {
  return request({
    url: '/aid/aidcomicepisode/' + id,
    method: 'get'
  })
}

// 新增剧集信息
export function addAidcomicepisode(data) {
  return request({
    url: '/aid/aidcomicepisode',
    method: 'post',
    data: data
  })
}

// 修改剧集信息
export function updateAidcomicepisode(data) {
  return request({
    url: '/aid/aidcomicepisode',
    method: 'put',
    data: data
  })
}

// 删除剧集信息
export function delAidcomicepisode(id) {
  return request({
    url: '/aid/aidcomicepisode/' + id,
    method: 'delete'
  })
}

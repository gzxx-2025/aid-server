import { request } from '@/utils/request'

// 查询角色道具场景形态(从)列表
export function listRolepropsceneform(query) {
  return request({
    url: '/aid/rolepropsceneform/list',
    method: 'get',
    params: query
  })
}

// 查询角色道具场景形态(从)详细
export function getRolepropsceneform(id) {
  return request({
    url: '/aid/rolepropsceneform/' + id,
    method: 'get'
  })
}

// 新增角色道具场景形态(从)
export function addRolepropsceneform(data) {
  return request({
    url: '/aid/rolepropsceneform',
    method: 'post',
    data: data
  })
}

// 修改角色道具场景形态(从)
export function updateRolepropsceneform(data) {
  return request({
    url: '/aid/rolepropsceneform',
    method: 'put',
    data: data
  })
}

// 删除角色道具场景形态(从)
export function delRolepropsceneform(id) {
  return request({
    url: '/aid/rolepropsceneform/' + id,
    method: 'delete'
  })
}

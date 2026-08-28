import { request } from '@/utils/request'

// 查询角色道具场景列表
export function listScenecp(query) {
  return request({
    url: '/aid/scenecp/list',
    method: 'get',
    params: query
  })
}

// 查询角色道具场景详细
export function getScenecp(id) {
  return request({
    url: '/aid/scenecp/' + id,
    method: 'get'
  })
}

// 新增角色道具场景
export function addScenecp(data) {
  return request({
    url: '/aid/scenecp',
    method: 'post',
    data: data
  })
}

// 修改角色道具场景
export function updateScenecp(data) {
  return request({
    url: '/aid/scenecp',
    method: 'put',
    data: data
  })
}

// 删除角色道具场景
export function delScenecp(id) {
  return request({
    url: '/aid/scenecp/' + id,
    method: 'delete'
  })
}

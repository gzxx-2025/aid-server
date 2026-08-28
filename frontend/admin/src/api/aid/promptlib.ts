import { request } from '@/utils/request'

// 查询提示词素材库(官方预设与用户自定义)列表
export function listPromptlib(query) {
  return request({
    url: '/aid/promptlib/list',
    method: 'get',
    params: query
  })
}

// 查询提示词素材库(官方预设与用户自定义)详细
export function getPromptlib(id) {
  return request({
    url: '/aid/promptlib/' + id,
    method: 'get'
  })
}

// 新增提示词素材库(官方预设与用户自定义)
export function addPromptlib(data) {
  return request({
    url: '/aid/promptlib',
    method: 'post',
    data: data
  })
}

// 修改提示词素材库(官方预设与用户自定义)
export function updatePromptlib(data) {
  return request({
    url: '/aid/promptlib',
    method: 'put',
    data: data
  })
}

// 删除提示词素材库(官方预设与用户自定义)
export function delPromptlib(id) {
  return request({
    url: '/aid/promptlib/' + id,
    method: 'delete'
  })
}

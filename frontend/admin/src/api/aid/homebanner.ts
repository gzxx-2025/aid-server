import { request } from '@/utils/request'

// 查询首页Banner配置列表
export function listHomebanner(query) {
  return request({
    url: '/aid/homebanner/list',
    method: 'get',
    params: query
  })
}

// 查询首页Banner配置详细
export function getHomebanner(id) {
  return request({
    url: '/aid/homebanner/' + id,
    method: 'get'
  })
}

// 新增首页Banner配置
export function addHomebanner(data) {
  return request({
    url: '/aid/homebanner',
    method: 'post',
    data: data
  })
}

// 修改首页Banner配置
export function updateHomebanner(data) {
  return request({
    url: '/aid/homebanner',
    method: 'put',
    data: data
  })
}

// 删除首页Banner配置
export function delHomebanner(id) {
  return request({
    url: '/aid/homebanner/' + id,
    method: 'delete'
  })
}

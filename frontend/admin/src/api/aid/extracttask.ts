import { request } from '@/utils/request'

// 查询资产提取任务列表
export function listExtracttask(query) {
  return request({
    url: '/aid/extracttask/list',
    method: 'get',
    params: query
  })
}

// 查询资产提取任务详细
export function getExtracttask(id) {
  return request({
    url: '/aid/extracttask/' + id,
    method: 'get'
  })
}

// 新增资产提取任务
export function addExtracttask(data) {
  return request({
    url: '/aid/extracttask',
    method: 'post',
    data: data
  })
}

// 修改资产提取任务
export function updateExtracttask(data) {
  return request({
    url: '/aid/extracttask',
    method: 'put',
    data: data
  })
}

// 删除资产提取任务
export function delExtracttask(id) {
  return request({
    url: '/aid/extracttask/' + id,
    method: 'delete'
  })
}

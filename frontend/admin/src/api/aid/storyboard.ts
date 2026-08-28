import { request } from '@/utils/request'

// 查询分镜时间轴主列表
export function listStoryboard(query) {
  return request({
    url: '/aid/storyboard/list',
    method: 'get',
    params: query
  })
}

// 查询分镜时间轴主详细
export function getStoryboard(id) {
  return request({
    url: '/aid/storyboard/' + id,
    method: 'get'
  })
}

// 新增分镜时间轴主
export function addStoryboard(data) {
  return request({
    url: '/aid/storyboard',
    method: 'post',
    data: data
  })
}

// 修改分镜时间轴主
export function updateStoryboard(data) {
  return request({
    url: '/aid/storyboard',
    method: 'put',
    data: data
  })
}

// 删除分镜时间轴主
export function delStoryboard(id) {
  return request({
    url: '/aid/storyboard/' + id,
    method: 'delete'
  })
}

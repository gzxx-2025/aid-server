import { request } from '@/utils/request'

// 查询剧集视频剪辑与成片最新状态列表
export function listPisodeeditor(query) {
  return request({
    url: '/aid/pisodeeditor/list',
    method: 'get',
    params: query
  })
}

// 查询剧集视频剪辑与成片最新状态详细
export function getPisodeeditor(id) {
  return request({
    url: '/aid/pisodeeditor/' + id,
    method: 'get'
  })
}

// 新增剧集视频剪辑与成片最新状态
export function addPisodeeditor(data) {
  return request({
    url: '/aid/pisodeeditor',
    method: 'post',
    data: data
  })
}

// 修改剧集视频剪辑与成片最新状态
export function updatePisodeeditor(data) {
  return request({
    url: '/aid/pisodeeditor',
    method: 'put',
    data: data
  })
}

// 删除剧集视频剪辑与成片最新状态
export function delPisodeeditor(id) {
  return request({
    url: '/aid/pisodeeditor/' + id,
    method: 'delete'
  })
}

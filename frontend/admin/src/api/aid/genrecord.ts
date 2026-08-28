import { request } from '@/utils/request'

// 查询AI生图/生视频抽卡记录列表
export function listGenrecord(query) {
  return request({
    url: '/aid/genrecord/list',
    method: 'get',
    params: query
  })
}

// 查询AI生图/生视频抽卡记录详细
export function getGenrecord(id) {
  return request({
    url: '/aid/genrecord/' + id,
    method: 'get'
  })
}

// 新增AI生图/生视频抽卡记录
export function addGenrecord(data) {
  return request({
    url: '/aid/genrecord',
    method: 'post',
    data: data
  })
}

// 修改AI生图/生视频抽卡记录
export function updateGenrecord(data) {
  return request({
    url: '/aid/genrecord',
    method: 'put',
    data: data
  })
}

// 删除AI生图/生视频抽卡记录
export function delGenrecord(id) {
  return request({
    url: '/aid/genrecord/' + id,
    method: 'delete'
  })
}

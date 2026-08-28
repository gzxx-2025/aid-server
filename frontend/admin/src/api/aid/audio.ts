import { request } from '@/utils/request'

// 查询分镜配音业务记录列表
export function listAudioRecord(query) {
  return request({
    url: '/aid/audiorecord/list',
    method: 'get',
    params: query
  })
}

// 查询分镜配音业务记录详细
export function getAudioRecord(id) {
  return request({
    url: '/aid/audiorecord/' + id,
    method: 'get'
  })
}

// 新增分镜配音业务记录（系统流水线写入，禁止手动新增）
export function addAudioRecord(data) {
  return request({
    url: '/aid/audiorecord',
    method: 'post',
    data: data
  })
}

// 修改分镜配音业务记录（系统流水线写入，禁止手动修改）
export function updateAudioRecord(data) {
  return request({
    url: '/aid/audiorecord',
    method: 'put',
    data: data
  })
}

// 删除分镜配音业务记录（系统流水线写入，禁止手动删除）
export function delAudioRecord(id) {
  return request({
    url: '/aid/audiorecord/' + id,
    method: 'delete'
  })
}

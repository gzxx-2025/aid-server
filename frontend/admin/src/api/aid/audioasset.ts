import { request } from '@/utils/request'

// 查询音频资产分页列表（POST）
export function listAudioAsset(data) {
  return request({
    url: '/aid/audio-asset/list',
    method: 'post',
    data: data,
    headers: { repeatSubmit: false } as any
  })
}

// 查询音频资产详情
export function getAudioAsset(id) {
  return request({
    url: '/aid/audio-asset/' + id,
    method: 'get'
  })
}

// 批量软删除音频资产
export function delAudioAsset(ids) {
  return request({
    url: '/aid/audio-asset/' + ids,
    method: 'delete'
  })
}

// 导出音频资产（按当前搜索条件）
export function exportAudioAsset(data) {
  return request({
    url: '/aid/audio-asset/export',
    method: 'post',
    data: data
  })
}

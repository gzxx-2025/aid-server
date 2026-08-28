import { request } from '@/utils/request'

export interface AssetUserDetail {
  id: number
  hiddenStylePromptJson?: string | null
  [key: string]: unknown
}

// 查询用户自定义漫画参考资产列表
export function listAssetuser(query) {
  return request({
    url: '/aid/assetuser/list',
    method: 'get',
    params: query
  })
}

// 查询用户自定义漫画参考资产详细
export function getAssetuser(id: number | string) {
  return request<AssetUserDetail>({
    url: '/aid/assetuser/' + id,
    method: 'get'
  })
}

// 新增用户自定义漫画参考资产
export function addAssetuser(data) {
  return request({
    url: '/aid/assetuser',
    method: 'post',
    data: data
  })
}

// 修改用户自定义漫画参考资产
export function updateAssetuser(data) {
  return request({
    url: '/aid/assetuser',
    method: 'put',
    data: data
  })
}

// 删除用户自定义漫画参考资产
export function delAssetuser(id) {
  return request({
    url: '/aid/assetuser/' + id,
    method: 'delete'
  })
}

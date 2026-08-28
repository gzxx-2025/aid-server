import { request } from '@/utils/request'

export interface AidProjectDetail {
  id: number
  hiddenStylePromptJson?: string | null
  [key: string]: unknown
}

// 查询漫剧项目主列表
export function listAidproject(query) {
  return request({
    url: '/aid/aidproject/list',
    method: 'get',
    params: query
  })
}

// 查询漫剧项目主详细
export function getAidproject(id: number | string) {
  return request<AidProjectDetail>({
    url: '/aid/aidproject/' + id,
    method: 'get'
  })
}

// 新增漫剧项目主
export function addAidproject(data) {
  return request({
    url: '/aid/aidproject',
    method: 'post',
    data: data
  })
}

// 修改漫剧项目主
export function updateAidproject(data) {
  return request({
    url: '/aid/aidproject',
    method: 'put',
    data: data
  })
}

// 删除漫剧项目主
export function delAidproject(id) {
  return request({
    url: '/aid/aidproject/' + id,
    method: 'delete'
  })
}

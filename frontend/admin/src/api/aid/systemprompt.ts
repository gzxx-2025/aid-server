import { request } from '@/utils/request'

// 查询系统提示词列表
export function listSystemPrompt(query) {
  return request({
    url: '/aid/promptlib/systemList',
    method: 'get',
    params: query
  })
}

// 查询系统提示词详情
export function getSystemPrompt(id) {
  return request({
    url: '/aid/promptlib/' + id,
    method: 'get'
  })
}

// 修改系统提示词
export function updateSystemPrompt(data) {
  return request({
    url: '/aid/promptlib/systemUpdate',
    method: 'put',
    data: data
  })
}

// 检查系统提示词版本更新状态
export function checkSystemPromptUpdate() {
  return request({
    url: '/aid/promptlib/systemCheckUpdate',
    method: 'get'
  })
}

// 根据文件名称获取提示词的历史版本列表
export function getSystemPromptVersions(remark) {
  return request({
    url: '/aid/promptlib/systemVersions/' + remark,
    method: 'get'
  })
}

// 拉取系统提示词更新
export function pullSystemPromptUpdate(remark) {
  return request({
    url: '/aid/promptlib/systemPullUpdate/' + remark,
    method: 'put'
  })
}

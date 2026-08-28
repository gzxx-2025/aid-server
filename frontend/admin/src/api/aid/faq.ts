import { request } from '@/utils/request'

// 查询常见问题列表
export function listFaq(query) {
  return request({
    url: '/aid/faq/list',
    method: 'get',
    params: query
  })
}

// 查询常见问题详细
export function getFaq(id) {
  return request({
    url: '/aid/faq/' + id,
    method: 'get'
  })
}

// 新增常见问题
export function addFaq(data) {
  return request({
    url: '/aid/faq',
    method: 'post',
    data: data
  })
}

// 修改常见问题
export function updateFaq(data) {
  return request({
    url: '/aid/faq',
    method: 'put',
    data: data
  })
}

// 删除常见问题
export function delFaq(id) {
  return request({
    url: '/aid/faq/' + id,
    method: 'delete'
  })
}

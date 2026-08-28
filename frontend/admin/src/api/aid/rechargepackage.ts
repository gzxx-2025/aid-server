import { request } from '@/utils/request'

// 查询充值套餐配置列表
export function listRechargepackage(query) {
  return request({
    url: '/aid/rechargepackage/list',
    method: 'get',
    params: query
  })
}

// 查询充值套餐配置详细
export function getRechargepackage(id) {
  return request({
    url: '/aid/rechargepackage/' + id,
    method: 'get'
  })
}

// 新增充值套餐配置
export function addRechargepackage(data) {
  return request({
    url: '/aid/rechargepackage',
    method: 'post',
    data: data
  })
}

// 修改充值套餐配置
export function updateRechargepackage(data) {
  return request({
    url: '/aid/rechargepackage',
    method: 'put',
    data: data
  })
}

// 删除充值套餐配置
export function delRechargepackage(id) {
  return request({
    url: '/aid/rechargepackage/' + id,
    method: 'delete'
  })
}

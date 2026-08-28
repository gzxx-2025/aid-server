import { request } from '@/utils/request'

// 查询支付订单列表
export function listPayorder(query) {
  return request({
    url: '/aid/payorder/list',
    method: 'get',
    params: query
  })
}

// 查询支付订单详细
export function getPayorder(id) {
  return request({
    url: '/aid/payorder/' + id,
    method: 'get'
  })
}

// 新增支付订单
export function addPayorder(data) {
  return request({
    url: '/aid/payorder',
    method: 'post',
    data: data
  })
}

// 修改支付订单
export function updatePayorder(data) {
  return request({
    url: '/aid/payorder',
    method: 'put',
    data: data
  })
}

// 删除支付订单
export function delPayorder(id) {
  return request({
    url: '/aid/payorder/' + id,
    method: 'delete'
  })
}

// 同步支付宝订单状态
export function syncPayorder(orderNo) {
  return request({
    url: '/aid/payorder/sync/' + orderNo,
    method: 'post'
  })
}

// 订单退款（后台运营操作）
export function refundPayorder(data) {
  return request({
    url: '/aid/payorder/refund',
    method: 'post',
    data: data
  })
}

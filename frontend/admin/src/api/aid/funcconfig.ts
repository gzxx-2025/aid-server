import { request } from '@/utils/request'
import { sharedReadRequest, invalidateSharedReads } from '@/utils/sharedReadRequest'

// 查询AI模型功能配置列表
export function listFuncconfig(query) {
  return sharedReadRequest('/aid/funcconfig/list', query)
}

// 查询AI模型功能配置详细
export function getFuncconfig(id) {
  return sharedReadRequest('/aid/funcconfig/' + id)
}

// 新增AI模型功能配置
export function addFuncconfig(data) {
  return request({
    url: '/aid/funcconfig',
    method: 'post',
    data: data
  }).then((result) => { invalidateSharedReads('/aid/funcconfig'); return result })
}

// 修改AI模型功能配置
export function updateFuncconfig(data) {
  return request({
    url: '/aid/funcconfig',
    method: 'put',
    data: data
  }).then((result) => { invalidateSharedReads('/aid/funcconfig'); return result })
}

// 删除AI模型功能配置
export function delFuncconfig(id) {
  return request({
    url: '/aid/funcconfig/' + id,
    method: 'delete'
  }).then((result) => { invalidateSharedReads('/aid/funcconfig'); return result })
}
// v2.34.0 鏂板锛氫緵鍔熻兘妯″瀷閰嶇疆椤甸潰鎷夊彇鍙€夋ā鍨嬶紝鐩存帴澶嶇敤鍚庡彴 /aid/aidmodel/list
// 涓嶆柊澧炲悗绔帴鍙ｏ紱query 閫忎紶 modelType / generateMode / modelCode / modelName / providerId / status 绛夌瓫閫夊弬鏁?
export function listModelForFuncconfig(query) {
  return sharedReadRequest('/aid/aidmodel/list', query)
}

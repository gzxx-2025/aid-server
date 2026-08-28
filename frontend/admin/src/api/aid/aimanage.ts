import { request } from '@/utils/request'

// ==================== 服务商接口 ====================

export function listProvider(query) {
  return request({ url: '/aid/aidprovider/list', method: 'get', params: query })
}

export function getProvider(id) {
  return request({ url: '/aid/aidprovider/' + id, method: 'get' })
}

export function addProvider(data) {
  return request({ url: '/aid/aidprovider', method: 'post', data })
}

export function updateProvider(data) {
  return request({ url: '/aid/aidprovider', method: 'put', data })
}

/** 只切换服务商启停状态，避免触发完整配置编辑校验。 */
export function updateProviderStatus(data: { id: number; status: '0' | '1' }) {
  return request({ url: '/aid/aidprovider/status', method: 'put', data })
}

export function delProvider(id) {
  return request({ url: '/aid/aidprovider/' + id, method: 'delete' })
}

/** 服务端动态声明供应商支持的后台扩展能力。 */
export function getProviderOperationCapabilities(id: number) {
  return request({ url: `/aid/aidprovider/${id}/operations/capabilities`, method: 'get' })
}

export function getProviderBalance(id: number, params?: Record<string, any>) {
  return request({ url: `/aid/aidprovider/${id}/operations/balance`, method: 'get', params })
}

export function listProviderUpstreamTasks(id: number, data?: Record<string, any>) {
  return request({ url: `/aid/aidprovider/${id}/operations/tasks`, method: 'post', data: data || {} })
}

// ==================== 模型接口 ====================

export function listModel(query) {
  return request({ url: '/aid/aidmodel/list', method: 'get', params: query })
}

export function getModel(id) {
  return request({ url: '/aid/aidmodel/' + id, method: 'get' })
}

export function addModel(data) {
  return request({ url: '/aid/aidmodel', method: 'post', data })
}

export function updateModel(data) {
  return request({ url: '/aid/aidmodel', method: 'put', data })
}

export function delModel(id) {
  return request({ url: '/aid/aidmodel/' + id, method: 'delete' })
}

/**
 * v2.59+：后台 12 模型管理页面专用 —— 按 funcCode 查可用模型池
 * 走后台 GET /aid/aidmodel/listByFunc，不经过 C 端加密链路
 */
export function listModelByFunc(funcCode: string) {
  return request({
    url: '/aid/aidmodel/listByFunc',
    method: 'get',
    params: { funcCode }
  })
}

// ==================== 真实模型总览接口 ====================

/** 真实模型总览行（同一真实模型下的单个展示模型） */
export interface RealModelItem {
  id: number;
  modelCode: string;
  modelName: string;
  realModelCode?: string;
  modelType: string;
  generateMode?: string;
  providerId?: number;
  providerName?: string;
  status: string;
  priority?: number;
}

/** 真实模型总览分组（按真实上游模型名聚合） */
export interface RealModelGroup {
  realModelCode: string;
  modelType: string;
  activeCount: number;
  totalCount: number;
  models: RealModelItem[];
}

/** 真实模型总览：按真实上游模型名聚合，支持关键字搜索 */
export function realModelOverview(keyword?: string) {
  return request<RealModelGroup[]>({
    url: '/aid/aidmodel/realModelOverview',
    method: 'get',
    params: keyword ? { keyword } : {}
  })
}

// ==================== 计费试算接口 ====================

export function billingPreview(data) {
  return request({ url: '/aid/billing/preview', method: 'post', data })
}

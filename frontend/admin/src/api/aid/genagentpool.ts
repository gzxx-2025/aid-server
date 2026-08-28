import { request } from '@/utils/request';

// ============ 矩阵可视化配置（推荐） ============

// 矩阵列表（按 步骤×业务场景×创作模式×剧本类型 聚合的格子）
export function getAgentMatrix(step?: string) {
  return request({ url: '/aid/genagentpool/matrix', method: 'get', params: { step } });
}

// 某业务场景下可选的智能体 + 带场景级清晰度/比例能力的模型（联动下拉数据）
export function getPoolOptions(biz: string) {
  return request({ url: '/aid/genagentpool/options', method: 'get', params: { biz } });
}

// 覆盖式保存一个格子
export function saveMatrixCell(data: any) {
  return request({ url: '/aid/genagentpool/matrix/save', method: 'post', data });
}

// 删除一个格子
export function deleteMatrixCell(data: any) {
  return request({ url: '/aid/genagentpool/matrix/delete', method: 'post', data });
}

// ============ 行级原始 CRUD（高级/排错） ============

export function listGenAgentPool(query: any) {
  return request({ url: '/aid/genagentpool/list', method: 'get', params: query });
}
export function delGenAgentPool(id: number | number[]) {
  return request({ url: '/aid/genagentpool/' + id, method: 'delete' });
}

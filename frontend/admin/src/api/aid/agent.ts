import { request } from '@/utils/request';

/**
 * 智能体记录（管理端：含 promptContent）
 */
export interface AgentItem {
  id: number;
  agentCode: string;
  name: string;
  /** 智能体图标地址（管理端上传、列表展示） */
  iconUrl?: string;
  subTitle?: string;
  introduction?: string;
  promptContent?: string;
  modelCode?: string;
  /** 业务分类编码（与 aid_ai_model_func_config.func_code 联动） */
  bizCategoryCode?: string;
  status: number;
  delFlag?: string;
  remark?: string;
  createBy?: string;
  createTime?: string;
  updateBy?: string;
  updateTime?: string;
}

/**
 * C 端智能体信息（不含 promptContent）
 */
export interface AgentInfoVO {
  id: number;
  agentCode: string;
  name: string;
  /** 智能体图标地址 */
  iconUrl?: string;
  subTitle?: string;
  introduction?: string;
  modelCode?: string;
  bizCategoryCode?: string;
  status: number;
}

/**
 * C 端：业务分类分组（不含 promptContent）
 */
export interface AgentListGroupVO {
  bizCategoryCode: string | null;
  agents: AgentInfoVO[];
}

/**
 * 后台查询条件
 */
export interface AgentQuery {
  pageNum?: number;
  pageSize?: number;
  bizCategoryCode?: string;
  name?: string;
  agentCode?: string;
  status?: number;
}

// ==================== 后台管理 ====================

/** 后台：分页查询智能体列表（支持 bizCategoryCode / name / agentCode / status 过滤） */
export function listAgent(query: AgentQuery) {
  return request({
    url: '/aid/agent/list',
    method: 'get',
    params: query
  });
}

/**
 * v2.59+：后台 12 模型管理页面专用 —— 按 bizCategoryCode 查启用智能体（不分页）
 * 走后台 GET /aid/agent/listByBizCategory，不经过 C 端加密链路。
 * 注意：与 C 端 `listAgentByBizCategory`（POST /aid/agent/list）区分，避免命名冲突。
 */
export function listAgentByBizCategoryAdmin(bizCategoryCode: string) {
  return request<AgentItem[]>({
    url: '/aid/agent/listByBizCategory',
    method: 'get',
    params: { bizCategoryCode }
  });
}

/** 后台：根据 ID 查询智能体详情（含 promptContent） */
export function getAgent(id: number | string) {
  return request({
    url: `/aid/agent/${id}`,
    method: 'get'
  });
}

/** 后台：新增智能体 */
export function createAgent(data: Partial<AgentItem>) {
  return request({
    url: '/aid/agent',
    method: 'post',
    data
  });
}

/** 后台：修改智能体 */
export function updateAgent(data: Partial<AgentItem>) {
  return request({
    url: '/aid/agent',
    method: 'put',
    data
  });
}

/** 后台：删除智能体 */
export function deleteAgent(id: number | string) {
  return request({
    url: `/aid/agent/${id}`,
    method: 'delete'
  });
}

// ==================== C 端 ====================

/** C 端：根据 agentCode 查询智能体信息（不含 promptContent） */
export function getAgentInfo(agentCode: string) {
  return request<AgentInfoVO>({
    url: '/aid/agent/info',
    method: 'post',
    data: { agentCode }
  });
}

/** C 端：按 bizCategoryCodes 查询启用智能体列表，按业务分类分组返回（不含 promptContent） */
export function listAgentByBizCategory(params: { bizCategoryCodes?: string[] }) {
  return request<AgentListGroupVO[]>({
    url: '/aid/agent/list',
    method: 'post',
    data: params || {}
  });
}

import { request } from '@/utils/request';

export interface OrchestrationImpactItem {
  type: string;
  label: string;
  count: number;
  action: string;
}

export interface OrchestrationImpact {
  resourceType: 'model' | 'agent';
  resourceId: number;
  resourceCode: string;
  resourceName: string;
  bizCategoryCode?: string;
  activeReferenceCount: number;
  canRetireDirectly: boolean;
  replacementSupported: boolean;
  references: OrchestrationImpactItem[];
  historyPolicy: string;
}

export function getModelRetirementImpact(id: number) {
  return request<OrchestrationImpact>({
    url: `/aid/orchestration/models/${id}/impact`,
    method: 'get'
  });
}

export function retireModel(id: number, replacementCode?: string) {
  return request({
    url: `/aid/orchestration/models/${id}/retire`,
    method: 'post',
    data: { confirmed: true, replacementCode: replacementCode || undefined }
  });
}

export function getAgentRetirementImpact(id: number) {
  return request<OrchestrationImpact>({
    url: `/aid/orchestration/agents/${id}/impact`,
    method: 'get'
  });
}

export function retireAgent(id: number, replacementCode?: string) {
  return request({
    url: `/aid/orchestration/agents/${id}/retire`,
    method: 'post',
    data: { confirmed: true, replacementCode: replacementCode || undefined }
  });
}

/** 创作流程：作品/剧集 scope 切换或从内嵌库回到流程时，通知各步骤恢复 loading 与 SSE */
export const CREATE_FLOW_SCOPE_CHANGED_EVENT = 'create-flow-scope-changed'

export type CreateFlowScopeChangedDetail = {
  reason: 'scope-or-route' | 'panel-return' | 'manual'
}

export function dispatchCreateFlowScopeChanged(detail: CreateFlowScopeChangedDetail): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(CREATE_FLOW_SCOPE_CHANGED_EVENT, { detail }))
}

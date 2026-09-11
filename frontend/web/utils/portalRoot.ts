/**
 * 全站浮层/弹窗 Portal 挂载点。
 *
 * 容器由根布局保留，浮层不依赖当前页面的布局与卸载时机。
 * 统一挂到独立 #aid-portal-root，容器本身 pointer-events: none，仅子节点可交互。
 */
export const AID_PORTAL_ROOT_ID = 'aid-portal-root'

export function getAidPortalRoot(): HTMLElement | null {
  if (typeof document === 'undefined') return null
  let root = document.getElementById(AID_PORTAL_ROOT_ID)
  if (!root) {
    root = document.createElement('div')
    root.id = AID_PORTAL_ROOT_ID
    document.body.appendChild(root)
  }
  return root
}

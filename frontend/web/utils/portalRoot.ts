/**
 * 全站浮层/弹窗 Portal 挂载点。
 *
 * 直接 portal 到 document.body 时，与 React 19 根节点争用 body 子节点，
 * 在 home 壳层内多次 client 路由切换后可能触发 removeChild(null) 并留下透明遮罩挡点击。
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

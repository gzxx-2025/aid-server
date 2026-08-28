/**
 * 资产提取成功 toast 门槛：
 * 仅当本次提取真正成功、未被停止，且仍处于发起时的作品/路由会话时才提示。
 * 避免离开创作页中断 SSE 后被误判为「提取已完成」。
 */
export function shouldAnnounceExtractSuccess(input: {
  extractOk: boolean
  stopRequested: boolean
  sessionAtStart: number
  sessionNow: number
  flowCtxAtStart: string
  flowCtxNow: string
}): boolean {
  if (!input.extractOk) return false
  if (input.stopRequested) return false
  if (input.sessionAtStart !== input.sessionNow) return false
  if (input.flowCtxAtStart !== input.flowCtxNow) return false
  return true
}

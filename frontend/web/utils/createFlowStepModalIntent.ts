/**
 * 跨步骤跳转后自动打开对应编辑弹窗的待处理意图。
 * 独立小模块：删除本文件并去掉各步骤的 request/consume 接线即可整段卸载该能力。
 */
export type CreateFlowStepModalKind =
  | 'storyboard-image'
  | 'storyboard-video'
  | 'storyboard-dubbing'

export type CreateFlowStepModalIntent = {
  kind: CreateFlowStepModalKind
  panelIndex: number
  token: number
}

type CreateFlowStepModalIntentListener = (intent: CreateFlowStepModalIntent | null) => void

/** 原 Vue shallowRef 的模块级状态替代；业务侧优先用 request / consume / peek。 */
let currentIntent: CreateFlowStepModalIntent | null = null

const listeners = new Set<CreateFlowStepModalIntentListener>()

let tokenSeq = 0

function setIntent(next: CreateFlowStepModalIntent | null): void {
  currentIntent = next
  for (const listener of [...listeners]) {
    listener(next)
  }
}

/** 原「供步骤页 watch」的订阅替代：返回取消订阅函数（可配 useSyncExternalStore / useEffect） */
export function subscribeCreateFlowStepModalIntent(
  listener: CreateFlowStepModalIntentListener
): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function requestCreateFlowStepModal(
  kind: CreateFlowStepModalKind,
  panelIndex: number
): CreateFlowStepModalIntent {
  tokenSeq += 1
  const next: CreateFlowStepModalIntent = {
    kind,
    panelIndex,
    token: tokenSeq
  }
  setIntent(next)
  return next
}

export function peekCreateFlowStepModalIntent(): CreateFlowStepModalIntent | null {
  return currentIntent
}

/** 仅当 kind 匹配时消费并清空；返回 panelIndex，否则返回 null（不消费）。 */
export function consumeCreateFlowStepModalIntent(
  kind: CreateFlowStepModalKind
): number | null {
  const pending = currentIntent
  if (!pending || pending.kind !== kind) return null
  setIntent(null)
  return pending.panelIndex
}

export function clearCreateFlowStepModalIntent(): void {
  setIntent(null)
}

/** 单测重置；业务代码勿调用。 */
export function resetCreateFlowStepModalIntentForTest(): void {
  setIntent(null)
  tokenSeq = 0
}

/**
 * 场景/角色/道具编辑弹窗 → 列表外层静默续跟的交接规则。
 *
 * 关窗时 suspend 会把 waitUserTaskSseTerminal 标成 superseded。
 * 若仍按「被继任跟随接管」跳过 endFollow，Pinia follow 计数与 modalSseTasks
 * 会残留，流程条 / 列表 loading 假死，直到再开弹窗。
 */

export type ModalDeferredDecisionKind = 'superseded' | 'restore' | 'stop'

/**
 * deferred 收口后，本路 finally 是否应「让出」给继任（不 endFollow / 不删锁）。
 * - 弹窗仍打开 + superseded：真正被新跟随抢占 → 让出
 * - 弹窗已关闭 + superseded：关窗主动 suspend，交给外层 → 不让出（须 endFollow）
 */
export function shouldRelinquishModalFollowOnDeferred(input: {
  modalOpen: boolean
  decisionKind: ModalDeferredDecisionKind
}): boolean {
  if (input.decisionKind !== 'superseded') return false
  return !!input.modalOpen
}

/**
 * 外层 startTrackTask 是否允许立刻跟该 taskId。
 * 弹窗 registry 槽仍活着时禁止，避免与 waitUserTaskSseTerminal 双连。
 */
export function shouldOuterStartStep3TaskFollow(input: {
  taskId: number
  modalFollowSlotLive: boolean
  pageStreamAlreadyOpen: boolean
}): boolean {
  const tid = Number(input.taskId)
  if (!Number.isFinite(tid) || tid <= 0) return false
  if (input.modalFollowSlotLive) return false
  if (input.pageStreamAlreadyOpen) return false
  return true
}

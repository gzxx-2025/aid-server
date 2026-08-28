/**
 * 素材准备（第三步）SSE 并发闸门：最多 MAX 路浏览器连接，超出 FIFO 排队。
 * 弹窗当前 Tab 的 follow 以 owner='modal' 占槽，可 preempt 最老的 outer。
 */

export const MAX_STEP3_SSE = 6

export type Step3SseSlotOwner = 'outer' | 'modal'

export type Step3SseActiveSlot = {
  taskId: number
  owner: Step3SseSlotOwner
  acquiredAt: number
}

export type Step3SseQueueItem = {
  taskId: number
  owner: Step3SseSlotOwner
}

export type Step3SseAcquireDecision =
  | { kind: 'already-active'; active: Step3SseActiveSlot[]; queue: Step3SseQueueItem[] }
  | { kind: 'acquired'; active: Step3SseActiveSlot[]; queue: Step3SseQueueItem[] }
  | { kind: 'enqueued'; active: Step3SseActiveSlot[]; queue: Step3SseQueueItem[] }
  | {
      kind: 'preempt'
      releaseTaskId: number
      active: Step3SseActiveSlot[]
      queue: Step3SseQueueItem[]
    }
  | { kind: 'rejected'; active: Step3SseActiveSlot[]; queue: Step3SseQueueItem[] }

export type Step3SseReleaseDecision = {
  active: Step3SseActiveSlot[]
  queue: Step3SseQueueItem[]
}

function normTaskId(taskId: unknown): number | null {
  const n = Number(taskId)
  if (!Number.isFinite(n) || n <= 0) return null
  return Math.floor(n)
}

function cloneActive(list: Step3SseActiveSlot[]): Step3SseActiveSlot[] {
  return list.map((s) => ({ ...s }))
}

function cloneQueue(list: Step3SseQueueItem[]): Step3SseQueueItem[] {
  return list.map((q) => ({ ...q }))
}

/** 纯函数：是否允许立刻占槽 / 入队 / preempt */
export function decideStep3SseAcquire(input: {
  maxSlots: number
  active: Step3SseActiveSlot[]
  queue: Step3SseQueueItem[]
  request: {
    taskId: number
    owner: Step3SseSlotOwner
    allowPreemptOuter?: boolean
    now?: number
  }
}): Step3SseAcquireDecision {
  const taskId = normTaskId(input.request.taskId)
  const active = cloneActive(input.active)
  const queue = cloneQueue(input.queue)
  if (taskId == null) return { kind: 'rejected', active, queue }

  if (active.some((s) => s.taskId === taskId)) {
    return { kind: 'already-active', active, queue }
  }

  // 已在队列：保持位置，避免重复入队打乱 FIFO
  if (queue.some((q) => q.taskId === taskId)) {
    return { kind: 'enqueued', active, queue }
  }

  const max = Math.max(1, Math.floor(Number(input.maxSlots) || MAX_STEP3_SSE))
  const now = Number(input.request.now)
  const acquiredAt = Number.isFinite(now) && now > 0 ? now : Date.now()
  const slot: Step3SseActiveSlot = {
    taskId,
    owner: input.request.owner === 'modal' ? 'modal' : 'outer',
    acquiredAt
  }

  if (active.length < max) {
    active.push(slot)
    return { kind: 'acquired', active, queue }
  }

  if (input.request.allowPreemptOuter && slot.owner === 'modal') {
    let oldestOuter: Step3SseActiveSlot | null = null
    for (const s of active) {
      if (s.owner !== 'outer') continue
      if (!oldestOuter || s.acquiredAt < oldestOuter.acquiredAt) oldestOuter = s
    }
    if (oldestOuter) {
      const releaseTaskId = oldestOuter.taskId
      const nextActive = active.filter((s) => s.taskId !== releaseTaskId)
      nextActive.push(slot)
      const nextQueue = queue.filter((q) => q.taskId !== releaseTaskId)
      nextQueue.push({ taskId: releaseTaskId, owner: 'outer' })
      return {
        kind: 'preempt',
        releaseTaskId,
        active: nextActive,
        queue: nextQueue
      }
    }
  }

  queue.push({ taskId, owner: slot.owner })
  return { kind: 'enqueued', active, queue }
}

/**
 * 纯函数：释放槽位；若 task 仍在队列也移除。
 * 不自动出队——由 drain 在有空位时 FIFO 弹出。
 */
export function decideStep3SseRelease(input: {
  active: Step3SseActiveSlot[]
  queue: Step3SseQueueItem[]
  releaseTaskId: number
}): Step3SseReleaseDecision {
  const releaseId = normTaskId(input.releaseTaskId)
  let active = cloneActive(input.active)
  let queue = cloneQueue(input.queue)

  if (releaseId != null) {
    active = active.filter((s) => s.taskId !== releaseId)
    queue = queue.filter((q) => q.taskId !== releaseId)
  }

  return { active, queue }
}

/** 纯函数：有空位时弹出队头；无空位或空队列则 drained=null */
export function decideStep3SseDrainOnce(input: {
  maxSlots: number
  active: Step3SseActiveSlot[]
  queue: Step3SseQueueItem[]
}): { active: Step3SseActiveSlot[]; queue: Step3SseQueueItem[]; drained: Step3SseQueueItem | null } {
  const max = Math.max(1, Math.floor(Number(input.maxSlots) || MAX_STEP3_SSE))
  const active = cloneActive(input.active)
  const queue = cloneQueue(input.queue)
  if (active.length >= max || queue.length === 0) {
    return { active, queue, drained: null }
  }
  const drained = queue[0]!
  return { active, queue: queue.slice(1), drained }
}

// ── 运行时单例 ──────────────────────────────────────────────

let runtimeActive: Step3SseActiveSlot[] = []
let runtimeQueue: Step3SseQueueItem[] = []
let acquireClock = 0

export function resetStep3SseConcurrencyGateForTest() {
  runtimeActive = []
  runtimeQueue = []
  acquireClock = 0
}

export function listActiveStep3SseTaskIds(): number[] {
  return runtimeActive.map((s) => s.taskId)
}

export function listQueuedStep3SseTaskIds(): number[] {
  return runtimeQueue.map((q) => q.taskId)
}

/** 该 task 是否仍占用活动槽（被 preempt 后为 false，finally 不得再 release 以免踢出队列） */
export function hasStep3SseSlot(taskId: number): boolean {
  const tid = normTaskId(taskId)
  if (tid == null) return false
  return runtimeActive.some((s) => s.taskId === tid)
}

export type Step3SseAcquireResult =
  | { kind: 'acquired' }
  | { kind: 'already-active' }
  | { kind: 'enqueued' }
  | { kind: 'preempt'; releaseTaskId: number }
  | { kind: 'rejected' }
  | { kind: 'skipped' }

export function tryAcquireStep3SseSlot(input: {
  taskId: number
  owner: Step3SseSlotOwner
  allowPreemptOuter?: boolean
}): Step3SseAcquireResult {
  acquireClock += 1
  const decision = decideStep3SseAcquire({
    maxSlots: MAX_STEP3_SSE,
    active: runtimeActive,
    queue: runtimeQueue,
    request: {
      taskId: input.taskId,
      owner: input.owner,
      allowPreemptOuter: input.allowPreemptOuter,
      now: acquireClock
    }
  })
  runtimeActive = decision.active
  runtimeQueue = decision.queue
  if (decision.kind === 'preempt') {
    return { kind: 'preempt', releaseTaskId: decision.releaseTaskId }
  }
  if (
    decision.kind === 'acquired' ||
    decision.kind === 'already-active' ||
    decision.kind === 'enqueued' ||
    decision.kind === 'rejected'
  ) {
    return { kind: decision.kind }
  }
  return { kind: 'rejected' }
}

/** 释放槽位（及队列中同 task）；随后应调用 drainStep3SseQueue */
export function releaseStep3SseSlot(taskId: number): void {
  const decision = decideStep3SseRelease({
    active: runtimeActive,
    queue: runtimeQueue,
    releaseTaskId: taskId
  })
  runtimeActive = decision.active
  runtimeQueue = decision.queue
}

/** 将条目放到队尾（drain 时跳过非当前 Tab 用） */
export function requeueStep3SseItemToEnd(item: Step3SseQueueItem): void {
  const tid = normTaskId(item.taskId)
  if (tid == null) return
  runtimeQueue = runtimeQueue.filter((q) => q.taskId !== tid)
  runtimeQueue.push({
    taskId: tid,
    owner: item.owner === 'modal' ? 'modal' : 'outer'
  })
}

/**
 * 有空位时 FIFO 出队并由 onDrain 真正连 SSE。
 * onDrain 应再调 tryAcquireStep3SseSlot；acquired / already-active / skipped 可继续。
 * enqueued / rejected 放回队头并停止。
 */
export function drainStep3SseQueue(
  onDrain: (item: Step3SseQueueItem) => Step3SseAcquireResult
): void {
  let guard = 0
  let consecutiveSkips = 0
  while (guard++ < MAX_STEP3_SSE * 4) {
    const step = decideStep3SseDrainOnce({
      maxSlots: MAX_STEP3_SSE,
      active: runtimeActive,
      queue: runtimeQueue
    })
    if (!step.drained) {
      runtimeActive = step.active
      runtimeQueue = step.queue
      break
    }
    runtimeActive = step.active
    runtimeQueue = step.queue
    const result = onDrain(step.drained)
    if (!result || result.kind === 'enqueued' || result.kind === 'rejected') {
      runtimeQueue = [step.drained, ...runtimeQueue]
      break
    }
    if (result.kind === 'skipped') {
      consecutiveSkips += 1
      // 队里剩余项都已被轮询跳过一轮则停止，避免空转
      if (consecutiveSkips > runtimeQueue.length) break
      continue
    }
    consecutiveSkips = 0
  }
}

/** 切作品/卸载：清空闸门（不断服务端任务；由上层 suspend SSE） */
export function clearStep3SseConcurrencyGate() {
  runtimeActive = []
  runtimeQueue = []
}

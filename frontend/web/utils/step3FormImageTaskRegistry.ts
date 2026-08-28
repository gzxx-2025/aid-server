/** 素材准备 · 形态图 SSE 任务注册表（taskId ↔ formIds，支持并行任务） */

export type Step3FormImageTaskTab = 'scene' | 'character' | 'prop'

export type Step3FormImageTaskMeta = {
  taskId: number
  tab: Step3FormImageTaskTab
  formIds: number[]
  taskType: string | null
}

export type Step3FormImageTaskDoneOutcome = {
  ok: boolean
  errorMessage?: string
}

const tasksById = new Map<number, Step3FormImageTaskMeta>()
const taskIdsByFormId = new Map<number, Set<number>>()
const doneWaiters = new Map<number, (outcome: Step3FormImageTaskDoneOutcome) => void>()

function normalizeFormIds(formIds: number[]): number[] {
  const out: number[] = []
  for (const raw of formIds) {
    const id = Number(raw)
    if (!Number.isFinite(id) || id <= 0 || out.includes(id)) continue
    out.push(id)
  }
  return out
}

function linkFormIdToTask(formId: number, taskId: number) {
  let set = taskIdsByFormId.get(formId)
  if (!set) {
    set = new Set<number>()
    taskIdsByFormId.set(formId, set)
  }
  set.add(taskId)
}

function unlinkFormIdFromTask(formId: number, taskId: number): boolean {
  const set = taskIdsByFormId.get(formId)
  if (!set) return true
  set.delete(taskId)
  if (!set.size) {
    taskIdsByFormId.delete(formId)
    return true
  }
  return false
}

export function registerStep3FormImageTask(meta: Step3FormImageTaskMeta): void {
  const taskId = Number(meta.taskId)
  if (!Number.isFinite(taskId) || taskId <= 0) return
  const formIds = normalizeFormIds(meta.formIds)
  const prev = tasksById.get(taskId)
  if (prev) {
    for (const fid of prev.formIds) {
      unlinkFormIdFromTask(fid, taskId)
    }
  }
  tasksById.set(taskId, {
    taskId,
    tab: meta.tab,
    formIds,
    taskType: meta.taskType ?? null
  })
  for (const fid of formIds) {
    linkFormIdToTask(fid, taskId)
  }
}

/** 任务终态：返回已无其它任务占用的 formId */
export function unregisterStep3FormImageTask(taskId: number): number[] {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return []
  const meta = tasksById.get(id)
  if (!meta) return []
  tasksById.delete(id)
  const freed: number[] = []
  for (const fid of meta.formIds) {
    if (unlinkFormIdFromTask(fid, id)) freed.push(fid)
  }
  return freed
}

export function getStep3FormImageTaskFormIds(taskId: number): number[] {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return []
  return [...(tasksById.get(id)?.formIds ?? [])]
}

export function getStep3FormImageTaskMeta(taskId: number): Step3FormImageTaskMeta | null {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return null
  const meta = tasksById.get(id)
  return meta ? { ...meta, formIds: [...meta.formIds] } : null
}

export function isFormIdUnderActiveStep3FormImageTask(formId: number): boolean {
  const id = Number(formId)
  if (!Number.isFinite(id) || id <= 0) return false
  return (taskIdsByFormId.get(id)?.size ?? 0) > 0
}

export function getActiveStep3FormImageFormIds(): Set<number> {
  return new Set(taskIdsByFormId.keys())
}

export function hasOngoingStep3FormImageTasks(): boolean {
  return tasksById.size > 0
}

export function hasOngoingStep3FormImageTasksForTab(tab: Step3FormImageTaskTab): boolean {
  for (const meta of tasksById.values()) {
    if (meta.tab === tab) return true
  }
  return false
}

export function clearStep3FormImageTaskRegistry(): void {
  tasksById.clear()
  taskIdsByFormId.clear()
}

export function waitForStep3FormImageTaskDone(taskId: number): Promise<Step3FormImageTaskDoneOutcome> {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) {
    return Promise.resolve({ ok: false, errorMessage: '任务ID无效' })
  }
  return new Promise((resolve) => {
    doneWaiters.set(id, resolve)
  })
}

/** 是否有 runFormImageGenerate 等在等待本任务终态（finalize 时应避免重复 toast） */
export function hasStep3FormImageTaskDoneWaiter(taskId: number): boolean {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return false
  return doneWaiters.has(id)
}

export function resolveStep3FormImageTaskDone(
  taskId: number,
  outcome: Step3FormImageTaskDoneOutcome
): void {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return
  const waiter = doneWaiters.get(id)
  if (!waiter) return
  doneWaiters.delete(id)
  waiter(outcome)
}

export function rejectAllStep3FormImageTaskWaiters(reason = '任务已中断'): void {
  for (const [taskId, waiter] of doneWaiters.entries()) {
    doneWaiters.delete(taskId)
    waiter({ ok: false, errorMessage: reason })
  }
}

/**
 * 创作流程任务指令总线（续生 / 重新开始 / 跟进进度）。
 *
 * 背景：全局任务面板操作需要先跳转到任务所属步骤页，再由该步骤页调用对应接口。
 * window CustomEvent 是即发即弃的：路由切换后目标步骤组件（含异步分包）尚未挂载、
 * 监听器未注册时指令会静默丢失，表现为「跳了页但续生接口没被调用」。
 *
 * 方案：dispatch 同步派发事件（已挂载页面立即受理），同时暂存为 pending 指令；
 * 步骤页挂载注册监听后调用 consume 领取属于自己的 pending 指令。
 * 受理方（事件路径或补投路径）通过 ack 确认消费，保证同一指令恰好执行一次。
 */
export type CreateFlowTaskCommandKind = 'resume' | 'restart' | 'track'

export interface CreateFlowTaskCommandDetail {
  taskId: number
  taskType: string | null
  /** 分镜脚本失败重启时，报价与正式提交共用的失败场次集合。 */
  sceneIds?: number[]
  /** 全局失败重启固定读取当前项目生成配置，不复用页面上一次手选状态。 */
  manualAgentModelPick?: boolean
}

export const CREATE_FLOW_TASK_COMMAND_EVENTS: Record<CreateFlowTaskCommandKind, string> = {
  resume: 'create-flow-resume-task',
  restart: 'create-flow-restart-task',
  track: 'create-flow-track-task'
}

/** 指令补投有效期：覆盖步骤路由切换 + 异步分包加载；超时视为用户已离开该场景 */
const PENDING_COMMAND_TTL_MS = 15_000

interface PendingCommand {
  detail: CreateFlowTaskCommandDetail
  issuedAt: number
}

const pendingByKind = new Map<CreateFlowTaskCommandKind, PendingCommand>()

/** 派发任务指令：立即广播事件，并暂存等待目标步骤页挂载后补投 */
export function dispatchCreateFlowTaskCommand(
  kind: CreateFlowTaskCommandKind,
  detail: CreateFlowTaskCommandDetail
): void {
  if (typeof window === 'undefined') return
  pendingByKind.set(kind, { detail, issuedAt: Date.now() })
  window.dispatchEvent(new CustomEvent(CREATE_FLOW_TASK_COMMAND_EVENTS[kind], { detail }))
}

/** 受理方确认已执行指令，防止后续挂载的组件重复领取 */
export function ackCreateFlowTaskCommand(kind: CreateFlowTaskCommandKind, taskId: number): void {
  const pending = pendingByKind.get(kind)
  if (pending && pending.detail.taskId === taskId) {
    pendingByKind.delete(kind)
  }
}

/**
 * 步骤页挂载后领取属于自己的 pending 指令（领取即消费）。
 * accepts 与该页事件监听器的受理条件保持一致。
 */
export function consumePendingCreateFlowTaskCommand(
  kind: CreateFlowTaskCommandKind,
  accepts: (detail: CreateFlowTaskCommandDetail) => boolean
): CreateFlowTaskCommandDetail | null {
  const pending = pendingByKind.get(kind)
  if (!pending) return null
  if (Date.now() - pending.issuedAt > PENDING_COMMAND_TTL_MS) {
    pendingByKind.delete(kind)
    return null
  }
  if (!accepts(pending.detail)) return null
  pendingByKind.delete(kind)
  return pending.detail
}

/** 补投时构造与广播路径同构的事件，复用步骤页既有事件处理器 */
export function createFlowTaskCommandEvent(
  kind: CreateFlowTaskCommandKind,
  detail: CreateFlowTaskCommandDetail
): CustomEvent<CreateFlowTaskCommandDetail> {
  return new CustomEvent(CREATE_FLOW_TASK_COMMAND_EVENTS[kind], { detail })
}

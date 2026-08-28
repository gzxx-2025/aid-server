/**
 * 切步 / suspend / 抢占 / 网络良性断连：统一静默 toast + 保活 loading（SSOT）。
 * 真实业务失败（余额不足、已停止、生成失败等）不得命中。
 */

export function isTaskBackgroundRunningMessage(message: unknown): boolean {
  const text = String(message ?? '')
  return text.includes('后台执行') || text.includes('自动恢复进度')
}

export function isContextSwitchKeepAliveMessage(message: unknown): boolean {
  return String(message ?? '').includes('已切换作品')
}

/**
 * 刷新/切页/弹窗重进导致的 SSE 中断文案（底层）。
 * 与业务「任务已取消」区分：仅匹配英文 cancel / Abort 等连接层语义。
 */
export function isBenignTaskSseDisconnectMessage(msg: unknown): boolean {
  const lower = String(msg ?? '').trim().toLowerCase()
  if (!lower) return false
  return (
    lower.includes('abort') ||
    lower.includes('superseded') ||
    lower.includes('cancel') ||
    lower.includes('user aborted') ||
    lower.includes('signal is aborted') ||
    lower.includes('ended unexpectedly') ||
    lower.includes('sse aborted') ||
    lower.includes('rate limited') ||
    lower.includes('networkerror') ||
    lower.includes('failed to fetch') ||
    lower.includes('load failed') ||
    lower.includes('network error') ||
    lower.includes('err_network') ||
    lower.includes('connection') ||
    lower.includes('断开') ||
    lower.includes('连接中断') ||
    lower.includes('body stream') ||
    lower.includes('任务连接中断') ||
    lower.includes('任务连接异常') ||
    (lower.includes('fetch') && lower.includes('failed'))
  )
}

/** 导航挂起 / 切作品 / 良性断连：follow 收尾应保活，禁止当业务失败 */
export function isNavigationOrSuspendBatchMessage(message: unknown): boolean {
  const text = String(message ?? '').trim()
  if (!text) return false
  // 用户主动停止 / 业务取消：允许 toast，不做导航静默
  if (text.includes('已停止') || text.includes('任务已取消') || text === '任务已取消') {
    return false
  }
  if (isTaskBackgroundRunningMessage(text) || isContextSwitchKeepAliveMessage(text)) {
    return true
  }
  // 各 batch 历史假失败文案：…连接中断，请稍后重试
  if (text.includes('连接中断') || text.includes('连接异常')) {
    return true
  }
  const lower = text.toLowerCase()
  if (lower.includes('superseded')) return true
  if (lower.includes('abort')) return true
  if (lower.includes('ended unexpectedly')) return true
  if (lower.includes('sse aborted')) return true
  if (lower.includes('rate limited')) return true
  if (lower.includes('networkerror') || lower.includes('network error') || lower.includes('err_network')) {
    return true
  }
  if (lower.includes('failed to fetch') || lower.includes('load failed')) return true
  if (lower.includes('connection') || lower.includes('断开') || lower.includes('body stream')) {
    return true
  }
  if (lower.includes('fetch') && lower.includes('failed')) return true
  return false
}

/** 分镜脚本/图/视频/配音 batch：此类文案禁止 message.error */
export function shouldSilentStoryboardBatchToast(message: unknown): boolean {
  return isNavigationOrSuspendBatchMessage(message)
}

/** 分镜图 batch：导航断连后保留 generating / taskId / 卡片 loading */
export function shouldKeepImageBatchLoadingAfterFollowMessage(message: unknown): boolean {
  return isNavigationOrSuspendBatchMessage(message)
}

/**
 * SSE 已给出业务终态（error / cancelled）时，禁止用滞后的 task/detail「进行中」改写成保活。
 * 良性断连 error 仍允许 detail ongoing 保活/重连。
 *
 * 根因：服务端先推 SSE error，task/detail 可能短暂仍 PROCESSING；
 * 若先判 ongoing 再读 r.type===error，会误返回「后台执行」并永久卡住 loading。
 */
export function shouldPreferSseBusinessTerminalOverOngoingDetail(event: {
  type?: string
  errorMessage?: string
} | null | undefined): boolean {
  if (!event) return false
  if (event.type === 'cancelled') return true
  if (event.type === 'error') {
    return !isBenignTaskSseDisconnectMessage(event.errorMessage)
  }
  return false
}

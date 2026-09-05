/** 防止异步提示词结果覆盖更晚的分镜或用户编辑。 */

export interface AsyncPromptApplyTicket {
  scopeKey: string
  operationRevision: number
  editRevision: number
}

export interface AsyncPromptApplyGuard {
  begin: (scopeKey: string) => AsyncPromptApplyTicket
  markEdited: () => void
  invalidate: () => void
  isCurrent: (ticket: AsyncPromptApplyTicket, currentScopeKey: string) => boolean
}

/**
 * 每个提示词通道独立持有一个 guard：后发操作淘汰先发操作，任意用户编辑淘汰在途回填。
 */
export function createAsyncPromptApplyGuard(): AsyncPromptApplyGuard {
  let operationRevision = 0
  let editRevision = 0

  return {
    begin(scopeKey) {
      operationRevision += 1
      return { scopeKey, operationRevision, editRevision }
    },
    markEdited() {
      editRevision += 1
    },
    invalidate() {
      operationRevision += 1
    },
    isCurrent(ticket, currentScopeKey) {
      return (
        ticket.scopeKey === currentScopeKey &&
        ticket.operationRevision === operationRevision &&
        ticket.editRevision === editRevision
      )
    }
  }
}

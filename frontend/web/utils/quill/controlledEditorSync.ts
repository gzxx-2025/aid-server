/** 受控富文本编辑器的本地回声确认与光标位置映射。 */

const DEFAULT_MAX_PENDING_ECHOES = 32

export interface ControlledEditorEchoTracker {
  record: (value: string) => void
  consume: (value: string) => boolean
  clear: () => void
}

export interface ControlledEditorValueCoordinator {
  recordLocal: (value: string) => number
  receive: (
    value: string,
    isComposing: boolean
  ) => {
    decision: ControlledEditorValueDecision
    value?: string
    localRevision?: number
  }
  clearLocalEchoes: (throughRevision?: number) => void
}

export interface MutableBooleanRef {
  current: boolean
}

export interface EditorSelectionRange {
  index: number
  length: number
}

export type ControlledEditorValueDecision = 'local-echo' | 'defer' | 'apply'
export type EditorChangeSource = 'api' | 'silent' | 'user'

export interface PromptEmbedInsertionPlan {
  replaceIndex: number
  replaceLength: number
  leadingSpace: boolean
  embedIndex: number
  caretIndex: number
}

/**
 * 记录编辑器已经向父组件发出的值。
 * React 可能合并或延后受控值提交，因此不能依赖一个宏任务内有效的布尔锁。
 */
export function createControlledEditorEchoTracker(
  maxPendingEchoes = DEFAULT_MAX_PENDING_ECHOES
): ControlledEditorEchoTracker {
  const pending: string[] = []
  const limit = Math.max(1, maxPendingEchoes)

  return {
    record(value) {
      if (pending[pending.length - 1] !== value) pending.push(value)
      if (pending.length > limit) pending.splice(0, pending.length - limit)
    },
    consume(value) {
      const index = pending.indexOf(value)
      if (index < 0) return false
      pending.splice(0, index + 1)
      return true
    },
    clear() {
      pending.length = 0
    }
  }
}

/** 将本地回声与 composition 延迟值收口为一个可重放的受控值协调器。 */
export function createControlledEditorValueCoordinator(): ControlledEditorValueCoordinator {
  const pendingEchoes: Array<{ value: string; revision: number }> = []
  let localRevision = 0
  let deferredValue: { value: string; observedAtRevision: number } | null = null

  return {
    recordLocal(value) {
      localRevision += 1
      if (pendingEchoes[pendingEchoes.length - 1]?.value === value) {
        pendingEchoes[pendingEchoes.length - 1] = { value, revision: localRevision }
      } else {
        pendingEchoes.push({ value, revision: localRevision })
      }
      if (pendingEchoes.length > DEFAULT_MAX_PENDING_ECHOES) {
        pendingEchoes.splice(0, pendingEchoes.length - DEFAULT_MAX_PENDING_ECHOES)
      }
      return localRevision
    },
    receive(value, isComposing) {
      const echoIndex = pendingEchoes.findIndex((echo) => echo.value === value)
      if (echoIndex >= 0) {
        const matched = pendingEchoes[echoIndex]
        pendingEchoes.splice(0, echoIndex + 1)
        // 最新受控值已确认采用本地编辑结果，之前的延迟外部值不再具有权威性。
        deferredValue = null
        return { decision: 'local-echo' as const, value, localRevision: matched.revision }
      }
      if (isComposing) {
        deferredValue = { value, observedAtRevision: localRevision }
        return { decision: 'defer' as const, localRevision }
      }
      const valueToApply = deferredValue?.value ?? value
      const observedAtRevision = deferredValue?.observedAtRevision ?? localRevision
      deferredValue = null
      return {
        decision: 'apply' as const,
        value: valueToApply,
        localRevision: observedAtRevision
      }
    },
    clearLocalEchoes(throughRevision) {
      if (throughRevision == null) {
        pendingEchoes.length = 0
        return
      }
      const firstNewerIndex = pendingEchoes.findIndex(
        (echo) => echo.revision > throughRevision
      )
      if (firstNewerIndex < 0) pendingEchoes.length = 0
      else if (firstNewerIndex > 0) pendingEchoes.splice(0, firstNewerIndex)
    }
  }
}

/**
 * 本地回声只有在编辑器仍保持同一内容时才能跳过；若曾被外部值覆盖，必须重放本地版本。
 */
export function shouldApplyControlledEditorValue(options: {
  decision: ControlledEditorValueDecision
  editorValue: string
  controlledValue: string
}): boolean {
  if (options.decision === 'defer') return false
  return options.editorValue !== options.controlledValue
}

/**
 * 将一组 Quill 原子操作作为一个受控事务提交。事务内 text-change 被 guard 屏蔽，
 * 成功后只调用一次最终 emit；异常时始终释放 guard 且不提交半成品值。
 */
export function commitControlledEditorMutation<T>(
  guard: MutableBooleanRef,
  mutate: () => T,
  emit: (result: T) => void
): T {
  guard.current = true
  let result: T
  try {
    result = mutate()
  } finally {
    guard.current = false
  }
  emit(result)
  return result
}

/** 将 Quill 选区限制在可编辑正文内，避免覆盖文档末尾的换行。 */
export function clampEditorSelection(
  range: EditorSelectionRange,
  maxIndex: number
): EditorSelectionRange {
  const safeMax = Math.max(0, maxIndex)
  const index = Math.min(Math.max(0, range.index), safeMax)
  const length = Math.min(Math.max(0, range.length), safeMax - index)
  return { index, length }
}

/**
 * 编辑器聚焦时采用实时选区；失焦后采用最后一次用户选区；从未聚焦时默认追加到文末。
 */
export function resolveEditorSelection(options: {
  liveRange: EditorSelectionRange | null | undefined
  savedRange: EditorSelectionRange | null | undefined
  hasFocus: boolean
  maxIndex: number
}): EditorSelectionRange {
  const { liveRange, savedRange, hasFocus, maxIndex } = options
  const candidate = hasFocus && liveRange ? liveRange : savedRange
  return clampEditorSelection(candidate ?? { index: maxIndex, length: 0 }, maxIndex)
}

/** 计算一次原子引用插入，选中的文本由同一编辑事务直接替换。 */
export function planPromptEmbedInsertion(options: {
  selection: EditorSelectionRange
  documentHasContent: boolean
  previousCharacter: string
}): PromptEmbedInsertionPlan {
  const { selection, documentHasContent, previousCharacter } = options
  const leadingSpace =
    documentHasContent && selection.index > 0 && !!previousCharacter && !/\s/.test(previousCharacter)
  const embedIndex = selection.index + (leadingSpace ? 1 : 0)
  return {
    replaceIndex: selection.index,
    replaceLength: selection.length,
    leadingSpace,
    embedIndex,
    // 引用块后保留一个空格，连续插入或继续输入时始终从引用块之后开始。
    caretIndex: embedIndex + 2
  }
}

/** 只有真实用户选区才能更新失焦后使用的保存选区。 */
export function shouldRememberEditorSelection(options: {
  range: EditorSelectionRange | null | undefined
  source: EditorChangeSource
  syncingInternally: boolean
}): options is {
  range: EditorSelectionRange
  source: 'user'
  syncingInternally: false
} {
  return (
    !options.syncingInternally &&
    options.source === 'user' &&
    options.range != null &&
    options.range.index >= 0
  )
}

/** 将旧光标映射到一次等长或非等长替换后的文档位置。 */
export function mapCaretThroughReplace(
  caretIndex: number,
  replaceIndex: number,
  deleteLength: number,
  insertLength: number
): number {
  if (caretIndex <= replaceIndex) return caretIndex
  const replacedEnd = replaceIndex + Math.max(0, deleteLength)
  if (caretIndex <= replacedEnd) return replaceIndex + Math.max(0, insertLength)
  return caretIndex + Math.max(0, insertLength) - Math.max(0, deleteLength)
}

/** 将完整选区映射到一次内容替换之后，保留选区方向无关的起止边界。 */
export function mapSelectionThroughReplace(
  range: EditorSelectionRange,
  replaceIndex: number,
  deleteLength: number,
  insertLength: number
): EditorSelectionRange {
  const start = mapCaretThroughReplace(range.index, replaceIndex, deleteLength, insertLength)
  const end = mapCaretThroughReplace(
    range.index + range.length,
    replaceIndex,
    deleteLength,
    insertLength
  )
  return {
    index: Math.min(start, end),
    length: Math.abs(end - start)
  }
}

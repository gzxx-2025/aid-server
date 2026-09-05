export type StoryScriptAgentDocumentStatus = 'complete' | 'streaming' | 'error' | 'stopped'

export interface StoryScriptAgentDocumentViewInput {
  status: StoryScriptAgentDocumentStatus
  content: string
  contentIsPlaceholder?: boolean
  actionsAllowed?: boolean
  statusText?: string
  responseMode?: 'SCREENPLAY' | 'DIAGNOSTIC'
}

export interface StoryScriptAgentDocumentView {
  title: string
  subtitle: string
  bodyText: string
  bodyIsPlaceholder: boolean
  showBody: boolean
  showActions: boolean
}

/**
 * 把 Runtime 状态、剧本增量和成稿映射成文档卡。
 */
export function resolveStoryScriptAgentDocumentView(
  input: StoryScriptAgentDocumentViewInput
): StoryScriptAgentDocumentView {
  const isStreaming = input.status === 'streaming'
  const isError = input.status === 'error'
  const isStopped = input.status === 'stopped'
  const rawContent = String(input.content || '')
  const stoppedStatusOnly = isStopped && /^(?:生成|诊断)?已停止$/.test(rawContent.trim())
  const content = stoppedStatusOnly ? '' : rawContent
  const hasRenderableContent = Boolean(content.trim())
  const hasContent = hasRenderableContent && input.contentIsPlaceholder !== true
  const showBody = isError || hasRenderableContent
  const bodyIsPlaceholder = !hasContent

  const view: StoryScriptAgentDocumentView = {
    title: isError ? '生成未完成' : isStopped ? '已停止' : isStreaming ? (hasContent ? '正在撰写剧本' : '正在思考') : 'Agent 剧本文档',
    subtitle: isStreaming
      ? hasContent
        ? '内容将实时写入文档'
        : '正在实时输出创作思路'
      : isStopped
        ? '本次生成已停止'
        : '可展开预览并带入当前剧本',
    bodyText: hasRenderableContent ? content : isError ? '暂无内容' : '',
    bodyIsPlaceholder,
    showBody,
    showActions: !isStreaming && !isError && hasContent && input.actionsAllowed !== false
  }
  if (input.responseMode === 'DIAGNOSTIC' && isStopped) {
    return {
      ...view,
      title: '诊断已停止',
      subtitle: '本次诊断已停止'
    }
  }
  if (input.responseMode === 'DIAGNOSTIC' && !isStreaming && !isError && !isStopped) {
    return {
      ...view,
      title: '剧本诊断',
      subtitle: '分析内容不会覆盖当前剧本'
    }
  }
  return view
}

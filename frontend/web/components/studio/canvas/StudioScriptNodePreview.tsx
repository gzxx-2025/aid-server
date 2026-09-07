'use client'

import { message } from 'antd'

export function StudioScriptNodePreview({
  content,
  badge,
  viewLabel = '查看 →',
  onView
}: {
  content: string
  badge?: string
  viewLabel?: string
  onView: () => void
}) {
  return (
    <div className="studio-script-node-preview">
      {badge ? <span className="studio-script-node-preview__badge">{badge}</span> : null}
      <p className="studio-script-node-preview__body">
        {content}
      </p>
      <div className="studio-script-node-preview__fade" aria-hidden />
      <button
        type="button"
        className="studio-script-node-preview__view nodrag"
        onClick={(event) => {
          event.stopPropagation()
          onView()
        }}
      >
        {viewLabel}
      </button>
    </div>
  )
}

export async function copyStudioScriptText(content: string, label = '剧本'): Promise<void> {
  if (!content.trim()) {
    message.warning(`${label}内容为空`)
    return
  }
  try {
    await navigator.clipboard.writeText(content)
    message.success(`${label}已复制`)
  } catch {
    message.error('复制失败，请手动选择文本复制')
  }
}

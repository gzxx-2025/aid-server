'use client'

import { CloseOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { STUDIO_NODE_META } from '@/config/studioMeta'
import type { StudioCanvasNode } from '@/types/studio'
import {
  STUDIO_FLOW_BATCH_INTENT_LABELS,
  studioFlowBatchIntentHint,
  type StudioFlowBatchSelectionResult
} from '@/utils/studio/studioFlowBatchSelection'

export function StudioFlowAgentContextCard({
  nodes,
  batchSelection,
  batchRunning,
  onRemove,
  onFocus,
  onRunBatch
}: {
  nodes: StudioCanvasNode[]
  batchSelection: StudioFlowBatchSelectionResult | null
  batchRunning?: boolean
  onRemove: (nodeId: string) => void
  onFocus: (nodeId: string) => void
  onRunBatch?: () => void
}) {
  if (!nodes.length) return null

  const batchIntent = batchSelection?.ok ? batchSelection.intent : null

  return (
    <div className="studio-conversation-context" aria-label="对话引用节点">
      <div className="studio-conversation-context__chips">
        {nodes.map((node) => (
          <span key={node.id} className="studio-conversation-context__chip">
            {node.data.mediaUrl ? (
              <i
                className="studio-conversation-context__thumb"
                style={{ backgroundImage: `url("${String(node.data.mediaUrl).replace(/"/g, '%22')}")` }}
              />
            ) : (
              <i className="studio-conversation-context__thumb studio-conversation-context__thumb--empty" />
            )}
            <button type="button" onClick={() => onFocus(node.id)}>
              {STUDIO_NODE_META[node.data.kind].label} · {node.data.title || '未命名节点'}
            </button>
            <button
              type="button"
              className="studio-conversation-context__remove"
              aria-label={`移除${node.data.title || '节点'}`}
              onClick={() => onRemove(node.id)}
            >
              <CloseOutlined />
            </button>
          </span>
        ))}
      </div>
      {batchIntent ? <p className="studio-conversation-context__hint">{studioFlowBatchIntentHint(batchIntent)}</p> : null}
      {batchSelection && !batchSelection.ok ? (
        <p className="studio-conversation-context__warn">{batchSelection.reason}</p>
      ) : null}
      {batchSelection?.ok && onRunBatch ? (
        <button
          type="button"
          className="studio-conversation-context__run"
          disabled={batchRunning}
          onClick={onRunBatch}
        >
          <ThunderboltOutlined />
          开始{STUDIO_FLOW_BATCH_INTENT_LABELS[batchSelection.intent]}
        </button>
      ) : null}
    </div>
  )
}

'use client'

import type { StudioCanvasNode } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { StudioComposerFrame } from '@/components/studio/composer/StudioComposerFrame'
import { StudioFlowComposerHeader } from './StudioFlowComposerShared'

export function StudioFlowDubbingComposer({ node }: { node: StudioCanvasNode }) {
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)

  return (
    <div className="studio-flow-composer nodrag nowheel">
      <StudioFlowComposerHeader node={node} subtitle="配音与音画同步" />
      <StudioComposerFrame
        node={node}
        placeholder="编辑台词或旁白文本"
        showSend={false}
      />
      <small className="studio-flow-composer__hint">暂存台词到节点摘要后，可在配音步骤继续配置音色与对口型。</small>
      <button
        type="button"
        className="studio-flow-composer__secondary-action"
        onClick={() => updateNodeData(node.id, {
          resultSummary: node.data.prompt.slice(0, 180)
        })}
      >
        暂存台词到节点摘要
      </button>
    </div>
  )
}

'use client'

import { Button } from 'antd'
import { PlayCircleOutlined } from '@ant-design/icons'
import type { StudioCanvasNode } from '@/types/studio'
import { useCreationStore } from '@/stores/creation'
import { useStudioFlowRuntime } from '@/components/studio/flow/StudioFlowRuntimeProvider'
import { StudioComposerFrame } from '@/components/studio/composer/StudioComposerFrame'
import { openVideoPreviewModal } from '@/utils/openVideoPreviewModal'
import { StudioFlowComposerHeader } from './StudioFlowComposerShared'
import { StudioFlowDeliveryMenu } from '../StudioFlowDeliveryMenu'

export function StudioFlowPreviewComposer({ node }: { node: StudioCanvasNode }) {
  const runtime = useStudioFlowRuntime()
  const projectType = useCreationStore((state) => state.currentProjectType)
  const previewReady = Boolean(node.data.mediaUrl)

  return (
    <div className="studio-flow-composer nodrag nowheel">
      <StudioFlowComposerHeader
        node={node}
        subtitle={projectType === 'series' ? '剧集成品预览' : '电影成品预览'}
      />
      <StudioComposerFrame
        node={node}
        readOnly
        showSend={false}
        placeholder={previewReady ? '成品视频已就绪，可在下方预览或导出。' : '尚未生成成品，请先在分镜视频与配音步骤完成生成。'}
      />
      <div className="studio-flow-composer__actions-row">
        <Button
          icon={<PlayCircleOutlined />}
          disabled={!previewReady}
          onClick={() => {
            if (!node.data.mediaUrl) return
            openVideoPreviewModal({ url: node.data.mediaUrl, title: node.data.title })
          }}
        >
          预览成品
        </Button>
        <Button onClick={() => void runtime?.refresh({ taskIntent: 'mutate' })}>
          刷新预览状态
        </Button>
        <StudioFlowDeliveryMenu disabled={!previewReady} />
      </div>
    </div>
  )
}

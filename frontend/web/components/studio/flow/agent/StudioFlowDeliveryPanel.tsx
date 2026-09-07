'use client'

import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import { filterStudioFlowCanvasVisibleNodes } from '@/utils/studio/studioFlowCanvasProjection'

export function StudioFlowDeliveryPanel() {
  const nodes = useStudioUiStore((state) => state.nodes)
  const tasks = useStudioUiStore((state) => state.tasks)
  const currentExportStatus = useCreationStore((state) => state.currentExportStatus)
  const currentFinalVideoUrl = useCreationStore((state) => state.currentFinalVideoUrl)
  const currentPendingVideoUrl = useCreationStore((state) => state.currentPendingVideoUrl)

  const visibleNodes = filterStudioFlowCanvasVisibleNodes(nodes)
  const units = visibleNodes.filter(
    (node) => node.data.kind === 'image' && node.data.imagePurpose === 'storyboard'
  )
  const success = visibleNodes.filter((node) => node.data.status === 'success')
  const failed = visibleNodes.filter((node) => node.data.status === 'failed')
  const exportStatusLabel = currentExportStatus === 2
    ? '已导出完整视频'
    : currentPendingVideoUrl
      ? '有待审核新片'
      : currentFinalVideoUrl
        ? '已有成片'
        : '尚未导出'

  return (
    <div className="studio-flow-delivery-panel">
      <section className="studio-delivery-summary">
        <h3>交付摘要</h3>
        <div><span>镜头</span><strong>{units.length}</strong></div>
        <div><span>完成节点</span><strong>{success.length}</strong></div>
        <div><span>失败记录</span><strong>{failed.length}</strong></div>
        <div><span>任务总数</span><strong>{tasks.length}</strong></div>
        <div className="studio-delivery-summary__status">
          <span>成片状态</span>
          <strong>{exportStatusLabel}</strong>
        </div>
      </section>
    </div>
  )
}

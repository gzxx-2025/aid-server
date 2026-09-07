'use client'

import { useMemo } from 'react'
import { Tag } from 'antd'
import type { StudioCanvasNode, StudioFlowStep } from '@/types/studio'
import { useCreationStore } from '@/stores/creation'
import { useStudioUiStore } from '@/stores/studioUi'
import { StudioComposerFrame } from '@/components/studio/composer/StudioComposerFrame'
import { selectStudioFlowStepNode } from '@/utils/studio/studioFlowNodeSelection'
import { StudioFlowComposerHeader } from './StudioFlowComposerShared'

const STEP_HINT: Record<StudioFlowStep, string> = {
  'global-setting': '管理作品级参数与风格配置',
  'story-script': '编辑完整剧本并运行 Agent',
  'scene-character': '批量管理场景、角色与道具素材',
  'storyboard-script': '列表编辑分镜脚本与批量生图',
  'storyboard-video': '批量生成分镜视频',
  dubbing: '批量配音与音画同步',
  preview: '时间轴预览与完整导出'
}

export function StudioFlowStepComposer({ node, step }: { node: StudioCanvasNode; step: StudioFlowStep }) {
  const nodes = useStudioUiStore((state) => state.nodes)
  const panels = useCreationStore((state) => state.formData.storyboardScript.panels)
  const stats = useMemo(() => {
    const items = nodes.filter((item) => item.data.flowBinding?.role === 'item' && item.data.flowBinding.step === step)
    const success = items.filter((item) => item.data.status === 'success').length
    return { total: items.length, success }
  }, [nodes, step])

  const extraTags = step === 'storyboard-script'
    ? [`${panels.length} 个镜头`]
    : step === 'scene-character'
      ? [`${stats.success}/${stats.total || stats.total} 素材就绪`]
      : [`${stats.success}/${stats.total || 0} 实体完成`]

  return (
    <div className="studio-flow-composer nodrag nowheel">
      <StudioFlowComposerHeader node={node} subtitle={STEP_HINT[step]} />
      <div className="studio-flow-composer__parameters">
        {extraTags.map((item) => <Tag key={item}>{item}</Tag>)}
      </div>
      <StudioComposerFrame
        node={node}
        readOnly
        showSend={false}
        placeholder={`这是「${node.data.title}」流程控制节点。选中下方实体节点可在对话框内编辑。`}
      />
      <button
        type="button"
        className="studio-flow-composer__secondary-action"
        onClick={() => selectStudioFlowStepNode(step)}
      >
        聚焦本步骤实体
      </button>
    </div>
  )
}

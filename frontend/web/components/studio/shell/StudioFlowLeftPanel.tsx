'use client'

import { useMemo } from 'react'
import { Button, Collapse, Tooltip, message } from 'antd'
import {
  ApartmentOutlined,
  AppstoreOutlined,
  DoubleLeftOutlined,
  ExportOutlined,
  ThunderboltOutlined
} from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioFlowStep } from '@/types/studio'
import { StudioNodeIcon } from '../canvas/StudioNodeIcon'
import { StudioStatusBadge } from '../canvas/StudioStatusBadge'
import { selectStudioFlowStepNode } from '@/utils/studio/studioFlowNodeSelection'
import { useStudioFlowRuntime } from '../flow/StudioFlowRuntimeProvider'
import { StudioTaskList } from './StudioTaskList'
import { filterStudioFlowCanvasVisibleNodes } from '@/utils/studio/studioFlowCanvasProjection'
import { StudioFlowDeliveryPanel } from '../flow/agent/StudioFlowDeliveryPanel'
import {
  useStudioPanelPresence,
  useStudioExitingPanelRef
} from '@/hooks/studio/useStudioPanelPresence'

export function StudioFlowLeftPanel() {
  const collapsed = useStudioUiStore((state) => state.leftCollapsed)
  const setCollapsed = useStudioUiStore((state) => state.setLeftCollapsed)
  const { rendered, exiting } = useStudioPanelPresence(!collapsed)
  return rendered
    ? <StudioFlowLeftPanelContent exiting={exiting} onCollapse={() => setCollapsed(true)} />
    : null
}

const FLOW_STEPS: Array<{ step: StudioFlowStep; label: string; description: string }> = [
  { step: 'global-setting', label: '项目配置', description: '风格、创作模型、比例与策略' },
  { step: 'story-script', label: '剧本创作', description: '富文本、导入、历史与 Agent' },
  { step: 'scene-character', label: '素材准备', description: '场景、角色、道具与形态' },
  { step: 'storyboard-script', label: '分镜设计', description: '拆镜、脚本与分镜图' },
  { step: 'storyboard-video', label: '视频生成', description: '原流程创作模式与视频任务' },
  { step: 'dubbing', label: '音画同步', description: '台词、音色、配音与对口型' },
  { step: 'preview', label: '成品预览', description: '时间轴与导出' }
]

function StudioFlowLeftPanelContent({ onCollapse, exiting }: { onCollapse: () => void; exiting: boolean }) {
  const runtime = useStudioFlowRuntime()
  const nodes = useStudioUiStore((state) => state.nodes)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const selectedNodeIds = useStudioUiStore((state) => state.selectedNodeIds)
  const activeNodeId = useStudioUiStore((state) => state.activeNodeId)
  const visibleItems = useMemo(
    () => filterStudioFlowCanvasVisibleNodes(nodes).filter((node) => node.data.flowBinding?.role === 'item'),
    [nodes]
  )
  const activeStep = useMemo(() => {
    const id = activeNodeId ?? selectedNodeIds[0]
    return nodes.find((node) => node.id === id)?.data.flowBinding?.step
  }, [activeNodeId, nodes, selectedNodeIds])
  const panelRef = useStudioExitingPanelRef(exiting)

  const openFlowStep = (step: StudioFlowStep) => {
    if (step === 'global-setting') {
      runtime?.openEditor('global-setting')
      return
    }
    if (!selectStudioFlowStepNode(step)) {
      message.info('该步骤尚无内容节点，请先在右侧 Agent 中生成')
    }
  }
  return (
    <aside
      ref={panelRef}
      className={`studio-left-panel${exiting ? ' is-exiting' : ''}`}
      data-studio-guide="planning"
      aria-hidden={exiting}
      inert={exiting}
    >
      <div className="studio-panel-heading">
        <div><strong>流程导航</strong><span>步骤 · 实体 · 任务 · 交付</span></div>
        <Tooltip title="收起流程面板">
          <Button size="small" type="text" icon={<DoubleLeftOutlined />} aria-label="收起流程面板" aria-expanded={!exiting} onClick={onCollapse} />
        </Tooltip>
      </div>
      <div className="studio-left-panel__content">
        <div className="studio-panel-scroll studio-flow-step-panel">
          <p className="studio-panel-help studio-panel-help--lead">右侧 Agent 负责创作，左侧用来跳转步骤和已生成节点。</p>
          <Collapse
            ghost
            size="small"
            className="studio-left-collapse"
            defaultActiveKey={['steps', 'entities', 'tasks', 'delivery']}
            items={[
              {
                key: 'steps',
                label: <span><ApartmentOutlined /> 创作步骤</span>,
                children: (
                  <div className="studio-flow-step-list">
                    {FLOW_STEPS.map((item, index) => (
                      <button
                        key={item.step}
                        type="button"
                        className={activeStep === item.step ? 'is-active' : ''}
                        onClick={() => openFlowStep(item.step)}
                      >
                        <i>{String(index + 1).padStart(2, '0')}</i>
                        <span><strong>{item.label}</strong><small>{item.description}</small></span>
                      </button>
                    ))}
                  </div>
                )
              },
              {
                key: 'entities',
                label: <span><AppstoreOutlined /> 画布实体 · {visibleItems.length}</span>,
                children: !visibleItems.length ? (
                  <p className="studio-panel-help">暂无可见节点。在右侧生成并带入剧本后，素材与分镜会自动出现在此。</p>
                ) : (
                  <div className="studio-node-list">
                    {visibleItems.slice(0, 120).map((node) => (
                      <button key={node.id} type="button" onClick={() => selectNodes([node.id])}>
                        <span className="studio-node-list__icon"><StudioNodeIcon kind={node.data.kind} /></span>
                        <span>
                          <strong>{node.data.title}</strong>
                          <small>{FLOW_STEPS.find((item) => item.step === node.data.flowBinding?.step)?.label ?? node.data.flowBinding?.step}</small>
                        </span>
                        <StudioStatusBadge status={node.data.status} />
                      </button>
                    ))}
                  </div>
                )
              },
              {
                key: 'tasks',
                label: <span><ThunderboltOutlined /> 生成任务</span>,
                children: (
                  <>
                    <p className="studio-panel-help">查看提取、出图、分镜等后台任务进度；与顶栏「进行中」统计同源。</p>
                    <StudioTaskList limit={14} />
                  </>
                )
              },
              {
                key: 'delivery',
                label: <span><ExportOutlined /> 交付</span>,
                children: <StudioFlowDeliveryPanel />
              }
            ]}
          />
        </div>
      </div>
    </aside>
  )
}

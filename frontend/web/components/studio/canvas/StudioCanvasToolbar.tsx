'use client'

import { memo } from 'react'
import { Button, Dropdown, type MenuProps } from 'antd'
import {
  AppstoreAddOutlined,
  BorderOutlined,
  CompressOutlined,
  DeploymentUnitOutlined,
  DragOutlined,
  RedoOutlined,
  UndoOutlined
} from '@ant-design/icons'
import { useReactFlow } from '@xyflow/react'
import { useStudioUiStore } from '@/stores/studioUi'
import { requestStudioFlowEditor } from '@/utils/studio/studioFlowEvents'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'

const flowSteps: Array<[Parameters<typeof requestStudioFlowEditor>[0], string]> = [
  ['global-setting', '项目配置'],
  ['story-script', '新建 / 编辑剧本'],
  ['scene-character', '新增 / 编辑素材'],
  ['storyboard-script', '新增 / 编辑分镜'],
  ['storyboard-video', '生成 / 编辑视频'],
  ['dubbing', '生成 / 编辑配音'],
  ['preview', '预览 / 导出成片']
]

function StudioCanvasToolbarComponent() {
  const canvasTool = useStudioUiStore((state) => state.canvasTool)
  const setCanvasTool = useStudioUiStore((state) => state.setCanvasTool)
  const historyPast = useStudioUiStore((state) => state.historyPast)
  const historyFuture = useStudioUiStore((state) => state.historyFuture)
  const undo = useStudioUiStore((state) => state.undo)
  const redo = useStudioUiStore((state) => state.redo)
  const { fitView } = useReactFlow()
  const flowMenu: MenuProps = {
    className: 'studio-add-menu studio-add-menu--canvas',
    items: flowSteps.map(([step, label]) => ({
      key: step,
      icon: <DeploymentUnitOutlined />,
      label,
      onClick: () => requestStudioFlowEditor(step)
    }))
  }
  return (
    <div className="studio-canvas-toolbar" aria-label="流程画布工具栏">
      <StudioCanvasTooltip title="打开流程步骤">
        <span className="studio-canvas-tool-trigger">
          <Dropdown menu={flowMenu} trigger={['click']} placement="bottomLeft" classNames={{ root: 'studio-canvas-menu-overlay studio-canvas-menu-overlay--canvas' }}>
            <Button className="studio-canvas-tool studio-canvas-tool--primary" type="text" icon={<AppstoreAddOutlined />} aria-label="流程步骤" />
          </Dropdown>
        </span>
      </StudioCanvasTooltip>
      <span className="studio-canvas-toolbar__divider" />
      <StudioCanvasTooltip title="选择：空白拖拽框选">
        <Button className={`studio-canvas-tool${canvasTool === 'select' ? ' is-active' : ''}`} type="text" icon={<BorderOutlined />} aria-label="选择工具" onClick={() => setCanvasTool('select')} />
      </StudioCanvasTooltip>
      <StudioCanvasTooltip title="抓手：拖动画布">
        <Button className={`studio-canvas-tool${canvasTool === 'pan' ? ' is-active' : ''}`} type="text" icon={<DragOutlined />} aria-label="抓手工具" onClick={() => setCanvasTool('pan')} />
      </StudioCanvasTooltip>
      <span className="studio-canvas-toolbar__divider" />
      <StudioCanvasTooltip title="撤销 (Ctrl/⌘ Z)">
        <Button className="studio-canvas-tool" type="text" icon={<UndoOutlined />} aria-label="撤销" disabled={!historyPast.length} onClick={undo} />
      </StudioCanvasTooltip>
      <StudioCanvasTooltip title="重做 (Ctrl/⌘ Shift Z)">
        <Button className="studio-canvas-tool" type="text" icon={<RedoOutlined />} aria-label="重做" disabled={!historyFuture.length} onClick={redo} />
      </StudioCanvasTooltip>
      <StudioCanvasTooltip title="适应全部内容">
        <Button className="studio-canvas-tool" type="text" icon={<CompressOutlined />} aria-label="适应全部内容" onClick={() => void fitView({ padding: 0.14, duration: 220 })} />
      </StudioCanvasTooltip>
    </div>
  )
}

export const StudioCanvasToolbar = memo(StudioCanvasToolbarComponent)

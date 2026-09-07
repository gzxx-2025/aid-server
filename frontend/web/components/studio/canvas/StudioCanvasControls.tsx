'use client'

import { memo, useState } from 'react'
import { AimOutlined, CompressOutlined, MinusOutlined, PlusOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons'
import { Button, Popover } from 'antd'
import { useReactFlow, useViewport } from '@xyflow/react'
import { useStudioUiStore } from '@/stores/studioUi'
import { StudioCanvasTooltip } from './StudioCanvasTooltip'

const ZOOM_PRESETS = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 2] as const

function StudioCanvasControlsComponent() {
  const miniMapOpen = useStudioUiStore((state) => state.miniMapOpen)
  const setMiniMapOpen = useStudioUiStore((state) => state.setMiniMapOpen)
  const { fitView, zoomIn, zoomOut, zoomTo } = useReactFlow()
  const { zoom } = useViewport()
  const [open, setOpen] = useState(false)
  const close = () => setOpen(false)
  const content = (
    <div className="studio-canvas-controls__zoom-panel" role="menu" aria-label="缩放选项">
      <button type="button" className="studio-canvas-controls__zoom-action" onClick={() => { void zoomIn({ duration: 160 }); close() }}>
        <ZoomInOutlined /><span>放大</span>
      </button>
      <button type="button" className="studio-canvas-controls__zoom-action" onClick={() => { void zoomOut({ duration: 160 }); close() }}>
        <ZoomOutOutlined /><span>缩小</span>
      </button>
      <button type="button" className="studio-canvas-controls__zoom-action" onClick={() => { void fitView({ padding: 0.14, duration: 220 }); close() }}>
        <CompressOutlined /><span>适应画布</span>
      </button>
      <span className="studio-canvas-controls__zoom-divider" />
      {ZOOM_PRESETS.map((preset) => (
        <button
          key={preset}
          type="button"
          role="menuitemradio"
          aria-checked={Math.abs(zoom - preset) < 0.02}
          className={`studio-canvas-controls__zoom-preset${Math.abs(zoom - preset) < 0.02 ? ' is-active' : ''}`}
          onClick={() => { void zoomTo(preset, { duration: 160 }); close() }}
        >
          {Math.round(preset * 100)}%
        </button>
      ))}
    </div>
  )
  return (
    <div className="studio-canvas-controls" aria-label="画布视图控件">
      <div className="studio-canvas-controls__bar">
        <StudioCanvasTooltip title={miniMapOpen ? '关闭小地图' : '打开小地图'}>
          <Button
            className={`studio-canvas-tool${miniMapOpen ? ' is-active' : ''}`}
            type="text"
            icon={<AimOutlined />}
            aria-label={miniMapOpen ? '关闭小地图' : '打开小地图'}
            onClick={() => setMiniMapOpen(!miniMapOpen)}
          />
        </StudioCanvasTooltip>
        <Popover
          trigger="click"
          placement="top"
          open={open}
          onOpenChange={setOpen}
          arrow={false}
          classNames={{ root: 'studio-canvas-controls__zoom-popover' }}
          content={content}
        >
          <button type="button" className={`studio-canvas-controls__zoom${open ? ' is-active' : ''}`} aria-label="缩放选项">
            <MinusOutlined /><span>{Math.round(zoom * 100)}%</span><PlusOutlined />
          </button>
        </Popover>
      </div>
    </div>
  )
}

export const StudioCanvasControls = memo(StudioCanvasControlsComponent)

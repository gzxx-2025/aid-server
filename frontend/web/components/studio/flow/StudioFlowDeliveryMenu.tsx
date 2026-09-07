'use client'

import { Button, Dropdown } from 'antd'
import { ExportOutlined } from '@ant-design/icons'
import { useStudioFlowExportContext } from './StudioFlowExportProvider'

export function StudioFlowDeliveryMenu({
  compact = false,
  disabled = false
}: {
  compact?: boolean
  disabled?: boolean
}) {
  const flowExport = useStudioFlowExportContext()
  if (!flowExport) return null

  const {
    exportMenuOpen,
    onExportMenuOpenChange,
    previewExportBusy,
    onExportFullVideo,
    onExportSegments
  } = flowExport

  const menu = (
    <div className="studio-flow-delivery-menu" role="menu" aria-label="导出">
      <div className="studio-flow-delivery-menu__title">导出</div>
      <button
        type="button"
        className="studio-flow-delivery-menu__btn"
        role="menuitem"
        disabled={previewExportBusy}
        onClick={() => void onExportFullVideo()}
      >
        导出完整视频
      </button>
      <button
        type="button"
        className="studio-flow-delivery-menu__btn studio-flow-delivery-menu__btn--secondary"
        role="menuitem"
        disabled={previewExportBusy}
        onClick={() => void onExportSegments()}
      >
        导出分段素材
      </button>
    </div>
  )

  return (
    <Dropdown
      open={exportMenuOpen}
      trigger={['click']}
      placement="bottomRight"
      disabled={disabled}
      classNames={{ root: 'studio-flow-delivery-dropdown' }}
      onOpenChange={(open) => void onExportMenuOpenChange(open)}
      popupRender={() => menu}
    >
      <Button
        type={compact ? 'default' : 'primary'}
        size={compact ? 'middle' : 'small'}
        className={compact ? 'studio-flow-delivery-trigger--compact' : 'studio-flow-delivery-trigger'}
        icon={<ExportOutlined />}
        disabled={disabled || previewExportBusy}
        loading={previewExportBusy}
      >
        {compact ? '交付' : '导出'}
      </Button>
    </Dropdown>
  )
}

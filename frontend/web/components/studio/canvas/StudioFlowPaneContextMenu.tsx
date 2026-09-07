'use client'

import { RollbackOutlined, SaveOutlined, SnippetsOutlined } from '@ant-design/icons'
import type { XYPosition } from '@xyflow/react'

export function StudioFlowPaneContextMenu({
  client,
  canUndo,
  saving,
  platform,
  onUndo,
  onSave,
  onClose
}: {
  client: XYPosition
  canUndo: boolean
  saving: boolean
  platform: 'mac' | 'win'
  onUndo: () => void
  onSave: () => void
  onClose: () => void
}) {
  const run = (command: () => void) => {
    command()
    onClose()
  }

  return (
    <div
      className="studio-pane-context-menu"
      role="menu"
      aria-label="流程画布右键菜单"
      style={{ left: client.x, top: client.y }}
    >
      <div className="studio-pane-context-menu__root">
        <button type="button" role="menuitem" disabled>
          <i><SnippetsOutlined /></i>
          <span>粘贴</span>
          <small>{platform === 'mac' ? '⌘ V' : 'Ctrl V'}</small>
        </button>
        <button type="button" role="menuitem" disabled={!canUndo} onClick={() => run(onUndo)}>
          <i><RollbackOutlined /></i>
          <span>撤销</span>
          <small>{platform === 'mac' ? '⌘ Z' : 'Ctrl Z'}</small>
        </button>
        <button type="button" role="menuitem" disabled={saving} onClick={() => run(onSave)}>
          <i><SaveOutlined /></i>
          <span>{saving ? '保存中' : '保存'}</span>
          <small>{platform === 'mac' ? '⌘ S' : 'Ctrl S'}</small>
        </button>
      </div>
    </div>
  )
}

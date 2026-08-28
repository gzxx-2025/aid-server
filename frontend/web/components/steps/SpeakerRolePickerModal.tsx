'use client'

import { CloseOutlined } from '@ant-design/icons'
import { Button,Modal } from 'antd'
import { useEffect,useState } from 'react'
import { assetUrl } from '~/utils/assetUrl'
import { emptyImageIconUrl as characterPlaceholderRaw } from '~/utils/emptyImageIcon'
import './SpeakerRolePickerModal.css'
const characterPlaceholderUrl = assetUrl(characterPlaceholderRaw)

export interface SpeakerRolePickerModalProps {
  open: boolean
  characters?: string[]
  initialName?: string
  /** 角色名 → 头像 URL，可选 */
  characterAvatars?: Record<string, string>
  onOpenChange: (open: boolean) => void
  onConfirm: (name: string) => void
}

export function SpeakerRolePickerModal({
  open,
  characters = [],
  initialName = '',
  characterAvatars = {},
  onOpenChange,
  onConfirm
}: SpeakerRolePickerModalProps) {
  const list = characters && characters.length ? characters : ['旁白', '陌生人']
  const displayCharacters = [...new Set(list.map((s) => (s || '').trim()).filter(Boolean))]

  const [selectedName, setSelectedName] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      const initial = (initialName || '').trim()
      const current = characters && characters.length ? characters : ['旁白', '陌生人']
      const display = [...new Set(current.map((s) => (s || '').trim()).filter(Boolean))]
      setSelectedName(display.includes(initial) ? initial : (display[0] ?? null))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, initialName])

  function hasAvatar(name: string) {
    return !!characterAvatars?.[name]?.trim()
  }

  function getAvatarUrl(name: string) {
    return characterAvatars?.[name]?.trim() || ''
  }

  function selectName(name: string) {
    setSelectedName(name)
  }

  function handleCancel() {
    onOpenChange(false)
  }

  function handleConfirm() {
    if (selectedName !== null) {
      onConfirm(selectedName)
      onOpenChange(false)
    }
  }

  return (
    <Modal
      open={open}
      width={472}
      footer={null}
      destroyOnHidden
      className="speaker-role-picker-modal"
      wrapClassName="create-flow-modal speaker-role-picker-wrap"
      centered
      zIndex={11000}
      onCancel={handleCancel}
      closeIcon={<CloseOutlined className="speaker-picker-close" />}
    >
      <div className="speaker-picker-shell">
        <h2 className="speaker-picker-title">发言角色</h2>

        <div className="speaker-picker-list">
          {displayCharacters.map((name, idx) => (
            <button
              key={`${name}-${idx}`}
              type="button"
              className={`speaker-picker-item${selectedName === name ? ' is-selected' : ''}`}
              onClick={() => selectName(name)}
            >
              {hasAvatar(name) ? (
                <img className="speaker-picker-cover" src={getAvatarUrl(name)} alt={name} />
              ) : (
                <div className="speaker-picker-cover speaker-picker-cover--placeholder">
                  <img src={characterPlaceholderUrl} alt="" />
                </div>
              )}
              <span className="speaker-picker-name">{name || '未命名'}</span>
            </button>
          ))}
          {displayCharacters.length === 0 ? (
            <div className="speaker-picker-empty">
              <p>暂无角色，请先在「素材准备」中添加角色</p>
            </div>
          ) : null}
        </div>

        <div className="speaker-picker-footer">
          <Button className="speaker-picker-btn-cancel" onClick={handleCancel}>
            <div className="text-gradient">取消</div>
          </Button>
          <Button
            type="primary"
            className="speaker-picker-btn-ok"
            disabled={selectedName === null}
            onClick={handleConfirm}
          >
            确定
          </Button>
        </div>
      </div>
    </Modal>
  )
}

export default SpeakerRolePickerModal

'use client'

import { CloseOutlined,LoadingOutlined } from '@ant-design/icons'
import { Modal } from 'antd'
import ModalTitleWatermark from '~/components/ModalTitleWatermark'
import CreateFirstStepFormBody from '~/components/steps/CreateFirstStepFormBody'
import type { GlobalSettingData } from '~/types'
import './create-first-step-modal.css'
type RatioValue = '16:9' | '9:16' | '4:3' | '3:4' | '1:1' | '21:9'
type ProjectTypeValue = 'movie' | 'series'
type ScriptTypeValue = GlobalSettingData['scriptType']
type StrategyValue = GlobalSettingData['modelStrategy']
type CreationModeValue = GlobalSettingData['creationMode']

export interface CreateFirstStepModalProps {
  open: boolean
  title: string
  projectType: ProjectTypeValue
  aspectRatio: RatioValue
  scriptType: ScriptTypeValue
  modelStrategy: StrategyValue
  creationMode: CreationModeValue
  modelValue: GlobalSettingData
  confirmLoading?: boolean
  flowEditMode?: boolean
  projectTypeLocked?: boolean
  contentConfigLocked?: boolean
  syncProjectTypeFromParent?: boolean
  onOpenChange: (open: boolean) => void
  onTitleChange: (value: string) => void
  onProjectTypeChange: (value: ProjectTypeValue) => void
  onAspectRatioChange: (value: RatioValue) => void
  onScriptTypeChange: (value: ScriptTypeValue) => void
  onModelStrategyChange: (value: StrategyValue) => void
  onCreationModeChange: (value: CreationModeValue) => void
  onModelValueChange: (value: GlobalSettingData) => void
  onConfirm: () => void
}

/** 创建作品第一步弹窗（新建项目 / 项目配置），原 CreateFirstStepModal.vue */
export function CreateFirstStepModal({
  open,
  title,
  projectType,
  aspectRatio,
  scriptType,
  modelStrategy,
  creationMode,
  modelValue,
  confirmLoading = false,
  flowEditMode = false,
  projectTypeLocked = false,
  contentConfigLocked = false,
  syncProjectTypeFromParent = false,
  onOpenChange,
  onTitleChange,
  onProjectTypeChange,
  onAspectRatioChange,
  onScriptTypeChange,
  onModelStrategyChange,
  onCreationModeChange,
  onModelValueChange,
  onConfirm
}: CreateFirstStepModalProps) {
  const confirmButtonLabel = flowEditMode ? '保存' : '创建作品'
  const confirmLoadingLabel = flowEditMode ? '保存中…' : '创建中…'
  const modalTitleText = flowEditMode ? '项目配置' : '新建项目'

  function onConfirmClick() {
    if (confirmLoading) return
    onConfirm()
  }

  return (
    <Modal
      open={open}
      footer={null}
      closable={false}
      width={1100}
      wrapClassName="create-flow-modal create-first-step-wrap"
      className="create-first-step-modal"
      onCancel={() => onOpenChange(false)}
    >
      <div className="modal-shell">
        <button
          className={`close-btn${confirmLoading ? ' is-disabled' : ''}`}
          type="button"
          onClick={() => !confirmLoading && onOpenChange(false)}
        >
          <CloseOutlined />
        </button>

        <div className="modal-title">
          <ModalTitleWatermark title={modalTitleText} watermark="NEW" />
        </div>

        <CreateFirstStepFormBody
          open={open}
          flowEditMode={flowEditMode}
          projectTypeLocked={projectTypeLocked}
          contentConfigLocked={contentConfigLocked}
          syncProjectTypeFromParent={syncProjectTypeFromParent}
          title={title}
          projectType={projectType}
          aspectRatio={aspectRatio}
          scriptType={scriptType}
          modelStrategy={modelStrategy}
          creationMode={creationMode}
          modelValue={modelValue}
          className="modal-body"
          onTitleChange={onTitleChange}
          onProjectTypeChange={onProjectTypeChange}
          onAspectRatioChange={onAspectRatioChange}
          onScriptTypeChange={onScriptTypeChange}
          onModelStrategyChange={onModelStrategyChange}
          onCreationModeChange={onCreationModeChange}
          onModelValueChange={onModelValueChange}
        />

        <div className="modal-actions">
          <button
            type="button"
            className={`btn btn-secondary${confirmLoading ? ' is-disabled' : ''}`}
            onClick={() => !confirmLoading && onOpenChange(false)}
          >
            <div className="text-gradient">取消</div>
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={confirmLoading}
            onClick={onConfirmClick}
          >
            {confirmLoading && <LoadingOutlined spin className="btn-loading-icon" />}
            <span>{confirmLoading ? confirmLoadingLabel : confirmButtonLabel}</span>
          </button>
        </div>
      </div>
    </Modal>
  )
}

export default CreateFirstStepModal

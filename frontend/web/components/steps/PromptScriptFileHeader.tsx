'use client'

import { Button, Tooltip } from 'antd'
import { FileTextOutlined, PictureOutlined } from '@ant-design/icons'
import { assetUrl } from '~/utils/assetUrl'
import scriptGrayIconMod from '~/assets/img/icon/script_gray.svg'
import './prompt-script-file-header.css'

const scriptGrayIcon = assetUrl(scriptGrayIconMod)

export interface PromptScriptFileHeaderProps {
  fileName: string
  /** 场景图弹窗用 script 图标；分镜/视频用文件图标 */
  iconType?: 'scene' | 'file-text'
  /** 是否显示「参考图」 */
  showReferenceButton?: boolean
  /** button：可点击上传；label：仅展示文案 */
  referenceDisplayMode?: 'button' | 'label'
  /** 是否显示「生成提示词」 */
  showGeneratePromptButton?: boolean
  /** 生成提示词进行中 */
  generatePromptLoading?: boolean
  /** 左侧标题行是否可点击（打开脚本/场景设定） */
  sceneFileClickable?: boolean
  /** 非空时：标题行不可点击，悬停展示说明（如手动添加的资产无法编辑设定） */
  settingClickBlockedTooltip?: string
  /**
   * panel：分镜步骤面板浅色
   * modal-dark：编辑分镜图弹窗内右侧
   * scene-modal：编辑场景图弹窗右侧
   */
  theme?: 'panel' | 'modal-dark' | 'scene-modal'
  onClickFile?: () => void
  onImportReference?: () => void
  onGeneratePrompt?: () => void
}

export function PromptScriptFileHeader({
  fileName,
  iconType = 'file-text',
  showReferenceButton = true,
  referenceDisplayMode = 'button',
  showGeneratePromptButton = true,
  generatePromptLoading = false,
  sceneFileClickable = true,
  settingClickBlockedTooltip = undefined,
  theme = 'panel',
  onClickFile,
  onImportReference,
  onGeneratePrompt
}: PromptScriptFileHeaderProps) {
  const hasActions = showReferenceButton || showGeneratePromptButton

  function handleFileClick() {
    if (sceneFileClickable) {
      onClickFile?.()
    }
  }

  const fileInfoInner = (
    <>
      {iconType === 'scene' ? (
        <img src={scriptGrayIcon} className="file-icon file-icon--img" alt="" />
      ) : (
        <FileTextOutlined className="file-icon" />
      )}
      <span className="scene-file-name">{fileName}</span>
    </>
  )

  return (
    <div
      className={[
        'prompt-script-file-header',
        !hasActions ? 'prompt-script-file-header--no-actions' : '',
        `prompt-script-file-header--${theme}`
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {settingClickBlockedTooltip ? (
        <Tooltip title={settingClickBlockedTooltip}>
          <div className="scene-file-info scene-file-info--blocked">{fileInfoInner}</div>
        </Tooltip>
      ) : (
        <div
          className={`scene-file-info${sceneFileClickable ? ' scene-file-info--clickable' : ''}`}
          onClick={handleFileClick}
        >
          {fileInfoInner}
        </div>
      )}
      {hasActions ? (
        <div className="prompt-row-actions">
          {showReferenceButton && referenceDisplayMode === 'label' ? (
            <span className="ref-link-label">
              <PictureOutlined className="ref-link-label__icon" />
              参考图
            </span>
          ) : showReferenceButton ? (
            <Button
              type="text"
              className="ref-link-btn"
              onClick={() => onImportReference?.()}
              icon={<PictureOutlined />}
            >
              参考图
            </Button>
          ) : null}
          {showGeneratePromptButton && theme === 'scene-modal' ? (
            <Button
              className="generate-prompt-btn generate-prompt-btn--gradient"
              type="primary"
              loading={generatePromptLoading}
              disabled={generatePromptLoading}
              onClick={() => onGeneratePrompt?.()}
            >
              <span className="text-gradient">生成提示词</span>
            </Button>
          ) : showGeneratePromptButton && theme === 'modal-dark' ? (
            <Button
              className="generate-prompt-btn generate-prompt-btn--gradient"
              loading={generatePromptLoading}
              disabled={generatePromptLoading}
              onClick={() => onGeneratePrompt?.()}
            >
              <span className="text-gradient">生成提示词</span>
            </Button>
          ) : showGeneratePromptButton ? (
            <Button
              type="primary"
              className="generate-prompt-btn generate-prompt-btn--gradient"
              loading={generatePromptLoading}
              disabled={generatePromptLoading}
              onClick={() => onGeneratePrompt?.()}
            >
              <span className="text-gradient">生成提示词</span>
            </Button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

export default PromptScriptFileHeader

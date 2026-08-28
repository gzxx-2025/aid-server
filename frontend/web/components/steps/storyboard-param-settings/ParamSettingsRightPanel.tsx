'use client'

import { Button, Input, Switch, message } from 'antd'
import { CopyOutlined, DeleteOutlined } from '@ant-design/icons'
import SettingSelectField from '../SettingSelectField'
import RichTextEditor from '~/components/common/RichTextEditor'
import { htmlPlainTextLength } from '~/utils/htmlPlain'
import type { SettingSelectOption } from '~/composables/usePromptDictionary'
import type { ParamSettingsDraftState, ParamSettingsMode } from './paramSettingsDraft'

interface DictOptions {
  cameraMovementOptions: SettingSelectOption[]
  shootingTechniqueOptions: SettingSelectOption[]
  compositionOptions: SettingSelectOption[]
  shotSizeOptions: SettingSelectOption[]
  cameraAngleOptions: SettingSelectOption[]
  focalLengthOptions: SettingSelectOption[]
  colorToneOptions: SettingSelectOption[]
  lightingOptions: SettingSelectOption[]
  techniqueOptions: SettingSelectOption[]
}

interface Props {
  mode: ParamSettingsMode
  draft: ParamSettingsDraftState
  dict: DictOptions
  onPatchDraft: (patch: Partial<ParamSettingsDraftState>) => void
}

function copyDraftText(text: string) {
  if (!text) return
  navigator.clipboard.writeText(text)
  message.success('已复制')
}

function copyDraftHtml(html: string) {
  if (!htmlPlainTextLength(html)) return
  navigator.clipboard.writeText(html)
  message.success('已复制')
}

/** 右侧参数选择区：分镜视频只读 / 多参生视频 / 生成分镜图 三种形态 */
export function ParamSettingsRightPanel({ mode, draft, dict, onPatchDraft }: Props) {
  const draftI2vPrimaryReference = (() => {
    const first = draft.i2vReferenceImages[0]
    if (!first?.url && !first?.thumbnail) return null
    return first
  })()

  /* 图生视频：右侧只读展示 */
  if (mode === 'storyboardVideo') {
    return (
      <>
        <div className="spsm-readonly-hint">右侧内容仅支持多参生视频</div>
        <div className="spsm-readonly-block">
          <div className="spsm-field">
            <div className="spsm-switch-row">
              <div className="spsm-switch-label">
                <span className="spsm-dot" />
                九宫格多机位
              </div>
              <Switch checked={draft.nineGridEnabled} disabled={true} size="small" />
            </div>
          </div>
          <div className="spsm-field">
            <div className="spsm-field-title">镜头运动</div>
            <SettingSelectField
              modelValue={draft.selectedCameraMovement}
              options={dict.cameraMovementOptions}
              placeholder="请选择镜头运动"
              panelTitle="选择镜头运动"
              open={false}
            />
            <div className="spsm-textarea-wrap">
              <Input
                value={draft.cameraMovementDesc}
                placeholder="请输入镜头运动描述"
                className="spsm-input-actions"
                disabled
                readOnly
              />
            </div>
          </div>
          <div className="spsm-field">
            <div className="spsm-field-title">特殊拍摄手法</div>
            <SettingSelectField
              modelValue={draft.selectedShootingTechnique}
              options={dict.shootingTechniqueOptions}
              placeholder="请选择特殊拍摄手法"
              panelTitle="选择特殊拍摄手法"
              open={false}
            />
          </div>
        </div>
      </>
    )
  }

  /* 多参生视频：右侧仅展示镜头/拍摄参数（无分区标题） */
  if (mode === 'imageToVideo') {
    return (
      <>
        <div className="spsm-field">
          <div className="spsm-switch-row">
            <div className="spsm-switch-label">
              <span className="spsm-dot" />
              九宫格多机位
            </div>
            <Switch
              checked={draft.i2vNineGridEnabled}
              onChange={(v) => onPatchDraft({ i2vNineGridEnabled: v })}
              disabled={!draftI2vPrimaryReference}
              size="small"
            />
          </div>
        </div>
        <div className="spsm-field">
          <div className="spsm-field-title">镜头运动</div>
          <SettingSelectField
            modelValue={draft.i2vSelectedCameraMovement}
            onModelValueChange={(v) => onPatchDraft({ i2vSelectedCameraMovement: v })}
            options={dict.cameraMovementOptions}
            placeholder="请选择镜头运动"
            panelTitle="选择镜头运动"
            open={draft.i2vActiveVideoSettingKey === 'cameraMovement'}
            onOpenChange={(v) =>
              onPatchDraft({ i2vActiveVideoSettingKey: v ? 'cameraMovement' : null })
            }
          />
          <div className="spsm-textarea-wrap">
            <Input
              value={draft.i2vCameraMovementDesc}
              onChange={(e) => onPatchDraft({ i2vCameraMovementDesc: e.target.value })}
              placeholder="请输入镜头运动描述"
              className="spsm-input-actions"
              suffix={
                <>
                  <DeleteOutlined
                    className="spsm-input-action"
                    onClick={(e) => {
                      e.stopPropagation()
                      onPatchDraft({ i2vCameraMovementDesc: '' })
                    }}
                  />
                  <CopyOutlined
                    className="spsm-input-action"
                    onClick={(e) => {
                      e.stopPropagation()
                      copyDraftText(draft.i2vCameraMovementDesc)
                    }}
                  />
                </>
              }
            />
          </div>
        </div>
        <div className="spsm-field">
          <div className="spsm-field-title">特殊拍摄手法</div>
          <SettingSelectField
            modelValue={draft.i2vSelectedShootingTechnique}
            onModelValueChange={(v) => onPatchDraft({ i2vSelectedShootingTechnique: v })}
            options={dict.shootingTechniqueOptions}
            placeholder="请选择特殊拍摄手法"
            panelTitle="选择特殊拍摄手法"
            open={draft.i2vActiveVideoSettingKey === 'shootingTechnique'}
            onOpenChange={(v) =>
              onPatchDraft({ i2vActiveVideoSettingKey: v ? 'shootingTechnique' : null })
            }
          />
        </div>
      </>
    )
  }

  /* 生成分镜图 */
  return (
    <>
      <div className="spsm-field">
        <div className="spsm-field-title">构图</div>
        <SettingSelectField
          modelValue={draft.selectedComposition}
          onModelValueChange={(v) => onPatchDraft({ selectedComposition: v })}
          options={dict.compositionOptions}
          placeholder="请选择构图"
          panelTitle="选择构图"
          open={draft.activeSettingKey === 'composition'}
          onOpenChange={(v) => onPatchDraft({ activeSettingKey: v ? 'composition' : null })}
        />
        <div className="spsm-textarea-wrap spsm-textarea-wrap--rich">
          <RichTextEditor
            value={draft.compositionDesc}
            onChange={(v) => onPatchDraft({ compositionDesc: v })}
            minHeight="238px"
            placeholder="请输入构图描述"
          />
          <div className="spsm-textarea-actions">
            <Button type="text" size="small" onClick={() => copyDraftHtml(draft.compositionDesc)}>
              <CopyOutlined />
            </Button>
            <Button type="text" size="small" onClick={() => onPatchDraft({ compositionDesc: '' })}>
              <DeleteOutlined />
            </Button>
          </div>
        </div>
      </div>
      <div className="spsm-field">
        <div className="spsm-field-title">景别</div>
        <SettingSelectField
          modelValue={draft.selectedShotSize}
          onModelValueChange={(v) => onPatchDraft({ selectedShotSize: v })}
          options={dict.shotSizeOptions}
          placeholder="请选择景别"
          panelTitle="选择景别"
          open={draft.activeSettingKey === 'shotSize'}
          onOpenChange={(v) => onPatchDraft({ activeSettingKey: v ? 'shotSize' : null })}
        />
      </div>
      <div className="spsm-field">
        <div className="spsm-field-title">拍摄角度</div>
        <SettingSelectField
          modelValue={draft.selectedCameraAngle}
          onModelValueChange={(v) => onPatchDraft({ selectedCameraAngle: v })}
          options={dict.cameraAngleOptions}
          placeholder="请选择拍摄角度"
          panelTitle="选择拍摄角度"
          open={draft.activeSettingKey === 'cameraAngle'}
          onOpenChange={(v) => onPatchDraft({ activeSettingKey: v ? 'cameraAngle' : null })}
        />
      </div>
      <div className="spsm-field">
        <div className="spsm-field-title">镜头焦距</div>
        <SettingSelectField
          modelValue={draft.selectedFocalLength}
          onModelValueChange={(v) => onPatchDraft({ selectedFocalLength: v })}
          options={dict.focalLengthOptions}
          placeholder="请选择镜头焦距"
          panelTitle="选择镜头焦距"
          open={draft.activeSettingKey === 'focalLength'}
          onOpenChange={(v) => onPatchDraft({ activeSettingKey: v ? 'focalLength' : null })}
        />
      </div>
      <div className="spsm-field">
        <div className="spsm-field-title">色彩倾向</div>
        <SettingSelectField
          modelValue={draft.selectedColorTone}
          onModelValueChange={(v) => onPatchDraft({ selectedColorTone: v })}
          options={dict.colorToneOptions}
          placeholder="请选择色彩倾向"
          panelTitle="选择色彩倾向"
          open={draft.activeSettingKey === 'colorTone'}
          onOpenChange={(v) => onPatchDraft({ activeSettingKey: v ? 'colorTone' : null })}
        />
      </div>
      <div className="spsm-field">
        <div className="spsm-field-title">光线</div>
        <SettingSelectField
          modelValue={draft.selectedLighting}
          onModelValueChange={(v) => onPatchDraft({ selectedLighting: v })}
          options={dict.lightingOptions}
          placeholder="请选择光线"
          panelTitle="选择光线"
          open={draft.activeSettingKey === 'lighting'}
          onOpenChange={(v) => onPatchDraft({ activeSettingKey: v ? 'lighting' : null })}
        />
      </div>
      <div className="spsm-field">
        <div className="spsm-field-title">摄影技法</div>
        <SettingSelectField
          modelValue={draft.selectedTechnique}
          onModelValueChange={(v) => onPatchDraft({ selectedTechnique: v })}
          options={dict.techniqueOptions}
          placeholder="请选择摄影技法"
          panelTitle="选择摄影技法"
          open={draft.activeSettingKey === 'technique'}
          onOpenChange={(v) => onPatchDraft({ activeSettingKey: v ? 'technique' : null })}
        />
      </div>
    </>
  )
}

export default ParamSettingsRightPanel

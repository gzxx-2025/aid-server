'use client'

import { Input } from 'antd'
import type { ModelOption } from '~/types/modelAgentOptions'
import { assetUrl } from '~/utils/assetUrl'
import { emptyImageIconUrl as emptyImageIconRaw } from '~/utils/emptyImageIcon'
import type { SelectOption } from '~/utils/modelCapability'
import {
createDefaultNineGridAngles,
DEFAULT_NINE_GRID_ANGLE_PROMPTS,
NINE_GRID_CELL_LABELS
} from '~/utils/nineGridCameraAngles'
import GenerateModelConfigBlock from './GenerateModelConfigBlock'
import ModelSelectDropdown from './ModelSelectDropdown'
import './NineGridGeneratePanel.css'
const { TextArea } = Input

const emptyImageIconUrl = assetUrl(emptyImageIconRaw)

export interface NineGridGeneratePanelProps {
  referenceImageUrl: string
  angles: string[]
  modelValue: ModelOption
  modelOptions: ModelOption[]
  modelExpanded: boolean
  aspectRatio: string
  aspectRatioOptions?: SelectOption<string>[]
  onAnglesChange: (value: string[]) => void
  onModelExpandedChange: (value: boolean) => void
  onSelectModel: (model: ModelOption) => void
  onAspectRatioChange: (value: string) => void
}

const cellLabels = NINE_GRID_CELL_LABELS
const defaultAngles = [...DEFAULT_NINE_GRID_ANGLE_PROMPTS]

export function NineGridGeneratePanel({
  referenceImageUrl,
  angles,
  modelValue,
  modelOptions,
  modelExpanded,
  aspectRatio,
  aspectRatioOptions,
  onAnglesChange,
  onModelExpandedChange,
  onSelectModel,
  onAspectRatioChange
}: NineGridGeneratePanelProps) {
  function onAngleInput(index: number, value: string) {
    const next = [...angles]
    while (next.length < 9) next.push('')
    next[index] = value
    onAnglesChange(next)
  }

  function resetAngles() {
    onAnglesChange(createDefaultNineGridAngles())
  }

  return (
    <div className="nine-grid-generate-panel">
      <div className="nine-grid-section">
        <div className="nine-grid-section__label">
          <span className="nine-grid-dot" />
          参考图
        </div>
        <p className="nine-grid-hint">使用画布当前选中图片作为九宫格基准图</p>
        <div className="nine-grid-reference">
          {referenceImageUrl ? (
            <img
              src={referenceImageUrl}
              alt="参考图"
              className="nine-grid-reference__img"
            />
          ) : (
            <div className="nine-grid-reference__empty">
              <img src={emptyImageIconUrl} alt="" className="empty-image-icon empty-image-icon--md" />
              <span>请先在左侧选择一张分镜图</span>
            </div>
          )}
        </div>
      </div>

      <div className="nine-grid-section">
        <div className="nine-grid-section__head">
          <div className="nine-grid-section__label">
            <span className="nine-grid-dot" />
            九格机位提示词
          </div>
          <button type="button" className="nine-grid-reset-btn" onClick={resetAngles}>
            恢复默认
          </button>
        </div>
        <p className="nine-grid-hint">每格对应 3×3 拼图中的一个机位，共 9 条，不可为空</p>
        <div className="nine-grid-cells">
          {cellLabels.map((label, idx) => (
            <div key={idx} className="nine-grid-cell">
              <label className="nine-grid-cell__label">{label}</label>
              <TextArea
                value={angles[idx]}
                rows={2}
                maxLength={200}
                className="nine-grid-cell__input"
                placeholder={defaultAngles[idx]}
                onChange={(e) => onAngleInput(idx, e.target.value)}
              />
            </div>
          ))}
        </div>
      </div>

      <GenerateModelConfigBlock
        aspectRatio={aspectRatio}
        count={1}
        quality={'2k'}
        aspectRatioOptions={aspectRatioOptions}
        countOptions={[]}
        qualityOptions={[]}
        selectClass="setting-select"
        density="storyboard"
        showQuality3k={false}
        showAction={false}
        showTitle={true}
        title="模型配置"
        onAspectRatioChange={(v) => onAspectRatioChange(v)}
        modelSlot={
          <ModelSelectDropdown
            value={modelValue}
            options={modelOptions}
            expanded={modelExpanded}
            onToggle={() => onModelExpandedChange(!modelExpanded)}
            onClose={() => onModelExpandedChange(false)}
            onSelect={(model: ModelOption) => onSelectModel(model)}
          />
        }
      />
    </div>
  )
}

export default NineGridGeneratePanel
